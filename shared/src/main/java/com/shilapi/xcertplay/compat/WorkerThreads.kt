package com.shilapi.xcertplay.compat

import android.util.Log

/**
 * Starts worker threads on this ROM, which loses the main thread's `ThreadGroup` after a while.
 *
 * ## The firmware defect
 *
 * On the CS11 head unit (Android 4.4.3 / API 19) `Thread.currentThread().getThreadGroup()` returns
 * `ThreadGroup[name=main]` while the app is starting and **`null` a few seconds later**. Every
 * `new Thread(...)` after that point dies inside the framework:
 *
 * ```
 * java.lang.NullPointerException
 *     at java.lang.Thread.create(Thread.java:403)
 *     at java.lang.Thread.<init>(Thread.java:210)
 * ```
 *
 * `Thread.create` resolves the group with `if (group == null) group = currentThread.getThreadGroup()`
 * and then calls `group.add(...)`, so a null group is an immediate NPE. It is not specific to lambdas,
 * method references or names: a probe on the car tried all ten construction forms and every one of them
 * failed once the group had gone null, while all ten succeeded during `onCreate`.
 *
 * The same defect breaks the framework's own `SharedPreferencesImpl.startLoadFromDisk`, which is why
 * `getSharedPreferences` for a not-yet-loaded file also throws from this ROM (`docs/_digest/89`).
 *
 * ## What this does about it
 *
 * Passes an explicitly captured group to the constructor, which skips the broken lookup. [capture] has
 * to run while the group is still valid — the application start is the reliable place — and everything
 * that starts a thread later should go through [start].
 *
 * This is a firmware workaround, not a design preference. It is harmless on healthy ROMs: the captured
 * group is the same one the constructor would have found.
 */
object WorkerThreads {
    private const val TAG = "DiPlay-Worker"

    /** The group seen at [capture] time; `null` until captured. */
    @Volatile
    private var group: ThreadGroup? = null

    /** Remembers the calling thread's group. Safe to call more than once; the first value wins. */
    fun capture() {
        val existing = group
        if (existing != null) return
        val current = runCatching { Thread.currentThread().threadGroup }.getOrNull()
        if (current != null) {
            group = current
            Log.i(TAG, "captured ThreadGroup ${current.name}")
        } else {
            Log.w(TAG, "ThreadGroup is already null; workers will fall back to the default lookup")
        }
    }

    /** True once a group was captured; surfaced so diagnostics can explain a missing one. */
    val captured: Boolean get() = group != null

    /**
     * Creates a thread in the captured group when there is one, so it survives the firmware's null
     * group. Returns a thread that has not been started.
     */
    fun create(name: String, body: Runnable): Thread {
        val pinned = group
        return if (pinned != null) Thread(pinned, body, name) else Thread(body, name)
    }

    /** Creates and starts a thread; see [create]. */
    fun start(name: String, body: Runnable): Thread = create(name, body).apply { start() }

    /** Creates and starts a daemon thread; see [create]. */
    fun startDaemon(name: String, body: Runnable): Thread =
        create(name, body).apply {
            isDaemon = true
            start()
        }
}
