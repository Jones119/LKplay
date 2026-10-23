package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The identity/retention rules that decide which cover the cluster is told about (docs/_digest/45 G3).
 *
 * The car showed the first track's art and then nothing, because a cover was published only when the
 * transfer id that finished decoding was still the current one. These tests drive the state machine
 * that replaces that rule by identity -- the scenarios are the ones the fault report names: a new
 * track with no new artwork yet, artwork arriving before or after its metadata, a paused player, a
 * missing title, an identity change, and the placeholder that must never be published as art.
 *
 * `String` stands in for the cover: the machine is generic, and a JVM test has no `Bitmap`.
 */
class ClusterArtworkAssociationTest {
    private val coverOne = "cover-one"
    private val coverTwo = "cover-two"

    @Test
    fun aCoverIsReAssociatedWhenALaterUpdateOfTheSameTrackCarriesNoNewArtwork() {
        val covers = ClusterArtworkAssociation<String>()

        assertSame(coverOne, covers.associate("Track A", "Artist", "Album", playing = true, artwork = coverOne))
        // The iPhone repeats NowPlayingUpdate about twice a second for the position alone; the cover
        // must survive that, which is what "retained" means.
        assertSame(coverOne, covers.associate("Track A", "Artist", "Album", playing = true, artwork = null))
        assertEquals(
            ClusterArtworkAssociation.Identity("Track A", "Artist", "Album"),
            covers.retainedIdentity(),
        )
    }

    @Test
    fun aNewTrackWithoutArtworkDoesNotKeepShowingThePreviousTracksCover() {
        val covers = ClusterArtworkAssociation<String>()
        assertSame(coverOne, covers.associate("Track A", "Artist 1", "Album 1", playing = true, artwork = coverOne))

        assertNull(covers.associate("Track B", "Artist 2", "Album 2", playing = true, artwork = null))
        assertNull(covers.retainedIdentity())
    }

    @Test
    fun aNewTrackShowsItsCoverOnceTheArtworkArrives() {
        val covers = ClusterArtworkAssociation<String>()
        assertSame(coverOne, covers.associate("Track A", "Artist 1", "Album 1", playing = true, artwork = coverOne))
        // The metadata for the new track arrives first, naming a transfer that has not been decoded
        // yet: the cluster is told there is no cover rather than being left with the old one.
        assertNull(covers.associate("Track B", "Artist 2", "Album 2", playing = true, artwork = null))
        // The bytes for that transfer decode afterwards -- or arrive under an id the metadata never
        // repeats. Either way they belong to what is playing now and must be published.
        assertSame(coverTwo, covers.associate("Track B", "Artist 2", "Album 2", playing = true, artwork = coverTwo))
    }

    @Test
    fun artworkArrivingBeforeItsMetadataIsUsedOnceTheMetadataNamesTheTrack() {
        val covers = ClusterArtworkAssociation<String>()
        // The bytes decoded while the *previous* track's metadata was still current: with no title the
        // machine refuses to attach them to anything.
        assertNull(covers.associate(null, "Artist 2", "Album 2", playing = true, artwork = coverTwo))
        assertSame(coverTwo, covers.associate("Track B", "Artist 2", "Album 2", playing = true, artwork = coverTwo))
    }

    @Test
    fun aDifferentPictureForTheSameTrackReplacesTheRetainedCover() {
        val covers = ClusterArtworkAssociation<String>()
        assertSame(coverOne, covers.associate("Track A", "Artist", "Album", playing = true, artwork = coverOne))
        assertSame(coverTwo, covers.associate("Track A", "Artist", "Album", playing = true, artwork = coverTwo))
        assertSame(coverTwo, covers.associate("Track A", "Artist", "Album", playing = true, artwork = null))
    }

    @Test
    fun theSamePictureOfferedWithACompletedIdentityKeepsTheCover() {
        val covers = ClusterArtworkAssociation<String>()
        // The cover is there before the artist and album are.
        assertSame(coverOne, covers.associate("Track A", null, null, playing = true, artwork = coverOne))
        // The fields arrive later. The identity changes, so a naive "identity changed -> drop" rule
        // would lose the cover; a completion of the previous identity is not a new track.
        assertSame(coverOne, covers.associate("Track A", "Artist", "Album", playing = true, artwork = coverOne))
        assertEquals(
            ClusterArtworkAssociation.Identity("Track A", "Artist", "Album"),
            covers.retainedIdentity(),
        )
    }

    @Test
    fun anIdentityThatOnlyArrivesWithTheNewPictureKeepsThatCoverWhenItIsCorrected() {
        val covers = ClusterArtworkAssociation<String>()
        assertSame(coverOne, covers.associate("Track A", "Artist 1", "Album 1", playing = true, artwork = coverOne))
        // A new title, but the update still carries the previous track's picture and its artist/album
        // (iAP2 sends only what changed). That is the "pending title" the machine remembers.
        assertNull(covers.associate("Track B", "Artist 1", "Album 1", playing = true, artwork = coverOne))
        // Track B's picture arrives before its own artist and album do.
        assertSame(coverTwo, covers.associate("Track B", "Artist 1", "Album 1", playing = true, artwork = coverTwo))
        // The inherited artist/album are now corrected. They were inherited, not a new track's, so the
        // cover stays instead of being dropped by the identity change.
        assertSame(coverTwo, covers.associate("Track B", "Artist 2", "Album 2", playing = true, artwork = coverTwo))
        assertSame(coverTwo, covers.associate("Track B", "Artist 2", "Album 2", playing = true, artwork = null))
    }

    @Test
    fun pausedPlaybackKeepsTheRetainedCover() {
        val covers = ClusterArtworkAssociation<String>()
        assertSame(coverOne, covers.associate("Track A", "Artist", "Album", playing = true, artwork = coverOne))

        // A paused frame arrives mid-playback (and whenever the car radio takes focus for a moment); the
        // cover stays so it does not flash and vanish, and resuming brings it straight back.
        assertSame(coverOne, covers.associate("Track A", "Artist", "Album", playing = false, artwork = null))
        assertNotNull(covers.retainedIdentity())
        assertSame(coverOne, covers.associate("Track A", "Artist", "Album", playing = true, artwork = null))
    }

    @Test
    fun anUpdateWithoutATitleNeverRetainsACover() {
        val covers = ClusterArtworkAssociation<String>()
        assertNull(covers.associate(null, "Artist", "Album", playing = true, artwork = coverOne))
        assertNull(covers.associate("   ", "Artist", "Album", playing = true, artwork = coverOne))
        assertNull(covers.retainedIdentity())
    }

    @Test
    fun thePlaceholderIsNeverPublishedAsACover() {
        val placeholder = "placeholder-art"
        val covers = ClusterArtworkAssociation<String>(placeholder = placeholder)

        assertNull(covers.associate("Track A", "Artist", "Album", playing = true, artwork = placeholder))
        assertNull(covers.retainedIdentity())

        assertSame(coverOne, covers.associate("Track A", "Artist", "Album", playing = true, artwork = coverOne))
        // Offering the placeholder again is "no new artwork": the real cover stays, and the head unit's
        // own default is never replaced by our drawing (docs/_digest/45 G6).
        assertSame(coverOne, covers.associate("Track A", "Artist", "Album", playing = true, artwork = placeholder))
    }

    @Test
    fun identityFieldsAreTrimmedBeforeTheyAreCompared() {
        val covers = ClusterArtworkAssociation<String>()
        assertSame(coverOne, covers.associate("  Track A ", " Artist ", " Album ", playing = true, artwork = coverOne))
        assertSame(coverOne, covers.associate("Track A", "Artist", "Album", playing = true, artwork = null))
    }

    @Test
    fun resetForgetsEverythingTheLastSessionLeftBehind() {
        val covers = ClusterArtworkAssociation<String>()
        assertSame(coverOne, covers.associate("Track A", "Artist", "Album", playing = true, artwork = coverOne))

        covers.reset()

        assertNull(covers.retainedIdentity())
        assertNull(covers.associate("Track A", "Artist", "Album", playing = true, artwork = null))
    }
}
