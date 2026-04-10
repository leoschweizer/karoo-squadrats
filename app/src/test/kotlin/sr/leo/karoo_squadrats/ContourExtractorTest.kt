package sr.leo.karoo_squadrats

import sr.leo.karoo_squadrats.extension.SquadratsExtension
import sr.leo.karoo_squadrats.grid.SquadratGrid.TileCoord
import sr.leo.karoo_squadrats.map.ContourExtractor
import sr.leo.karoo_squadrats.map.ContourExtractor.GridPoint
import org.junit.Assert.*
import org.junit.Test

class ContourExtractorTest {

    private fun tileKeys(vararg coords: Pair<Int, Int>): Set<Long> =
        coords.map { (x, y) -> TileCoord(x, y).toKey() }.toSet()

    private fun totalEdges(contours: List<ContourExtractor.Contour>): Int =
        contours.sumOf { it.points.size - 1 }

    @Test
    fun `empty input produces no contours`() {
        val result = ContourExtractor.extract(emptySet())
        assertTrue(result.contours.isEmpty())
        assertTrue(result.innerEdges.isEmpty())
    }

    @Test
    fun `single tile produces one closed contour with 4 edges and no inner edges`() {
        val result = ContourExtractor.extract(tileKeys(100 to 100))
        assertEquals(1, result.contours.size)
        assertEquals(result.contours[0].points.first(), result.contours[0].points.last())
        assertEquals(5, result.contours[0].points.size) // 4 edges + closing point
        assertTrue(result.innerEdges.isEmpty())
    }

    @Test
    fun `two horizontal tiles produce one contour and one inner edge`() {
        val result = ContourExtractor.extract(tileKeys(100 to 100, 101 to 100))
        assertEquals(1, result.contours.size)
        assertEquals(result.contours[0].points.first(), result.contours[0].points.last())
        // 2 top + 1 right + 2 bottom + 1 left = 6 boundary edges
        assertEquals(6, totalEdges(result.contours))
        // 1 shared vertical edge between the two tiles
        assertEquals(1, result.innerEdges.size)
    }

    @Test
    fun `two vertical tiles produce one contour and one inner edge`() {
        val result = ContourExtractor.extract(tileKeys(100 to 100, 100 to 101))
        assertEquals(1, result.contours.size)
        assertEquals(result.contours[0].points.first(), result.contours[0].points.last())
        assertEquals(6, totalEdges(result.contours))
        assertEquals(1, result.innerEdges.size)
    }

    @Test
    fun `L-shaped group produces one contour and two inner edges`() {
        // * .
        // * *
        val result = ContourExtractor.extract(tileKeys(0 to 0, 0 to 1, 1 to 1))
        assertEquals(1, result.contours.size)
        assertEquals(result.contours[0].points.first(), result.contours[0].points.last())
        assertEquals(8, totalEdges(result.contours))
        assertEquals(2, result.innerEdges.size)
    }

    @Test
    fun `2x2 block produces one contour with 8 edges and 4 inner edges`() {
        val result = ContourExtractor.extract(
            tileKeys(0 to 0, 1 to 0, 0 to 1, 1 to 1),
        )
        assertEquals(1, result.contours.size)
        assertEquals(result.contours[0].points.first(), result.contours[0].points.last())
        assertEquals(8, totalEdges(result.contours))
        // 2 horizontal shared + 2 vertical shared = 4 inner edges
        assertEquals(4, result.innerEdges.size)
    }

    @Test
    fun `collected hole in 3x3 grid produces two contours and inner edges`() {
        // 8 uncollected tiles in a ring around collected center
        val tiles = mutableSetOf<Long>()
        for (x in 0..2) {
            for (y in 0..2) {
                if (x != 1 || y != 1) {
                    tiles.add(TileCoord(x, y).toKey())
                }
            }
        }

        val result = ContourExtractor.extract(tiles)
        assertEquals(2, result.contours.size)
        // Both contours should be closed
        for (c in result.contours) {
            assertEquals(c.points.first(), c.points.last())
        }
        // Outer boundary: 12 edges, inner hole: 4 edges = 16 total
        assertEquals(16, totalEdges(result.contours))
        // Inner edges: 8 tiles in a ring have shared edges between them
        assertTrue(result.innerEdges.isNotEmpty())
    }

    @Test
    fun `scattered tiles produce separate contours and no inner edges`() {
        val result = ContourExtractor.extract(tileKeys(0 to 0, 10 to 10))
        assertEquals(2, result.contours.size)
        assertEquals(8, totalEdges(result.contours)) // 4 + 4
        assertTrue(result.innerEdges.isEmpty())
    }

    @Test
    fun `boundary edges count for single tile`() {
        val edges = ContourExtractor.extractBoundaryEdges(tileKeys(0 to 0))
        assertEquals(4, edges.size)
    }

    @Test
    fun `boundary edges count drops for adjacent tiles`() {
        // Two adjacent tiles: 8 total edges minus 2 shared = 6 boundary edges
        val edges = ContourExtractor.extractBoundaryEdges(tileKeys(0 to 0, 1 to 0))
        assertEquals(6, edges.size)
    }

    @Test
    fun `shared edge between adjacent tiles is not in boundary`() {
        val edges = ContourExtractor.extractBoundaryEdges(tileKeys(0 to 0, 1 to 0))

        // The shared edge runs between grid points (1,0) and (1,1)
        val hasSharedEdge = edges.any { (a, b) ->
            (a == GridPoint(1, 0) && b == GridPoint(1, 1)) ||
                (a == GridPoint(1, 1) && b == GridPoint(1, 0))
        }
        assertFalse("Shared edge should not appear in boundary edges", hasSharedEdge)
    }

    @Test
    fun `plus shape produces one contour`() {
        //   *
        // * * *
        //   *
        val result = ContourExtractor.extract(
            tileKeys(1 to 0, 0 to 1, 1 to 1, 2 to 1, 1 to 2),
        )
        assertEquals(1, result.contours.size)
        assertEquals(result.contours[0].points.first(), result.contours[0].points.last())
        assertEquals(12, totalEdges(result.contours))
        // 4 shared edges (center shares one edge with each neighbor)
        assertEquals(4, result.innerEdges.size)
    }

    @Test
    fun `contour points are valid lat lon`() {
        val result = ContourExtractor.extract(tileKeys(8192 to 5460))
        assertEquals(1, result.contours.size)
        for ((lat, lon) in result.contours[0].points) {
            assertTrue("lat $lat should be in range", lat in -90.0..90.0)
            assertTrue("lon $lon should be in range", lon in -180.0..180.0)
        }
    }

    @Test
    fun `contour points match tile bounds`() {
        // Verify contour corners align with SquadratGrid.tileBounds
        val tile = TileCoord(8192, 5460)
        val bounds = sr.leo.karoo_squadrats.grid.SquadratGrid.tileBounds(tile)
        val result = ContourExtractor.extract(setOf(tile.toKey()))

        val lats = result.contours[0].points.map { it.first }.distinct().sorted()
        val lons = result.contours[0].points.map { it.second }.distinct().sorted()

        assertEquals(2, lats.size)
        assertEquals(2, lons.size)
        assertEquals(bounds.latMin, lats[0], 1e-10)
        assertEquals(bounds.latMax, lats[1], 1e-10)
        assertEquals(bounds.lonMin, lons[0], 1e-10)
        assertEquals(bounds.lonMax, lons[1], 1e-10)
    }

    @Test
    fun `inner edges have exactly 2 points each`() {
        val result = ContourExtractor.extract(tileKeys(0 to 0, 1 to 0, 0 to 1, 1 to 1))
        for (edge in result.innerEdges) {
            assertEquals(2, edge.points.size)
        }
    }

    @Test
    fun `inner edge points are valid lat lon`() {
        val result = ContourExtractor.extract(tileKeys(8192 to 5460, 8193 to 5460))
        assertEquals(1, result.innerEdges.size)
        for ((lat, lon) in result.innerEdges[0].points) {
            assertTrue("lat $lat should be in range", lat in -90.0..90.0)
            assertTrue("lon $lon should be in range", lon in -180.0..180.0)
        }
    }

    @Test
    fun `adaptive line width increases with zoom`() {
        assertTrue(SquadratsExtension.lineWidth(11.0) < SquadratsExtension.lineWidth(14.0))
        assertTrue(SquadratsExtension.lineWidth(14.0) < SquadratsExtension.lineWidth(16.0))
    }

    @Test
    fun `adaptive line width values at specific zoom levels`() {
        assertEquals(2, SquadratsExtension.lineWidth(11.0))
        assertEquals(3, SquadratsExtension.lineWidth(13.0))
        assertEquals(4, SquadratsExtension.lineWidth(14.5))
        assertEquals(5, SquadratsExtension.lineWidth(16.0))
    }
}
