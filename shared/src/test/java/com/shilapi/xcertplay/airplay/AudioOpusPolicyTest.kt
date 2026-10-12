package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `debug.diplay.opus`: the CarPlay `/info` Opus advertisement, added because the reference legacy
 * build on this car advertises no Opus at all while this fork does (docs/_digest/28, 2026-10-12).
 */
class AudioOpusPolicyTest {
    @Test fun theShippedModeIsTheDefault() {
        assertEquals(AudioOpusMode.AUTO, AudioOpusPolicy.parse(null))
        assertEquals(AudioOpusMode.AUTO, AudioOpusPolicy.parse(""))
        assertEquals(AudioOpusMode.AUTO, AudioOpusPolicy.parse("  "))
        assertEquals(AudioOpusMode.AUTO, AudioOpusPolicy.parse("auto"))
        assertEquals(AudioOpusMode.AUTO, AudioOpusPolicy.parse("1"))
    }

    @Test fun theTwoOverrideSpellingsAreAccepted() {
        for (value in listOf("input-off", "INPUT_OFF", " no-input ", "pcm-input")) {
            assertEquals(AudioOpusMode.INPUT_OFF, AudioOpusPolicy.parse(value))
        }
        for (value in listOf("off", "OFF", "no-opus", "pcm", "pcm-only")) {
            assertEquals(AudioOpusMode.OFF, AudioOpusPolicy.parse(value))
        }
    }

    @Test fun onlyTheOffModeDropsOpusFromTheOutputs() {
        assertTrue(AudioOpusPolicy.opusOutput(AudioOpusMode.AUTO))
        // iOS built no audio stream at all when a head unit advertised no Opus output (docs 27/39),
        // so the input-only mode must leave the output entries alone.
        assertTrue(AudioOpusPolicy.opusOutput(AudioOpusMode.INPUT_OFF))
        assertFalse(AudioOpusPolicy.opusOutput(AudioOpusMode.OFF))
    }

    @Test fun theMicrophoneOffersOpusOnlyWhenItCanEncodeItAndTheModeAllowsIt() {
        assertTrue(AudioOpusPolicy.microphoneOpus(AudioOpusMode.AUTO, encoderAvailable = true))
        // No encoder: the shipped probe already answers this, and the override cannot invent one.
        assertFalse(AudioOpusPolicy.microphoneOpus(AudioOpusMode.AUTO, encoderAvailable = false))
        assertFalse(AudioOpusPolicy.microphoneOpus(AudioOpusMode.INPUT_OFF, encoderAvailable = true))
        assertFalse(AudioOpusPolicy.microphoneOpus(AudioOpusMode.OFF, encoderAvailable = true))
    }
}
