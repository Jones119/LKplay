package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.VideoCodec
import com.shilapi.xcertplay.airplay.VideoFramePool
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class VideoDecodeQueueTest {
    @Test fun lostReferenceChainWaitsForSuccessfullyQueuedKeyframe() {
        val chain = VideoReferenceChain()
        val predicted = byteArrayOf(0, 0, 0, 1, 0x41, 1)
        val idr = byteArrayOf(0, 0, 0, 1, 0x65, 1)
        assertFalse(chain.accepts(predicted, VideoCodec.H264))
        assertTrue(chain.accepts(idr, VideoCodec.H264))
        assertTrue(chain.needsKeyFrame) // Receiving it is insufficient if the codec is still busy.
        chain.onQueued()
        assertTrue(chain.accepts(predicted, VideoCodec.H264))
        chain.reset()
        assertFalse(chain.accepts(predicted, VideoCodec.H264))
    }

    @Test fun overflowPreservesConfigurationAndResetsBeforeNewReferenceChain() {
        val queue = VideoDecodeQueue(maxFrames = 2)
        val config = VideoJob.Config(VideoCodec.H264, byteArrayOf(1))
        val surface = VideoJob.SurfaceChanged(null)
        queue.offer(config)
        queue.offer(VideoJob.Frame(byteArrayOf(1)))
        queue.offer(surface)
        queue.offer(VideoJob.Frame(byteArrayOf(2)))
        queue.offer(VideoJob.Frame(byteArrayOf(3)))
        assertSame(config, queue.poll(0))
        assertSame(surface, queue.poll(0))
        assertEquals(VideoJob.Resync, queue.poll(0))
        assertArrayEquals(byteArrayOf(3), (queue.poll(0) as VideoJob.Frame).nalus)
        assertNull(queue.poll(0))
    }

    @Test fun byteBudgetAlsoTriggersRecoveryAndRejectsOversizedFrame() {
        val queue = VideoDecodeQueue(maxFrames = 8, maxBytes = 5)
        queue.offer(VideoJob.Frame(ByteArray(3)))
        queue.offer(VideoJob.Frame(ByteArray(3)))
        assertEquals(VideoJob.Resync, queue.poll(0))
        assertEquals(3, (queue.poll(0) as VideoJob.Frame).nalus.size)
        queue.offer(VideoJob.Frame(ByteArray(6)))
        assertEquals(VideoJob.Resync, queue.poll(0))
        assertNull(queue.poll(0))
    }

    @Test fun fullOutputMustBeDrainedWhileRetryingTheSameInput() {
        var heldOutputs = 2
        var dequeues = 0
        val submitted = mutableListOf<Int>()
        for (frame in 1..3) {
            val index = VideoInputPump.acquire(
                running = { true },
                drain = { if (heldOutputs > 0) heldOutputs-- },
                dequeue = { dequeues++; if (heldOutputs > 0) -1 else 0 },
            )
            assertEquals(0, index)
            submitted.add(frame)
            heldOutputs = 2
        }
        assertEquals(listOf(1, 2, 3), submitted)
        assertEquals(6, dequeues)
    }

    @Test fun stalledDecoderHasFiniteWaitAndShutdownCancelsImmediately() {
        var time = 0L
        var attempts = 0
        assertEquals(-1, VideoInputPump.acquire(
            running = { true }, drain = {}, dequeue = { attempts++; -1 },
            nanoTime = { time.also { time += 10 } }, timeoutNs = 30,
        ))
        assertEquals(3, attempts)
        assertEquals(-1, VideoInputPump.acquire(running = { false }, drain = { fail() }, dequeue = { fail(); 0 }))
    }

    @Test fun pendingFramesTracksDepthWithoutScanningTheQueue() {
        val queue = VideoDecodeQueue()
        queue.offer(VideoJob.Frame(ByteArray(4)))
        queue.offer(VideoJob.Frame(ByteArray(4)))
        assertEquals(2, queue.pendingFrames())

        queue.poll(0)
        assertEquals(1, queue.pendingFrames())

        queue.discardFrames()
        assertEquals(0, queue.pendingFrames())
    }

    @Test fun discardingFramesReleasesTheirPooledBuffers() {
        val pool = VideoFramePool()
        val queue = VideoDecodeQueue(maxFrames = 1)
        val first = pool.acquire(8)
        val second = pool.acquire(8)

        queue.offer(VideoJob.Frame(first.bytes, length = first.length, owned = first))
        // The second frame overflows maxFrames = 1, which drops - and releases - the first.
        queue.offer(VideoJob.Frame(second.bytes, length = second.length, owned = second))
        assertEquals(1, pool.retainedFrames())

        // The queue still owns the second frame's buffer, and dropping it returns that one too.
        queue.discardFrames()
        assertEquals(2, pool.retainedFrames())
    }

    @Test fun discardedOversizedFrameReturnsItsBuffer() {
        val pool = VideoFramePool()
        val queue = VideoDecodeQueue(maxBytes = 4)
        val frame = pool.acquire(8)

        queue.offer(VideoJob.Frame(frame.bytes, length = frame.length, owned = frame))

        // The oversized frame is dropped (its buffer returned) and a resync is queued in its place.
        assertEquals(1, pool.retainedFrames())
        assertEquals(VideoJob.Resync, queue.poll(0))
        assertNull(queue.poll(0))
    }

    @Test fun concurrentOfferAndPollKeepTheDepthInsideItsBudget() {
        // poll() dequeues without the monitor offer()/discardFrames() hold, so it used to be able to
        // decrement a frame the producer had published but not counted yet: the depth read negative,
        // and an offer() in that window skipped the overflow it should have seen. This is a stress
        // test rather than a deterministic one - the window is nanoseconds wide - so it asserts the
        // invariant the fix guarantees (a depth between zero and maxFrames at every observation, and
        // an empty queue with a zero depth at rest) rather than reproducing an interleaving.
        val maxFrames = 8
        val queue = VideoDecodeQueue(maxFrames = maxFrames, maxBytes = 1 shl 20)
        val stop = AtomicBoolean(false)
        val violations = CopyOnWriteArrayList<String>()
        val threads = listOf(
            Thread { while (!stop.get()) queue.offer(VideoJob.Frame(ByteArray(8))) },
            Thread { while (!stop.get()) queue.offer(VideoJob.Frame(ByteArray(8))) },
            Thread {
                while (!stop.get()) {
                    val job = queue.poll(1)
                    if (job is VideoJob.Frame) job.release()
                }
            },
            Thread {
                while (!stop.get()) {
                    val job = queue.poll(1)
                    if (job is VideoJob.Frame) job.release()
                }
            },
        )
        threads.forEach { it.isDaemon = true; it.start() }
        var samples = 0
        try {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(STRESS_MILLIS)
            while (System.nanoTime() < deadline) {
                val pending = queue.pendingFrames()
                samples++
                if ((pending < 0 || pending > maxFrames) && violations.size < MAX_RECORDED_VIOLATIONS) {
                    violations += "pending=$pending"
                }
            }
        } finally {
            stop.set(true)
            threads.forEach { it.join(5_000) }
        }

        var drained = 0
        while (true) {
            val job = queue.poll(0) ?: break
            if (job is VideoJob.Frame) drained++
        }
        assertTrue("no sample was taken while the queue was under load", samples > 0)
        assertTrue("the depth left its budget: $violations", violations.isEmpty())
        assertTrue("the queue held more frames than its budget: $drained", drained in 0..maxFrames)
        // At rest every counted frame has been removed again, so the depth must be exactly zero.
        assertEquals(0, queue.pendingFrames())
    }

    private companion object {
        /** How long the producers and consumers race; long enough for many samples, still fast. */
        const val STRESS_MILLIS = 200L
        const val MAX_RECORDED_VIOLATIONS = 8
    }
}
