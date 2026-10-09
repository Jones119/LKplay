package com.shilapi.xcertplay.diag

import android.util.Log

/**
 * Gate for the high-rate diagnostic logs: per-frame iAP2 dumps, event-channel hex, cluster pushes.
 *
 * Measured on the CS11 (docs/_digest/40): the app wrote ~55 logcat lines per second, of which 37/s were
 * iAP2 tunnel frame dumps - that filled the 256 KB main log buffer in 30 seconds and did the format
 * work inside the video/audio/event paths. Those traces only matter while debugging, so they stay off
 * unless the user enables "debug logs" in settings or sets `adb shell setprop debug.diplay.trace 1`.
 */
object DiagLog {
    /** Read on the hot paths; written by [update] from the app. */
    @Volatile
    var traceEnabled: Boolean = false
        private set

    /** Called at start-up and whenever the setting changes. */
    fun update(fromPreference: Boolean) {
        traceEnabled = fromPreference || systemPropertyEnabled()
    }

    /** `adb shell setprop debug.diplay.trace 1` traces one session without touching the settings. */
    private fun systemPropertyEnabled(): Boolean = runCatching {
        val get = Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
        (get.invoke(null, TRACE_PROPERTY) as? String) == "1"
    }.getOrDefault(false)

    /**
     * Emits a trace line only while [traceEnabled].
     *
     * The message is a lambda on purpose: when tracing is off the lambda is never invoked, so call
     * sites can keep expensive work (hex dumps, `joinToString`) inside it and pay nothing.
     */
    inline fun trace(tag: String, message: () -> String) {
        if (!traceEnabled) return
        Log.i(tag, message())
    }

    private const val TRACE_PROPERTY = "debug.diplay.trace"
}
