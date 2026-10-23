package com.shilapi.xcertplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Precedence for the experimental SurfaceView sink: property override, then the settings switch. */
class VideoSurfaceModeTest {

    @Test
    fun textureViewIsTheDefault() {
        assertFalse(VideoSurfaceMode.choose(preferenceEnabled = false, property = null))
    }

    @Test
    fun theSettingsSwitchEnablesSurfaceView() {
        assertTrue(VideoSurfaceMode.choose(preferenceEnabled = true, property = null))
    }

    @Test
    fun thePropertyWinsOverTheSwitch() {
        assertTrue(VideoSurfaceMode.choose(preferenceEnabled = false, property = "surface"))
        assertFalse(VideoSurfaceMode.choose(preferenceEnabled = true, property = "texture"))
    }

    @Test
    fun thePropertyAcceptsTheLabSpellings() {
        assertTrue(VideoSurfaceMode.choose(false, "1"))
        assertTrue(VideoSurfaceMode.choose(false, " TRUE "))
        assertFalse(VideoSurfaceMode.choose(true, "0"))
        assertFalse(VideoSurfaceMode.choose(true, "false"))
    }

    @Test
    fun anEmptyPropertyFallsThroughToTheSwitch() {
        assertTrue(VideoSurfaceMode.choose(preferenceEnabled = true, property = ""))
        assertFalse(VideoSurfaceMode.choose(preferenceEnabled = false, property = "  "))
    }
}
