package com.shilapi.xcertplay

/**
 * Refuses the second delivery of one physical media-key press.
 *
 * On this head unit a single steering-wheel press can reach us through more than one path: the CarPlay
 * host activity's key dispatch, the Android 4.4 `MEDIA_BUTTON` broadcast, and the wheel-key
 * accessibility filter. EasyPlay guards the same hazard with the same three fields — keyCode plus the
 * event's own downTime and eventTime (`LegacyMediaKeyGate`, docs/_digest/37) — and identity rather
 * than a time window is what keeps a deliberate double tap working.
 *
 * Lives in the Android-free part of this module: callers pass the values, tests pass literals.
 */
internal object MediaKeyEchoGate {
    private var keyCode = Int.MIN_VALUE
    private var downTime = Long.MIN_VALUE
    private var eventTime = Long.MIN_VALUE

    /** True when this exact press has not been accepted before. */
    @Synchronized
    fun accept(keyCode: Int, downTime: Long, eventTime: Long): Boolean {
        if (keyCode == this.keyCode && downTime == this.downTime && eventTime == this.eventTime) return false
        this.keyCode = keyCode
        this.downTime = downTime
        this.eventTime = eventTime
        return true
    }

    /** Called when the media-key route is torn down so a later session starts clean. */
    @Synchronized
    fun reset() {
        keyCode = Int.MIN_VALUE
        downTime = Long.MIN_VALUE
        eventTime = Long.MIN_VALUE
    }
}
