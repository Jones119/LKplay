package com.shilapi.xcertplay.media

import android.media.AudioManager
import android.media.AudioTrack
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Upstream `1cb3a763` — a focus callback the coordinator does not act on is reported as such.
 *
 * The listener used to print one line whether it acted on the callback or not, so a callback that
 * arrived with no track holding focus looked exactly like a genuinely handled one, and both looked
 * like the report of a head unit that never called back at all. That gap — a telephony track
 * closing, media not reopened yet — is precisely where a head unit that keeps focus for itself
 * produces its callback, so the car needs to show it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class AudioFocusDiagnosticsTest {
    @Test
    fun aCallbackThatArrivesWithNoLiveRequestIsReportedAsDropped() {
        val diagnostics = CopyOnWriteArrayList<String>()
        listenerOf(coordinator(diagnostics::add)).onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)

        assertEquals(
            listOf("Audio: focus change=${AudioManager.AUDIOFOCUS_GAIN} dropped held=false activeTracks=0"),
            diagnostics.toList(),
        )
    }

    @Test
    fun aCallbackWithALiveRequestKeepsTheUnqualifiedReport() {
        val diagnostics = CopyOnWriteArrayList<String>()
        val coordinator = coordinator(diagnostics::add)
        holdFocusWithOneActiveTrack(coordinator)
        listenerOf(coordinator).onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)

        assertEquals(
            listOf("Audio: focus change=${AudioManager.AUDIOFOCUS_LOSS} activeTracks=1"),
            diagnostics.toList(),
        )
    }

    @Test
    fun aThrowingReportCallbackDoesNotEscapeTheFocusListener() {
        var reports = 0
        val listener = listenerOf(coordinator { reports++; throw IllegalStateException("diagnostic callback failed") })

        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertEquals(1, reports)
    }

    private fun coordinator(report: (String) -> Unit) =
        AudioFocusCoordinator(RuntimeEnvironment.getApplication(), true, report)

    private fun listenerOf(coordinator: AudioFocusCoordinator): AudioManager.OnAudioFocusChangeListener =
        AudioFocusCoordinator::class.java.getDeclaredField("listener")
            .apply { isAccessible = true }
            .get(coordinator) as AudioManager.OnAudioFocusChangeListener

    /**
     * A live request with one eligible track, set without going through the Android focus APIs:
     * the classification under test reads only the coordinator's own state, which is also what the
     * exported report shows.
     */
    private fun holdFocusWithOneActiveTrack(coordinator: AudioFocusCoordinator) {
        val entry = Class.forName("com.shilapi.xcertplay.media.AudioFocusCoordinator\$Entry")
            .getDeclaredConstructors().single { it.parameterCount == 3 }
            .apply { isAccessible = true }
            .newInstance(AudioChannel.MEDIA, null, AudioManager.STREAM_MUSIC)
        val track = AudioTrack(
            AudioManager.STREAM_MUSIC,
            48_000,
            android.media.AudioFormat.CHANNEL_OUT_STEREO,
            android.media.AudioFormat.ENCODING_PCM_16BIT,
            4_096,
            AudioTrack.MODE_STREAM,
        )
        @Suppress("UNCHECKED_CAST")
        val active = AudioFocusCoordinator::class.java.getDeclaredField("active")
            .apply { isAccessible = true }
            .get(coordinator) as MutableMap<Any?, Any?>
        active[track] = entry
        AudioFocusCoordinator::class.java.getDeclaredField("held")
            .apply { isAccessible = true }
            .setBoolean(coordinator, true)
    }
}
