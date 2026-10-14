package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.AirPlayInfoPlist.DOCK_EDGE_BOTTOM
import com.shilapi.xcertplay.airplay.AirPlayInfoPlist.DOCK_EDGE_DRIVER_SIDE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CarPlayViewAreasTest {
    @Test
    fun automaticDockNeedsNoAreas() {
        assertNull(CarPlayViewAreas.build(2560, 1440, CarPlayDock.AUTOMATIC))
    }

    @Test
    fun fixedDockDeclaresTheScreenOncePerEdgeAndStartsOnTheChosenOne() {
        val driver = CarPlayViewAreas.build(2560, 1440, CarPlayDock.DRIVER_SIDE)!!
        assertEquals(listOf(DOCK_EDGE_DRIVER_SIDE, DOCK_EDGE_BOTTOM), driver.areas.map { it.dockEdge })
        assertEquals(0, driver.current)
        assertEquals(2560, driver.areas[0].width)
        assertEquals(1440, driver.areas[0].height)
        val bottom = CarPlayViewAreas.build(2560, 1440, CarPlayDock.BOTTOM)!!
        assertEquals(1, bottom.current)
        assertEquals(0, bottom.withDock(DOCK_EDGE_DRIVER_SIDE))
    }

    @Test
    fun theDockMovesBetweenTheEdgesAreas() {
        val areas = CarPlayViewAreas.build(2560, 1440, CarPlayDock.DRIVER_SIDE)!!
        val moved = areas.withDock(DOCK_EDGE_BOTTOM)!!
        assertEquals(DOCK_EDGE_BOTTOM, areas.areas[moved].dockEdge)
        areas.use(moved)
        assertEquals(1, areas.current)
    }
}
