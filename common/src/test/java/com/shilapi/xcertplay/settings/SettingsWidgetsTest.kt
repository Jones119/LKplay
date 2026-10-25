package com.shilapi.xcertplay.settings

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.TextView
import com.shilapi.xcertplay.airplay.CarPlayDisplayScale
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SettingsWidgetsTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun constructingAndChangingASwitchDoesNotSaveTheInitialValue() {
        for (theme in SettingsTheme.entries) {
            val saved = mutableListOf<Boolean>()
            val row = SettingsWidgets.createSwitchRow(context, "Title", "Details", true, theme,
                onChanged = saved::add)
            assertTrue(saved.isEmpty())
            row.switch.isChecked = false
            row.switch.isChecked = false
            assertEquals(listOf(false), saved)
        }
    }

    @Test fun choicesEmitOnlyNewSelectionsAndIgnoreClearingTheSelection() {
        val saved = mutableListOf<MfiTarget>()
        val row = ConnectionSettingsSection.createMfiTargetChoice(context, MfiTarget.LOCAL,
            onSelected = saved::add)
        assertTrue(saved.isEmpty())
        val target = views(row.container).filterIsInstance<RadioButton>().first { it.tag == MfiTarget.REMOTE }
        row.radioGroup.check(target.id)
        row.radioGroup.check(target.id)
        row.radioGroup.clearCheck()
        assertEquals(listOf(MfiTarget.REMOTE), saved)
    }

    @Test fun resolutionDisplayAndSliderStayWithinTheSameRangeWithoutSavingDuringConstruction() {
        for (initial in listOf(-1, 73, 999)) {
            val saved = mutableListOf<Int>()
            val row = SettingsWidgets.createResolutionSlider(context, initial, onPercentChanged = saved::add)
            val expected = initial.coerceIn(CarPlayDisplayScale.MIN_PERCENT, CarPlayDisplayScale.MAX_PERCENT)
            assertEquals(expected - CarPlayDisplayScale.MIN_PERCENT, row.seekBar.progress)
            assertEquals(expected.toString() + "%", row.valueTextView.text.toString())
            assertTrue(saved.isEmpty())
            row.seekBar.progress = 0
            row.seekBar.progress = Int.MAX_VALUE
            assertEquals(CarPlayDisplayScale.MAX_PERCENT.toString() + "%", row.valueTextView.text.toString())
            assertTrue(saved.all { it in CarPlayDisplayScale.MIN_PERCENT..CarPlayDisplayScale.MAX_PERCENT })
            assertEquals(CarPlayDisplayScale.MAX_PERCENT, saved.last())
        }
    }

    /**
     * The overlay keeps the monochrome tokens and the label-only metrics, and now shows a description
     * too when the caller supplies one.
     *
     * The description is the point: the overlay used to drop it, so the same switch explained itself on
     * the settings page and said nothing in the three-finger panel, and the panel drew its own prose
     * with a hardcoded colour from the retired palette (docs/_digest/60).
     */
    @Test fun overlayUsesTheMonochromeTokensAndLabelMetrics() {
        val row = ConnectionSettingsSection.createWirelessCarPlayRow(context, true, onChanged = {})
        val texts = views(row.rowView).filterIsInstance<TextView>().filter { it !== row.switch }.toList()
        // Label first, then the description the section passes for this row.
        assertEquals(2, texts.size)
        val label = texts[0]
        val description = texts[1]
        // 18 sp, the size every overlay row label uses: a switch, a choice and a slider label all read at
        // the same rank. The wireless row used to ask for 20 sp and stood out in the panel.
        assertEquals(18f, label.textSize / context.resources.displayMetrics.scaledDensity, 0.01f)
        assertFalse(label.includeFontPadding)
        assertEquals(Color.rgb(0x9A, 0x9A, 0x96), label.currentTextColor)
        assertEquals(Color.rgb(0x9A, 0x9A, 0x96), description.currentTextColor)
        // The switch is painted through drawables, not tint lists: `thumbTintList` is API 23+ and the CS11
        // is API 19, where it is silently absent — which is how the head unit ended up drawing the
        // framework's own Holo switch in colours this project never chose.
        val thumb = row.switch.thumbDrawable as GradientDrawable
        val track = row.switch.trackDrawable as GradientDrawable
        // The checked thumb is `buttonText`, not `accent`. `accent` is also the *fill* of a primary button,
        // and against the light `accentTrack` it measured about 1.9:1 — the driver reported the night
        // switches as "contrast a bit small" on the car (2026-10-18). `buttonText` is the colour that is
        // meant to sit on that family of fill, so the thumb-to-track contrast is roughly 8:1 in every theme.
        assertEquals(SettingsTheme.OVERLAY.buttonText, thumb.color?.defaultColor)
        assertEquals(SettingsTheme.OVERLAY.accentTrack, track.color?.defaultColor)
        // What actually matters for the driver: the thumb must not vanish into its own track.
        assertTrue(thumb.color!!.defaultColor != track.color!!.defaultColor)
        val heading = SettingsWidgets.createCategoryHeader(context, "Heading")
        assertEquals(Color.rgb(0xFF, 0xFF, 0xFF), heading.currentTextColor)
        assertFalse(heading.includeFontPadding)
    }

    /** A row with no description stays label-only, so the overlay does not gain empty space. */
    @Test fun anOverlayRowWithoutADescriptionRendersOnlyItsLabel() {
        val row = SettingsWidgets.createSwitchRow(context, "Title", "", false,
            SettingsTheme.OVERLAY, onChanged = {})
        val texts = views(row.rowView).filterIsInstance<TextView>().filter { it !== row.switch }.toList()
        assertEquals(listOf("Title"), texts.map { it.text.toString() })
    }

    @Test fun cardDescriptionsKeepMultilineSpacingAndTheirAccessibleTitle() {
        val row = SettingsWidgets.createSwitchRow(context, "Title", "First line\nSecond line", false,
            SettingsTheme.NIGHT, onChanged = {})
        val texts = views(row.rowView).filterIsInstance<TextView>().filter { it !== row.switch }.toList()
        assertEquals(listOf("Title", "First line\nSecond line"), texts.map { it.text.toString() })
        assertTrue(texts.all { it.lineSpacingExtra == SettingsTheme.NIGHT.dp(context, 3).toFloat() })
        assertEquals("Title", row.switch.contentDescription)
    }

    @Test @Config(sdk = [28]) fun android9HotspotChoiceExposesWifiDirectAndHidesExistingWifi() {
        // API 19 has a legacy Wi-Fi Direct path and no "attach to an existing Wi-Fi" backend.
        val row = ConnectionSettingsSection.createHotspotModeChoice(context, WirelessHotspotMode.MANUAL,
            onSelected = {})
        val options = views(row.container).filterIsInstance<RadioButton>().toList()
        assertEquals(listOf(WirelessHotspotMode.MANUAL, WirelessHotspotMode.WIFI_P2P), options.map { it.tag })
        assertEquals(WirelessHotspotMode.MANUAL, options.first { it.isChecked }.tag)
    }

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(views(view.getChildAt(i)))
    }
}
