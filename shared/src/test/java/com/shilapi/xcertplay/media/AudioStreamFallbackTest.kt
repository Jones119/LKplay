package com.shilapi.xcertplay.media

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the CS11 rule that a rejected vendor stream must keep trying instead of playing silence:
 * doc 35 verified stream 12 on the head unit, but nothing guarantees the ROM accepts it forever.
 */
class AudioStreamFallbackTest {
    @Test fun aChannelAlreadyOnMusicDoesNotRetryItself() {
        assertEquals(
            listOf(AudioTrackRoute.Stream(AudioManager.STREAM_MUSIC)),
            AudioStreamFallback.fallbackCandidates(AudioManager.STREAM_MUSIC),
        )
    }

    @Test fun theVendorStreamFallsBackStraightToMusic() {
        assertEquals(
            listOf(AudioTrackRoute.Stream(12), AudioTrackRoute.Stream(AudioManager.STREAM_MUSIC)),
            AudioStreamFallback.fallbackCandidates(12),
        )
    }

    @Test fun aNonStreamChannelHasNothingToFallBackTo() {
        assertTrue(AudioStreamFallback.candidates(AudioManager.USE_DEFAULT_STREAM_TYPE).isEmpty())
        assertTrue(AudioStreamFallback.fallbackCandidates(AudioManager.USE_DEFAULT_STREAM_TYPE).isEmpty())
    }

    @Test fun mediaChannelNeverRetriesItself() {
        assertEquals(listOf(3), AudioStreamFallback.candidates(AudioManager.STREAM_MUSIC))
    }

    @Test fun reportNamesThePreferredAndTheAcceptedRoute() {
        val line = AudioStreamFallback.rejectionReport(
            12,
            listOf(AudioTrackRoute.Stream(12), AudioTrackRoute.Stream(AudioManager.STREAM_MUSIC)),
        )
        assertEquals("Audio: stream rejected preferred=12 attempts=streamType=12>streamType=3", line)
        assertNull(AudioStreamFallback.rejectionReport(12, emptyList()))
    }

    @Test fun rejectedCandidatesAreReleasedAndTheLastResortWins() {
        val events = mutableListOf<String>()
        val attempts = LegacyAudioFallback.Attempts(
            AudioStreamFallback.fallbackCandidates(12),
        )
        val choice = attempts.next(
            build = { route -> events += "build:$route"; route },
            isInitialized = { it == AudioTrackRoute.Stream(AudioManager.STREAM_MUSIC) },
            release = { events += "release:$it" },
        )
        assertEquals(AudioTrackRoute.Stream(AudioManager.STREAM_MUSIC), choice.route)
        assertEquals(
            listOf(
                "build:${AudioTrackRoute.Stream(12)}",
                "release:${AudioTrackRoute.Stream(12)}",
                "build:${AudioTrackRoute.Stream(AudioManager.STREAM_MUSIC)}",
            ),
            events,
        )
        assertEquals(AudioStreamFallback.fallbackCandidates(12), choice.attempted)
    }

    @Test fun aThrowingCandidateDoesNotStopTheFallback() {
        val attempts = LegacyAudioFallback.Attempts(
            AudioStreamFallback.fallbackCandidates(12),
        )
        val choice = attempts.next(
            build = { route ->
                if (route == AudioTrackRoute.Stream(12)) throw IllegalArgumentException("Unsupported stream")
                route
            },
            isInitialized = { true },
            release = { },
        )
        assertEquals(AudioTrackRoute.Stream(AudioManager.STREAM_MUSIC), choice.route)
        assertEquals(2, choice.attempted.size)
    }

    @Test fun candidatesAreConsumedSoTheRendererNeverRetriesOneForever() {
        val attempts = LegacyAudioFallback.Attempts(
            AudioStreamFallback.fallbackCandidates(12),
        )
        val first = attempts.next(build = { it }, isInitialized = { true }, release = { })
        assertEquals(AudioTrackRoute.Stream(12), first.route)
        // The next call sees only what is left, so a rejected preferred route is not re-created.
        val second = attempts.next(build = { it }, isInitialized = { true }, release = { })
        assertEquals(AudioTrackRoute.Stream(AudioManager.STREAM_MUSIC), second.route)
    }

    @Test fun allCandidatesRejectedFailsInsteadOfClaimingSilenceIsAudio() {
        val attempts = LegacyAudioFallback.Attempts(
            AudioStreamFallback.fallbackCandidates(12),
        )
        val error = runCatching {
            attempts.next(build = { null }, isInitialized = { false }, release = { })
        }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        // Every candidate is consumed by the failed attempts, so a retry cannot loop forever.
        val retry = runCatching {
            attempts.next(build = { null }, isInitialized = { false }, release = { })
        }.exceptionOrNull()
        assertEquals(
            "no AudioTrack route accepted for this stream",
            (retry as? IllegalStateException)?.message,
        )
    }
}
