package com.shilapi.xcertplay.settings

import android.content.Context
import android.graphics.Color

/**
 * The visual tokens every DiPlay surface is drawn with, in Lynk & Co's language.
 *
 * Evidence rather than taste (docs/_digest/60): the official wordmark asset is pure `#000000` on white, the
 * brand's digital surfaces sampled from its own site imagery are near-black and slightly warm (`#10100E`,
 * `#141311`) rather than blue-black, and LYNK OS N's published layout language is swipe-divided surfaces
 * with a control centre and a customisable dock - which is what the three-finger overlay is.
 *
 * So: monochrome first (white on warm black), weight and spacing carry the hierarchy, one restrained
 * functional palette for warnings, small 4 dp radii for a precise technical feel, and 1 dp hairlines
 * instead of shadows. [accent] is deliberately white: the brand's digital identity is black and white, so
 * colour is not used to mean "selected" - see [accentTrack] and the widget states.
 */
enum class SettingsTheme(
    val isOverlay: Boolean,
    val textPrimary: Int,
    val textSecondary: Int,
    val accent: Int,
    val accentTrack: Int,
    val trackOff: Int,
    val buttonText: Int,
    /** Page background behind every card. */
    val background: Int = Color.BLACK,
    /** Card and list surface. */
    val surface: Int = Color.rgb(0x10, 0x10, 0x10),
    /** Raised surface: pressed rows, switches, chips. */
    val surfaceElevated: Int = Color.rgb(0x1A, 0x19, 0x17),
    /** 1 dp separators and outlines; there are no drop shadows anywhere in this system. */
    val hairline: Int = Color.rgb(0x2A, 0x29, 0x26),
    val textDisabled: Int = Color.rgb(0x5C, 0x5B, 0x58),
    val warning: Int = Color.rgb(0xE0, 0xA6, 0x4A),
    val danger: Int = Color.rgb(0xE0, 0x57, 0x4A),
    val success: Int = Color.rgb(0x7F, 0xC7, 0x8E),
) {
    /** Cards and settings pages: white on warm black, hierarchy from weight. */
    CARD(
        isOverlay = false,
        textPrimary = Color.rgb(0xFF, 0xFF, 0xFF),
        textSecondary = Color.rgb(0x9A, 0x9A, 0x96),
        accent = Color.rgb(0xFF, 0xFF, 0xFF),
        accentTrack = Color.rgb(0x3A, 0x39, 0x36),
        trackOff = Color.rgb(0x2A, 0x29, 0x26),
        buttonText = Color.rgb(0x0A, 0x0A, 0x0A),
    ),

    /** The three-finger overlay sits on top of CarPlay, so it is darker and quieter than a page. */
    OVERLAY(
        isOverlay = true,
        textPrimary = Color.rgb(0xFF, 0xFF, 0xFF),
        textSecondary = Color.rgb(0x9A, 0x9A, 0x96),
        accent = Color.rgb(0xFF, 0xFF, 0xFF),
        accentTrack = Color.rgb(0x3A, 0x39, 0x36),
        trackOff = Color.rgb(0x33, 0x32, 0x2F),
        buttonText = Color.rgb(0x0A, 0x0A, 0x0A),
        background = Color.rgb(0x00, 0x00, 0x00),
        surface = Color.rgb(0x0C, 0x0C, 0x0B),
        surfaceElevated = Color.rgb(0x18, 0x17, 0x16),
    );

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    /** The 4 dp corner used by cards, chips and rows: squared enough to read as technical. */
    fun corner(context: Context): Float = dp(context, 4).toFloat()
}
