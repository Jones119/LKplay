package com.shilapi.xcertplay

import android.graphics.Color

/** The screen before CarPlay's video follows CarPlay's day/night mode, so it does not glare at night. */
internal data class WaitingScreenColors(val background: Int, val text: Int, val secondary: Int) {
    companion object {
        // Lynk & Co tokens (docs/_digest/60): the night side is the brand's own near-black rather than the
        // old blue-black, and the day side is the same monochrome family inverted instead of a blue tint.
        fun of(night: Boolean): WaitingScreenColors = if (night) {
            WaitingScreenColors(Color.rgb(0x00, 0x00, 0x00), Color.rgb(0xFF, 0xFF, 0xFF), Color.rgb(0x9A, 0x9A, 0x96))
        } else {
            WaitingScreenColors(Color.rgb(0xF4, 0xF4, 0xF2), Color.rgb(0x0A, 0x0A, 0x0A), Color.rgb(0x6E, 0x6C, 0x68))
        }
    }
}
