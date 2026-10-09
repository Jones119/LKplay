package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** The `onVideoFrame` overloads: the default bridge must copy and release the pooled buffer. */
class MediaSinkOwnedFrameTest {
    @Test
    fun defaultSinkCopiesTheFrameAndReleasesIt() {
        val pool = VideoFramePool()
        val frame = pool.acquire(3)
        frame.bytes[0] = 1
        frame.bytes[1] = 2
        frame.bytes[2] = 3
        var received: ByteArray? = null
        val sink = object : MediaSink {
            override fun onVideoFrame(type: Int, naluBytes: ByteArray) {
                received = naluBytes
            }
        }

        sink.onVideoFrame(110, frame)

        assertArrayEquals(byteArrayOf(1, 2, 3), received)
        // The bridge owns the buffer, so it must already have returned it to the pool.
        assertEquals(1, pool.retainedFrames())
        // A second close must not recycle the same buffer twice.
        frame.close()
        assertEquals(1, pool.retainedFrames())
    }

    @Test
    fun owningSinkReceivesThePooledBufferWithoutACopy() {
        val pool = VideoFramePool()
        val frame = pool.acquire(5)
        var seen: OwnedVideoFrame? = null
        val sink = object : MediaSink {
            override fun onVideoFrame(type: Int, owned: OwnedVideoFrame) {
                seen = owned
                assertEquals(5, owned.length)
                owned.close()
            }
        }

        sink.onVideoFrame(110, frame)

        assertEquals(frame, seen)
        assertEquals(1, pool.retainedFrames())
    }
}
