package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class AndroidMediaSinkStateTest {
    @Test fun onlyTheMainScreenMarksThePictureAsReceived() {
        // The host asks the driver to restart the iPhone when the main stream stays pictureless;
        // the alternate (cluster) stream must not clear that state (upstream #975/#249).
        val sink = AndroidMediaSink()
        sink.noteVideoFrameType(111)
        assertFalse(sink.mainVideoReceived)
        sink.noteVideoFrameType(110)
        assertTrue(sink.mainVideoReceived)
        sink.close()
    }
    @Test fun recreatingTheScreenRestoresItsActiveVideoState() {
        val sink = AndroidMediaSink()
        sink.onScreenStreamActive(110, true)
        sink.onScreenStreamActive(111, true)
        sink.onScreenStreamActive(111, false)
        val events = mutableListOf<Pair<Int, Boolean>>()
        sink.setScreenStreamActiveChangedListener { type, active -> events.add(type to active) }
        assertEquals(listOf(110 to true), events)
        sink.close()
        assertEquals(listOf(110 to true, 110 to false), events)
        val afterClose = mutableListOf<Pair<Int, Boolean>>()
        sink.setScreenStreamActiveChangedListener { type, active -> afterClose.add(type to active) }
        assertTrue(afterClose.isEmpty())
    }
}
