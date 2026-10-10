package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure geometry of the screen corner mask (docs/_digest/42 §C12).
 *
 * No Robolectric and no View here on purpose: the shape of the four corner squares decides whether
 * the mask can eat the picture, and that has to be checkable for any panel the car reports without
 * a device in the loop.
 */
class CarPlayCornerMaskGeometryTest {
    @Test
    fun theRadiusIsTheReferencesMinSideOverFifteen() {
        // EasyPlay v71's empirical value on the 1920x1080 panel: 1080 / 15 = 72.
        assertEquals(72f, CarPlayCornerGeometry.radiusFor(1920, 1080), 0.0001f)
        assertEquals(72f, CarPlayCornerGeometry.radius(1920, 1080), 0.0001f)
        // Portrait and multi-window sizes follow the shorter side.
        assertEquals(128f, CarPlayCornerGeometry.radiusFor(1920, 1920), 0.0001f)
        assertEquals(48f, CarPlayCornerGeometry.radiusFor(1920, 720), 0.0001f)
    }

    @Test
    fun theEdgeIsTheRoundedUpRadiusSoTheArcIsNeverCropped() {
        assertEquals(72, CarPlayCornerGeometry.maskEdge(1920, 1080))
        // 1000 / 15 = 66.67, and the reference measures the square with ceil() so the arc fits.
        assertEquals(67, CarPlayCornerGeometry.maskEdge(1000, 1000))
        assertEquals(1, CarPlayCornerGeometry.maskEdge(15, 15))
        // Odd sizes: 1921 / 15 = 128.07 -> 129, and 1081 / 15 = 72.07 -> 73 (the shorter side wins).
        assertEquals(73, CarPlayCornerGeometry.maskEdge(1921, 1081))
    }

    @Test
    fun theFourSquaresArePinnedToTheFourCorners() {
        val edge = CarPlayCornerGeometry.maskEdge(1920, 1080)
        assertEquals(
            listOf(
                CornerRect(0, 0, 72, 72),
                CornerRect(1848, 0, 1920, 72),
                CornerRect(1848, 1008, 1920, 1080),
                CornerRect(0, 1008, 72, 1080),
            ),
            CarPlayCornerGeometry.cornerRects(1920, 1080, edge),
        )
    }

    @Test
    fun theMaskCanNeverSwallowThePictureEvenWithAnAbsurdRadius() {
        // A small panel: the radius is its min side over fifteen, so the squares stay tiny.
        assertEquals(2, CarPlayCornerGeometry.maskEdge(40, 30))
        // The guard is the edge clamp, not the radius, so it holds even when the square asked for is
        // larger than the screen: half the shorter side at most, so the centre always stays visible.
        val oversized = CarPlayCornerGeometry.cornerRects(1920, 1080, 5000)
        assertEquals(540, oversized[0].width)
        assertEquals(CornerRect(0, 0, 540, 540), oversized[0])
        assertEquals(CornerRect(1380, 540, 1920, 1080), oversized[2])
        // The four squares meet in the middle but never overlap.
        assertTrue(oversized[0].right <= oversized[1].left)
        assertTrue(oversized[0].bottom <= oversized[3].top)
    }

    @Test
    fun aDegeneratePanelDrawsNothingRatherThanFourOverlappingSquares() {
        assertEquals(0, CarPlayCornerGeometry.maskEdge(0, 1080))
        assertEquals(0, CarPlayCornerGeometry.maskEdge(1920, 0))
        assertEquals(0f, CarPlayCornerGeometry.radiusFor(0, 1080), 0.0001f)
        assertEquals(0f, CarPlayCornerGeometry.radiusFor(1920, -5), 0.0001f)
        val empty = List(4) { CornerRect(0, 0, 0, 0) }
        assertEquals(empty, CarPlayCornerGeometry.cornerRects(0, 0, 0))
        assertEquals(empty, CarPlayCornerGeometry.cornerRects(1920, 1080, 0))
    }
}
