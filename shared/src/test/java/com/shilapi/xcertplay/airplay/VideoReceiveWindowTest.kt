package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The window is the only end-to-end throttle on the wired video sender (docs/_digest/18 §3 item 3), so
 * a bad property value must never be able to disable the video path: every unparsable or out-of-range
 * value has to fall back to the shipped default or clamp into the legal range.
 */
class VideoReceiveWindowTest {
    @Test
    fun unsetPropertyKeepsTheShippedWindow() {
        assertEquals(VideoReceiveWindow.DEFAULT_BYTES, VideoReceiveWindow.sanitize(null))
        assertEquals(VideoReceiveWindow.DEFAULT_BYTES, VideoReceiveWindow.sanitize(""))
        assertEquals(VideoReceiveWindow.DEFAULT_BYTES, VideoReceiveWindow.sanitize("   "))
    }

    @Test
    fun unparsablePropertyKeepsTheShippedWindow() {
        assertEquals(VideoReceiveWindow.DEFAULT_BYTES, VideoReceiveWindow.sanitize("32768B"))
        assertEquals(VideoReceiveWindow.DEFAULT_BYTES, VideoReceiveWindow.sanitize("64k"))
        assertEquals(VideoReceiveWindow.DEFAULT_BYTES, VideoReceiveWindow.sanitize("8k"))
    }

    @Test
    fun aNonPositiveValueReadsAsUnset() {
        assertEquals(VideoReceiveWindow.DEFAULT_BYTES, VideoReceiveWindow.sanitize("-1"))
        assertEquals(VideoReceiveWindow.DEFAULT_BYTES, VideoReceiveWindow.sanitize("0"))
    }

    @Test
    fun aValueInsideTheRangeIsUsedAsWritten() {
        assertEquals(32 * 1024, VideoReceiveWindow.sanitize("32768"))
        assertEquals(48 * 1024, VideoReceiveWindow.sanitize("49152"))
        assertEquals(96 * 1024, VideoReceiveWindow.sanitize(" 98304 "))
        assertEquals(VideoReceiveWindow.MIN_BYTES, VideoReceiveWindow.sanitize("4096"))
        assertEquals(VideoReceiveWindow.MAX_BYTES, VideoReceiveWindow.sanitize("262144"))
    }

    @Test
    fun positiveValuesOutsideTheRangeAreClampedInsteadOfRefused() {
        assertEquals(VideoReceiveWindow.MIN_BYTES, VideoReceiveWindow.sanitize("1"))
        assertEquals(VideoReceiveWindow.MAX_BYTES, VideoReceiveWindow.sanitize("9999999"))
    }
}
