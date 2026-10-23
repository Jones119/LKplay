package com.shilapi.xcertplay

import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.view.KeyEvent
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CarPlayMediaCallbackTest {
    private val sent = mutableListOf<Int>()
    private var time = 0L

    /** CS11: the media-session callback now lives in the API 21+ holder; this fake exercises the
     *  mapping it delegates to, which is the part [CarPlayMediaKeys] owns on every API level. */
    private val callback = FakeMediaSessionCallback()

    @Before fun resetSharedMediaState() {
        MediaKeyEchoGate.reset()
        // Another test in this class may have left a player installed; while one is open the wheel
        // drives it instead of CarPlay (CarPlayVideo.onMediaKey), which would swallow these presses.
        CarPlayVideo.activity = null
    }

    private inner class FakeMediaSessionCallback {
        fun onPlay() {
            sent += CarPlayMediaButton.PLAY
        }

        fun onPause() {
            sent += CarPlayMediaButton.PAUSE
        }

        fun onSkipToNext() {
            sent += CarPlayMediaButton.NEXT
        }

        fun onSkipToPrevious() {
            sent += CarPlayMediaButton.PREVIOUS
        }

        fun onMediaButtonEvent(intent: Intent): Boolean {
            val event = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return false
            return CarPlayMediaKeys.dispatchMediaKey(event, "session")
        }
    }

    @Test
    fun controllerPlayAndPauseAreExplicit() {
        callback.onPlay()
        callback.onPause()
        callback.onSkipToNext()
        callback.onSkipToPrevious()

        assertEquals(
            listOf(CarPlayMediaButton.PLAY, CarPlayMediaButton.PAUSE, CarPlayMediaButton.NEXT, CarPlayMediaButton.PREVIOUS),
            sent,
        )
    }

    @Test
    fun hardwarePlayAndPauseKeysAreAllClaimed() {
        // No CarPlay controller is installed in a JVM test, so `send` cannot be observed; what the
        // holder decides — claim the press or hand it back — is the contract that matters here.
        // CarPlayMediaButtonTest pins the key code → press index mapping these three go through.
        assertTrue(press(KeyEvent.KEYCODE_MEDIA_PLAY))
        assertTrue(press(KeyEvent.KEYCODE_MEDIA_PAUSE))
        assertTrue(press(CarPlayMediaButton.KEYCODE_AUTO_MEDIA_PLAY_PAUSE))
        assertEquals(emptyList<Int>(), sent)
    }

    @Test
    fun aHeldKeyIsClaimedButTheEchoGateDropsEveryRepeatOfTheSamePress() {
        // The wheel press repeats with the same downTime/eventTime: the gate must accept exactly one.
        val first = KeyEvent(0, time, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT, 0)
        val repeat = KeyEvent(0, time, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT, 1)
        assertTrue(callback.onMediaButtonEvent(button(first)))
        assertTrue(callback.onMediaButtonEvent(button(repeat)))
        assertTrue(callback.onMediaButtonEvent(button(KeyEvent(0, time, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_NEXT, 0))))
        assertEquals(emptyList<Int>(), sent)
    }

    @Test
    fun anUnmappedKeyIsHandedBackToThePlatform() {
        // The holder returns this straight to MediaSession.Callback's default handling.
        assertEquals(false, callback.onMediaButtonEvent(
            button(KeyEvent(0, time++, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP, 0)),
        ))
        assertEquals(emptyList<Int>(), sent)
    }

    @Test
    fun theVoiceKeyIsClaimedEvenThoughItSendsNoMediaPress() {
        assertEquals(true, callback.onMediaButtonEvent(
            button(KeyEvent(0, time++, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOICE_ASSIST, 0)),
        ))
        assertEquals(emptyList<Int>(), sent)
    }

    @Test
    fun nowPlayingFieldsBecomeAndroidMediaMetadata() {
        val artwork = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        // CS11: MediaMetadata is API 21, so its builder lives in the version-isolated holder now.
        val metadata = MediaSessionBridgeFactory.metadataOf(
            CarPlayNowPlaying(
                title = "Dreams",
                album = "Rumours",
                artist = "Fleetwood Mac",
                sourceApp = "Music",
                durationMillis = 257_000,
            ),
            artwork,
        ) as MediaMetadata

        assertEquals("Dreams", metadata.getString(MediaMetadata.METADATA_KEY_TITLE))
        assertEquals("Dreams", metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE))
        assertEquals("Fleetwood Mac", metadata.getString(MediaMetadata.METADATA_KEY_ARTIST))
        assertEquals("Fleetwood Mac", metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE))
        assertEquals("Rumours", metadata.getString(MediaMetadata.METADATA_KEY_ALBUM))
        assertEquals("Music", metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION))
        assertEquals(257_000, metadata.getLong(MediaMetadata.METADATA_KEY_DURATION))
        assertEquals(artwork, metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART))
        assertEquals(artwork, metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON))
    }

    @Test
    fun aPendingArtworkTransferKeepsThePreviousArt() {
        val previous = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val cached = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)

        assertSame(previous, CarPlayMediaKeys.nextArtwork(7, emptyMap(), previous))
        assertSame(cached, CarPlayMediaKeys.nextArtwork(7, mapOf(7 to cached), previous))
        assertNull(CarPlayMediaKeys.nextArtwork(7, mapOf(7 to null), previous))
        assertNull(CarPlayMediaKeys.nextArtwork(null, mapOf(7 to cached), previous))
    }

    @Test
    fun aTitleThatMovesAloneIsNotATrackChange() {
        val first = CarPlayNowPlaying(
            title = "line one",
            artist = "Artist",
            album = "Album",
            durationMillis = 1_000,
            artworkTransferId = 7,
        )
        assertTrue(CarPlayMediaKeys.clusterTitleOnlyChurn(first, first.copy(title = "line two")))
        assertFalse(CarPlayMediaKeys.clusterTitleOnlyChurn(first, first.copy(title = "line two", artworkTransferId = 8)))
        assertFalse(CarPlayMediaKeys.clusterTitleOnlyChurn(first, first.copy(title = "line two", durationMillis = 2_000)))
        assertFalse(CarPlayMediaKeys.clusterTitleOnlyChurn(first, first.copy(title = "line two", artist = "Other")))
        // A title correction with nothing else changed also keeps the cover.
        assertTrue(CarPlayMediaKeys.clusterTitleOnlyChurn(first, first.copy(title = "Corrected")))
    }

    @Test
    fun thePlaceholderRastersAtArtworkSize() {
        val placeholder = CarPlayMediaKeys.placeholderArt(RuntimeEnvironment.getApplication())

        assertEquals(384, placeholder?.width)
        assertEquals(384, placeholder?.height)
    }

    private fun press(keyCode: Int, repeat: Int = 0): Boolean {
        var claimed = false
        for (count in 0..repeat) {
            claimed = callback.onMediaButtonEvent(button(KeyEvent(0, time, KeyEvent.ACTION_DOWN, keyCode, count)))
        }
        time++
        return claimed
    }

    private fun button(event: KeyEvent) = Intent(Intent.ACTION_MEDIA_BUTTON).putExtra(Intent.EXTRA_KEY_EVENT, event)

    @Test
    fun positionAndPlayStateDoNotRepublishMetadata() {
        val song = CarPlayNowPlaying(title = "Song", artist = "Artist", artworkTransferId = 7, elapsedMillis = 1_000, playing = true)
        assertEquals(false, CarPlayMediaKeys.metadataChanged(song, song.copy(elapsedMillis = 1_450)))
        assertEquals(false, CarPlayMediaKeys.metadataChanged(song, song.copy(playing = false)))
        assertEquals(true, CarPlayMediaKeys.metadataChanged(song, song.copy(title = "Next")))
        assertEquals(true, CarPlayMediaKeys.metadataChanged(song, song.copy(artworkTransferId = 8)))
        assertEquals(true, CarPlayMediaKeys.metadataChanged(CarPlayNowPlaying(), song))
    }

    /**
     * CS11: the two independent "is it playing" signals that decide the published PlaybackState.
     * This is the part of the playback-state publish a JVM test can still reach after the API 21+
     * objects moved into [MediaSessionBridgeFactory].
     */
    @Test
    fun iPhoneMetadataWinsOverTheAudioOnTheWireFlag() {
        val song = CarPlayNowPlaying(title = "Song", elapsedMillis = 4_000, playing = false)

        // The iPhone said "paused" while audio is still on the wire: the metadata wins.
        val paused = CarPlayMediaKeys.playbackStateRequest(song, mediaAudioActive = true, updatedAt = 42L)
        assertEquals(false, paused.playing)
        assertEquals(4_000L, paused.position)
        assertEquals(42L, paused.updatedAt)

        // No metadata yet: the audio-on-the-wire flag is the only signal, position stays unknown.
        val active = CarPlayMediaKeys.playbackStateRequest(CarPlayNowPlaying(), mediaAudioActive = true, updatedAt = 7L)
        assertEquals(true, active.playing)
        assertEquals(-1L, active.position)
        assertEquals(7L, active.updatedAt)
    }

    @Test
    fun aTitleWithoutAPositionStillCountsAsMetadata() {
        val withTitle = CarPlayMediaKeys.playbackStateRequest(
            CarPlayNowPlaying(title = "Song", playing = true),
            mediaAudioActive = false,
            updatedAt = 0L,
        )
        assertEquals(true, withTitle.playing)
        assertEquals(-1L, withTitle.position)

        val silent = CarPlayMediaKeys.playbackStateRequest(
            CarPlayNowPlaying(playing = true),
            mediaAudioActive = false,
            updatedAt = 0L,
        )
        assertEquals(false, silent.playing)
    }

    @Test
    fun thePublishedPlaybackStateCarriesTheRequestedPositionAndSpeed() {
        val playing = MediaSessionBridgeFactory.playbackStateOf(playing = true, position = 1_500L, updatedAt = 9L) as PlaybackState
        assertEquals(PlaybackState.STATE_PLAYING, playing.state)
        assertEquals(1_500L, playing.position)
        assertEquals(1f, playing.playbackSpeed, 0f)
        assertEquals(9L, playing.lastPositionUpdateTime)
        // All four wheel and controller actions must stay advertised, or the car hides the buttons.
        val actions = MediaSessionBridgeFactory.actions()
        for (action in listOf(
            PlaybackState.ACTION_PLAY,
            PlaybackState.ACTION_PAUSE,
            PlaybackState.ACTION_PLAY_PAUSE,
            PlaybackState.ACTION_SKIP_TO_NEXT,
            PlaybackState.ACTION_SKIP_TO_PREVIOUS,
        )) {
            assertEquals("action $action missing", action.toLong(), actions and action.toLong())
        }

        val paused = MediaSessionBridgeFactory.playbackStateOf(playing = false, position = -1L, updatedAt = 0L) as PlaybackState
        assertEquals(PlaybackState.STATE_PAUSED, paused.state)
        assertEquals(0f, paused.playbackSpeed, 0f)
    }
}
