package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.AudioStreamId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The inference that puts a VOIP call on the cluster without the iPhone ever reporting it
 * (docs/_digest/121): a CarPlay audio stream that has both a running microphone and downlink audio.
 *
 * The negative cases matter as much as the positive one. On this car the voice-note path is a
 * microphone with no downlink and Siri answers on `speechrecognition`, and both would otherwise read as
 * a call in progress.
 */
class VoiceCallWatchTest {
    private val events = mutableListOf<Pair<Boolean, Long>>()
    private val lines = mutableListOf<String>()
    private var now = 1_000L

    private val watch = VoiceCallWatch(
        report = { lines += it },
        publish = { active, since -> events += active to since },
        clock = { now },
    )

    private fun stream(audioType: String, type: Int = 100) = AudioStreamId(type, audioType)

    @Test
    fun aTwoWayTelephonyStreamIsACall() {
        val call = stream("telephony")

        watch.microphoneStarted(call)
        assertTrue("half a call is not a call", events.isEmpty())

        watch.downlinkStarted(call)
        assertEquals(listOf(true to 1_000L), events)
    }

    @Test
    fun theOrderOfTheTwoHalvesDoesNotMatter() {
        val call = stream("default")

        watch.downlinkStarted(call)
        assertTrue(events.isEmpty())

        watch.microphoneStarted(call)
        assertEquals(listOf(true to 1_000L), events)
    }

    @Test
    fun aConfirmedCallIsPublishedOnlyOnce() {
        val call = stream("telephony")

        watch.microphoneStarted(call)
        watch.downlinkStarted(call)
        // The engine re-reports the same stream when it replaces it, and a repeated transition would cost
        // the cluster a binder transaction for a card that is already there.
        watch.microphoneStarted(call)
        watch.downlinkStarted(call)

        assertEquals(listOf(true to 1_000L), events)
    }

    @Test
    fun aVoiceNoteIsNotACall() {
        // Measured on the car: holding the button in WeChat opens the microphone and plays nothing back.
        val note = stream("speechrecognition")
        watch.microphoneStarted(note)
        now = 4_000L
        watch.microphoneStopped(note)

        assertTrue(events.isEmpty())
    }

    @Test
    fun theTraceSaysWhenAVoiceStreamUsedATypeThatIsNotACallByPolicy() {
        // If a VOIP call ever arrives on another type, this line is what says so -- the card's absence
        // alone would not distinguish "iOS kept the audio on the phone" from "we chose not to show it".
        watch.microphoneStarted(stream("speechrecognition"))

        assertEquals(1, lines.size)
        assertTrue(lines[0], lines[0].contains("type=speechrecognition"))
        assertTrue(lines[0], lines[0].contains("not a call by policy"))
    }

    @Test
    fun siriIsNotACallEvenThoughItAnswersBack() {
        // Siri's own audio arrives on its stream too, so direction alone would call every turn a call.
        val siri = stream("speechrecognition")
        watch.microphoneStarted(siri)
        watch.downlinkStarted(siri)
        watch.microphoneStopped(siri)

        assertTrue(events.isEmpty())
    }

    @Test
    fun aNavigationPromptAndMusicAreNotCalls() {
        watch.downlinkStarted(stream("default"))
        watch.downlinkStarted(stream("media"))
        watch.downlinkStarted(stream("alert"))

        assertTrue(events.isEmpty())
    }

    @Test
    fun aMicrophoneThatNeverStartsIsNotHalfACall() {
        // AndroidMediaSink reports a refused capture as a stop, and a stop before any start must not
        // confirm anything -- nor may a stale stop clear a call of its own.
        val call = stream("telephony")
        watch.microphoneStopped(call)
        watch.downlinkStarted(call)

        assertTrue(events.isEmpty())
    }

    @Test
    fun endingEitherHalfEndsTheCall() {
        val call = stream("telephony")
        watch.microphoneStarted(call)
        watch.downlinkStarted(call)
        now = 61_000L

        watch.downlinkStopped(call)
        assertEquals(listOf(true to 1_000L, false to 61_000L), events)
    }

    @Test
    fun endingTheMicrophoneEndsTheCallToo() {
        val call = stream("default")
        watch.microphoneStarted(call)
        watch.downlinkStarted(call)

        watch.microphoneStopped(call)
        assertEquals(listOf(true to 1_000L, false to 1_000L), events)
    }

    @Test
    fun aSecondCallAfterTheFirstIsPublishedAgain() {
        val first = stream("telephony")
        watch.microphoneStarted(first)
        watch.downlinkStarted(first)
        watch.downlinkStopped(first)

        val second = stream("default")
        watch.microphoneStarted(second)
        watch.downlinkStarted(second)

        assertEquals(listOf(true to 1_000L, false to 1_000L, true to 1_000L), events)
    }

    @Test
    fun endingTheSessionWithdrawsAnActiveCall() {
        val call = stream("telephony")
        watch.microphoneStarted(call)
        watch.downlinkStarted(call)

        watch.reset()
        watch.reset()

        assertEquals("the card must be withdrawn exactly once", listOf(true to 1_000L, false to 1_000L), events)
    }

    @Test
    fun theDiagnosticLineNamesTheAudioTypeSoTheCarCanSettleWhichOneItIs() {
        watch.microphoneStarted(stream("telephony"))
        watch.downlinkStarted(stream("telephony"))
        now = 62_500L
        watch.downlinkStopped(stream("telephony"))

        assertEquals(2, lines.size)
        assertTrue(lines[0], lines[0].contains("type=telephony"))
        assertTrue(lines[0], lines[0].contains("bothWays=true"))
        assertTrue(lines[1], lines[1].contains("durationMs=61500"))
    }

    @Test
    fun aCallIsNotConfirmedOnACaseDifferentAudioType() {
        // The SETUP plist is not case-stable; the engine lowercases it, and so must the rule.
        val call = stream("TELEPHONY")
        watch.microphoneStarted(call)
        watch.downlinkStarted(call)

        assertTrue(events.isNotEmpty())
        assertFalse(lines.isEmpty())
    }
}
