package com.shilapi.xcertplay.settings

import android.content.Context
import android.graphics.Color

/**
 * The visual tokens every DiPlay surface is drawn with, in Lynk & Co's language.
 *
 * Evidence rather than taste (docs/_digest/60): the official wordmark asset is pure `#000000` on white, the
 * brand's digital surfaces sampled from its own site imagery are near-black and slightly warm rather than
 * blue-black, and LYNK OS N's published layout language is swipe-divided surfaces with a control centre and
 * a customisable dock - which is what the three-finger overlay is.
 *
 * ## Boundaries without shadows
 *
 * The reference design system (docs/_digest/85) gives a surface three ways to read as a surface: a
 * background-luminance step, a border, and a shadow, and asks for at least two of the three. **This platform
 * can only ever have two.** A drop shadow on a `View` requires `setLayerType(LAYER_TYPE_SOFTWARE)`, which on
 * the CS11's i.MX6Q software-rasterises the whole subtree every frame; the picture pipeline is already the
 * bottleneck, so no surface in this app draws one.
 *
 * That leaves the border to carry the boundary, which is why there are three of them rather than one, and
 * why none of them is as quiet as a web design would make it:
 *
 * | token | against the card | job |
 * |---|---|---|
 * | [divider] | ~1.3:1 | separates rows inside one card. Deliberately quiet. |
 * | [cardBorder] | ~2.4:1 | outlines a card against the page. Never negative-contrast. |
 * | [controlBorder] | >=3:1 | outlines anything the driver can operate. |
 *
 * The 3:1 on [controlBorder] is the non-text threshold, and it is not decoration: these tokens used to be
 * #2A2926 / #3A3936, which measured 1.31:1 and 1.50:1, so a column of buttons and a row of switches had no
 * visible edges at all (docs/_digest/84). Keep it there.
 *
 * ## Day and night
 *
 * [dark] and [light] differ in more than inverted greys: in daylight the luminance step between card and
 * page is 8.6%, so the border can stay quiet; at night the step is 7.1% on a near-black page and the border
 * does all the work. The night values are optimised for a dark cabin - never a pure-white field, and no
 * surface brighter than #1F1F21.
 */
enum class SettingsTheme(
    val isOverlay: Boolean,
    val textPrimary: Int,
    val textSecondary: Int,
    val textDisabled: Int,
    val accent: Int,
    val accentTrack: Int,
    val trackOff: Int,
    val buttonText: Int,
    /** Page background behind every card. */
    val background: Int,
    /** Card and list surface. */
    val surface: Int,
    /** Raised surface: switches, chips, pressed rows. */
    val surfaceElevated: Int,
    /** Row separator inside a card. Kept quiet on purpose. */
    val divider: Int,
    /** The outline of a card against the page. */
    val cardBorder: Int,
    /** The outline of anything operable: buttons, switches, inputs. Holds >=3:1. */
    val controlBorder: Int,
    val warning: Int,
    val danger: Int,
    val success: Int,
) {
    /** The settings page and the home screen at night: white on near-black, borders carry the structure. */
    NIGHT(
        isOverlay = false,
        textPrimary = Color.rgb(0xFF, 0xFF, 0xFF),
        textSecondary = Color.rgb(0xA0, 0xA0, 0xA0),
        textDisabled = Color.rgb(0x6E, 0x6E, 0x73),
        accent = Color.rgb(0xFF, 0xFF, 0xFF),
        accentTrack = Color.rgb(0x9A, 0x9A, 0x96),
        trackOff = Color.rgb(0x6B, 0x6A, 0x65),
        buttonText = Color.rgb(0x0A, 0x0A, 0x0A),
        background = Color.rgb(0x0A, 0x0A, 0x0A),
        surface = Color.rgb(0x1C, 0x1C, 0x1E),
        surfaceElevated = Color.rgb(0x2A, 0x2A, 0x2C),
        divider = Color.rgb(0x2C, 0x2C, 0x2E),
        cardBorder = Color.rgb(0x55, 0x54, 0x4F),
        controlBorder = Color.rgb(0x6B, 0x6A, 0x65),
        warning = Color.rgb(0xE0, 0xB3, 0x41),
        danger = Color.rgb(0xE5, 0x53, 0x4B),
        success = Color.rgb(0x4C, 0xAF, 0x6E),
    ),

    /** The same page in daylight. The card/page step is 8.6%, so the borders can be quieter. */
    DAY(
        isOverlay = false,
        textPrimary = Color.rgb(0x1A, 0x1A, 0x1A),
        textSecondary = Color.rgb(0x66, 0x66, 0x66),
        textDisabled = Color.rgb(0x8E, 0x8E, 0x93),
        accent = Color.rgb(0x00, 0x00, 0x00),
        // The thumb is `accent`, so the track it sits on has to be a different value or the "on" switch
        // becomes one solid block. It was #000000 here (the same as `accent`) and the car rendered the
        // whole control black with no visible thumb — measured on the head unit 2026-10-18. NIGHT pairs a
        // white thumb with a mid grey track for the same reason; this is that pairing inverted.
        //
        // The value is dark enough to differ from `trackOff` (#8E8E93) by about 2.8:1 and light enough to
        // stay about 2.4:1 above the black thumb. A first attempt at #6E6E73 passed the thumb test but left
        // the two states only 1.55:1 apart, which `CarPlayHostSettingsAppearanceTest` rejected.
        accentTrack = Color.rgb(0x4A, 0x4A, 0x4E),
        trackOff = Color.rgb(0x8E, 0x8E, 0x93),
        buttonText = Color.rgb(0xFF, 0xFF, 0xFF),
        background = Color.rgb(0xF5, 0xF5, 0xF7),
        surface = Color.rgb(0xFF, 0xFF, 0xFF),
        surfaceElevated = Color.rgb(0xED, 0xED, 0xEF),
        divider = Color.rgb(0xE5, 0xE5, 0xE5),
        cardBorder = Color.rgb(0xB8, 0xB8, 0xBC),
        controlBorder = Color.rgb(0x8E, 0x8E, 0x93),
        warning = Color.rgb(0xB8, 0x86, 0x0B),
        danger = Color.rgb(0xB3, 0x26, 0x1E),
        success = Color.rgb(0x3A, 0x7D, 0x44),
    ),

    /**
     * The three-finger overlay, which is drawn **on top of live CarPlay video** rather than on a page.
     *
     * It does not follow day/night. Its background is a black scrim over moving picture, so a light variant
     * would flash the cabin at night and wash out the video underneath; what changes with the cabin instead
     * is CarPlay's own night mode, which is a separate stream.
     */
    OVERLAY(
        isOverlay = true,
        textPrimary = Color.rgb(0xFF, 0xFF, 0xFF),
        textSecondary = Color.rgb(0x9A, 0x9A, 0x96),
        textDisabled = Color.rgb(0x5C, 0x5B, 0x58),
        accent = Color.rgb(0xFF, 0xFF, 0xFF),
        accentTrack = Color.rgb(0x9A, 0x9A, 0x96),
        trackOff = Color.rgb(0x6B, 0x6A, 0x65),
        buttonText = Color.rgb(0x0A, 0x0A, 0x0A),
        background = Color.rgb(0x00, 0x00, 0x00),
        surface = Color.rgb(0x0C, 0x0C, 0x0B),
        surfaceElevated = Color.rgb(0x18, 0x17, 0x16),
        divider = Color.rgb(0x2A, 0x29, 0x26),
        cardBorder = Color.rgb(0x55, 0x54, 0x4F),
        controlBorder = Color.rgb(0x6B, 0x6A, 0x65),
        warning = Color.rgb(0xE0, 0xA6, 0x4A),
        danger = Color.rgb(0xE0, 0x57, 0x4A),
        success = Color.rgb(0x7F, 0xC7, 0x8E),
    );

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    /** The 4 dp corner used by cards, chips and rows: squared enough to read as technical. */
    fun corner(context: Context): Float = dp(context, 4).toFloat()

    companion object {
        /** The page theme for a cabin that is currently in night mode. */
        fun page(night: Boolean): SettingsTheme = if (night) NIGHT else DAY
    }
}
