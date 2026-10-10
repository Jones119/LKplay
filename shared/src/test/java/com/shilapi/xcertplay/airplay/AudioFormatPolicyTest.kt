package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The override exists to swap the wired and wireless media wire formats on the car, so an unknown or
 * misspelled value must mean "as shipped" rather than silently forcing a format.
 */
class AudioFormatPolicyTest {
    @Test
    fun unsetOrUnknownMeansShippedBehaviour() {
        assertEquals(AudioFormatOverride.AUTO, AudioFormatPolicy.parse(null))
        assertEquals(AudioFormatOverride.AUTO, AudioFormatPolicy.parse(""))
        assertEquals(AudioFormatOverride.AUTO, AudioFormatPolicy.parse("  "))
        assertEquals(AudioFormatOverride.AUTO, AudioFormatPolicy.parse("both"))
        assertEquals(AudioFormatOverride.AUTO, AudioFormatPolicy.parse("no-mp3"))
    }

    @Test
    fun forcingAacAcceptsTheSpellingsAFieldSessionWouldUse() {
        for (raw in listOf("no-pcm", "no_pcm", "NO-PCM", " aac ", "aac-only")) {
            assertEquals(raw, AudioFormatOverride.NO_PCM, AudioFormatPolicy.parse(raw))
        }
    }

    @Test
    fun forcingPcmAcceptsTheSpellingsAFieldSessionWouldUse() {
        for (raw in listOf("no-aac", "no_aac", "NO-AAC", " pcm ", "pcm-only")) {
            assertEquals(raw, AudioFormatOverride.NO_AAC, AudioFormatPolicy.parse(raw))
        }
    }
}
