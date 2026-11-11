package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The home page's "last connection problem" line (`doc 127` LastConnectionProblem). It is persisted
 * because a failed wired bring-up usually restarts the process before the user sees the reason.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class LastConnectionProblemTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Test
    fun reasonAndTimeSurviveUntilCleared() {
        AirPlayPersistence.clearLastConnectionProblem(app)
        AirPlayPersistence.saveLastConnectionProblem(app, "AirPlay iAP tunnel closed")
        val saved = AirPlayPersistence.loadLastConnectionProblem(app)
        assertEquals("AirPlay iAP tunnel closed", saved?.first)
        assertTrue((saved?.second ?: 0L) > 0L)
    }

    @Test
    fun clearRemovesTheRecord() {
        AirPlayPersistence.saveLastConnectionProblem(app, "wired link lost")
        AirPlayPersistence.clearLastConnectionProblem(app)
        assertNull(AirPlayPersistence.loadLastConnectionProblem(app))
    }

    @Test
    fun aBlankReasonIsNotStored() {
        AirPlayPersistence.clearLastConnectionProblem(app)
        AirPlayPersistence.saveLastConnectionProblem(app, "   ")
        assertNull(AirPlayPersistence.loadLastConnectionProblem(app))
    }

    @Test
    fun aVeryLongReasonIsTruncated() {
        AirPlayPersistence.saveLastConnectionProblem(app, "x".repeat(500))
        assertTrue(AirPlayPersistence.loadLastConnectionProblem(app)!!.first.length <= 120)
    }
}
