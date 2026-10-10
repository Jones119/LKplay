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
 * The window is a live system property so one ignition window can compare values without a rebuild:
 *
 * ```
 * adb shell setprop debug.diplay.video_rcvbuf 32768   # takes effect on the next stream SETUP
 * ```
 *
 * **The kernel doubles what is requested**: `setReceiveBufferSize(8192)` makes `getReceiveBufferSize()`
 * report 16384, so the numbers here are requests and the socket's own log line ("accepted rcvbuf=")
 * reports the real, doubled value. [DEFAULT_BYTES] carries the retuning measurements.
 *
 * Anything unset, unparsable, zero or negative falls back to the shipped default, and a positive
 * out-of-range value is clamped, so a bad property can never make the video path untestable.
 */
object VideoReceiveWindow {
    private const val TAG = "xcertplay-usb"
    private const val PROPERTY = "debug.diplay.video_rcvbuf"

    /**
     * The shipped request: 8 KiB, which the kernel doubles into a 16 KiB window.
     *
     * It began as 64 KiB (docs/_digest/18 §3 item 3) and was retuned on the car on 2026-10-10 with an
     * injected interaction load, three samples per size, all at similar or higher bitrate than the
     * larger windows (so the difference is not "cheaper content"):
     *
     * | requested | real window | late frames dropped | max frame gap | touch2frame p50 |
     * |---|---|---|---|---|
     * | 64 KiB (old) | 128 KiB | 161 | 1736 ms | 86 ms |
     * | 32 KiB | 64 KiB | 53 | 1687 ms | 65 ms |
     * | 8 KiB | 16 KiB | 17-28 | 304-331 ms | 53-60 ms |
     * | 4 KiB | 8 KiB | 17 | 239 ms | 51 ms |
     *
     * The kernel-dropped UDP audio (`Udp6InErrors`) did not move with the window (0.20-1.19 % at 16 KiB
     * against 0.60-0.65 % at 128 KiB), so this tuning buys frame delivery and touch latency, not loss.
     * 16 KiB is the shipped value rather than the marginally better 8 KiB because the headroom matters
     * if a heavier stream (1080p/60) is ever selected.
     */
    const val DEFAULT_BYTES = 8 * 1024

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
