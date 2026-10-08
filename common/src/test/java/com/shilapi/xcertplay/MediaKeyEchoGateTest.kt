package com.shilapi.xcertplay

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One physical steering-wheel press can be delivered more than once on this head unit (the CarPlay
 * host activity's dispatch, the Android 4.4 MEDIA_BUTTON broadcast, the wheel-key accessibility
 * filter), and a duplicated NEXT silently skips two tracks. EasyPlay guards the same harness with the
 * same three fields (LegacyMediaKeyGate), which is what this mirrors.
 */
class MediaKeyEchoGateTest {

    @After
    fun tearDown() = MediaKeyEchoGate.reset()

    @Test
    fun theSamePressIsAcceptedOnceEvenAcrossPaths() {
        assertTrue(MediaKeyEchoGate.accept(87, 1_000L, 1_010L))
        assertFalse(MediaKeyEchoGate.accept(87, 1_000L, 1_010L))
        assertFalse(MediaKeyEchoGate.accept(87, 1_000L, 1_010L))
    }

    @Test
    fun aDeliberateDoubleTapIsNotEaten() {
        assertTrue(MediaKeyEchoGate.accept(87, 1_000L, 1_010L))
        // A second real press has its own downTime/eventTime, so it passes — a time window would not.
        assertTrue(MediaKeyEchoGate.accept(87, 1_200L, 1_210L))
        assertTrue(MediaKeyEchoGate.accept(88, 1_400L, 1_410L))
    }

    @Test
    fun theKeyUpOfTheSamePressIsAlsoIgnored() {
        assertTrue(MediaKeyEchoGate.accept(126, 5_000L, 5_010L))
        assertFalse(MediaKeyEchoGate.accept(126, 5_000L, 5_010L))
    }

    @Test
    fun resetLetsTheNextSessionThrough() {
        assertTrue(MediaKeyEchoGate.accept(87, 1_000L, 1_010L))
        MediaKeyEchoGate.reset()
        assertTrue(MediaKeyEchoGate.accept(87, 1_000L, 1_010L))
    }
}
