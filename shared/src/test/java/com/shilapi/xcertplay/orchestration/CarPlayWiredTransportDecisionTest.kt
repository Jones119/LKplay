package com.shilapi.xcertplay.orchestration

import android.os.Build
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayMediaHandler
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.network.Cs11NetdevBootstrap
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * CS11: the transport decision behind `CarPlayController.openDataPaths`.
 *
 * The audit found `shared/.../orchestration/CarPlayController.kt`'s old condition
 * (`SDK_INT < 21 && Cs11NetdevBootstrap.supported`, whose right-hand side is itself `SDK_INT < 21`)
 * degenerate: lint reported the inner `< 21` degradation branch as `ObsoleteSdkInt`, that log line
 * could never print, and — more importantly — the whole API-19 NCM discovery path was unreachable on
 * the car. The condition is now a single explicit level test with a JVM-testable decision function,
 * and `openNcm()`'s API-19 half was removed because the kernel cdc_ncm netdev superseded the raw-USB
 * bridge in `5b0f5774` (see docs/_digest/49).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class CarPlayWiredTransportDecisionTest {
    private val controllers = mutableListOf<CarPlayController>()

    @After fun tearDown() {
        controllers.forEach { it.close() }
    }

    @Test
    fun theCarLevelUsesTheKernelNetdevInsteadOfTheRawUsbBridge() {
        val decision = controller().rawNcmDecision(19)
        assertTrue("API 19 must not attempt the raw-USB NCM bridge", decision.usesKernelNetdevOnly)
        assertTrue(
            "the replacement log line has to name the path it took: ${decision.reason}",
            decision.reason.contains("kernel netdev transport selected"),
        )
    }

    @Test
    fun everyLevelBelow21TakesTheKernelNetdevPath() {
        for (level in 1..20) {
            assertTrue("API $level", controller().rawNcmDecision(level).usesKernelNetdevOnly)
        }
    }

    @Test
    fun api21AndAboveStillOpenTheRawUsbBridge() {
        for (level in 21..37) {
            val decision = controller().rawNcmDecision(level)
            assertFalse("API $level must still call openNcm", decision.usesKernelNetdevOnly)
        }
    }

    @Test
    fun theKernelNetdevGateMatchesTheApi19LevelTest() {
        // The old form ANDed the level with `Cs11NetdevBootstrap.supported`, which is *defined* as
        // `SDK_INT < 21`. Asserting the equivalence documents why the extra term was redundant and
        // why removing it cannot change behaviour on a device.
        val supportedIsTheLevelTest = Cs11NetdevBootstrap.supported == (Build.VERSION.SDK_INT < 21)
        assertTrue("Cs11NetdevBootstrap.supported is no longer just the level test", supportedIsTheLevelTest)
    }

    private fun controller(): CarPlayController {
        val context = RuntimeEnvironment.getApplication()
        return CarPlayController(
            context,
            CarPlayRuntimeConfig(
                transport = com.shilapi.xcertplay.orchestration.CarPlayTransport.WIRED,
                identification = Iap2IdentificationConfig(
                    name = "test",
                    modelIdentifier = "test",
                    manufacturer = "test",
                    serialNumber = "test",
                    firmwareVersion = "1",
                    hardwareVersion = "1",
                    carPlayUsbInterfaceNumber = 3,
                ),
            ),
            AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480)),
            AirPlayIdentity(ByteArray(32), ByteArray(32), "test"),
            PairingStore(),
            object : AirPlaySessionListener {},
            object : AirPlayMediaHandler {},
            {},
        ).also(controllers::add)
    }
}
