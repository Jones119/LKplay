package com.shilapi.xcertplay.transport

import android.os.Process
import android.util.Log

/**
 * Scheduling priority for the wired transport threads: `ncm-us-rx` and `ncm-us-tx` (set here) and
 * lwIP's `lwip-tcpip` (bridge/sys_arch.c reads the same property).
 *
 * Every inbound video and LPCM audio packet and every outbound ACK, window update and touch report
 * passes through these three threads, yet they ran at nice 0 while their consumers run higher: the
 * video receive/decode threads at -8 ([com.shilapi.xcertplay.airplay.VideoThreadPriority]) and the audio
 * threads at -19. Under the 60 fps load in doc 102 that is a priority inversion in the middle of the
 * wired path. The shipped default is `AUDIO` (-16): above video, still below the audio writer.
 *
 * ```
 * adb shell setprop debug.diplay.transport_priority video    # -8, same as the video threads
 * adb shell setprop debug.diplay.transport_priority normal   # 0, the previous behaviour (A/B side)
 * adb shell setprop debug.diplay.transport_priority ''       # back to the default below
 * ```
 *
 * Read when a pump starts, so the value takes effect on the next wired session.
 */
object TransportThreadPriority {
    private const val TAG = "xcertplay-usb"
    private const val PROPERTY = "debug.diplay.transport_priority"

    val DEFAULT = Process.THREAD_PRIORITY_AUDIO

    /** The pure part: one property value to one priority. JVM-testable without a device. */
    fun sanitize(raw: String?): Int = when (raw?.trim()?.lowercase()) {
        "video", "-8" -> Process.THREAD_PRIORITY_URGENT_DISPLAY
        "normal", "0" -> Process.THREAD_PRIORITY_DEFAULT
        else -> DEFAULT
    }

    fun current(): Int = sanitize(systemProperty()).also { priority ->
        if (priority != DEFAULT) Log.i(TAG, "transport thread priority overridden by $PROPERTY: $priority")
    }

    /** Applies [current] to the calling thread; a refusal is logged, never fatal. */
    fun applyToCurrentThread() {
        runCatching { Process.setThreadPriority(current()) }
            .onFailure { Log.w(TAG, "transport thread priority not applied: ${it.javaClass.simpleName}") }
    }

    /** `debug.diplay.transport_priority`; the ROM has no public API for it on API 19. */
    private fun systemProperty(): String? = runCatching {
        val get = Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
        get.invoke(null, PROPERTY) as? String
    }.getOrNull()
}
