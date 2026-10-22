package com.shilapi.xcertplay.orchestration

import android.content.Context
import android.content.SharedPreferences

/**
 * The USB enumeration (device node) of the iPhone DiPlay last opened wired data paths on.
 *
 * If this process dies before it releases the mode (stopped, replaced or crashed while connected), the iPhone
 * stays in CarPlay mode on the same node and opens no AirPlay connection until it resets USB 30-150 s later;
 * a fresh CarPlay mode request instead connected within seconds (upstream 5e8a6f9a, #335, docs/_digest/102
 * P0-3). Switching the phone out of the mode and letting the normal request bring it back is what replaces the
 * physical replug. The node changes whenever the iPhone re-enumerates, so a match means it is still in that
 * session's mode.
 *
 * Unlike upstream's in-process object this is persisted: a crash or a reinstall kills the process without the
 * clean teardown that resets the phone's configuration, and that is exactly the case that needed the replug.
 */
internal object WiredSessionMemory {
    private const val PREFS = "diplay_wired"
    private const val KEY = "wired_session_started_on"

    @Volatile private var prefs: SharedPreferences? = null
    @Volatile private var startedOn: String? = null

    fun install(context: Context) {
        val store = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = store
        startedOn = store.getString(KEY, null)
    }

    fun started(deviceName: String) {
        startedOn = deviceName
        prefs?.edit()?.putString(KEY, deviceName)?.apply()
    }

    fun startedOn(deviceName: String): Boolean = startedOn == deviceName

    internal fun clear() {
        startedOn = null
        prefs?.edit()?.remove(KEY)?.apply()
    }
}
