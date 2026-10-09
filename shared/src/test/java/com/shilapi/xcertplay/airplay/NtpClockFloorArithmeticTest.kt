package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.math.BigInteger

/**
 * CS11: proves the local floor helpers replaced `Math.floorDiv`/`Math.floorMod` (API 24, not
 * covered by core library desugaring) with identical semantics, including the negative operands
 * that are the reason those functions exist at all.
 *
 * A separate regression test scans the module sources for a reintroduced `Math.floorDiv` call; this
 * file only pins the arithmetic the timing port depends on.
 */
class NtpClockFloorArithmeticTest {
    @Test
    fun floorDivisionRoundsTowardNegativeInfinityForEverySignCombination() {
        // Positive dividend: identical to truncating division.
        assertEquals(1L, floorDivLong(7L, 5L))
        assertEquals(0L, floorDivLong(3L, 5L))
        // Negative dividend, positive divisor: truncation would give 0 and -1.
        assertEquals(-1L, floorDivLong(-3L, 5L))
        assertEquals(-2L, floorDivLong(-7L, 5L))
        // Negative divisor: truncation rounds up, so the result is one lower than `/` gives when the
        // magnitudes do not divide exactly, and unchanged when they do.
        assertEquals(-1L, floorDivLong(3L, -5L))
        assertEquals(-2L, floorDivLong(7L, -5L))
        assertEquals(0L, floorDivLong(-3L, -5L))
        assertEquals(1L, floorDivLong(-7L, -5L))
        // Exact multiples never need the correction, in any sign combination.
        assertEquals(1L, floorDivLong(5L, 5L))
        assertEquals(-1L, floorDivLong(-5L, 5L))
        assertEquals(-1L, floorDivLong(5L, -5L))
        assertEquals(1L, floorDivLong(-5L, -5L))
    }

    @Test
    fun floorDivisionMatchesBigIntegerForExtremesAndResistsOverflow() {
        val extremes = listOf(0L, 1L, -1L, 999_999_999L, -999_999_999L, 1_000_000_000L,
            Long.MIN_VALUE, Long.MIN_VALUE + 1, Long.MAX_VALUE)
        val divisors = listOf(1_000_000_000L, 1L, -1L, 7L, -7L, Long.MIN_VALUE, Long.MAX_VALUE)
        for (dividend in extremes) {
            for (divisor in divisors) {
                val expected = BigInteger.valueOf(dividend).divideAndRemainder(BigInteger.valueOf(divisor))
                    .let { (quotient, remainder) ->
                        // BigInteger.divideAndRemainder truncates; correct it the same way the helper
                        // does, so the two implementations are compared, not restated.
                        val roundedUp = remainder.signum() != 0 &&
                            (remainder.signum() < 0) != (divisor < 0L)
                        if (roundedUp) quotient.subtract(BigInteger.ONE) else quotient
                    }.toLong()
                assertEquals("floorDiv($dividend, $divisor)", expected, floorDivLong(dividend, divisor))
            }
        }
        // Long.MIN_VALUE / -1 overflows in every integer implementation; the helper must stay
        // exception-free rather than throw, because it runs on the NTP sender thread.
        assertEquals(
            Long.MIN_VALUE,
            floorDivLong(Long.MIN_VALUE, -1L),
        )
    }

    @Test
    fun remainderFollowsTheSignOfTheDivisor() {
        assertEquals(0L, floorModLong(10L, 5L))
        assertEquals(2L, floorModLong(7L, 5L))
        // The dividend's sign alone must not decide the result: these are the cases Math.floorMod
        // exists for, and the ones a plain `%` gets wrong.
        assertEquals(3L, floorModLong(-7L, 5L))
        assertEquals(-3L, floorModLong(7L, -5L))
        assertEquals(-2L, floorModLong(-7L, -5L))
        assertEquals(4L, floorModLong(-1L, 5L))
    }

    @Test
    fun remainderIsTheFloorDivisionDifferenceAndStaysWithinTheDivisor() {
        val dividends = listOf(0L, 1L, -1L, 1_000_000_000L, -1_000_000_000L, 999_999_999L,
            -999_999_999L, Long.MIN_VALUE + 1, Long.MAX_VALUE)
        val divisors = listOf(1_000_000_000L, -1_000_000_000L, 3L, -3L)
        for (dividend in dividends) {
            for (divisor in divisors) {
                val remainder = floorModLong(dividend, divisor)
                assertEquals(
                    "floorMod is the floorDiv difference for $dividend / $divisor",
                    dividend,
                    floorDivLong(dividend, divisor) * divisor + remainder,
                )
                // |remainder| < |divisor|, without overflow-prone absolute values.
                assert(remainder > -kotlin.math.abs(divisor) && remainder < kotlin.math.abs(divisor)) {
                    "floorMod($dividend, $divisor) = $remainder escaped the divisor range"
                }
                assert(remainder == 0L || (remainder < 0L) == (divisor < 0L)) {
                    "floorMod($dividend, $divisor) = $remainder does not follow the divisor sign"
                }
            }
        }
    }

    @Test
    fun zeroDivisorIsRejectedInsteadOfDividingByZeroSilently() {
        assertThrows(IllegalArgumentException::class.java) { floorDivLong(1L, 0L) }
        assertThrows(IllegalArgumentException::class.java) { floorModLong(1L, 0L) }
    }

    @Test
    fun theTimingStampBuildsFutureStampsForNegativeNanos() {
        // `ntpFromNanos` is private to NtpClock.kt, so the property it relies on is asserted through
        // the helpers: a negative nanosecond count must not borrow into the next second.
        assertEquals(-1L, floorDivLong(-1L, 1_000_000_000L))
        assertEquals(999_999_999L, floorModLong(-1L, 1_000_000_000L))
        assertEquals(-2L, floorDivLong(-1_000_000_001L, 1_000_000_000L))
        assertEquals(999_999_999L, floorModLong(-1_000_000_001L, 1_000_000_000L))
    }
}
