package com.shilapi.xcertplay.airplay

import java.io.Closeable
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One frame's byte buffer, handed from the screen receive thread to the video decoder thread.
 *
 * Ownership moves with the object: whoever consumes the bytes must call [close], which returns the
 * buffer to its [VideoFramePool]. Closing twice is safe and recycles once, so a double release can
 * never put the same buffer into the pool twice.
 */
class OwnedVideoFrame internal constructor(
    val bytes: ByteArray,
    /** Bytes that belong to this frame; [bytes] may be a larger pooled buffer. */
    val length: Int,
    private val onRelease: (ByteArray) -> Unit,
) : Closeable {
    private val released = AtomicBoolean(false)

    /** A right-sized copy, for consumers that cannot take ownership of the pooled buffer. */
    fun copyOfBytes(): ByteArray = bytes.copyOf(length)

    override fun close() {
        if (released.compareAndSet(false, true)) onRelease(bytes)
    }
}

/**
 * Reuses frame buffers between the screen receive thread and the video decoder thread.
 *
 * Ported from EasyPlay's `VideoFramePool`/`OwnedVideoFrame` (see `docs/_digest/41-代码层优化清单` §9).
 * Before this, every video frame allocated two multi-kilobyte arrays - the sealed body read from the
 * socket and the decrypted plaintext - which is the bulk of this app's Dalvik GC pressure on the
 * CS11. That GC ran every 1.2-3 s while scrolling and blocked threads for 37-67 ms at a time, which
 * is what the video stats show as `maxGap`/`shownP95` spikes.
 *
 * Buffers are kept by capacity class (rounded up to a power of two so an old buffer usually fits a
 * later frame) and grown on demand, so [acquire] never blocks the receive thread; retention is
 * bounded by [maxRetainedFrames] and [maxRetainedBytes].
 */
class VideoFramePool(
    private val maxRetainedBytes: Int = DEFAULT_MAX_RETAINED_BYTES,
    private val maxRetainedFrames: Int = DEFAULT_MAX_RETAINED_FRAMES,
) : Closeable {
    private val buffers = ArrayDeque<ByteArray>()
    private var retainedBytes = 0
    private var closed = false

    /** A buffer of at least [length] bytes. */
    @Synchronized fun acquire(length: Int): OwnedVideoFrame {
        require(length in 0..MAX_FRAME_BYTES) { "video frame length out of range: $length" }
        check(!closed) { "Video frame pool is closed" }
        var capacity = MIN_CAPACITY
        while (capacity < length) capacity = capacity shl 1
        var selected: ByteArray? = null
        for (candidate in buffers) {
            if (candidate.size >= capacity && (selected == null || candidate.size < selected.size)) {
                selected = candidate
            }
        }
        val bytes = if (selected != null) {
            buffers.remove(selected)
            retainedBytes -= selected.size
            selected
        } else {
            ByteArray(capacity)
        }
        return OwnedVideoFrame(bytes, length, ::recycle)
    }

    @Synchronized private fun recycle(bytes: ByteArray) {
        if (closed || maxRetainedFrames <= 0 || bytes.size > maxRetainedBytes) return
        while (buffers.isNotEmpty() &&
            (buffers.size >= maxRetainedFrames || retainedBytes + bytes.size > maxRetainedBytes)
        ) {
            retainedBytes -= buffers.removeFirst().size
        }
        buffers.addLast(bytes)
        retainedBytes += bytes.size
    }

    /** Buffers currently held for reuse; exposed for diagnostics. */
    @Synchronized fun retainedFrames(): Int = buffers.size

    @Synchronized override fun close() {
        closed = true
        buffers.clear()
        retainedBytes = 0
    }

    private companion object {
        const val MIN_CAPACITY = 1024
        const val MAX_FRAME_BYTES = 8 * 1024 * 1024
        const val DEFAULT_MAX_RETAINED_BYTES = 4 * 1024 * 1024
        const val DEFAULT_MAX_RETAINED_FRAMES = 8
    }
}
