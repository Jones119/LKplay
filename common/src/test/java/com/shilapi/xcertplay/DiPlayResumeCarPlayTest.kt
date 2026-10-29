package com.shilapi.xcertplay

import android.content.Intent
import android.view.Surface
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayMediaHandler
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import com.shilapi.xcertplay.orchestration.MfiTarget
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Where DiPlay lands when it is opened while CarPlay is already running.
 *
 * The owner asked for the app to come straight back to the CarPlay screen instead of its own home page, and
 * then asked for that to be a switch (docs/_digest/84). Both halves matter: the behaviour, and the fact that
 * turning it off really does put the home page back.
 *
 * Only the *already connected* case is governed — with no session there is nothing to return to, and that is
 * asserted here too so the switch cannot quietly swallow the connect flow.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DiPlayResumeCarPlayTest {

    private lateinit var activity: DiPlayActivity

    @Before fun setUp() {
        activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
    }

    @After fun tearDown() {
        CarPlayBackgroundSession.clear()
    }

    /** With the switch on (the shipped default) an open app goes straight back to CarPlay. */
    @Test fun aRunningSessionIsBroughtForwardByDefault() {
        assertTrue("the switch ships on", DiPlayPreferences.resumeCarPlayOnOpen(activity))
        attachRunningSession()

        assertTrue("the CarPlay screen should be brought forward", openCarPlayScreen())
        assertNotNull("CarPlayHostActivity should have been started", shadowOf(activity).nextStartedActivity)
    }

    /** Turning it off puts the home page back, which is the whole point of the switch. */
    @Test fun turningTheSwitchOffLeavesTheHomePageInFront() {
        DiPlayPreferences.saveResumeCarPlayOnOpen(activity, false)
        attachRunningSession()

        assertTrue("the switch must be off for this test to mean anything", !DiPlayPreferences.resumeCarPlayOnOpen(activity))
        assertTrue("the home page should stay", !openCarPlayScreen())
        assertNull("nothing should be started", shadowOf(activity).nextStartedActivity)
    }

    /**
     * With no session the switch is irrelevant: an explicit `page` still wins, and nothing is brought
     * forward, so the connect flow is untouched.
     */
    @Test fun withoutASessionTheSwitchHasNoSay() {
        assertTrue("no session yet", !CarPlayBackgroundSession.hasSession())
        assertTrue("nothing to return to", !openCarPlayScreen())
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    /** An explicit page request outranks the switch, even while a session runs. */
    @Test fun anExplicitPageOutranksARunningSession() {
        attachRunningSession()
        activity.intent = Intent(activity, DiPlayActivity::class.java).putExtra("page", "settings")

        assertTrue("an explicit page must stay", !openCarPlayScreen())
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    /**
     * Only a bare launcher tap counts.
     *
     * Every internal entry point carries extras, and the recovery links carry `page`; treating those as "the
     * driver wants CarPlay back" would hijack the very screen they are trying to reach. Ported from upstream
     * 10f19a6b, whose note is that the user-leave hint cannot tell these apart.
     */
    @Test fun onlyAPlainLauncherIntentReturnsToCarPlay() {
        val plain = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        assertTrue("a plain launcher tap must count", activity.isLauncherIntent(plain))

        val withPage = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .putExtra("page", "connection")
        assertTrue("an explicit page must not count", !activity.isLauncherIntent(withPage))

        val noCategory = Intent(Intent.ACTION_MAIN)
        assertTrue("no launcher category must not count", !activity.isLauncherIntent(noCategory))

        val otherAction = Intent(Intent.ACTION_VIEW).addCategory(Intent.CATEGORY_LAUNCHER)
        assertTrue("a non-MAIN action must not count", !activity.isLauncherIntent(otherAction))

        assertTrue("a null intent must not count", !activity.isLauncherIntent(null))
    }

    /** Brings [DiPlayActivity.openCarPlayScreenIfConnected] out where the test can drive it. */
    private fun openCarPlayScreen(): Boolean =
        DiPlayActivity::class.java.getDeclaredMethod("openCarPlayScreenIfConnected")
            .apply { isAccessible = true }
            .invoke(activity) as Boolean

    /**
     * Publishes a session owned by this activity. `hasSession()` keys off the stop action and `isOwner` off
     * the owner, so both are supplied through the same `store` the host activity uses.
     */
    private fun attachRunningSession() {
        val config = CarPlayRuntimeConfig(
            mfiTarget = MfiTarget.LOCAL,
            identification = Iap2IdentificationConfig(
                name = "test", modelIdentifier = "test", manufacturer = "test", serialNumber = "test",
                firmwareVersion = "1", hardwareVersion = "1", carPlayUsbInterfaceNumber = 3,
            ),
        )
        val controller = CarPlayController(
            activity,
            config,
            AirPlayConfig(
                deviceName = "test", deviceId = "02:00:00:00:00:02", btMac = "02:00:00:00:00:01",
                sourceVersion = "1", main = AirPlayDisplayConfig(widthPixels = 1536, heightPixels = 792),
            ),
            AirPlayIdentity.generate(), PairingStore(), object : AirPlaySessionListener {},
            object : AirPlayMediaHandler {}, {},
        )
        val display = CarPlaySessionDisplay(1920, 990, Surface.ROTATION_0, true, true, 1920, 990)
        CarPlayBackgroundSession.store(controller, AndroidMediaSink(), 1920, 990, activity, display) {}
        CarPlayBackgroundSession.active = true
        assertTrue("the session should be owned by this activity", CarPlayBackgroundSession.isOwner(activity))
        assertTrue("the session should read as running", CarPlayBackgroundSession.hasSession())
    }
}
