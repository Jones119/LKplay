package com.shilapi.xcertplay.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The allocation report is what accepts or rejects the GC work on the car (docs/_digest/51 §12), so its
 * arithmetic - and the 32-bit counter wrap it has to survive - is pinned here rather than trusted to a
 * field reading.
 */
class AllocationStatsTest {
    @Test
    fun aFiveSecondWindowBecomesAKibibytePerSecondRate() {
        val line = AllocationStats.format(
            allocatedBytes = 5 * 1024 * 1024,
            freedBytes = 4 * 1024 * 1024,
            gcCount = 3,
            elapsedNs = 5_000_000_000L,
        )
        assertEquals("alloc=1024KiB/s freed=819KiB/s gc=3", line)
    }

    @Test
    fun aNonPositiveWindowReportsZerosInsteadOfDividingByIt() {
        assertEquals("alloc=0KiB/s freed=0KiB/s gc=0", AllocationStats.format(1024, 1024, 0, 0L))
        assertEquals("alloc=0KiB/s freed=0KiB/s gc=0", AllocationStats.format(1024, 1024, 0, -1L))
    }

    @Test
    fun subSecondWindowsStillScaleToTheFullSecond() {
        // 512 KiB in 500 ms is 1024 KiB/s, which is what "per second" has to mean for an A/B.
        val line = AllocationStats.format(512 * 1024, 0, 0, 500_000_000L)
        assertTrue(line, line.startsWith("alloc=1024KiB/s"))
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
