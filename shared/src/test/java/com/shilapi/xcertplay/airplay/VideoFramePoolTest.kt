package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The pool that keeps the screen stream from allocating a fresh buffer per video frame. */
class VideoFramePoolTest {
    @Test
    fun releasedBuffersAreReusedByCapacityClass() {
        val pool = VideoFramePool()
        val first = pool.acquire(1000)
        val buffer = first.bytes
        assertEquals(1024, buffer.size) // rounded up to a power of two so it stays reusable
        first.close()
        assertEquals(1, pool.retainedFrames())

        val second = pool.acquire(1000)
        assertSame(buffer, second.bytes)
        assertEquals(0, pool.retainedFrames())
        second.close()
        assertEquals(1, pool.retainedFrames())
    }

    @Test
    fun aBiggerFrameGetsABiggerBuffer() {
        val pool = VideoFramePool()
        pool.acquire(1000).close()
        val big = pool.acquire(4000)
        assertEquals(4096, big.bytes.size)
        big.close()
    }

    @Test
    fun retentionIsBoundedByFramesAndBytes() {
        val pool = VideoFramePool(maxRetainedBytes = 4096, maxRetainedFrames = 2)
        // Four buffers are in use at once (acquire reuses whatever is retained, so hold them).
        val inUse = List(4) { pool.acquire(1024) }
        inUse.forEach { it.close() }
        assertEquals(2, pool.retainedFrames())

        // A buffer larger than the byte budget is dropped instead of retained.
        val capped = VideoFramePool(maxRetainedBytes = 512)
        capped.acquire(1024).close()
        assertEquals(0, capped.retainedFrames())
    }

    @Test
    fun doubleReleaseRecyclesOnce() {
        val pool = VideoFramePool()
        val frame = pool.acquire(64)
        frame.close()
        frame.close()
        assertEquals(1, pool.retainedFrames())
    }

    @Test
    fun copiesOnlyTheFrameLength() {
        val pool = VideoFramePool()
        val frame = pool.acquire(10)
        assertTrue(frame.bytes.size >= 10)
        assertEquals(10, frame.copyOfBytes().size)
        frame.close()
    }

    @Test
    fun closingThePoolDropsRetainedBuffersAndRefusesAcquire() {
        val pool = VideoFramePool()
        pool.acquire(64).close()
        pool.close()
        assertEquals(0, pool.retainedFrames())
        try {
            pool.acquire(64)
            fail("expected a closed pool to refuse an acquire")
        } catch (expected: IllegalStateException) {
            // Expected.
        }
    }

    @Test
    fun oversizedFramesAreRejected() {
        val pool = VideoFramePool()
        try {
            pool.acquire(16 * 1024 * 1024)
            fail("expected an oversized frame to be rejected")
        } catch (expected: IllegalArgumentException) {
            // Expected.
        }
    }
}
