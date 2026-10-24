package com.shilapi.xcertplay.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cover is matched to a track by [sameTrack]. NowPlaying updates are incremental, so the frame
 * that carried a cover may have no artist/album yet; the later frame that supplies them must still
 * count as the same track rather than dropping the cover.
 *
 * The rule is the one EasyPlay's `LegacyVehicleArtwork.Identity.fillsMissingFieldsOf` uses: a missing
 * previous value is compatible, a present previous value must match.
 */
class ClusterMediaTrackMatchTest {
    @Test
    fun aMissingPreviousArtistAcceptsTheArtistThatArrivesLater() {
        val cover = now(title = "晴天", artist = null, album = "叶惠美")
        val later = now(title = "晴天", artist = "周杰伦", album = "叶惠美")

        assertTrue(sameTrack(cover, later))
    }

    @Test
    fun aMissingPreviousAlbumAcceptsTheAlbumThatArrivesLater() {
        val cover = now(title = "晴天", artist = "周杰伦", album = null)
        val later = now(title = "晴天", artist = "周杰伦", album = "叶惠美")

        assertTrue(sameTrack(cover, later))
    }

    @Test
    fun aDifferentArtistBreaksTheMatchEvenWhenTheTitleIsTheSame() {
        val cover = now(title = "晴天", artist = "周杰伦", album = "叶惠美")
        val other = now(title = "晴天", artist = "翻唱歌手", album = "叶惠美")

        assertFalse(sameTrack(cover, other))
    }

    @Test
    fun aDifferentAlbumBreaksTheMatch() {
        val cover = now(title = "晴天", artist = "周杰伦", album = "叶惠美")
        val other = now(title = "晴天", artist = "周杰伦", album = "范特西")

        assertFalse(sameTrack(cover, other))
    }

    @Test
    fun aDifferentTitleIsNeverTheSameTrack() {
        val cover = now(title = "晴天", artist = "周杰伦", album = "叶惠美")
        val other = now(title = "七里香", artist = "周杰伦", album = "叶惠美")

        assertFalse(sameTrack(cover, other))
    }

    @Test
    fun theCoverRefreshInvisibleTitleStillMatchesTheTrack() {
        val cover = now(title = "晴天", artist = "周杰伦", album = "叶惠美")
        val refreshed = cover.copy(title = "晴天\u200B")

        assertTrue(sameTrack(cover, refreshed))
    }

    @Test
    fun noPreviousTrackNeverMatches() {
        assertFalse(sameTrack(null, now(title = "晴天", artist = "周杰伦", album = "叶惠美")))
    }

    @Test
    fun aTitleChangeWithTheSameArtworkTransferIsLyricChurn() {
        val base = clusterMediaUpdate(now("晴天", "周杰伦", "叶惠美"), null)
        val lyricLine = base.copy(title = "刮风这天")

        assertTrue(titleOnlyChange(base, lyricLine, 7, 7))
    }

    @Test
    fun aNewArtworkTransferIsNotLyricChurn() {
        val base = clusterMediaUpdate(now("晴天", "周杰伦", "叶惠美"), null)

        assertFalse(titleOnlyChange(base, base, 7, 8))
    }

    @Test
    fun aTitleChangeWithADifferentDurationIsNotLyricChurn() {
        val base = clusterMediaUpdate(now("晴天", "周杰伦", "叶惠美"), null)
        val nextTrack = base.copy(title = "晴天 (Live)", durationMillis = 300_000L)

        assertFalse(titleOnlyChange(base, nextTrack, 7, 7))
    }

    private fun now(
        title: String?,
        artist: String?,
        album: String?,
    ) = CarPlayNowPlaying(
        title = title,
        artist = artist,
        album = album,
    )
}
