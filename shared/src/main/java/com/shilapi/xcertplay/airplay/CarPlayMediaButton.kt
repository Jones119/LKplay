package com.shilapi.xcertplay.airplay

import android.view.KeyEvent

/**
 * Hardware media keys → CarPlay media HID presses (indices into [AirPlayHid]'s media report).
 *
 * Hardware play and pause keys both map to the toggle: the head unit picks PLAY or PAUSE from its own
 * idea of the play state, and a wrong guess would make the button do nothing. Explicit play and pause
 * commands from media controllers use [PLAY] and [PAUSE].
 */
object CarPlayMediaButton {
    const val PLAY = 1
    const val PAUSE = 2
    const val PLAY_PAUSE = 3
    const val NEXT = 4
    const val PREVIOUS = 5

    /** The steering-wheel play/pause key of this head unit; the firmware normally rewrites it to MEDIA_PLAY/PAUSE. */
    const val KEYCODE_AUTO_MEDIA_PLAY_PAUSE = 353

    /** Whether [keyCode] is a voice key that opens Siri. */
    fun opensSiri(keyCode: Int): Boolean = keyCode == KeyEvent.KEYCODE_VOICE_ASSIST

    /** The CarPlay press for [keyCode], or null when the key is not a media key CarPlay handles. */
    fun forKeyCode(keyCode: Int): Int? = when (keyCode) {
        KeyEvent.KEYCODE_MEDIA_NEXT -> NEXT
        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> PREVIOUS
        KeyEvent.KEYCODE_MEDIA_PLAY,
        KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_HEADSETHOOK,
        KEYCODE_AUTO_MEDIA_PLAY_PAUSE -> PLAY_PAUSE
        else -> null
    }
}
