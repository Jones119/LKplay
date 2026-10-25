package com.shilapi.xcertplay

import android.content.Intent
import android.graphics.Bitmap
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

    /** Exercises the key mapping [CarPlayMediaKeys] owns on the legacy media-key route. */
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
        // The legacy receiver returns this straight to the platform.
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
}
