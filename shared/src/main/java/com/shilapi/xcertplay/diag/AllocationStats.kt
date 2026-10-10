package com.shilapi.xcertplay.diag

/**
 * Allocation- and GC-rate reporting for the CS11 tail-latency work (docs/_digest/51 §12).
 *
 * The stutter on this head unit is a tail-latency problem, not an average-throughput one: Dalvik GC
 * blocks threads for 37-67 ms (docs 40 §1.3), and those blocks land on the audio write, the video feed
 * and the touch uplink. The allocation rate is what drives them, but nothing in the app measured it.
 * This reports what Dalvik can report - bytes allocated per second, bytes freed per GC, and GC
 * invocations - so an allocation change can be accepted or rejected on data instead of on a feeling.
 *
 * Dalvik counts every allocation, which is far too expensive to leave on, so this is opt-in exactly
 * like the per-frame traces: it only reports while `DiagLog.traceEnabled` is set
 * (`adb shell setprop debug.diplay.trace 1`). On ART (API 21+) these counters are inert, and the first
 * read then returns a negative size, which leaves the segment empty instead of reporting zeros.
 */
object AllocationStats {
    private var started = false
    private var lastBytes = 0
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
        val freed = tryOr(0) { android.os.Debug.getGlobalFreedSize() }
        val gcCount = tryOr(0) { android.os.Debug.getGlobalGcInvocationCount() }
        val line = format(
            counterDelta(allocated, lastBytes),
            counterDelta(freed, lastFreedBytes),
            gcCount - lastGcCount,
            nowNs - lastAtNs,
        )
        lastBytes = allocated
        lastFreedBytes = freed
        lastGcCount = gcCount
        lastAtNs = nowNs
        return line
    }

    /**
     * The pure arithmetic, JVM-testable without a device: one window's deltas to one report fragment.
     * A non-positive window reports zeros rather than dividing by it.
     */
    fun format(allocatedBytes: Long, freedBytes: Long, gcCount: Int, elapsedNs: Long): String {
        val seconds = if (elapsedNs > 0) elapsedNs / 1e9 else 0.0
        fun rate(bytes: Long) = if (seconds > 0) "%.0f".format(bytes / 1024.0 / seconds) else "0"
        return "alloc=${rate(allocatedBytes)}KiB/s freed=${rate(freedBytes)}KiB/s gc=$gcCount"
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

    private const val WRAP = 0xffff_ffffL
}
