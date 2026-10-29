package com.shilapi.xcertplay.settings

import android.content.Context
import android.graphics.Color

/**
 * The visual tokens every DiPlay surface is drawn with.
 *
 * The palette is the one picked from the redesign (work/ui-redesign-20261028): a cool grey page, white cards
 * and a single teal signal. Day and night are two value sets of the same structure, so switching the cabin
 * theme never moves a control.
 *
 * ## Boundaries without shadows
 *
 * A drop shadow on a `View` requires `setLayerType(LAYER_TYPE_SOFTWARE)`, which on the CS11's i.MX6Q
 * software-rasterises the whole subtree every frame; the picture pipeline is already the bottleneck, so no
 * surface in this app draws one. A surface reads as a surface through a background-luminance step and a
 * 1 dp outline instead, and three outline tiers carry the boundary:
 *
 * | token | job |
 * |---|---|
 * | [divider] | separates rows inside one card. Deliberately quiet. |
 * | [cardBorder] | outlines a card against the page. |
 * | [controlBorder] | outlines anything the driver can operate. Holds >=3:1. |
 *
 * The 3:1 on [controlBorder], [trackOff] and [accentTrack] is not decoration: the old tokens measured
 * 1.31:1 / 1.50:1 and a column of buttons and a row of switches had no visible edges at all (docs/_digest/84).
 * `CarPlayHostSettingsAppearanceTest` measures every value in this enum - the switch-state gap, the
 * button-label ratio and the order of the three borders - so change a value there only after re-running it.
 *
 * ## Day and night
 *
 * In daylight the page is `#E8EBEF` and cards are white, so the luminance step is small and the outline can
 * stay quiet; at night the page is near-black and the outline does the work. No night surface is brighter
 * than `#1F1F24`, which is what keeps a dark cabin from being flashed by the settings page.
 */
enum class SettingsTheme(
    val isOverlay: Boolean,
    val textPrimary: Int,
    val textSecondary: Int,
    val textDisabled: Int,
    /** Fill of the primary action, the switch track when on, and the gauge's live segments. */
    val accent: Int,
    /** The switch track when on: [accent] held at 3:1 against the surface, and 1.8:1 against [trackOff]. */
    val accentTrack: Int,
    val trackOff: Int,
    /** The label that sits on [accent], and the switch thumb when on. */
    val buttonText: Int,
    /** Page background behind every card. */
    val background: Int,
    /** Card and list surface. */
    val surface: Int,
    /** Raised surface: switches, chips, pressed rows. */
    val surfaceElevated: Int,
    /** A soft wash of [accent] for a selected tab, a pill, a highlighted row. */
    val accentSoft: Int,
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
        textPrimary = Color.rgb(0xF1, 0xF4, 0xF7),
        textSecondary = Color.rgb(0x9A, 0xA4, 0xB2),
        textDisabled = Color.rgb(0x6C, 0x76, 0x83),
        accent = Color.rgb(0x35, 0xD6, 0xBE),
        accentTrack = Color.rgb(0x2A, 0xA8, 0x94),
        trackOff = Color.rgb(0x5E, 0x68, 0x75),
        buttonText = Color.rgb(0x06, 0x20, 0x1C),
        background = Color.rgb(0x0B, 0x0D, 0x10),
        surface = Color.rgb(0x15, 0x18, 0x1D),
        surfaceElevated = Color.rgb(0x25, 0x2D, 0x37),
        accentSoft = Color.rgb(0x12, 0x30, 0x2C),
        divider = Color.rgb(0x23, 0x29, 0x32),
        cardBorder = Color.rgb(0x3B, 0x46, 0x53),
        controlBorder = Color.rgb(0x66, 0x71, 0x7F),
        warning = Color.rgb(0xE0, 0xA6, 0x4A),
        danger = Color.rgb(0xE5, 0x65, 0x5C),
        success = Color.rgb(0x4F, 0xD6, 0xA0),
    ),

    /** The same page in daylight. Cards are white on a cool grey page. */
    DAY(
        isOverlay = false,
        textPrimary = Color.rgb(0x17, 0x1A, 0x20),
        textSecondary = Color.rgb(0x6E, 0x76, 0x82),
        textDisabled = Color.rgb(0x9A, 0xA1, 0xAC),
        accent = Color.rgb(0x0B, 0x7A, 0x6D),
        accentTrack = Color.rgb(0x0A, 0x6B, 0x60),
        trackOff = Color.rgb(0x80, 0x8C, 0x9A),
        buttonText = Color.rgb(0xFF, 0xFF, 0xFF),
        background = Color.rgb(0xE8, 0xEB, 0xEF),
        surface = Color.rgb(0xFF, 0xFF, 0xFF),
        surfaceElevated = Color.rgb(0xEE, 0xF1, 0xF6),
        accentSoft = Color.rgb(0xE1, 0xF2, 0xEF),
        divider = Color.rgb(0xE8, 0xEC, 0xF0),
        cardBorder = Color.rgb(0xB8, 0xC3, 0xCF),
        controlBorder = Color.rgb(0x6F, 0x7B, 0x88),
        warning = Color.rgb(0x8A, 0x5A, 0x00),
        danger = Color.rgb(0xB3, 0x26, 0x1E),
        success = Color.rgb(0x2C, 0x7A, 0x4E),
    ),

    /**
     * The three-finger overlay, which is drawn **on top of live CarPlay video** rather than on a page.
     *
     * It does not follow day/night: its background is a black scrim over moving picture, so a light variant
     * would flash the cabin at night and wash out the video underneath. The teal is the page's signal colour,
     * one step brighter so it survives a bright video frame behind it.
     */
    OVERLAY(
        isOverlay = true,
        textPrimary = Color.rgb(0xF1, 0xF4, 0xF7),
        textSecondary = Color.rgb(0x9A, 0xA4, 0xB2),
        textDisabled = Color.rgb(0x5C, 0x64, 0x6E),
        accent = Color.rgb(0x35, 0xD6, 0xBE),
        accentTrack = Color.rgb(0x2A, 0xA8, 0x94),
        trackOff = Color.rgb(0x5E, 0x68, 0x75),
        buttonText = Color.rgb(0x06, 0x20, 0x1C),
        background = Color.rgb(0x0B, 0x0D, 0x10),
        surface = Color.rgb(0x12, 0x16, 0x1B),
        surfaceElevated = Color.rgb(0x25, 0x2D, 0x37),
        accentSoft = Color.rgb(0x12, 0x30, 0x2C),
        divider = Color.rgb(0x23, 0x29, 0x32),
        cardBorder = Color.rgb(0x3B, 0x46, 0x53),
        controlBorder = Color.rgb(0x66, 0x71, 0x7F),
        warning = Color.rgb(0xE0, 0xA6, 0x4A),
        danger = Color.rgb(0xE5, 0x65, 0x5C),
        success = Color.rgb(0x4F, 0xD6, 0xA0),
    );

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    /** The 12 dp corner used by controls: buttons, chips, tab cells. */
    fun corner(context: Context): Float = dp(context, 12).toFloat()

    /** The 18 dp corner used by cards, the largest surface on a page. */
    fun cardCorner(context: Context): Float = dp(context, 18).toFloat()

    companion object {
        /** The page theme for a cabin that is currently in night mode. */
        fun page(night: Boolean): SettingsTheme = if (night) NIGHT else DAY
    }
}

