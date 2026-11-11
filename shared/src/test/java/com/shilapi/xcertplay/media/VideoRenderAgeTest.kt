package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure part of the `debug.diplay.video_age_ms` knob (see [VideoRenderAge]). */
class VideoRenderAgeTest {
    @Test
    fun missingOrGarbageKeepsTheShipped250ms() {
        assertEquals(250L, VideoRenderAge.sanitize(null))
        assertEquals(250L, VideoRenderAge.sanitize(""))
        assertEquals(250L, VideoRenderAge.sanitize("abc"))
    }

    @Test
    fun aValueIsClampedToTheSupportedRange() {
        assertEquals(5_000L, VideoRenderAge.sanitize("999999"))
        assertEquals(0L, VideoRenderAge.sanitize("-1"))
        assertEquals(1_000L, VideoRenderAge.sanitize(" 1000 "))
    }
}
