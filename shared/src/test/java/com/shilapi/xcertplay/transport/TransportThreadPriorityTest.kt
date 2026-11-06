package com.shilapi.xcertplay.transport

import android.os.Process
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportThreadPriorityTest {
    @Test fun theDefaultIsTheAudioValue() {
        assertEquals(Process.THREAD_PRIORITY_AUDIO, TransportThreadPriority.DEFAULT)
        assertEquals(TransportThreadPriority.DEFAULT, TransportThreadPriority.sanitize(null))
        assertEquals(TransportThreadPriority.DEFAULT, TransportThreadPriority.sanitize(""))
    }

    @Test fun thePropertySelectsTheVideoValueOrThePreviousBehaviour() {
        assertEquals(Process.THREAD_PRIORITY_URGENT_DISPLAY, TransportThreadPriority.sanitize("video"))
        assertEquals(Process.THREAD_PRIORITY_URGENT_DISPLAY, TransportThreadPriority.sanitize(" VIDEO "))
        assertEquals(Process.THREAD_PRIORITY_URGENT_DISPLAY, TransportThreadPriority.sanitize("-8"))
        assertEquals(Process.THREAD_PRIORITY_DEFAULT, TransportThreadPriority.sanitize("normal"))
        assertEquals(Process.THREAD_PRIORITY_DEFAULT, TransportThreadPriority.sanitize("NORMAL"))
        assertEquals(Process.THREAD_PRIORITY_DEFAULT, TransportThreadPriority.sanitize(" 0"))
    }

    @Test fun anUnknownValueFallsBackToTheDefaultInsteadOfFailing() {
        // bridge/sys_arch.c parses the same property the same way for lwip-tcpip.
        assertEquals(TransportThreadPriority.DEFAULT, TransportThreadPriority.sanitize("nonsense"))
        assertEquals(TransportThreadPriority.DEFAULT, TransportThreadPriority.sanitize("-19"))
    }

    @Test fun theTransportThreadsStayBelowTheAudioWriterAndAboveVideo() {
        assertTrue(TransportThreadPriority.DEFAULT > Process.THREAD_PRIORITY_URGENT_AUDIO)
        assertTrue(TransportThreadPriority.DEFAULT < Process.THREAD_PRIORITY_URGENT_DISPLAY)
    }
}
