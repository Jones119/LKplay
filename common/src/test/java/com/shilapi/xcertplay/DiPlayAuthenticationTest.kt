package com.shilapi.xcertplay

import android.content.Context
import android.os.Looper
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.orchestration.*
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@LooperMode(LooperMode.Mode.PAUSED)
class DiPlayAuthenticationTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val directory get() = File(context.noBackupFilesDir, "offline-mfi")

    @Before fun setUp() {
        // The JVM test host has no vehicle SOME/IP gateway.
        shadowOf(context).declareActionUnbindable("com.ts.car.someip.SomeIpServerService")
        DiPlayBootstrap::class.java.getDeclaredField("ready").apply { isAccessible = true }
            .setBoolean(null, false)
    }

    @Test fun freshInstallStillDefaultsToLocalAuthentication() {
        assertEquals(MfiTarget.LOCAL, AirPlayPersistence.loadMfiTarget(context))
        assertThrows(Exception::class.java) { DiPlayBootstrap.ensure(context, MfiTarget.LOCAL) }
    }

    @Test fun savedRemoteChoiceSurvivesBootstrapWithoutLocalAssets() {
        AirPlayPersistence.saveMfiTarget(context, MfiTarget.REMOTE)
        DiPlayBootstrap.ensure(context, AirPlayPersistence.loadMfiTarget(context))
        assertEquals(MfiTarget.REMOTE, AirPlayPersistence.loadMfiTarget(context))
        assertFalse(directory.exists())
        assertFalse(File(context.noBackupFilesDir, "offline-mfi-staging").exists())
    }

    @Test fun remoteBootstrapDoesNotReadAnExistingBrokenLocalIdentity() {
        assertTrue(directory.mkdirs())
        File(directory, "identity.pk8").writeText("invalid local identity")
        DiPlayBootstrap.ensure(context, MfiTarget.REMOTE)
        assertEquals("invalid local identity", File(directory, "identity.pk8").readText())
        assertFalse(File(directory, "certificate.p7b").exists())
        assertThrows(Exception::class.java) { DiPlayBootstrap.ensure(context, MfiTarget.LOCAL) }
    }

    @Test fun switchingFromRemoteToLocalStillRequiresLocalIdentity() {
        DiPlayBootstrap.ensure(context, MfiTarget.REMOTE)
        assertThrows(Exception::class.java) { DiPlayBootstrap.ensure(context, MfiTarget.LOCAL) }
    }

    @Test fun remoteControllerFailsClosedWithoutLocalAssets() {
        val statuses = startAuthentication(MfiTarget.REMOTE)
        assertFalse(statuses.contains(CarPlayStatus.WaitingForMfi))
        assertTrue(statuses.any { it is CarPlayStatus.Failed })
        assertFalse(directory.exists())
    }

    @Test fun existingLocalDirectoryCannotOverrideRemoteControllerSelection() {
        assertTrue(directory.mkdirs())
        File(directory, "identity.pk8").writeText("invalid local identity")
        val statuses = startAuthentication(MfiTarget.REMOTE)
        assertFalse(statuses.contains(CarPlayStatus.WaitingForMfi))
        assertTrue(statuses.any { it is CarPlayStatus.Failed })
        assertEquals("invalid local identity", File(directory, "identity.pk8").readText())
    }

    @Test fun missingLocalIdentityFailsWithoutFallingBackToTheRemoteService() {
        val statuses = startAuthentication(MfiTarget.LOCAL)
        assertTrue(statuses.any { it is CarPlayStatus.Failed })
        assertFalse(statuses.contains(CarPlayStatus.WaitingForMfi))
    }

    private fun startAuthentication(target: MfiTarget): List<CarPlayStatus> {
        val statuses = mutableListOf<CarPlayStatus>()
        val controller = CarPlayController(context,
            CarPlayRuntimeConfig(mfiTarget = target,
                remoteMfiServer = REMOTE_MFI_SERVER.takeIf { target == MfiTarget.REMOTE },
                identification = Iap2IdentificationConfig("test", "test", "test", "test", "1", "1", 3)),
            AirPlayConfig("test", "02:00:00:00:00:02", "02:00:00:00:00:01", "1", AirPlayDisplayConfig(1920, 990)),
            AirPlayIdentity.generate(), PairingStore(), object : AirPlaySessionListener {}, object : AirPlayMediaHandler {},
            { statuses.add(it) },
        )
        try {
            controller.javaClass.getDeclaredMethod("startMfi").apply { isAccessible = true }.invoke(controller)
            val executor = controller.javaClass.getDeclaredField("executor").apply { isAccessible = true }
                .get(controller) as ExecutorService
            executor.submit {}.get(5, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
            return statuses.toList()
        } finally {
            controller.close()
            assertTrue(controller.awaitClosed(2_000))
        }
    }

    private companion object {
        /** No listener is bound here, so the request is refused immediately instead of resolving DNS. */
        const val REMOTE_MFI_SERVER = "http://127.0.0.1:1/mfi"
    }
}
