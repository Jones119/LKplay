package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.glance.CarPlayGlance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What one guidance frame sends to the instrument cluster (docs/_digest/46 §2/§3/§10, G2–G5).
 *
 * Four rules decide what the driver sees, and each of them was wrong or missing on the car:
 *  - the arrow is a vendor integer id, never a bitmap (the vendor's Bitmap setter is an empty method)
 *    and never a straight-ahead guess for a maneuver Apple sent but we cannot map;
 *  - the id comes from the **factory navigation's** 101-143 family, not the `ARROW_ICON_NAVI_*` 1-45
 *    family the reference implementation used: the cluster drew the latter yellow and offset to the
 *    left, while the factory's is white and centred (docs/_digest/46 §10);
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
 *
 * Everything here is plain JVM: the mapping, the card gate, the override parser and the change gate are
 * pure functions over their arguments, so no Robolectric test is needed for any of them.
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
    fun everyMappedManeuverTypeBecomesItsFactoryArrowId() {
        // The whole table, one row per semantic group of docs/_digest/46 §10, so a missing or swapped
        // row fails. Apple's ids are the `RouteGuidanceManeuverType` values; the numbers on the right
        // are the factory navigation's own `NaviDashboardManager` icons.
        val expected = listOf(
            // turn_left, including the left turn "at the end" and the sharp left (no sharper glyph).
            1 to 102, 20 to 102, 47 to 102,
            // turn_right, likewise.
            2 to 103, 21 to 103, 48 to 103,
            // straight ahead and follow-road share the straight-on glyph.
            3 to 109, 5 to 109,
            // entering the ring; leaving it (with or without an exit number).
            6 to 111,
            7 to 112, 28 to 112, 30 to 112, 40 to 112, 46 to 112,
            // arrival at the destination, from whichever side.
            10 to 115, 12 to 115, 24 to 115, 25 to 115, 27 to 115,
            // keep left / slight left / highway off-ramp left / change highway left.
            13 to 104, 22 to 104, 49 to 104, 52 to 104,
            // keep right / slight right / highway off-ramp right / change highway right.
            14 to 105, 23 to 105, 50 to 105, 53 to 105,
            // No ferry glyph exists in this family; a crossing continues straight on (see the mapping's
            // KDoc for why the card is not refused instead).
            15 to 109, 16 to 109, 17 to 109,
        )
        for ((type, arrow) in expected) {
            assertEquals("Apple maneuver type $type", arrow, clusterArrowId(type, drivingSide = 0, proceedToRoute = false))
        }
    }

    @Test
    fun theUTurnTypesFollowTheDrivingSide() {
        for (type in listOf(4, 18, 26)) {
            assertEquals("type $type in left-hand traffic", 106, clusterArrowId(type, drivingSide = 0, proceedToRoute = false))
            assertEquals("type $type in right-hand traffic", 107, clusterArrowId(type, drivingSide = 1, proceedToRoute = false))
            // An unknown driving side leaves the direction ambiguous, and the family has a u-turn glyph
            // that asks for no direction at all: 108 is submitted rather than clearing the card, so the
            // driver keeps the road, distance and ETA and is never sent the wrong way round.
            assertEquals("type $type with an unknown driving side", 108, clusterArrowId(type, drivingSide = 2, proceedToRoute = false))
            assertEquals(
                "type $type with an unknown driving side",
                108,
                clusterGuidanceCard(snapshot(type = type, drivingSide = 2))!!.arrowId,
            )
        }
    }

    @Test
    fun theRoundaboutUTurnFollowsTheDrivingSide() {
        // Apple type 19 is "u-turn at the roundabout", which is the ring taken back the way the driver
        // came: the factory's turn_ring_leftback / turn_ring_rightback, with turn_ring_back as the
        // direction-free fallback.
        assertEquals(141, clusterArrowId(19, drivingSide = 0, proceedToRoute = false))
        assertEquals(142, clusterArrowId(19, drivingSide = 1, proceedToRoute = false))
        assertEquals(143, clusterArrowId(19, drivingSide = 2, proceedToRoute = false))
    }

    @Test
    fun anUnknownManeuverTypeClearsTheCardInsteadOfDrawingStraightAhead() {
        // 8, 9, 11, 51 and everything above 53 are real Apple types with no factory icon; the card is
        // cleared rather than filled with a straight arrow that tells the driver to carry on.
        for (type in listOf(8, 9, 11, 51, 54, 100)) {
            assertNull("type $type", clusterArrowId(type, drivingSide = 0, proceedToRoute = false))
            assertNull("type $type", clusterGuidanceCard(snapshot(type = type)))
        }
        assertNull(clusterArrowId(null, drivingSide = 0, proceedToRoute = false))
        assertNull(clusterGuidanceCard(snapshot(type = null)))
    }

    @Test
    fun theIdsWithNoAppleManeuverAreUnreachableAndOnlyTheOverrideReachesThem() {
        // turn_via_* (110, a waypoint), turn_tollgate (114) and turn_along (120) are real factory ids,
        // but Apple's maneuver set has no source for them: sweeping every type Apple can send must never
        // produce one of them, which is what makes the override the only way to try them on the car.
        val produced = buildSet {
            for (type in 0..255) {
                for (side in 0..2) {
                    clusterArrowId(type, drivingSide = side, proceedToRoute = false)?.let { add(it) }
                }
            }
        }
        for (unreachable in listOf(110, 114, 120)) {
            assertFalse("factory id $unreachable must not come from the mapping", unreachable in produced)
        }
        assertEquals(
            "the mapping's whole output, so a new row is a deliberate change",
            setOf(102, 103, 104, 105, 106, 107, 108, 109, 111, 112, 115, 141, 142, 143),
            produced,
        )
        // The override is exactly the door for those three (and for -1, which hides the icon slot).
        for (forced in listOf(110, 114, 120, -1)) {
            assertEquals(forced, clusterGuidanceCard(snapshot(type = 8), forcedArrowId = forced)!!.arrowId)
        }
    }

    @Test
    fun proceedingToTheRouteStartsTheRouteAndNamesTheRouteStart() {
        // The factory sends turn_start (101) when a route starts, so the cluster shows the same start
        // icon as with the car's own navigation. It is no longer the hidden slot -1.
        assertEquals(101, clusterArrowId(11, drivingSide = 0, proceedToRoute = true))
        assertEquals(101, clusterArrowId(null, drivingSide = 0, proceedToRoute = true))

        val card = clusterGuidanceCard(
            snapshot(type = 11, routeState = 6, road = "", afterRoad = "", currentRoad = "", proceedToRoute = true),
        )
        assertNotNull(card)
        assertEquals(101, card!!.arrowId)
        assertEquals("前往路线", card.road)
    }

    @Test
    fun theBenchOverrideForcesTheArrowIdAndItsAbsenceLeavesTheMappingInCharge() {
        // `adb shell setprop debug.diplay.arrow 114` on the car: the id wins over the mapping, including
        // over a mapping that has no answer, which is how a sweep reaches ids no Apple maneuver carries.
        assertEquals(114, parseArrowOverride("114"))
        assertEquals(-1, parseArrowOverride("-1"))
        // Whitespace is tolerated, because setprop hands over whatever the shell passed.
        assertEquals(102, parseArrowOverride(" 102 "))
        // Unset, empty and unparseable all mean "use the mapping": a typo must not blank the arrow.
        for (value in listOf(null, "", "   ", "on", "10 2", "0x66", "1.5")) {
            assertNull("property value '$value'", parseArrowOverride(value))
        }

        // Right turn (Apple type 2 -> 103) with and without the override.
        assertEquals(103, clusterGuidanceCard(snapshot(type = 2))!!.arrowId)
        assertEquals(102, clusterGuidanceCard(snapshot(type = 2), forcedArrowId = parseArrowOverride("102"))!!.arrowId)
        assertEquals(103, clusterGuidanceCard(snapshot(type = 2), forcedArrowId = parseArrowOverride("on"))!!.arrowId)
        assertEquals(103, clusterGuidanceCard(snapshot(type = 2), forcedArrowId = parseArrowOverride(null))!!.arrowId)

        // The override replaces the arrow slot only: the rest of the completeness gate still applies.
        assertNull(clusterGuidanceCard(snapshot(type = 2, routeState = 3), forcedArrowId = 102))
        assertNull(clusterGuidanceCard(snapshot(type = 2, remainingMeters = null), forcedArrowId = 102))
        assertNull(clusterGuidanceCard(snapshot(type = 2, distanceMeters = -1), forcedArrowId = 102))
    }

    @Test
    fun theArrowIsAnnouncedOncePerChangeAndNotOncePerFrame() {
        val gate = ClusterArrowChangeGate()
        assertTrue("the first card must be announced", gate.shouldLog(103, fromOverride = false))
        // The push runs per guidance frame; a repeated id must not produce a repeated log line.
        assertFalse("an unchanged arrow must not be announced again", gate.shouldLog(103, fromOverride = false))
        assertTrue("a changed arrow is announced", gate.shouldLog(102, fromOverride = false))
        assertTrue("the same id from the override is a change of source, so it is announced", gate.shouldLog(102, fromOverride = true))
        assertFalse(gate.shouldLog(102, fromOverride = true))
        assertTrue("back to the mapping", gate.shouldLog(102, fromOverride = false))
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
                "updateTurnByTurnArrow(102)",
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
            listOf("arrow=102", "road=Main Street", "next=350", "destination=41900", "eta=600"),
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
