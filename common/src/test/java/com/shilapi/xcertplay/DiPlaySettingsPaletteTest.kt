package com.shilapi.xcertplay

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.drawable.GradientDrawable
import com.shilapi.xcertplay.settings.SettingsTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * What the settings page actually draws, in both cabin modes.
 *
 * The page is the one surface that does not go through [SettingsTheme] at every call site: `DiPlayActivity`
 * resolves a palette and then exposes it as `BG` / `SURFACE` / `BORDER` / … The previous round of work fixed
 * the *theme* and left the page untouched, so the owner reported the same "cannot tell the controls apart"
 * problem a second time (docs/_digest/84). This test reads the resolved palette through the page's own
 * properties, which is what a token fix has to reach to count.
 *
 * It runs on SDK 33 rather than the car's API 19: Robolectric 4.17 ships no image below 21, and its default
 * image trips a JVM FileDescriptor introspection failure on this JDK. The API-19-specific branch - the
 * switch's drawables - is covered by its own test in `SettingsWidgetsTest` instead of being pretended here.
 * What this file owns is the palette *selection*, which is SDK-independent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DiPlaySettingsPaletteTest {

    private val workDir = File(
        System.getProperty("diplay.render.dir")
            ?: "build/rendered-settings",
    )

    /**
     * The car is API 19 and always in a dark cabin, so this is the configuration the head unit runs: the
     * owner's day/night setting is `SYSTEM` and Android reports night.
     */
    @Test @Config(sdk = [33], qualifiers = "night") fun theHeadUnitResolvesTheNightPalette() {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        assertEquals(SettingsTheme.NIGHT, paletteOf(activity))
        assertEquals(SettingsTheme.NIGHT.background, colour(activity, "BG"))
        assertEquals(SettingsTheme.NIGHT.surface, colour(activity, "SURFACE"))
        assertEquals(SettingsTheme.NIGHT.cardBorder, colour(activity, "BORDER"))
        assertEquals(SettingsTheme.NIGHT.controlBorder, colour(activity, "CONTROL_BORDER"))
        // There is deliberately no DIVIDER property on the page: its in-card separators come from BORDER, and
        // the divider token's own ordering is asserted by the appearance test.
    }

    /** And in daylight the same binary draws the light page, without a second build. */
    @Test @Config(sdk = [33], qualifiers = "notnight") fun daylightResolvesTheDayPalette() {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        assertEquals(SettingsTheme.DAY, paletteOf(activity))
        assertEquals(SettingsTheme.DAY.background, colour(activity, "BG"))
        assertNotEquals(
            "day and night must not resolve to the same page colour",
            colour(activity, "BG"),
            SettingsTheme.NIGHT.background,
        )
    }

    /**
     * The explicit settings win over Android's own mode, because `CarPlayNightMode` is also what drives
     * CarPlay's night mode: the page has to agree with the picture on the screen next to it.
     */
    @Test @Config(sdk = [33], qualifiers = "notnight") fun anExplicitNightChoiceOverridesTheSystemMode() {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        AirPlayPersistence.saveCarPlayNightMode(activity, CarPlayNightMode.NIGHT)
        assertEquals(SettingsTheme.NIGHT, paletteOf(activity))

        AirPlayPersistence.saveCarPlayNightMode(activity, CarPlayNightMode.DAY)
        assertEquals(SettingsTheme.DAY, paletteOf(activity))
    }

    /**
     * The three border tiers have to reach the widgets, not just the palette: reading the tokens is not the
     * same as a switch being painted from them, which is exactly how the switch shipped drawing the
     * framework's own colours on API 19 (see `SettingsWidgets.styleSwitch`).
     */
    @Test @Config(sdk = [33], qualifiers = "night") fun theResolvedPaletteReachesTheSettingsPageWidgets() {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        val column = LinearLayout(activity)
        DiPlayActivity::class.java.getDeclaredMethod("settings", LinearLayout::class.java)
            .apply { isAccessible = true }
            .invoke(activity, column)

        val outline = StringBuilder("settings page — resolved ${paletteOf(activity)}\n")
        describe(column, 0, outline)
        workDir.mkdirs()
        File(workDir, "settings-page.txt").writeText(outline.toString())

        val colours = collectTextColours(column)
        assertTrue("the settings page should draw labels", colours.size > 10)
        val allowed = setOf(
            SettingsTheme.NIGHT.textPrimary,
            SettingsTheme.NIGHT.textSecondary,
            SettingsTheme.NIGHT.textDisabled,
            SettingsTheme.NIGHT.accent,
            // The label on a solid action button is black-on-white, so its colour is buttonText, not a text
            // rank. It belongs in the set for the same reason the panel's does.
            SettingsTheme.NIGHT.buttonText,
            SettingsTheme.NIGHT.warning,
            SettingsTheme.NIGHT.danger,
            SettingsTheme.NIGHT.success,
        )
        val offenders = colours.filterNot { it in allowed }
        assertEquals(
            "labels outside the night palette: ${offenders.map { Integer.toHexString(it) }}",
            emptyList<Int>(),
            offenders,
        )
    }

    /**
     * A button's background has to be a control surface, not the card it sits on and not bare white.
     *
     * The owner's report was that a button's background either "merges with the container" or is "jarringly
     * white" (docs/_digest/84). Both halves were real: the secondary tier was transparent with a white
     * hairline, and the two places that did fill a control used `SURFACE`, which **is** the card's colour.
     * So this asserts the property that was missing rather than the specific colour: every button's fill is
     * measurably distinct from the card behind it.
     */
    @Test @Config(sdk = [33], qualifiers = "night") fun everyButtonsFillLiftsOffTheCard() {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        val column = LinearLayout(activity)
        DiPlayActivity::class.java.getDeclaredMethod("settings", LinearLayout::class.java)
            .apply { isAccessible = true }
            .invoke(activity, column)

        val palette = paletteOf(activity)
        val buttons = mutableListOf<Button>()
        collect(column, buttons)
        assertTrue("the settings page should draw buttons", buttons.isNotEmpty())

        val offenders = buttons.mapNotNull { button ->
            val fill = (button.background as? GradientDrawable)?.color?.defaultColor ?: return@mapNotNull null
            val ratio = contrastOf(fill, palette.surface)
            // A fill equal to the card is the "merges" case; a ratio this small means the eye sees one surface.
            if (ratio < 1.10) "${button.text} fills with #${Integer.toHexString(fill)} ($ratio:1)" else null
        }
        assertEquals("buttons that do not lift off the card: $offenders", emptyList<String>(), offenders)
    }

    /**
     * The primary fill is reserved for the page's own main action, never for a step inside a card.
     *
     * "Set up USB and map permissions" was drawn as a primary at one call site and a secondary at the
     * identical button elsewhere, so the same action was a white block on one screen and a dark one on the
     * other. This is the guard against that: a settings page has at most one primary action per rendered
     * screen, and the page here has none at all, because connecting is done from the home screen.
     */
    @Test @Config(sdk = [33], qualifiers = "night") fun thePageDoesNotSpendThePrimaryFillOnCardActions() {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        val column = LinearLayout(activity)
        DiPlayActivity::class.java.getDeclaredMethod("settings", LinearLayout::class.java)
            .apply { isAccessible = true }
            .invoke(activity, column)

        val palette = paletteOf(activity)
        val buttons = mutableListOf<Button>()
        collect(column, buttons)
        val primaries = buttons.filter { button ->
            (button.background as? GradientDrawable)?.color?.defaultColor == palette.accent
        }
        // Listed by label so a failure says which one, not just how many.
        val labels = primaries.map { it.text.toString() }
        assertTrue(
            "the settings page draws ${primaries.size} primary buttons ($labels); a card action is a " +
                "secondary, and the primary fill belongs to the page's own main action",
            primaries.size <= 1,
        )
    }

    /** WCAG relative-luminance contrast, the same measure the appearance test uses. */
    private fun contrastOf(a: Int, b: Int): Double {
        fun channel(value: Double) =
            if (value <= 0.03928) value / 12.92 else Math.pow((value + 0.055) / 1.055, 2.4)
        fun luminance(color: Int) = 0.2126 * channel(((color shr 16) and 0xff) / 255.0) +
            0.7152 * channel(((color shr 8) and 0xff) / 255.0) +
            0.0722 * channel((color and 0xff) / 255.0)
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun collect(view: View, into: MutableList<Button>) {
        if (view.visibility == View.GONE) return
        if (view is Button) into += view
        if (view is ViewGroup) for (i in 0 until view.childCount) collect(view.getChildAt(i), into)
    }

    /** `private val` in Kotlin is a getter, not a field, so the palette is read through `getNightPalette`. */
    private fun paletteOf(activity: DiPlayActivity): SettingsTheme =
        DiPlayActivity::class.java.getDeclaredMethod("getNightPalette")
            .apply { isAccessible = true }
            .invoke(activity) as SettingsTheme

    /** Reads one of the page's palette properties, e.g. `BORDER`, through its generated getter. */
    private fun colour(activity: DiPlayActivity, name: String): Int =
        DiPlayActivity::class.java.getDeclaredMethod("get$name")
            .apply { isAccessible = true }
            .invoke(activity) as Int

    private fun collectTextColours(view: View): List<Int> {
        val out = mutableListOf<Int>()
        fun walk(v: View) {
            if (v.visibility == View.GONE) return
            if (v is TextView && v.text.isNotEmpty()) out += v.currentTextColor
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(view)
        return out
    }

    private fun describe(view: View, depth: Int, out: StringBuilder) {
        if (view.visibility == View.GONE) return
        val indent = "  ".repeat(depth)
        when (view) {
            is TextView -> out.append(
                "$indent${view.javaClass.simpleName} text=\"${view.text}\"" +
                    " colour=#${Integer.toHexString(view.currentTextColor)}\n",
            )
            is ViewGroup -> {
                out.append("$indent${view.javaClass.simpleName} (${view.childCount})\n")
                for (i in 0 until view.childCount) describe(view.getChildAt(i), depth + 1, out)
            }
            else -> out.append("$indent${view.javaClass.simpleName}\n")
        }
    }
}
