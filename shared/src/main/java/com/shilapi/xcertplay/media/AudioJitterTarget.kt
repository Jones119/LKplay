package com.shilapi.xcertplay.media

/**
 * Packet-size arithmetic for the audio jitter queue's drop accounting.
 *
 * The queue itself keeps its original hard cap; what changed is *which* packet is dropped when it is
 * genuinely full: the oldest one (skip forward) instead of refusing the newest, which used to tear
 * holes exactly when a Wi-Fi burst arrived (docs/_digest/41 §12).
 *
 * A first attempt trimmed the queue back to a fixed target depth on every arrival. That is wrong for
 * a bursty link: audio arrives in ~400 ms bursts, so trimming a healthy buffer on each arrival threw
 * away most of the music (measured: `skippedMs` ≈ 2400 ms per 5000 ms window, i.e. ~half of all
 * audio). Do not reintroduce that policy without an interval/hysteresis and a bench run.
 *
 * Kept free of Android types so the arithmetic stays unit-testable on the JVM.
 */
internal object AudioJitterTarget {
    /** Fallback packet size until two RTP timestamps have been seen (AAC-LC packs 1024 frames). */
    const val DEFAULT_PACKET_FRAMES = 1024

    /** Frames carried by one packet, from consecutive RTP timestamps; 0 while still unknown. */
    fun packetFrames(previous: Int, current: Int, hasPrevious: Boolean, sampleRate: Int): Int {
        if (!hasPrevious || sampleRate <= 0) return 0
        val delta = (current.toLong() - previous.toLong()) and 0xffff_ffffL
        // A jump larger than a second is a discontinuity, not a packet size.
        return if (delta in 1..sampleRate.toLong()) delta.toInt() else 0
    }

    /** Duration of one packet in ms, for the "how much audio did we drop" counter. */
    fun packetMillis(framesPerPacket: Int, sampleRate: Int): Long {
        if (sampleRate <= 0) return 0L
        val frames = if (framesPerPacket > 0) framesPerPacket else DEFAULT_PACKET_FRAMES
        return frames * 1000L / sampleRate
    }
}
