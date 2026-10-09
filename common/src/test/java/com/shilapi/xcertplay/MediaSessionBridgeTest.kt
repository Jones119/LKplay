package com.shilapi.xcertplay

import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.PlaybackState
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * CS11: the API-21 split for media keys. The bridge itself can only be exercised on a JVM at API 21+,
 * so these tests cover
 *  - the level decisions that keep `MediaSession`/`AudioFocusRequest` off the 4.4 path
 *    ([MediaKeysPlatform]), which is what the car's Dalvik validator actually reacts to, and
 *  - the pieces that survive the split ([MediaSessionBridgeFactory], the media-session callback).
 *
 * Splitting a live media session on Android 4.4 still needs a device or a Robolectric run at API 19;
 * Robolectric 4.17 only ships android-all jars from 21 up, so API 19 is out of reach here and has to
 * be confirmed on the head unit (see docs/_digest/49).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class MediaSessionBridgeTest {
    private val context = RuntimeEnvironment.getApplication()
    private val focusReports = mutableListOf<Boolean>()
    private val sent = mutableListOf<Pair<Int, String>>()
    private val controller = object : MediaSessionController {
        override fun audioFocusHeld(held: Boolean) {
            focusReports += held
        }

        override fun mediaKey(index: Int, source: String) {
            sent += index to source
        }

        override fun unclaimedMediaKey(event: android.view.KeyEvent): Boolean = false

        override fun audioFocusChanged(change: Int) = Unit
    }

    @Test
    fun mediaSessionsAreOnlyOwnedFromApi21() {
        assertFalse(MediaKeysPlatform.ownsMediaSession(19))
        assertFalse(MediaKeysPlatform.ownsMediaSession(20))
        assertTrue(MediaKeysPlatform.ownsMediaSession(21))
        assertTrue(MediaKeysPlatform.ownsMediaSession(26))
        assertTrue(MediaKeysPlatform.ownsMediaSession(37))
    }

    @Test
    fun theFocusRequestObjectIsOnlyOwnedFromApi26() {
        assertFalse(MediaKeysPlatform.ownsAudioFocusRequest(19))
        assertFalse(MediaKeysPlatform.ownsAudioFocusRequest(21))
        assertFalse(MediaKeysPlatform.ownsAudioFocusRequest(25))
        assertTrue(MediaKeysPlatform.ownsAudioFocusRequest(26))
        assertTrue(MediaKeysPlatform.ownsAudioFocusRequest(37))
    }

    @Test
    fun theBridgeIsCreatedOnApi21AndUp() {
        assertTrue(MediaKeysPlatform.ownsMediaSession(21))
        assertNotNull(MediaSessionBridge.of(context, controller))
    }

    @Test
    fun theBridgeNamesItsApiLevelForTheLegacyPathToDecide() {
        // The 4.4 owner asks exactly this question before it touches any media-session type; the
        // answer for the car is false, which is what keeps `session` null there.
        assertEquals(false, MediaKeysPlatform.ownsMediaSession(19))
        assertEquals(false, MediaKeysPlatform.ownsAudioFocusRequest(19))
    }

    @Test
    fun theBridgeReportsTheFocusHoldBackToTheOwner() {
        assertNotNull(MediaSessionBridge.of(context, controller))
        // The holder hands the focus result to the owner exactly once, on construction; that is how
        // `focusHeld` stays truthful on the car without the owner touching an audio-focus type.
        assertEquals(1, focusReports.size)
    }

    @Test
    fun theSessionIsActiveAndTheReturnedSurfaceIsTheBridge() {
        val bridge = MediaSessionBridge.of(context, controller)
        assertNotNull(bridge)
        assertTrue(bridge!!.isActive)
    }

    @Test
    fun metadataAndPlaybackStateAreBuiltByTheHolder() {
        val artwork = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val metadata = MediaSessionBridgeFactory.metadataOf(
            CarPlayNowPlaying(title = "T", artist = "A", durationMillis = 1_000),
            artwork,
        ) as MediaMetadata
        assertEquals("T", metadata.getString(MediaMetadata.METADATA_KEY_TITLE))
        assertEquals(artwork, metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART))

        val state = MediaSessionBridgeFactory.playbackStateOf(true, 2_000L, 5L) as PlaybackState
        assertEquals(PlaybackState.STATE_PLAYING, state.state)
        assertEquals(2_000L, state.position)
    }
}
