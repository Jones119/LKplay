package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.glance.CarPlayGlance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What one guidance frame sends to the instrument cluster (docs/_digest/46 §2/§3, G2–G5).
 *
 * Four rules decide what the driver sees, and each of them was wrong or missing on the car:
 *  - the arrow is a vendor `ARROW_ICON_NAVI_*` integer id, never a bitmap (the vendor's Bitmap setter is
 *    an empty method) and never a straight-ahead guess for a maneuver Apple sent but we cannot map;
 *  - the ETA is the *remaining* time in seconds, because the vendor renders it through `secondToTime`
 *    (the absolute arrival epoch printed nonsense / `<1min`);
 *  - a card is only submitted when every slot has a real value, so the cluster never paints its own
 *    `0 m` / `<1min` placeholder for a slot we left empty;
 *  - the road name walks the reference's `afterRoad → road → currentRoad` chain and is cut to 120 UTF-8
 *    bytes without splitting a character.
 *
 * The submission sequence is pinned down separately, against both a recording fake and a fake with the
 * vendor's method shapes: `notifyTurnByTurnStarted()` must never be reached (it submits `turnState=0`,
 * which the service answers with `RuntimeException("Navigation state is not correct.")`).
 */
class ClusterGuidanceCardTest {
    private fun snapshot(
        type: Int? = 1,
        drivingSide: Int = 0,
        routeState: Int? = 1,
        distanceMeters: Int = 350,
        road: String = "Main Street",
        afterRoad: String = "",
        currentRoad: String = "",
        remainingMeters: Long? = 41_900L,
        remainingSeconds: Long? = 600L,
        arrivalEpochSeconds: Long? = 1_791_467_456L,
        proceedToRoute: Boolean = false,
    ) = CarPlayGlance.Snapshot(
        connected = true,
        maneuverType = type,
        drivingSide = drivingSide,
        distanceMeters = distanceMeters,
        road = road,
        afterRoad = afterRoad,
        currentRoad = currentRoad,
        routeState = routeState,
        arrivalEpochSeconds = arrivalEpochSeconds,
        remainingSeconds = remainingSeconds,
        remainingMeters = remainingMeters,
        proceedToRoute = proceedToRoute,
    )

    @Test
    fun everyMappedManeuverTypeBecomesItsVendorArrowId() {
        // The whole table, one row per line of docs/_digest/46 §3, so a missing or swapped row fails.
        val expected = listOf(
            1 to 7, 20 to 7, 47 to 7, 49 to 7,
            2 to 6, 21 to 6, 48 to 6, 50 to 6,
            3 to 1, 5 to 1,
            6 to 15, 19 to 15,
            7 to 29, 28 to 29, 30 to 29, 40 to 29, 46 to 29,
            10 to 17, 12 to 17, 24 to 17, 25 to 17, 27 to 17,
            13 to 11, 52 to 11,
            14 to 10, 53 to 10,
            15 to 18, 16 to 18, 17 to 18,
            22 to 19,
            23 to 12,
        )
        for ((type, arrow) in expected) {
            assertEquals("Apple maneuver type $type", arrow, clusterArrowId(type, drivingSide = 0, proceedToRoute = false))
        }
    }

    @Test
    fun theUTurnTypesFollowTheDrivingSide() {
        for (type in listOf(4, 18, 26)) {
            assertEquals("type $type in left-hand traffic", 2, clusterArrowId(type, drivingSide = 0, proceedToRoute = false))
            assertEquals("type $type in right-hand traffic", 3, clusterArrowId(type, drivingSide = 1, proceedToRoute = false))
            // An unknown driving side leaves the direction ambiguous; submitting one of the two would be
            // a guess about which way the driver must turn.
            assertNull("type $type with an unknown driving side", clusterArrowId(type, drivingSide = 2, proceedToRoute = false))
            assertNull("type $type with an unknown driving side", clusterGuidanceCard(snapshot(type = type, drivingSide = 2)))
        }
    }

    @Test
    fun anUnknownManeuverTypeClearsTheCardInsteadOfDrawingStraightAhead() {
        // 8, 9, 11 and everything above 53 are real Apple types with no vendor icon; the card is cleared
        // rather than filled with a straight arrow that tells the driver to carry on.
        for (type in listOf(8, 9, 11, 51, 54, 100)) {
            assertNull("type $type", clusterArrowId(type, drivingSide = 0, proceedToRoute = false))
            assertNull("type $type", clusterGuidanceCard(snapshot(type = type)))
        }
        assertNull(clusterArrowId(null, drivingSide = 0, proceedToRoute = false))
        assertNull(clusterGuidanceCard(snapshot(type = null)))
    }

    @Test
    fun proceedingToTheRouteHidesTheArrowAndNamesTheRouteStart() {
        // The vendor reads -1 as "this icon slot is invisible"; 0 would be a *visible* slot with icon 0.
        assertEquals(-1, clusterArrowId(11, drivingSide = 0, proceedToRoute = true))

        val card = clusterGuidanceCard(
            snapshot(type = 11, routeState = 6, road = "", afterRoad = "", currentRoad = "", proceedToRoute = true),
        )
        assertNotNull(card)
        assertEquals(-1, card!!.arrowId)
        assertEquals("前往路线", card.road)
    }

    @Test
    fun theEtaIsTheRemainingTimeInSecondsAndNotTheArrivalEpoch() {
        val card = clusterGuidanceCard(snapshot(remainingSeconds = 600L, arrivalEpochSeconds = 1_791_467_456L))
        assertNotNull(card)
        assertEquals(600, card!!.remainingSeconds)
        // `secondToTime` adds 30 s and divides by 60, so 89 s and below all read "<1min" on the cluster:
        // the value has to arrive unchanged, not pre-rounded and above all not replaced by a timestamp.
        assertEquals(89, clusterGuidanceCard(snapshot(remainingSeconds = 89L))!!.remainingSeconds)
        assertEquals(0, clusterGuidanceCard(snapshot(remainingSeconds = 0L))!!.remainingSeconds)
    }

    @Test
    fun aRemainingTimeOutsideTheVendorsRangeRefusesTheCard() {
        assertEquals(2_147_483_617, clusterGuidanceCard(snapshot(remainingSeconds = 2_147_483_617L))!!.remainingSeconds)
        assertNull(clusterGuidanceCard(snapshot(remainingSeconds = 2_147_483_618L)))
        assertNull(clusterGuidanceCard(snapshot(remainingSeconds = -1L)))
        assertNull(clusterGuidanceCard(snapshot(remainingSeconds = null)))
        // An arrival epoch is about 1.79e9, far above the bound -- which is why the old payload could
        // never render as a countdown, and why the epoch is kept for the widget only.
        assertNull(clusterGuidanceCard(snapshot(remainingSeconds = null, arrivalEpochSeconds = 1_791_467_456L)))
    }

    @Test
    fun anIncompleteSnapshotClearsTheCardAndACompleteOneIsSubmitted() {
        assertNotNull("a complete frame must still submit", clusterGuidanceCard(snapshot()))

        assertNull("no maneuver", clusterGuidanceCard(snapshot(type = null)))
        for (state in listOf(null, 0, 2, 3, 4, 7)) {
            assertNull("route state $state is not displayable", clusterGuidanceCard(snapshot(routeState = state)))
        }
        for (state in listOf(1, 5, 6)) {
            assertNotNull("route state $state is displayable", clusterGuidanceCard(snapshot(routeState = state)))
        }

        assertNull("a negative distance", clusterGuidanceCard(snapshot(distanceMeters = -1)))
        assertNull("an unknown destination distance", clusterGuidanceCard(snapshot(remainingMeters = null)))
        assertNull("a negative destination distance", clusterGuidanceCard(snapshot(remainingMeters = -1L)))
        assertNull("an overflowing destination distance", clusterGuidanceCard(snapshot(remainingMeters = 1L shl 31)))
        assertEquals(
            Int.MAX_VALUE,
            clusterGuidanceCard(snapshot(remainingMeters = Int.MAX_VALUE.toLong()))!!.distanceToDestinationMeters,
        )
    }

    @Test
    fun theDistancesKeepTheVendorsUnitsOfMetres() {
        val card = clusterGuidanceCard(snapshot(distanceMeters = 744, remainingMeters = 41_900L))!!
        // `revertDistance("744")` renders 744m and `revertDistance("41900")` renders 41.9km: the numbers
        // are metres as sent, and must not be pre-converted to kilometres or rounded here.
        assertEquals(744, card.distanceToNextGuidancePointMeters)
        assertEquals(41_900, card.distanceToDestinationMeters)
    }

    @Test
    fun theRoadFallsBackFromAfterRoadToRoadToCurrentRoad() {
        fun roadOf(snapshot: CarPlayGlance.Snapshot) = clusterGuidanceCard(snapshot)!!.road

        assertEquals(
            "Turn Street",
            roadOf(snapshot(afterRoad = "Turn Street", road = "Main Street", currentRoad = "Current Street")),
        )
        assertEquals("Main Street", roadOf(snapshot(road = "Main Street", currentRoad = "Current Street")))
        assertEquals("Current Street", roadOf(snapshot(road = "", currentRoad = "Current Street")))
        assertEquals("", roadOf(snapshot(road = "", currentRoad = "")))
        // A step that cleans away to nothing falls through like an empty one: control characters are
        // stripped first, so garbage in the "after the turn" slot does not hide a usable road.
        assertEquals(
            "Current Street",
            roadOf(snapshot(afterRoad = "\u0001\u0007", road = "\u0001", currentRoad = "Current Street")),
        )
    }

    @Test
    fun aRoadNameIsCutTo120Utf8BytesWithoutSplittingACharacter() {
        // 60 three-byte characters are 180 bytes; the first 40 fit exactly in the budget.
        val sixtyCjk = "弯".repeat(60)
        assertEquals(120, cleanClusterRoad(sixtyCjk).toByteArray(Charsets.UTF_8).size)
        assertEquals(40, cleanClusterRoad(sixtyCjk).length)

        assertEquals(120, cleanClusterRoad("a".repeat(121)).length)

        // 39 CJK (117 bytes) + "ab" (2) = 119; the next character needs 3 more, so it is dropped whole
        // rather than split into an invalid byte sequence.
        val straddling = "弯".repeat(39) + "ab" + "弯"
        val cleanedStraddling = cleanClusterRoad(straddling)
        assertEquals(straddling.dropLast(1), cleanedStraddling)
        assertEquals(119, cleanedStraddling.toByteArray(Charsets.UTF_8).size)

        assertEquals("Main Street", cleanClusterRoad("  \u0000Main Street\u0007  "))
    }

    @Test
    fun aCardIsSubmittedAsTheFiveLegalSettersAndNeverStartsGuidance() {
        val sink = RecordingSink()
        submitClusterCard(sink, clusterGuidanceCard(snapshot())!!)

        assertEquals(
            listOf(
                "updateTurnByTurnArrow(7)",
                "updateNextGuidancePointName(Main Street)",
                "updateDistanceToNextGuidancePoint(350)",
                "updateDistanceToDestination(41900)",
                "updateETA(600)",
            ),
            sink.calls,
        )
        // `notifyTurnByTurnStarted()` submits turnState=0, which the service rejects; the card appears
        // with the first turnState=1 setter above, so there is nothing to start.
        assertTrue(sink.calls.none { it.contains("notifyTurnByTurnStarted") })
        assertFalse(sink.calls.any { it.startsWith("notify") })
    }

    @Test
    fun clearingSendsOnlyTheStopCall() {
        val sink = RecordingSink()
        clearClusterCard(sink)
        assertEquals(listOf("notifyTurnByTurnStopped"), sink.calls)
    }

    /**
     * Drives the real reflective dispatch, against a fake shaped like the vendor's `INaviInteraction`.
     *
     * This is the end of the chain the unit tests can reach: the bridge's own sink, the overload matcher
     * and the primitive setters, with a `notifyTurnByTurnStarted()` on the target that would record its
     * own illegal use if anything ever called it.
     */
    @Test
    fun theReflectiveSinkReachesTheVendorSettersAndNeverTheIllegalStart() {
        val target = VendorLikeNavigation()
        submitClusterCard(VendorClusterBridge.guidanceSink(target), clusterGuidanceCard(snapshot())!!)

        assertEquals(
            listOf("arrow=7", "road=Main Street", "next=350", "destination=41900", "eta=600"),
            target.calls,
        )
        clearClusterCard(VendorClusterBridge.guidanceSink(target))
        assertEquals("stopped", target.calls.last())
        assertTrue("notifyTurnByTurnStarted must never be called", target.calls.none { it == "started" })
    }

    /** The five setters plus the clear, recording the call order. */
    private class RecordingSink : ClusterGuidanceSink {
        val calls = mutableListOf<String>()

        override fun updateTurnByTurnArrow(arrowId: Int) {
            calls += "updateTurnByTurnArrow($arrowId)"
        }

        override fun updateNextGuidancePointName(road: String) {
            calls += "updateNextGuidancePointName($road)"
        }

        override fun updateDistanceToNextGuidancePoint(meters: Int) {
            calls += "updateDistanceToNextGuidancePoint($meters)"
        }

        override fun updateDistanceToDestination(meters: Int) {
            calls += "updateDistanceToDestination($meters)"
        }

        override fun updateETA(seconds: Int) {
            calls += "updateETA($seconds)"
        }

        override fun notifyTurnByTurnStopped() {
            calls += "notifyTurnByTurnStopped"
        }
    }

    /** The vendor's method shapes, including the illegal start that must stay untouched. */
    private class VendorLikeNavigation {
        val calls = mutableListOf<String>()

        fun updateTurnByTurnArrow(arrowId: Int) {
            calls += "arrow=$arrowId"
        }

        fun updateNextGuidancePointName(road: String) {
            calls += "road=$road"
        }

        fun updateDistanceToNextGuidancePoint(meters: Int) {
            calls += "next=$meters"
        }

        fun updateDistanceToDestination(meters: Int) {
            calls += "destination=$meters"
        }

        fun updateETA(seconds: Int) {
            calls += "eta=$seconds"
        }

        fun notifyTurnByTurnStopped() {
            calls += "stopped"
        }

        fun notifyTurnByTurnStarted() {
            calls += "started"
        }
    }
}
