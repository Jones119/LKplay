package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which audio types open the CarPlay microphone. Calls and Siri always record; app audio records only
 * when the iPhone asks for input, because guidance prompts share the same audio type (upstream
 * c2afb171: a voice note recorded as silence unless a call had just ended).
 */
class MicrophoneRequestTest {
    @Test fun callsAndSiriAlwaysRecord() {
        assertTrue(CarPlayMediaEngine.wantsMicrophone("telephony", emptyMap()))
        assertTrue(CarPlayMediaEngine.wantsMicrophone("speechrecognition", emptyMap()))
    }

    @Test fun appAudioRecordsOnlyWhenTheIphoneAsksForInput() {
        // No input flag and no uplink port: a guidance prompt, not a recording.
        assertFalse(CarPlayMediaEngine.wantsMicrophone("default", mapOf("type" to 100)))
        assertFalse(CarPlayMediaEngine.wantsMicrophone("compatibility", mapOf("input" to false)))
        assertTrue(CarPlayMediaEngine.wantsMicrophone("default", mapOf("input" to true)))
        assertTrue(CarPlayMediaEngine.wantsMicrophone("default", mapOf("input" to 1)))
        // The uplink port is the alternative signal when no flag is present.
        assertTrue(CarPlayMediaEngine.wantsMicrophone("default", mapOf("dataPort" to 5001)))
        assertFalse(CarPlayMediaEngine.wantsMicrophone("default", mapOf("dataPort" to 0)))
        assertFalse(CarPlayMediaEngine.wantsMicrophone("default", mapOf("dataPort" to 70000)))
    }

    @Test fun mediaAndOtherTypesNeverRecord() {
        assertFalse(CarPlayMediaEngine.wantsMicrophone("media", mapOf("input" to true)))
        assertFalse(CarPlayMediaEngine.wantsMicrophone("alert", mapOf("input" to true)))
        assertFalse(CarPlayMediaEngine.wantsMicrophone("", mapOf("input" to true)))
    }
}
