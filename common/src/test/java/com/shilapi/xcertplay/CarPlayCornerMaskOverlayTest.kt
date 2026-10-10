package com.shilapi.xcertplay

import android.os.Handler
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.Switch
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockedConstruction
import org.mockito.Mockito.mockConstruction
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.android.util.concurrent.PausedExecutorService

/**
 * The screen corner mask as it is actually mounted (docs/_digest/42 §C12).
 *
 * What a device still has to confirm is the drawing itself: Robolectric cannot say that the four
 * wedges are anti-aliased, that they land exactly on the car's bezel curve, or that 72 px is the
 * right radius for this 1920x1080 panel. Everything asserted here is the wiring around that -- four
 * children, the persisted default, the live toggle, the draw order, and the promise that the mask
 * can never take a touch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayCornerMaskOverlayTest {
    private lateinit var activity: CarPlayHostActivity
    private lateinit var controllers: MockedConstruction<CarPlayController>

    @Before fun setUp() {
        activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        controllers = mockConstruction(CarPlayController::class.java)
        (field("teardownExecutor") as ExecutorService).shutdownNow()
        setField("teardownExecutor", PausedExecutorService())
        setField("airPlayIdentity", AirPlayIdentity.generate())
        val sizeClass = Class.forName("com.shilapi.xcertplay.CarPlayHostActivity\$DisplaySize")
        val size = sizeClass.getDeclaredConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.newInstance(1920, 1080)
        setField("activeDisplaySize", size)
        CarPlayBackgroundSession::class.java.getDeclaredField("owner").apply { isAccessible = true }
            .set(CarPlayBackgroundSession, activity)
        // Source-only tests have no provisioned local identity, so the persisted choice is the
        // remote service that validation accepts without local assets.
        AirPlayPersistence.saveMfiTarget(activity, MfiTarget.REMOTE)
        AirPlayPersistence.saveRemoteMfiServer(activity, REMOTE_MFI_SERVER)
        AirPlayPersistence.saveWirelessHotspotMode(activity, WirelessHotspotMode.WIFI_P2P)
    }

    @After fun tearDown() {
        (field("shuttingDown") as AtomicBoolean).set(true)
        (field("mainHandler") as Handler).removeCallbacksAndMessages(null)
        (field("teardownExecutor") as ExecutorService).shutdownNow()
        (field("airPlayCommandExecutor") as ExecutorService).shutdownNow()
        CarPlayBackgroundSession.clear()
        controllers.close()
    }

    @Test
    fun theMaskIsOffUntilTheOwnerAsksForItAndHoldsNoExtraTouch() {
        invoke("loadPersistedSettings")
        val root = invoke("buildContentView") as ViewGroup
        val mask = maskOf(root)

        assertEquals("off by default", View.GONE, mask.visibility)
        assertEquals("one view per corner", 4, mask.childCount)
        for (index in 0 until mask.childCount) {
            val corner = mask.getChildAt(index) as View
            assertFalse("corner $index must not be clickable", corner.isClickable)
            assertFalse("corner $index must not be focusable", corner.isFocusable)
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, corner.importantForAccessibility)
            // False is also what dispatchTouchEvent hands back for a child that does not take the
            // event, so no corner can consume any part of a gesture.
            assertFalse("corner $index must not consume touch", corner.onTouchEvent(event(10f, 10f)))
        }
        assertMaskNeverTakesTouch(mask)
        // Above the picture and below the touch layer, so it is drawn over CarPlay but dispatched to
        // after the layer that owns the three-finger settings gesture.
        assertEquals(0, root.indexOfChild(videoOf(root)))
        assertEquals(root.indexOfChild(mask), root.indexOfChild(field("gestureOverlay") as View) - 1)
    }

    @Test
    fun turningTheSettingOnMountsFourCornerViewsAndSavesTheChoiceForTheNextBoot() {
        AirPlayPersistence.saveScreenCornerMask(activity, true)
        invoke("loadPersistedSettings")
        val root = invoke("buildContentView") as ViewGroup
        val mask = maskOf(root)

        // On activity start the overlay follows the persisted flag, which is what survives an
        // ignition: nothing else has to run for the mask to come back.
        assertEquals(View.VISIBLE, mask.visibility)
        assertEquals(4, mask.childCount)
        // 1920x1080 -> radius 1080 / 15 = 72, so each corner square is 72 px and lands on a corner.
        layout(root, 1920, 1080)
        assertEquals(listOf(72, 72, 72, 72), List(4) { mask.getChildAt(it).width })
        assertEquals("top left", 0, mask.getChildAt(0).left)
        assertEquals("top left", 0, mask.getChildAt(0).top)
        assertEquals("top right", 1920, mask.getChildAt(1).right)
        assertEquals("bottom right", 1080, mask.getChildAt(2).bottom)
        assertEquals("bottom left", 0, mask.getChildAt(3).left)
        assertEquals("bottom left", 1080, mask.getChildAt(3).bottom)
    }

    @Test
    fun togglingTheSwitchAppliesImmediatelyAndCancellingTheMenuPutsItBack() {
        invoke("loadPersistedSettings")
        // Keep the overlay this test owns: buildContentView is the only thing that mounts one, and
        // the activity remembers the last one it built.
        val mask = maskOf(invoke("buildContentView") as ViewGroup)
        invoke("openSettingsMenu")
        assertFalse("the persisted default is off", AirPlayPersistence.loadScreenCornerMask(activity))

        // The switch is found through the menu that the display group built, so a rename or a move
        // out of the menu cannot pass silently.
        switchFor(field("settingsMenu") as View).performClick()
        assertTrue(AirPlayPersistence.loadScreenCornerMask(activity))
        // Live: the overlay was already in the tree, so closing the menu shows the mask without a
        // restart or a new CarPlay session.
        invoke("saveSettingsAndReconnect")
        assertEquals(View.VISIBLE, mask.visibility)

        // The mask saves as soon as the switch moves, so Cancel has to undo both the setting and the
        // live overlay.
        invoke("openSettingsMenu")
        switchFor(field("settingsMenu") as View).performClick()
        assertFalse(AirPlayPersistence.loadScreenCornerMask(activity))
        invoke("cancelSettingsEdits")
        assertEquals(View.VISIBLE, mask.visibility)
        assertTrue(field("screenCornerMask") as Boolean)
    }

    @Test
    fun theMaskIsHiddenWhileTheMenuCoversTheScreen() {
        AirPlayPersistence.saveScreenCornerMask(activity, true)
        invoke("loadPersistedSettings")
        val mask = maskOf(invoke("buildContentView") as ViewGroup)
        assertEquals(View.VISIBLE, mask.visibility)

        invoke("openSettingsMenu")
        assertEquals(View.GONE, mask.visibility)

        invoke("cancelSettingsEdits")
        assertEquals(View.VISIBLE, mask.visibility)
    }

    private fun assertMaskNeverTakesTouch(mask: CarPlayCornerMaskOverlay) {
        assertFalse("the mask itself must not be clickable", mask.isClickable)
        assertFalse("the mask must not be focusable", mask.isFocusable)
        assertEquals(ViewGroup.FOCUS_BLOCK_DESCENDANTS, mask.descendantFocusability)
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, mask.importantForAccessibility)
        assertFalse("the mask must not consume touch", mask.onTouchEvent(event(10f, 10f)))
        assertFalse("a corner tap belongs to the gesture layer", mask.onTouchEvent(event(1910f, 1070f)))
    }

    private fun maskOf(root: View): CarPlayCornerMaskOverlay =
        views(root).filterIsInstance<CarPlayCornerMaskOverlay>().firstOrNull()
            ?: throw AssertionError("no corner mask in the view tree")

    private fun videoOf(root: ViewGroup): TextureView = root.getChildAt(0) as TextureView

    /**
     * The mask switch, found through the menu the display group built. The row names the switch by
     * its description (the overlay theme drops the description label and keeps it for accessibility),
     * so that is the handle, and a rename of either string cannot pass silently.
     */
    private fun switchFor(root: View): Switch {
        val description = activity.getString(R.string.screen_corner_mask_description)
        return views(root).filterIsInstance<Switch>()
            .firstOrNull { it.contentDescription?.toString() == description }
            ?: throw AssertionError(
                "no switch row described as $description; found " +
                    views(root).filterIsInstance<Switch>().joinToString { "${it.contentDescription}" },
            )
    }

    private fun event(x: Float, y: Float): MotionEvent =
        MotionEvent.obtain(0, 10, MotionEvent.ACTION_DOWN, x, y, 0)

    private fun layout(root: View, width: Int, height: Int) {
        root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, width, height)
    }

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(views(view.getChildAt(index)))
    }

    private fun invoke(name: String): Any? = activity.javaClass.getDeclaredMethod(name)
        .apply { isAccessible = true }.invoke(activity)
    private fun field(name: String): Any? = activity.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(activity)
    private fun setField(name: String, value: Any?) {
        activity.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(activity, value)
    }

    private companion object {
        /** A remote MFi service address the settings screen accepts without local assets. */
        const val REMOTE_MFI_SERVER = "https://mfi.example.test"
    }
}
