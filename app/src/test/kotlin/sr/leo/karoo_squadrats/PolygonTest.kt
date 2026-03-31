package sr.leo.karoo_squadrats

import sr.leo.karoo_squadrats.data.MvtParser
import sr.leo.karoo_squadrats.data.TileRepository
import sr.leo.karoo_squadrats.grid.SquadratGrid
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests the point-in-polygon and clockwise logic used by TileRepository,
 * using real polygon data extracted from the z=10 fixture tile.
 */
class PolygonTest {

    private fun loadFixture(): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("tile_z10_549_335.pbf")!!.readBytes()

    private val rings by lazy {
        MvtParser.extractPolygons(loadFixture(), "squadrats", 10, 549, 335)
    }

    // -- isClockwise --

    @Test
    fun `exterior ring (ring 0) is clockwise`() {
        assertTrue("Exterior ring should be clockwise", TileRepository.isClockwise(rings[0]))
    }

    @Test
    fun `hole rings are counter-clockwise`() {
        assertFalse("Ring 1 (hole) should be counter-clockwise", TileRepository.isClockwise(rings[1]))
        assertFalse("Ring 2 (hole) should be counter-clockwise", TileRepository.isClockwise(rings[2]))
    }

    // -- pointInRing --

    @Test
    fun `point inside exterior ring returns true`() {
        // 13.3, 52.5 is within the collected area polygon (verified via hole test)
        assertTrue(TileRepository.pointInRing(13.3, 52.5, rings[0].points))
    }

    @Test
    fun `point outside polygon returns false`() {
        // 12.5, 52.5 is west of the polygon
        assertFalse(TileRepository.pointInRing(12.5, 52.5, rings[0].points))
    }

    @Test
    fun `point south of polygon returns false`() {
        // 13.06, 52.4 is south of the exterior ring (lat min ~52.48)
        assertFalse(TileRepository.pointInRing(13.06, 52.4, rings[0].points))
    }

    @Test
    fun `point inside hole ring 1 returns true for that ring`() {
        // Hole 1 bbox: lon=[13.23, 13.25], lat=[52.48, 52.50]
        assertTrue(TileRepository.pointInRing(13.238, 52.49, rings[1].points))
    }

    @Test
    fun `point in hole is inside exterior but inside hole - net uncollected`() {
        // 13.238, 52.49: inside exterior ring 0 (collected), but also inside hole ring 1 (uncollected)
        val inExterior = TileRepository.pointInRing(13.238, 52.49, rings[0].points)
        val inHole = TileRepository.pointInRing(13.238, 52.49, rings[1].points)
        assertTrue("Should be inside exterior", inExterior)
        assertTrue("Should be inside hole", inHole)
        // With the exterior=clockwise, hole=ccw logic, the result should be NOT collected
        assertTrue(TileRepository.isClockwise(rings[0]))
        assertFalse(TileRepository.isClockwise(rings[1]))
    }

    // -- Integration: z=14 tile membership --

    @Test
    fun `z14 tile in collected area is correctly identified`() {
        // Find a z14 tile whose center is near 13.3, 52.5 (known inside exterior)
        val tile = SquadratGrid.lonLatToTile(13.3, 52.5)
        val (lon, lat) = SquadratGrid.tileCenterLonLat(tile)

        // Should be inside exterior, not in any hole
        assertTrue("z14 tile center should be in exterior",
            TileRepository.pointInRing(lon, lat, rings[0].points))
        assertFalse("z14 tile center should not be in hole 1",
            TileRepository.pointInRing(lon, lat, rings[1].points))
        assertFalse("z14 tile center should not be in hole 2",
            TileRepository.pointInRing(lon, lat, rings[2].points))
    }

    @Test
    fun `z14 tile outside area is correctly excluded`() {
        // z14 tile at 12.5, 52.5 (well outside)
        val tile = SquadratGrid.lonLatToTile(12.5, 52.5)
        val (lon, lat) = SquadratGrid.tileCenterLonLat(tile)
        assertFalse(TileRepository.pointInRing(lon, lat, rings[0].points))
    }

    // -- Simple geometry tests --

    @Test
    fun `pointInRing works for simple square`() {
        val square = listOf(
            0.0 to 0.0, 1.0 to 0.0, 1.0 to 1.0, 0.0 to 1.0, 0.0 to 0.0,
        )
        assertTrue(TileRepository.pointInRing(0.5, 0.5, square))
        assertFalse(TileRepository.pointInRing(1.5, 0.5, square))
        assertFalse(TileRepository.pointInRing(0.5, 1.5, square))
    }

    @Test
    fun `isClockwise detects CW square`() {
        val cwRing = MvtParser.Ring(
            listOf(0.0 to 0.0, 1.0 to 0.0, 1.0 to 1.0, 0.0 to 1.0, 0.0 to 0.0),
        )
        val ccwRing = MvtParser.Ring(
            listOf(0.0 to 0.0, 0.0 to 1.0, 1.0 to 1.0, 1.0 to 0.0, 0.0 to 0.0),
        )
        assertNotEquals(
            "CW and CCW rings should have different winding",
            TileRepository.isClockwise(cwRing),
            TileRepository.isClockwise(ccwRing),
        )
    }
}
