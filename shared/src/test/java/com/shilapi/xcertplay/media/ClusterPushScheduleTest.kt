package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The while-playing re-push and its retry (docs/_digest/45 G7).
 *
 * The push used to happen only when the iPhone sent an update or a cover decoded, so a vendor service
 * that restarted -- or a cluster screen switched away and back -- stayed empty until the next track.
 * The timing is a pure function of an injected "now", which is what these tests inject; the timer that
 * calls it is three lines in the bridge and is verified on the car.
 */
class ClusterPushScheduleTest {
    @Test
    fun aSuccessfulPushIsNotRepeatedBeforeItsIntervalHasPassed() {
        val schedule = ClusterPushSchedule(intervalMillis = 10_000, retryMillis = 3_000)
        schedule.notePushed(100_000)

        assertFalse(schedule.isDue(109_999, playing = true))
        assertTrue(schedule.isDue(110_000, playing = true))
    }

    @Test
    fun aPausedPlayerIsNeverRePushed() {
        val schedule = ClusterPushSchedule(intervalMillis = 10_000, retryMillis = 3_000)

        assertFalse("nothing is playing, so there is nothing to keep alive", schedule.isDue(10_000_000, playing = false))
        schedule.notePushed(0)
        assertFalse(schedule.isDue(10_000_000, playing = false))
    }

    @Test
    fun aFailedPushIsRetriedSoonerThanTheRegularRefresh() {
        val schedule = ClusterPushSchedule(intervalMillis = 10_000, retryMillis = 3_000)
        schedule.noteFailed(100_000)

        assertFalse(schedule.isDue(102_999, playing = true))
        assertTrue("a dead binder recovers long before the next refresh", schedule.isDue(103_000, playing = true))
    }

    @Test
    fun aPushThatSucceededAfterAFailureGoesBackToTheLongInterval() {
        val schedule = ClusterPushSchedule(intervalMillis = 10_000, retryMillis = 3_000)
        schedule.noteFailed(0)
        schedule.notePushed(3_000)

        assertFalse(schedule.isDue(12_999, playing = true))
        assertTrue(schedule.isDue(13_000, playing = true))
    }

    @Test
    fun theFirstTickAfterAStartPushesWithoutWaitingForAFullInterval() {
        val schedule = ClusterPushSchedule(intervalMillis = 10_000, retryMillis = 3_000)

        // No push has happened yet in this process, so the cluster has nothing of ours on it.
        assertTrue(schedule.isDue(500_000, playing = true))
    }

    @Test
    fun theIntervalAndTheRetryAreInjectedValues() {
        val schedule = ClusterPushSchedule(intervalMillis = 100, retryMillis = 10)
        schedule.notePushed(0)
        assertFalse(schedule.isDue(99, playing = true))
        assertTrue(schedule.isDue(100, playing = true))
        schedule.noteFailed(100)
        assertTrue(schedule.isDue(110, playing = true))
    }

    @Test
    fun theDefaultCadenceIsTheReferencesTenSecondsWithAShorterRetry() {
        // `LegacyEcarxMediaCenter.PRESENTATION_REFRESH_TIMEOUT_MS = 10000` (docs/_digest/45 §2.3).
        assertEquals(10_000L, CLUSTER_REPUSH_INTERVAL_MILLIS)
        assertEquals(3_000L, CLUSTER_REPUSH_RETRY_MILLIS)
        // The tick only decides *when* the schedule is consulted, so it must be the shorter of the two.
        assertTrue(CLUSTER_REPUSH_TICK_MILLIS <= CLUSTER_REPUSH_RETRY_MILLIS)
        assertTrue(CLUSTER_REPUSH_TICK_MILLIS < CLUSTER_REPUSH_INTERVAL_MILLIS)

        val defaults = ClusterPushSchedule()
        defaults.noteFailed(0)
        assertTrue(defaults.isDue(CLUSTER_REPUSH_RETRY_MILLIS, playing = true))
        assertFalse(defaults.isDue(CLUSTER_REPUSH_RETRY_MILLIS - 1, playing = true))
    }
}
