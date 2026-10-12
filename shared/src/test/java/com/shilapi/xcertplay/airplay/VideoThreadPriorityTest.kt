package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoThreadPriorityTest {
    @Test fun theDefaultIsTheUrgentDisplayValue() {
        assertEquals(VideoThreadPriority.URGENT_DISPLAY, VideoThreadPriority.DEFAULT)
        assertEquals(VideoThreadPriority.DEFAULT, VideoThreadPriority.sanitize(null))
        assertEquals(VideoThreadPriority.DEFAULT, VideoThreadPriority.sanitize(""))
        assertEquals(VideoThreadPriority.DEFAULT, VideoThreadPriority.sanitize("urgent"))
    }

    @Test fun thePropertyCanRestoreThePreviouslyShippedPriority() {
        assertEquals(VideoThreadPriority.DISPLAY, VideoThreadPriority.sanitize("display"))
        assertEquals(VideoThreadPriority.DISPLAY, VideoThreadPriority.sanitize(" DISPLAY "))
        assertEquals(VideoThreadPriority.DISPLAY, VideoThreadPriority.sanitize("-4"))
    }

    @Test fun anUnknownValueFallsBackToTheDefaultInsteadOfFailing() {
        assertEquals(VideoThreadPriority.DEFAULT, VideoThreadPriority.sanitize("nonsense"))
        assertEquals(VideoThreadPriority.DEFAULT, VideoThreadPriority.sanitize("-99"))
    }

    @Test fun theVideoThreadStaysBelowTheAudioThreads() {
        // URGENT_AUDIO (-19) is more urgent than any value this tunable can produce.
        assertTrue(VideoThreadPriority.DEFAULT > android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        assertTrue(VideoThreadPriority.DISPLAY > android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
    }
}
