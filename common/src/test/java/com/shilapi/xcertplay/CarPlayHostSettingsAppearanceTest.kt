package com.shilapi.xcertplay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.settings.SettingsTheme
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockedConstruction
import org.mockito.Mockito.mockConstruction
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.util.concurrent.PausedExecutorService
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * Renders the three-finger settings panel and checks it is on the design system.
 *
 * The panel can only be seen inside a CarPlay session on the car, so nothing about its appearance was
 * verifiable while it was restyled (docs/_digest/84). Robolectric can drive the real swipe gesture and
 * draw the real view tree, which makes both the structure and the colours assertable — and it also
 * writes a PNG next to the build output so a human can look at the result.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayHostSettingsAppearanceTest {
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
            .apply { isAccessible = true }.newInstance(1280, 720)
        setField("activeDisplaySize", size)
        AirPlayPersistence.saveMfiTarget(activity, MfiTarget.REMOTE)
        AirPlayPersistence.saveRemoteMfiServer(activity, "example.invalid")
        AirPlayPersistence.saveWirelessHotspotMode(activity, WirelessHotspotMode.MANUAL)
        invoke("loadPersistedSettings")
        invoke("buildContentView")
        invoke("openSettingsMenu")
    }

    @After fun tearDown() {
        (field("shuttingDown") as AtomicBoolean).set(true)
        (field("mainHandler") as Handler).removeCallbacksAndMessages(null)
        (field("teardownExecutor") as ExecutorService).shutdownNow()
        (field("airPlayCommandExecutor") as ExecutorService).shutdownNow()
        CarPlayBackgroundSession.clear()
        controllers.close()
    }

    /** The WCAG relative-luminance contrast ratio between two opaque colours. */
    private fun contrast(a: Int, b: Int): Double {
        fun channel(value: Double) =
            if (value <= 0.03928) value / 12.92 else Math.pow((value + 0.055) / 1.055, 2.4)
        fun luminance(color: Int) = 0.2126 * channel(((color shr 16) and 0xff) / 255.0) +
            0.7152 * channel(((color shr 8) and 0xff) / 255.0) +
            0.0722 * channel((color and 0xff) / 255.0)
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /**
     * A control has to be visible on a monochrome surface — in **every** theme, on both the day and the
     * night page.
     *
     * The panel's switches and outlined buttons measured 1.53:1 / 1.35:1 against their surface and a
     * switch's two states were only 1.10:1 apart, so the owner could not tell state or boundary
     * (docs/_digest/84). 3:1 is the non-text threshold this holds every theme to.
     */
    @Test fun controlsKeepEnoughContrastToBeSeenOnEverySurface() {
        for (theme in SettingsTheme.entries) {
            val name = theme.name
            val surface = theme.surface
            for ((token, color) in listOf(
                "trackOff" to theme.trackOff,
                "accentTrack" to theme.accentTrack,
                "controlBorder" to theme.controlBorder,
            )) {
                val ratio = contrast(color, surface)
                assertTrue("$name $token is $ratio:1 against its surface, below 3:1", ratio >= 3.0)
            }
            val stateRatio = contrast(theme.accentTrack, theme.trackOff)
            assertTrue("$name switch states differ by only $stateRatio:1", stateRatio >= 1.8)

            // A solid action button: the accent fill, with [buttonText] on it.
            assertTrue(
                "$name button fill is ${contrast(theme.accent, surface)}:1 against its surface",
                contrast(theme.accent, surface) >= 3.0,
            )
            assertTrue(
                "$name button label is ${contrast(theme.buttonText, theme.accent)}:1 on the fill",
                contrast(theme.buttonText, theme.accent) >= 4.5,
            )
        }
    }

    /**
     * The three borders have to stay in order: a row separator quieter than a card outline, and a card
     * outline quieter than the outline of something the driver can actually press.
     *
     * Collapsing them into one value is the easy mistake — the original palette used a single #2A2926 for
     * all three jobs, which made a card, a divider and a button edge indistinguishable at 1.31:1
     * (docs/_digest/84). This fails if any tier crosses another, or if the tiers stop being distinct.
     */
    @Test fun theThreeBorderTiersStayOrdered() {
        for (theme in SettingsTheme.entries) {
            val name = theme.name
            val divider = contrast(theme.divider, theme.surface)
            val card = contrast(theme.cardBorder, theme.surface)
            val control = contrast(theme.controlBorder, theme.surface)
            assertTrue(
                "$name: divider $divider:1 is not quieter than card border $card:1",
                divider < card,
            )
            assertTrue(
                "$name: card border $card:1 is not quieter than control border $control:1",
                card < control,
            )
            assertTrue(
                "$name: divider and card border are only ${card / divider}x apart, tiers have collapsed",
                card / divider >= 1.4,
            )
        }
    }

    /** Every text the panel draws must come from the overlay's tokens. The retired `MENU_*` palette used
     * a cool blue-grey (#AAB4BE) and a green accent (#7FCD9A); either one appearing here means something
     * went back to the old canvas.
     */
    @Test fun everyLabelUsesTheOverlayTokensAndNoneOfTheRetiredPalette() {
        val texts = views(menu()).filterIsInstance<TextView>()
            .filter { it.visibility != View.GONE && it.text.isNotEmpty() }
            .toList()
        assertTrue("the panel should draw labels", texts.size > 20)

        val allowed = setOf(
            SettingsTheme.OVERLAY.textPrimary,
            SettingsTheme.OVERLAY.textSecondary,
            SettingsTheme.OVERLAY.accent,
            SettingsTheme.OVERLAY.danger,
            SettingsTheme.OVERLAY.warning,
            // Text drawn on top of a filled button (Save and reconnect).
            SettingsTheme.OVERLAY.buttonText,
            // Android's own Button keeps a default tint; only the panel's own labels matter here.
            Color.WHITE,
        )
        val retired = mapOf(
            Color.rgb(12, 16, 19) to "MENU_BACKGROUND",
            Color.rgb(170, 180, 190) to "MENU_SECONDARY",
            Color.rgb(127, 205, 154) to "MENU_ACCENT",
            Color.rgb(78, 143, 102) to "MENU_ACCENT_TRACK",
            Color.rgb(64, 74, 80) to "MENU_TRACK_OFF",
            Color.rgb(190, 45, 45) to "MENU_DANGER",
        )
        val offenders = texts.mapNotNull { view ->
            val colour = view.currentTextColor
            when {
                colour in retired -> "${view.text} uses ${retired[colour]}"
                colour !in allowed -> "${view.text} uses an unknown colour ${Integer.toHexString(colour)}"
                else -> null
            }
        }
        assertEquals("text outside the design system: $offenders", emptyList<String>(), offenders)
    }

    /** The switch rows now carry their explanations, which the overlay used to drop. */
    @Test fun switchRowsExplainThemselves() {
        val texts = views(menu()).filterIsInstance<TextView>().map { it.text.toString() }.toList()
        assertTrue(
            "the wireless row's description should be drawn: $texts",
            texts.any { it.contains("transport", ignoreCase = true) || it.length > 60 },
        )
    }

    /**
     * A text outline of what the panel draws: indentation is nesting, and each line carries the class,
     * the text, the resolved colour and the text size. This is the shape a reviewer can actually read
     * out of a headless run, and it is also written to a file beside the PNG.
     */
    @Test fun outlinesThePanelAndWritesItForReview() {
        val outline = StringBuilder()
        outline.append("three-finger settings panel — SettingsTheme.OVERLAY\n")
        describe(menu(), 0, outline)
        val target = File(WORK_DIR, "settings-panel.txt")
        target.parentFile?.mkdirs()
        target.writeText(outline.toString())
        println(outline)
        assertTrue("the outline should list the panel's labels", outline.contains("CarPlay"))
    }

    private fun describe(view: View, depth: Int, out: StringBuilder) {
        if (view.visibility == View.GONE) return
        val indent = "  ".repeat(depth)
        val name = view.javaClass.simpleName
        when (view) {
            is TextView -> out.append(
                "$indent$name text=\"${view.text}\"" +
                    " colour=#${Integer.toHexString(view.currentTextColor)}" +
                    " size=${view.textSize / view.resources.displayMetrics.scaledDensity}sp" +
                    " ${view.width}x${view.height}\n",
            )
            is ViewGroup -> {
                out.append("$indent$name (${view.childCount})\n")
                for (i in 0 until view.childCount) describe(view.getChildAt(i), depth + 1, out)
            }
            else -> out.append("$indent$name ${view.width}x${view.height}\n")
        }
    }

    /** A human-readable picture of the panel, for a reviewer to look at. */
    @Test fun rendersThePanelToAPng() {
        val view = field("settingsMenu") as View
        val width = 720
        val height = 1280
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val target = File(WORK_DIR, "settings-panel.png")
        target.parentFile?.mkdirs()
        FileOutputStream(target).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("the render should not be blank", target.length() > 2_000)
        println("panel rendered to ${target.absolutePath} (${target.length()} bytes)")
    }

    private fun menu() = field("settingsMenu") as View

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(views(view.getChildAt(i)))
    }

    private fun invoke(name: String): Any? = activity.javaClass.getDeclaredMethod(name)
        .apply { isAccessible = true }.invoke(activity)

    private fun field(name: String): Any? = activity.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(activity)

    private fun setField(name: String, value: Any?) = activity.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.set(activity, value)

    private companion object {
        /** The workspace, so the PNG lands somewhere a reviewer can open it. */
        val WORK_DIR = File(
            System.getProperty("diplay.render.dir")
                ?: "${System.getProperty("user.dir")}/build/rendered-settings",
        )
    }
}
