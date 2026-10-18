package com.shilapi.xcertplay.diag

/**
 * Allocation- and GC-rate reporting for the CS11 tail-latency work (docs/_digest/51 §12,
 * docs/_digest/88 §1.3).
 *
 * The stutter on this head unit is a tail-latency problem, not an average-throughput one: Dalvik GC
 * blocks threads for 37-67 ms (docs 40 §1.3), and those blocks land on the audio write, the video feed
 * and the touch uplink. The allocation rate is what drives them, but nothing in the app measured it.
 * This reports what Dalvik can report - bytes and objects allocated per second, the average size of
 * those objects, bytes freed per GC, and GC invocations - so an allocation change can be accepted or
 * rejected on data instead of on a feeling.
 *
 * **Bytes alone are not enough.** docs/_digest/81 showed that a byte reading hides an "object sea": the
 * touch uplink spent ~60 objects per event while costing only kilobytes, and Dalvik's non-generational
 * collector pays per *object* (marking, not just copying). `obj=` and `perObj=` are therefore part of
 * the report: a falling `perObj=` at a constant `alloc=` means the allocation is spreading into more,
 * smaller objects - the shape that raises GC *frequency* without raising throughput cost.
 *
 * Dalvik counts every allocation, which is far too expensive to leave on, so this is opt-in exactly
 * like the per-frame traces: it only reports while `DiagLog.traceEnabled` is set
 * (`adb shell setprop debug.diplay.trace 1`). On ART (API 21+) these counters are inert, and the first
 * read then returns a negative size, which leaves the segment empty instead of reporting zeros.
 * For the same reason allocation rate and stutter must be measured in **separate** windows: the
 * counting itself inflates CPU.
 */
object AllocationStats {
    private var started = false
    private var lastBytes = 0
    private var lastCount = 0
    private var lastFreedBytes = 0
    private var lastGcCount = 0
    private var lastAtNs = 0L

    /**
     * The fragment for the window that just ended, or null when tracing is off. The first call only
     * establishes the baseline (and starts counting), so a window is never measured from zero.
     */
    @Synchronized fun reportIfTracing(nowNs: Long): String? {
        if (!DiagLog.traceEnabled) return null
        if (!started) {
            start(nowNs)
            return null
        }
        if (lastAtNs == 0L) {
            lastAtNs = nowNs
            return null
        }
        val allocated = tryOr(-1) { android.os.Debug.getGlobalAllocSize() }
        if (allocated < 0) return null
        // The count is read separately: on a device that reports size but not count, the fragment keeps
        // working and says "?" for the object half instead of dropping the whole line.
        val allocatedCount = tryOr(-1) { android.os.Debug.getGlobalAllocCount() }
        val freed = tryOr(0) { android.os.Debug.getGlobalFreedSize() }
        val gcCount = tryOr(0) { android.os.Debug.getGlobalGcInvocationCount() }
        val line = format(
            allocatedBytes = counterDelta(allocated, lastBytes),
            allocatedCount = if (allocatedCount >= 0) counterDelta(allocatedCount, lastCount) else UNKNOWN,
            freedBytes = counterDelta(freed, lastFreedBytes),
            gcCount = gcCount - lastGcCount,
            elapsedNs = nowNs - lastAtNs,
        )
        lastBytes = allocated
        if (allocatedCount >= 0) lastCount = allocatedCount
        lastFreedBytes = freed
        lastGcCount = gcCount
        lastAtNs = nowNs
        return line
    }

    /**
     * The pure arithmetic, JVM-testable without a device: one window's deltas to one report fragment.
     * A non-positive window reports zeros rather than dividing by it; an [allocatedCount] of [UNKNOWN]
     * reports `?` for the object half.
     */
    fun format(
        allocatedBytes: Long,
        allocatedCount: Long,
        freedBytes: Long,
        gcCount: Int,
        elapsedNs: Long,
    ): String {
        val seconds = if (elapsedNs > 0) elapsedNs / 1e9 else 0.0
        fun rate(bytes: Long) = if (seconds > 0) "%.0f".format(bytes / 1024.0 / seconds) else "0"
        val objects = when {
            allocatedCount == UNKNOWN -> "?"
            seconds <= 0 -> "0"
            else -> "%.0f".format(allocatedCount / seconds)
        }
        // Bytes per allocated object: the "object sea" indicator. A zero-length window has no ratio to
        // report, and neither has a window with no bytes or no objects.
        val perObject = if (seconds > 0 && allocatedCount > 0 && allocatedBytes > 0) {
            (allocatedBytes / allocatedCount).toString()
        } else {
            "?"
        }
        return "alloc=${rate(allocatedBytes)}KiB/s obj=$objects/s perObj=${perObject}B " +
            "freed=${rate(freedBytes)}KiB/s gc=$gcCount"
    }

    /**
     * Dalvik's global counters are 32-bit and wrap (every ~8 minutes at the rate this app allocates),
     * so a negative difference is a wrap and not a shrinking total.
     */
    internal fun counterDelta(now: Int, before: Int): Long {
        val difference = now.toLong() - before.toLong()
        return if (difference >= 0) difference else now.toLong() + WRAP + 1 - before.toLong()
    }

    private fun start(nowNs: Long) {
        runCatching { android.os.Debug.startAllocCounting() }
        lastBytes = tryOr(0) { android.os.Debug.getGlobalAllocSize() }
        lastCount = tryOr(0) { android.os.Debug.getGlobalAllocCount() }
        lastFreedBytes = tryOr(0) { android.os.Debug.getGlobalFreedSize() }
        lastGcCount = tryOr(0) { android.os.Debug.getGlobalGcInvocationCount() }
        lastAtNs = nowNs
        started = true
    }

    private inline fun <T> tryOr(fallback: T, block: () -> T): T = try {
        block()
    } catch (_: Throwable) {
        fallback
    }

    /** [format]'s "this device did not report an allocation count" marker. */
    private const val UNKNOWN = -1L

    private const val WRAP = 0xffff_ffffL
}
