package com.shilapi.xcertplay

import com.shilapi.xcertplay.media.CarPlayNowPlaying
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that keeps a cached cluster cover from being republished for the next song
 * (docs/_digest/119 §9).
 *
 * Measured on the car 2026-10-28: the iPhone's first metadata frame for song B still carries song
 * A's artwork transfer id, so the bitmap cached for that id is A's picture. Publishing it renamed
 * A's cover to B's file name; B's real cover then resolved to the same Uri and the vendor dropped it
 * as "totally same", which is the one-song cover lag the driver saw. Identity is artist + album +
 * duration -- the same fields `isNewTrack` uses -- because the title is rewritten per lyric line.
 */
class ClusterCoverIdentityTest {
    private fun track(
        title: String,
        artist: String,
        album: String,
        durationMillis: Long,
        transferId: Int,
    ) = CarPlayNowPlaying(
        title = title,
        artist = artist,
        album = album,
        durationMillis = durationMillis,
        artworkTransferId = transferId,
        playing = true,
    )

    @Test
    fun aCoverPublishedForTheSameTrackMayBeReused() {
        val song = track("Rotten (Inst.)", "YROR", "Rotten", 188_000, 139)

        assertTrue(CarPlayMediaKeys.coverMayBelongTo(CarPlayMediaKeys.coverTrackOf(song), song))
        assertTrue("nothing published yet accepts any candidate", CarPlayMediaKeys.coverMayBelongTo(null, song))
    }

    @Test
    fun aCoverPublishedForThePreviousSongIsRefusedForTheNextOne() {
        val previous = track("Royal Flush Mindset", "V Cenzo", "Royal Flush Mindset", 173_500, 144)
        val next = track("Titanic x My Heart Will Go On", "LaBack", "Titanic x My Heart Will Go On", 188_000, 144)

        assertFalse(CarPlayMediaKeys.coverMayBelongTo(CarPlayMediaKeys.coverTrackOf(previous), next))
    }

    @Test
    fun aTitleChangeAloneKeepsTheCover() {
        val song = track("Stronger", "EQRIC", "Stronger", 179_000, 141)
        val lyricLine = song.copy(title = "我变得更坚强")

        assertTrue("lyric churn must not refuse the cover", CarPlayMediaKeys.coverMayBelongTo(CarPlayMediaKeys.coverTrackOf(song), lyricLine))
    }

    @Test
    fun missingFieldsThatArriveLaterKeepTheCover() {
        val bare = track("Stronger", "", "", 179_000, 141)
        val enriched = bare.copy(artist = "EQRIC", album = "Stronger")

        // The identity changed (blank -> value), so the rule is conservative and refuses; the cover is
        // already on the cluster and the next decode republishes it. What must not happen is the
        // opposite: treating two different songs as one, which the previous test covers.
        assertFalse(CarPlayMediaKeys.coverMayBelongTo(CarPlayMediaKeys.coverTrackOf(bare), enriched))
    }
}

