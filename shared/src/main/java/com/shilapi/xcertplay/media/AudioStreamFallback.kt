package com.shilapi.xcertplay.media

import android.media.AudioManager

/**
 * Route candidates and legacy-stream fallback for [AndroidMediaSink]'s AudioTrack.
 *
 * The CS11 vendor ROM is the only thing that decides whether a legacy stream type is routable
 * (doc 35: guidance runs on stream 12 and only stream 12 reaches the navigation output). A ROM
 * that rejects a stream throws from the AudioTrack constructor or returns an uninitialized
 * track, and the caller would otherwise play silence with no fallback. Every candidate list
 * therefore ends on the plain music stream: media is the one stream this ROM is guaranteed to
 * have, and a prompt that plays on media volume still beats a prompt that is never heard.
 */
internal sealed class AudioTrackRoute {
    data class Stream(val streamType: Int) : AudioTrackRoute()

    /** API 23+ usage-based route; the legacy AudioTrack constructor is the only fallback below it. */
    data object UsageAttributes : AudioTrackRoute()

    /** Short label for the existing `Audio: ready ... route=` report line. */
    val label: String
        get() = when (this) {
            is Stream -> "streamType=$streamType"
            UsageAttributes -> "usage"
        }
}

internal object AudioStreamFallback {
    /**
     * Stream types to try in order for the stream this renderer selected, preferred first.
     *
     * [AudioManager.USE_DEFAULT_STREAM_TYPE] means the renderer is not on a legacy stream at all
     * (API 23+ takes the usage-based route), so there is no stream to fix up.
     */
    fun candidates(preferredStreamType: Int): List<Int> {
        if (preferredStreamType == AudioManager.USE_DEFAULT_STREAM_TYPE) return emptyList()
        if (preferredStreamType == AudioManager.STREAM_MUSIC) return listOf(AudioManager.STREAM_MUSIC)
        return listOf(preferredStreamType, AudioManager.STREAM_MUSIC)
    }

    /**
     * Routes to attempt, in order, when the preferred stream is rejected or builds an
     * uninitialized track. Empty when [preferredStreamType] is not a legacy stream.
     */
    fun fallbackCandidates(preferredStreamType: Int, sdkInt: Int): List<AudioTrackRoute> =
        candidates(preferredStreamType).map { streamType ->
            if (sdkInt >= USAGE_ATTRIBUTES_MIN_API && streamType != AudioManager.STREAM_MUSIC) {
                AudioTrackRoute.UsageAttributes
            } else {
                AudioTrackRoute.Stream(streamType)
            }
        }.distinct()

    /** One diagnostic line naming the stream that failed and the stream that carries the audio. */
    fun rejectionReport(preferredStreamType: Int, attempts: List<AudioTrackRoute>): String? {
        if (attempts.isEmpty()) return null
        return "Audio: stream rejected preferred=$preferredStreamType attempts=" +
            attempts.joinToString(">") { it.label }
    }

    private const val USAGE_ATTRIBUTES_MIN_API = 23
}
