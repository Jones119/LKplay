package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `debug.diplay.video_backlog`: the decoded-video backlog tolerance, added to test the one
 * difference from the legacy EasyPlay build that our own measurements had not already ruled out
 * (docs/_digest/57 §5).
 */
class VideoBacklogPolicyTest {
    @Test fun theShippedModeIsTheDefault() {
        assertEquals(VideoBacklogMode.AUTO, VideoBacklogPolicy.parse(null))
        assertEquals(VideoBacklogMode.AUTO, VideoBacklogPolicy.parse(""))
        assertEquals(VideoBacklogMode.AUTO, VideoBacklogPolicy.parse("auto"))
        assertEquals(VideoBacklogMode.AUTO, VideoBacklogPolicy.parse("60"))
    }

    @Test fun theLegacySpellingsAreAccepted() {
        for (value in listOf("legacy", "LEGACY", " tight ", "easyplay", "20")) {
            assertEquals(VideoBacklogMode.LEGACY, VideoBacklogPolicy.parse(value))
        }
    }

    @Test fun theShippedValuesAreTheOnesTheDecoderLoopDocuments() {
        assertEquals(60, VideoBacklogPolicy.maxFrames(VideoBacklogMode.AUTO))
        assertEquals(8 * 1024 * 1024, VideoBacklogPolicy.maxBytes(VideoBacklogMode.AUTO))
        assertEquals(1_000_000_000L, VideoBacklogPolicy.stallRecoveryNs(VideoBacklogMode.AUTO))
    }

    @Test fun theExperimentIsStrictlyTighterThanShipped() {
        // The legacy build's numbers (LegacyVideoDecoder.java:214,324). If this ever stops being
        // tighter, the experiment no longer tests what it claims to.
        val legacyFrames = VideoBacklogPolicy.maxFrames(VideoBacklogMode.LEGACY)
        val legacyBytes = VideoBacklogPolicy.maxBytes(VideoBacklogMode.LEGACY)
        val legacyStall = VideoBacklogPolicy.stallRecoveryNs(VideoBacklogMode.LEGACY)
        assertEquals(20, legacyFrames)
        assertEquals(4 * 1024 * 1024, legacyBytes)
        assertEquals(250_000_000L, legacyStall)
        assertTrue(legacyFrames < VideoBacklogPolicy.maxFrames(VideoBacklogMode.AUTO))
        assertTrue(legacyBytes < VideoBacklogPolicy.maxBytes(VideoBacklogMode.AUTO))
        assertTrue(legacyStall < VideoBacklogPolicy.stallRecoveryNs(VideoBacklogMode.AUTO))
    }
}
