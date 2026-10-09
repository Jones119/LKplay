package com.shilapi.xcertplay.media

/** Tracks the unsigned AudioTrack head across wrap without flushing already queued sound. */
internal class AudioBufferProgress(private val frameBytes: Int) {
    private var writtenBytes = 0L
    private var playedFrames = 0L
    private var lastHead = 0L

    fun written(bytes: Int) { writtenBytes += bytes }

    fun queuedBytes(rawHead: Int): Long {
        val head = rawHead.toLong() and 0xffff_ffffL
        playedFrames += (head - lastHead) and 0xffff_ffffL
        lastHead = head
        return (writtenBytes - playedFrames * frameBytes).coerceAtLeast(0)
    }

    /**
     * Upstream `f5452c5` — "re-arm the media audio prebuffer after underruns".
     *
     * The condition is a *floor* ([floorBytes]), not "exactly empty": the call site passes half the
     * start threshold, so a track that starved with a little PCM still in it pauses and re-arms, and
     * [AndroidMediaSink]'s renderer then counts that residual toward the restart threshold.
     * Ported together with upstream's `AudioRebufferCapacityTest` (docs/_digest/31 §6).
     */
    fun shouldRebuffer(isMedia: Boolean, playing: Boolean, underrunSinceStart: Boolean,
        compressedQueueEmpty: Boolean, rawHead: Int, floorBytes: Long): Boolean =
        isMedia && playing && underrunSinceStart && compressedQueueEmpty && queuedBytes(rawHead) <= floorBytes
}
