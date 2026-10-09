package com.shilapi.xcertplay.media

import android.view.Surface
import com.shilapi.xcertplay.airplay.OwnedVideoFrame
import com.shilapi.xcertplay.airplay.VideoCodec
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

internal sealed interface VideoJob {
    data class Config(val codec: VideoCodec, val codecData: ByteArray) : VideoJob

    data class Frame(
        val nalus: ByteArray,
        val receivedNs: Long = System.nanoTime(),
        /** Bytes that belong to this frame; [nalus] can be a longer pooled buffer. */
        val length: Int = nalus.size,
        /** Set when [nalus] belongs to a VideoFramePool; released once the decoder is done with it. */
        val owned: OwnedVideoFrame? = null,
    ) : VideoJob {
        /** Returns a pooled buffer; a no-op for borrowed frames, and safe to call more than once. */
        fun release() { owned?.close() }
    }

    data class SurfaceChanged(val surface: Surface?) : VideoJob
    data object Resync : VideoJob
}

/** Do not resume dependent pictures after losing a reference frame. */
internal class VideoReferenceChain {
    var needsKeyFrame = true
        private set
    fun reset() { needsKeyFrame = true }
    fun accepts(bytes: ByteArray, codec: VideoCodec): Boolean = accepts(bytes, bytes.size, codec)
    fun accepts(bytes: ByteArray, length: Int, codec: VideoCodec): Boolean =
        !needsKeyFrame || MediaCodecSupport.isRandomAccess(bytes, length, codec)
    fun onQueued() { needsKeyFrame = false }
}

/** Limit latency and memory without ever dropping a reference frame silently. */
internal class VideoDecodeQueue(
    // Wi-Fi delivers frames in bursts after a radio gap; the decoder's 250 ms age check bounds latency.
    private val maxFrames: Int = 60,
    private val maxBytes: Int = 8 * 1024 * 1024,
) {
    private val jobs = LinkedBlockingQueue<VideoJob>()

    // CS11: the decoder thread asks for the depth on every frame, and offer() used to walk the whole
    // queue twice per frame (allocating an ArrayList) while holding this monitor. Counters keep both
    // O(1) - and the old shape got more expensive exactly when frames were already backing up.
    private val frameCount = AtomicInteger(0)
    private val frameBytes = AtomicLong(0)

    @Synchronized fun offer(job: VideoJob) {
        if (job is VideoJob.Frame) {
            if (frameCount.get() >= maxFrames || frameBytes.get() + job.length > maxBytes) {
                discardFrames()
                jobs.offer(VideoJob.Resync)
            }
            // A single oversized frame is also a lost reference chain.
            if (job.length > maxBytes) {
                job.release()
                return
            }
        }
        jobs.offer(job)
        if (job is VideoJob.Frame) {
            frameCount.incrementAndGet()
            frameBytes.addAndGet(job.length.toLong())
        }
    }

    /** Drops queued frames (and their pooled buffers) but keeps configuration and surface changes. */
    @Synchronized fun discardFrames() {
        val kept = ArrayList<VideoJob>(4)
        while (true) {
            val job = jobs.poll() ?: break
            when (job) {
                is VideoJob.Frame -> {
                    frameCount.decrementAndGet()
                    frameBytes.addAndGet(-job.length.toLong())
                    job.release()
                }
                is VideoJob.Resync -> Unit
                else -> kept.add(job)
            }
        }
        kept.forEach { jobs.offer(it) }
    }

    /** Frames still waiting for the decoder thread; reported so backlog growth is visible. */
    fun pendingFrames(): Int = frameCount.get()

    fun poll(timeoutMillis: Long): VideoJob? {
        val job = jobs.poll(timeoutMillis, TimeUnit.MILLISECONDS)
        if (job is VideoJob.Frame) {
            frameCount.decrementAndGet()
            frameBytes.addAndGet(-job.length.toLong())
        }
        return job
    }
}

/** Drain output while waiting for input: full output buffers can otherwise starve input forever. */
internal object VideoInputPump {
    fun acquire(
        running: () -> Boolean,
        drain: () -> Unit,
        dequeue: () -> Int,
        nanoTime: () -> Long = System::nanoTime,
        timeoutNs: Long = TimeUnit.MILLISECONDS.toNanos(500),
    ): Int {
        val start = nanoTime()
        while (running()) {
            drain()
            val index = dequeue()
            if (index >= 0) return index
            if (nanoTime() - start >= timeoutNs) break
        }
        return -1
    }
}
