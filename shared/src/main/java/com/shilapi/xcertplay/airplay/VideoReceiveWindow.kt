package com.shilapi.xcertplay.airplay

import android.util.Log

/**
 * The TCP receive window the video stream advertises to the iPhone, in bytes.
 *
 * On the CS11 the kernel NCM link carries both the video (TCP, bursty) and the wired LPCM audio
 * (UDP, 137 packets/s, no retransmission), and the video bursts are what evict the audio datagrams
 * (`Udp6InErrors` spikes while the screen is dragged, docs/_digest/18 §2). The receive window is the
 * only end-to-end throttle we own: a smaller window caps how much the phone may have in flight, so it
 * arrives in smaller bursts and competes less with the audio.
 *
 * 64 KiB is the shipped value (docs/_digest/18 §3 item 3). The window is a live system property so a
 * single ignition window can compare values without a rebuild:
 *
 * ```
 * adb shell setprop debug.diplay.video_rcvbuf 32768   # takes effect on the next stream SETUP
 * ```
 *
 * **The kernel doubles what is requested**: `setReceiveBufferSize(8192)` makes `getReceiveBufferSize()`
 * report 16384, so the numbers here are requests and the socket's own log line ("accepted rcvbuf=")
 * reports the real, doubled value. Measured on the wire (2026-10-10, same injected load, CS11 wired):
 * effective 128 KiB -> 0.65 % UDP loss, 161 late frames dropped, max frame gap 1736 ms;
 * effective 16 KiB -> 0.20 % UDP loss, 28 dropped, max gap 331 ms.
 *
 * Anything unset, unparsable, zero or negative falls back to the shipped default, and a positive
 * out-of-range value is clamped, so a bad property can never make the video path untestable.
 */
object VideoReceiveWindow {
    private const val TAG = "xcertplay-usb"
    private const val PROPERTY = "debug.diplay.video_rcvbuf"

    /** The shipped window (docs/_digest/18 §3 item 3): full pipe, sender still paced. */
    const val DEFAULT_BYTES = 64 * 1024

    /** Below one large video frame in flight there is no pipelining left at all. */
    const val MIN_BYTES = 4 * 1024

    /** Above the shipped value the phone may dump a multi-megabit burst into the audio's queue. */
    const val MAX_BYTES = 256 * 1024

    /** The pure part: one property value to one window size. JVM-testable without a device. */
    fun sanitize(raw: String?): Int {
        val value = raw?.trim()?.toIntOrNull() ?: return DEFAULT_BYTES
        // A zero or negative value is not a window at all, so it reads as "unset" rather than as the
        // smallest legal window; a positive out-of-range value is clamped instead.
        if (value <= 0) return DEFAULT_BYTES
        return value.coerceIn(MIN_BYTES, MAX_BYTES)
    }

    /** The window for the next accepted video socket. Logs any non-default choice once. */
    fun current(): Int = sanitize(systemProperty()).also { bytes ->
        if (bytes != DEFAULT_BYTES) {
            Log.i(TAG, "video receive window overridden by $PROPERTY: ${bytes}B")
        }
    }

    /** `debug.diplay.video_rcvbuf`; the ROM has no public API for it on API 19. */
    private fun systemProperty(): String? = runCatching {
        val get = Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
        get.invoke(null, PROPERTY) as? String
    }.getOrNull()
}
