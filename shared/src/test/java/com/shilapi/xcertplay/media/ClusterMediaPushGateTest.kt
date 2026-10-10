package com.shilapi.xcertplay.media

import android.net.Uri
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The media-push throttle (docs/_digest/45 G9).
 *
 * `NowPlayingUpdate` arrives about twice a second and every one of them used to cost a full reflective
 * dispatch plus a proxy allocation at the vendor. The payload's identity is what the vendor's getters
 * can actually see, so a frame that changed nothing they can see -- a new `artworkTransferId`, a new
 * `sourceApp`, the same position -- must not be dispatched again. Robolectric is used because a real
 * `Uri` is part of the payload and `Uri.parse` is not available to a plain JVM test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class ClusterMediaPushGateTest {
    private val silent = Uri.parse("content://com.shihab.diplay.hudtest.artwork/covers/session/cover.jpg")

    @Test
    fun anUnchangedPayloadIsDispatchedOnlyOnce() {
        val gate = ClusterMediaPushGate()
        val update = clusterMediaUpdate(nowPlaying(), artworkUri = null)

        assertTrue(gate.shouldPush(update))
        gate.notePushed(update)
        assertFalse(gate.shouldPush(update))
        assertFalse("the same frame again costs nothing", gate.shouldPush(clusterMediaUpdate(nowPlaying(), null)))
    }

    @Test
    fun aPositionChangeIsARealChangeAndIsDispatched() {
        val gate = ClusterMediaPushGate()
        val update = clusterMediaUpdate(nowPlaying(), artworkUri = null)
        gate.notePushed(update)

        assertTrue(gate.shouldPush(clusterMediaUpdate(nowPlaying(elapsedMillis = 1001L), null)))
    }

    @Test
    fun fieldsTheVendorCannotSeeAreNotPartOfThePayload() {
        val gate = ClusterMediaPushGate()
        gate.notePushed(clusterMediaUpdate(nowPlaying(), artworkUri = null))

        // Only `artworkTransferId` and `sourceApp` differ: neither reaches the cluster, because
        // `IPlaybackInfo` has no getter for them.
        val moved = nowPlaying().copy(artworkTransferId = 9, sourceApp = "Spotify")
        assertFalse(gate.shouldPush(clusterMediaUpdate(moved, artworkUri = null)))
    }

    @Test
    fun aNewCoverUriIsPartOfThePayload() {
        val gate = ClusterMediaPushGate()
        gate.notePushed(clusterMediaUpdate(nowPlaying(), artworkUri = silent))

        assertTrue(gate.shouldPush(clusterMediaUpdate(nowPlaying(), artworkUri = null)))
        gate.notePushed(clusterMediaUpdate(nowPlaying(), artworkUri = null))
        assertTrue(gate.shouldPush(clusterMediaUpdate(nowPlaying(), artworkUri = silent)))
    }

    @Test
    fun aPushThatFailedIsNotRememberedAsSent() {
        val gate = ClusterMediaPushGate()
        val update = clusterMediaUpdate(nowPlaying(), artworkUri = null)
        gate.notePushed(update)

        gate.forget()

        // Without this the retry after a dead binder would be skipped as a duplicate for good.
        assertTrue(gate.shouldPush(update))
    }

    @Test
    fun everyFieldTheClusterShowsIsPartOfThePayload() {
        val base = nowPlaying()
        val gate = ClusterMediaPushGate()
        gate.notePushed(clusterMediaUpdate(base, artworkUri = null))

        assertTrue(gate.shouldPush(clusterMediaUpdate(base.copy(title = "Other"), null)))
        assertTrue(gate.shouldPush(clusterMediaUpdate(base.copy(artist = "Other"), null)))
        assertTrue(gate.shouldPush(clusterMediaUpdate(base.copy(album = "Other"), null)))
        assertTrue(gate.shouldPush(clusterMediaUpdate(base.copy(durationMillis = 1L), null)))
        assertTrue(gate.shouldPush(clusterMediaUpdate(base.copy(playing = false), null)))
    }

    private fun nowPlaying(elapsedMillis: Long? = 1000L) = CarPlayNowPlaying(
        title = "Track",
        artist = "Artist",
        album = "Album",
        artworkTransferId = 3,
        sourceApp = "Music",
        durationMillis = 200_000L,
        elapsedMillis = elapsedMillis,
        playing = true,
    )
}
