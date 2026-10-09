package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The no-argument [OpusEncoderSupport.isAvailable] walks the real platform codec list, so it is the
 * one entry point a pure-JVM test cannot reach with injected probes. It is worth its own test
 * because getting it wrong once meant a lazy initializer that read the property it initialized, and
 * every host activity that built an info plist then died with StackOverflowError — the injected-probe
 * tests never touch that path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class OpusEncoderSupportPlatformTest {
    @Test
    fun probingThePlatformCapabilityTerminatesAndIsMemoized() {
        val first = OpusEncoderSupport.isAvailable()
        // Recursion through the lazy initializer shows up here as a StackOverflowError, not as a
        // wrong answer, so the calls have to actually be made.
        assertEquals(first, OpusEncoderSupport.isAvailable())
        assertEquals(first, OpusEncoderSupport.isAvailable())
    }
}
