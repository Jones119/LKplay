package com.shilapi.xcertplay

import android.app.AlertDialog
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], qualifiers = "en", manifest = Config.NONE)
class NightModeSettingsTest {
    private lateinit var controller: ActivityController<DiPlayActivity>
    private lateinit var activity: DiPlayActivity
    private lateinit var page: LinearLayout

    @Before fun setUp() {
        controller = Robolectric.buildActivity(DiPlayActivity::class.java)
        activity = controller.get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        activity.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        CarPlayBackgroundSession.clear()
        controller.setup().visible()
    }

    @After fun tearDown() {
        controller.pause().stop().destroy()
        CarPlayBackgroundSession.clear()
    }

    @Test fun savedModesShowAmbientSettingsOnlyForAmbientLight() {
        for (mode in CarPlayNightMode.entries) {
            AirPlayPersistence.saveCarPlayNightMode(activity, mode)
            renderSettings()
            assertAmbientVisible(mode == CarPlayNightMode.AMBIENT)
        }
    }

    /**
     * A mode change rebuilds the page, so the palette switches immediately.
     *
     * The old contract was "save, then go back to re-render", and the owner reported that picking 固定夜间
     * did nothing until some unrelated action rebuilt the page (2026-10-18). Once the mode choice calls
     * `render()` the settings rows are rebuilt too — and a real `render()` applies the category filter, so
     * this test re-opens the section with [renderSettings] instead of holding on to the previous views.
     */
    @Test fun savingModeRebuildsThePageAndKeepsEditedValues() {
        AirPlayPersistence.saveCarPlayNightMode(activity, CarPlayNightMode.AMBIENT)
        renderSettings()
        editNumber(R.string.ambient_light_threshold_title, 75)
        editNumber(R.string.ambient_delay_title, 7)

        for (mode in listOf(CarPlayNightMode.SYSTEM, CarPlayNightMode.DAY,
            CarPlayNightMode.NIGHT, CarPlayNightMode.AMBIENT)) {
            selectMode(mode).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(mode, AirPlayPersistence.loadCarPlayNightMode(activity))
            // Whatever the page did, the values edited before the switch have to survive it.
            assertEquals(75, AirPlayPersistence.loadAmbientLightThreshold(activity).lux)
            assertEquals(7, AirPlayPersistence.loadAmbientDelaySeconds(activity))
            renderSettings()
        }
        assertAmbientVisible(true)
        assertTrue(button(R.string.ambient_light_threshold_title).text.toString().endsWith("75 lux"))
        assertTrue(button(R.string.ambient_delay_title).text.toString().endsWith("7 s"))
    }

    @Test fun cancellingModeChoiceKeepsSavedModeAndVisibility() {
        for (mode in listOf(CarPlayNightMode.SYSTEM, CarPlayNightMode.AMBIENT)) {
            AirPlayPersistence.saveCarPlayNightMode(activity, mode)
            renderSettings()
            val target = if (mode == CarPlayNightMode.SYSTEM) CarPlayNightMode.AMBIENT else CarPlayNightMode.SYSTEM
            val dialog = selectMode(target)
            assertAmbientVisible(mode == CarPlayNightMode.AMBIENT)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(mode, AirPlayPersistence.loadCarPlayNightMode(activity))
            assertAmbientVisible(mode == CarPlayNightMode.AMBIENT)
        }
    }

    private fun renderSettings() {
        // The activity is told it is on the settings page, because the mode choice now calls `render()`
        // to switch the palette at once and `render()` dispatches on that field. Without this the rebuild
        // drew the home page and the settings rows vanished from the test.
        DiPlayActivity::class.java.getDeclaredField("page")
            .apply { isAccessible = true }.set(activity, "settings")
        page = LinearLayout(activity)
        DiPlayActivity::class.java.getDeclaredMethod("settings", LinearLayout::class.java)
            .apply { isAccessible = true }.invoke(activity, page)
        activity.setContentView(page)
    }

    /**
     * Whether the row is really on screen: its own flag and every ancestor's.
     *
     * `View.isShown()` would also require the tree to be attached to a window. Selecting a night mode now
     * rebuilds the page so the palette changes at once, and a tree rebuilt inside a click handler is not
     * attached yet in this harness, so `isShown` reported false for rows that are in fact visible. This
     * walks the same chain without the attachment requirement, which is the property under test.
     */
    private fun effectivelyVisible(view: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current.visibility != View.VISIBLE) return false
            current = current.parent as? View
        }
        return true
    }

    private fun assertAmbientVisible(expected: Boolean) {
        for (id in listOf(R.string.ambient_light_threshold_title, R.string.ambient_delay_title)) {
            assertEquals(activity.getString(id), expected, effectivelyVisible(button(id)))
        }
        assertEquals(expected, effectivelyVisible(label(R.string.carplay_night_ambient_hint)))
        for (id in listOf(R.string.carplay_night_hint, R.string.carplay_night_time_note, R.string.picture_adjustments)) {
            assertTrue(activity.getString(id), effectivelyVisible(label(id)))
        }
    }

    private fun selectMode(mode: CarPlayNightMode): AlertDialog {
        button(R.string.carplay_night_mode).performClick()
        return ShadowAlertDialog.getLatestAlertDialog().also {
            it.listView.performItemClick(null, CarPlayNightMode.entries.indexOf(mode), 0)
        }
    }

    private fun editNumber(title: Int, value: Int) {
        button(title).performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        shadowOf(Looper.getMainLooper()).idle()
        views(dialog.window!!.decorView).filterIsInstance<EditText>().single().setText(value.toString())
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    /**
     * The live view tree, not the captured `page`.
     *
     * Selecting a night mode rebuilds the page so the palette switches at once, which means the layout the
     * test built is replaced by a new one. Looking views up through the activity keeps these helpers
     * pointing at whatever is actually on screen.
     */
    private val currentPage: View
        get() = activity.window.decorView ?: page

    private fun button(title: Int) = views(currentPage).filterIsInstance<Button>().single {
        it.text.startsWith(activity.getString(title) + " · ")
    }

    private fun label(text: Int) = views(currentPage).filterIsInstance<TextView>().single {
        it.text == activity.getString(text)
    }

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(views(view.getChildAt(index)))
    }
}
