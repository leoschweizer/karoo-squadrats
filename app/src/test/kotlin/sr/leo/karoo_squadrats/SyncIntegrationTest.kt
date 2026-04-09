package sr.leo.karoo_squadrats

import sr.leo.karoo_squadrats.data.MvtParser
import sr.leo.karoo_squadrats.data.TileRepository
import sr.leo.karoo_squadrats.grid.SquadratGrid
import org.junit.Assert.*
import org.junit.Test

/**
 * Simulates the exact data path that occurs during sync on a real device:
 * the HTTP response body is decompressed (if gzipped), so MvtParser receives
 * raw protobuf bytes.
 */
class SyncIntegrationTest {

    private fun loadFixture(): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("tile_z10_549_335.pbf")!!.readBytes()

    @Test
    fun `extractPolygons works with decompressed data`() {
        val rings = MvtParser.extractPolygons(loadFixture(), "squadrats", 10, 549, 335)
        assertEquals("should extract 3 rings", 3, rings.size)
    }

    @Test
    fun `hasFeatures works with decompressed data`() {
        assertTrue(MvtParser.hasFeatures(loadFixture(), "squadrats"))
    }

    // -- Full sync simulation --

    @Test
    fun `full sync pipeline finds collected z14 tiles`() {
        // Step 1: Extract polygons (simulating what sync() does after HTTP fetch)
        val allRings = MvtParser.extractPolygons(loadFixture(), "squadrats", 10, 549, 335)
        assertTrue("should have rings", allRings.isNotEmpty())

        // Step 2: Compute z=14 tiles in a small radius around center of polygon
        // The polygon is roughly around lon 13.0-13.4, lat 52.48-52.70
        val centerLat = 52.55
        val centerLon = 13.2
        val radiusKm = 15.0
        val displayTiles = SquadratGrid.tilesInRadius(centerLat, centerLon, radiusKm)
        assertTrue("should have display tiles", displayTiles.isNotEmpty())

        // Step 3: Point-in-polygon classification (mimicking sync logic)
        data class RingWithBounds(
            val ring: MvtParser.Ring,
            val minLon: Double, val maxLon: Double,
            val minLat: Double, val maxLat: Double,
            val clockwise: Boolean,
        )

        val ringsWithBounds = allRings.map { ring ->
            var minLon = Double.MAX_VALUE; var maxLon = -Double.MAX_VALUE
            var minLat = Double.MAX_VALUE; var maxLat = -Double.MAX_VALUE
            for ((lon, lat) in ring.points) {
                if (lon < minLon) minLon = lon
                if (lon > maxLon) maxLon = lon
                if (lat < minLat) minLat = lat
                if (lat > maxLat) maxLat = lat
            }
            RingWithBounds(ring, minLon, maxLon, minLat, maxLat, TileRepository.isClockwise(ring))
        }

        val collected = mutableSetOf<Long>()
        for (tile in displayTiles) {
            val (lon, lat) = SquadratGrid.tileCenterLonLat(tile)
            var inside = false
            for (rb in ringsWithBounds) {
                if (lon < rb.minLon || lon > rb.maxLon || lat < rb.minLat || lat > rb.maxLat) continue
                if (TileRepository.pointInRing(lon, lat, rb.ring.points)) {
                    inside = rb.clockwise
                }
            }
            if (inside) {
                collected.add(tile.toKey())
            }
        }

        assertTrue("should find some collected tiles, got ${collected.size}",
            collected.isNotEmpty()
        )
        assertTrue("should not mark all tiles as collected",
            collected.size < displayTiles.size)

        // Sanity check: the collected region should be roughly in the right area
        val collectedTiles = collected.map { SquadratGrid.TileCoord.fromKey(it) }
        for (tile in collectedTiles) {
            val bounds = SquadratGrid.tileBounds(tile)
            assertTrue("collected tile lon should be in polygon range",
                bounds.lonMax > 12.9 && bounds.lonMin < 13.5)
            assertTrue("collected tile lat should be in polygon range",
                bounds.latMax > 52.4 && bounds.latMin < 52.8)
        }
    }
}
