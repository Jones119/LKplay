package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.AirPlayInfoPlist
import com.shilapi.xcertplay.airplay.AirPlayViewArea

/**
 * The view areas the main screen declares for one session, and the one CarPlay uses now. A fixed dock
 * declares the whole screen once per dock edge; the car moves CarPlay between them with updateViewArea,
 * without reconnecting. Null (no object at all) when the dock is automatic and one whole-screen area
 * says everything.
 */
class CarPlayViewAreas private constructor(
    val areas: List<AirPlayViewArea>,
    initial: Int,
) {
    /** The area CarPlay uses now; shared by the session's host and its settings. */
    @Volatile var current: Int = initial
        private set

    /** The area in use after moving the dock to [dockEdge], or null without one on this screen. */
    fun withDock(dockEdge: Int): Int? =
        areas.indices.firstOrNull { areas[it].dockEdge == dockEdge }

    fun use(index: Int) {
        current = index.coerceIn(0, areas.lastIndex)
    }

    companion object {
        /**
         * The areas for a [width] x [height] stream under [dock], or null when one whole-screen area
         * (an automatic dock) will do.
         */
        fun build(width: Int, height: Int, dock: CarPlayDock): CarPlayViewAreas? {
            val edges = dock.edge?.let { listOf(AirPlayInfoPlist.DOCK_EDGE_DRIVER_SIDE, AirPlayInfoPlist.DOCK_EDGE_BOTTOM) }
                ?: return null
            val areas = edges.map { AirPlayViewArea(width, height, dockEdge = it) }
            val initial = edges.indexOf(dock.edge).coerceAtLeast(0)
            return CarPlayViewAreas(areas, initial)
        }
    }
}
