package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The window is the only end-to-end throttle on the wired video sender (docs/_digest/18 §3 item 3), so
 * a bad property value must never be able to disable the video path: every unparsable or out-of-range
 * value has to fall back to the shipped 64 KiB.
 */
class VideoReceiveWindowTest {
    @Test
    fun unsetPropertyKeepsTheShippedWindow() {
        assertEquals(64 * 1024, VideoReceiveWindow.sanitize(null))
        assertEquals(64 * 1024, VideoReceiveWindow.sanitize(""))
        assertEquals(64 * 1024, VideoReceiveWindow.sanitize("   "))
    }

    @Test
    fun unparsablePropertyKeepsTheShippedWindow() {
        assertEquals(64 * 1024, VideoReceiveWindow.sanitize("32768B"))
        assertEquals(64 * 1024, VideoReceiveWindow.sanitize("64k"))
    }

    @Test
    fun aNonPositiveValueReadsAsUnset() {
        assertEquals(64 * 1024, VideoReceiveWindow.sanitize("-1"))
        assertEquals(64 * 1024, VideoReceiveWindow.sanitize("0"))
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
