package com.shilapi.xcertplay.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The allocation report is what accepts or rejects the GC work on the car (docs/_digest/51 §12,
 * docs/_digest/88 §1.3), so its arithmetic - the object half, the "object sea" ratio, and the 32-bit
 * counter wrap it has to survive - is pinned here rather than trusted to a field reading.
 */
class AllocationStatsTest {
    @Test
    fun aFiveSecondWindowBecomesAKibibytePerSecondRate() {
        val line = AllocationStats.format(
            allocatedBytes = 5L * 1024 * 1024,
            allocatedCount = 5_120,
            freedBytes = 4L * 1024 * 1024,
            gcCount = 3,
            elapsedNs = 5_000_000_000L,
        )
        // 5 MiB over 5 s = 1024 KiB/s; 5120 objects over 5 s = 1024/s; 1024 B per object.
        assertEquals("alloc=1024KiB/s obj=1024/s perObj=1024B freed=819KiB/s gc=3", line)
    }

    @Test
    fun aNonPositiveWindowReportsZerosInsteadOfDividingByIt() {
        assertEquals("alloc=0KiB/s obj=0/s perObj=?B freed=0KiB/s gc=0", AllocationStats.format(1024, 1, 1024, 0, 0L))
        assertEquals("alloc=0KiB/s obj=0/s perObj=?B freed=0KiB/s gc=0", AllocationStats.format(1024, 1, 1024, 0, -1L))
    }

    @Test
    fun subSecondWindowsStillScaleToTheFullSecond() {
        // 512 KiB in 500 ms is 1024 KiB/s, which is what "per second" has to mean for an A/B.
        val line = AllocationStats.format(512L * 1024, 0, 0, 0, 500_000_000L)
        assertTrue(line, line.startsWith("alloc=1024KiB/s"))
    }

    @Test
    fun aDeviceThatReportsNoObjectCountStillReportsBytes() {
        // API 19 does report it, but the fragment must not vanish if a counter read fails.
        val line = AllocationStats.format(2L * 1024 * 1024, -1L, 0, 1, 1_000_000_000L)
        assertEquals("alloc=2048KiB/s obj=?/s perObj=?B freed=0KiB/s gc=1", line)
    }

    @Test
    fun manySmallObjectsShowUpAsALowPerObjectFigure() {
        // The "object sea" (docs/_digest/81): the same 4 MiB as 100k small objects versus 4k big ones.
        val small = AllocationStats.format(4L * 1024 * 1024, 100_000, 0, 1, 1_000_000_000L)
        val big = AllocationStats.format(4L * 1024 * 1024, 4_000, 0, 1, 1_000_000_000L)
        assertTrue(small, small.contains("perObj=41B"))
        assertTrue(big, big.contains("perObj=1048B"))
        // Byte rate is identical: this is exactly what a bytes-only report could not tell apart.
        assertTrue(small, small.startsWith("alloc=4096KiB/s") && big.startsWith("alloc=4096KiB/s"))
    }

    @Test
    fun aZeroByteWindowStillReportsItsObjectCount() {
        // Objects allocated but no bytes recorded byte-count wise (a wrapped counter, say): the ratio is
        // unknown but the rate is not, so it must not be reported as "?".
        val line = AllocationStats.format(0, 500, 0, 0, 1_000_000_000L)
        assertEquals("alloc=0KiB/s obj=500/s perObj=?B freed=0KiB/s gc=0", line)
    }

    @Test
    fun aWrappedThirtyTwoBitCounterReportsTheDeltaNotAShrinkingTotal() {
        assertEquals(5L, AllocationStats.counterDelta(5, 0))
        assertEquals(1L, AllocationStats.counterDelta(0, -1))
        // The counter wrapped: 100 bytes before the wrap plus 20 after it.
        assertEquals(120L, AllocationStats.counterDelta(20, -100))
        // Full wrap: from Int.MIN_VALUE to Int.MAX_VALUE is 4294967295 bytes.
        assertEquals(4294967295L, AllocationStats.counterDelta(Int.MAX_VALUE, Int.MIN_VALUE))
    }
}
