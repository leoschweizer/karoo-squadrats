package sr.leo.karoo_squadrats

import sr.leo.karoo_squadrats.data.MvtParser
import sr.leo.karoo_squadrats.data.TileRepository
import sr.leo.karoo_squadrats.grid.SquadratGrid
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream

/**
 * Simulates the exact data path that occurs during sync on a real device:
 * OkHttp transparently decompresses the gzip response, so MvtParser receives
 * raw protobuf bytes (not gzipped).
 */
class SyncIntegrationTest {

    private fun loadGzippedFixture(): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("tile_z10_549_335.pbf")!!.readBytes()

    /** Decompress manually, simulating what OkHttp does transparently */
    private fun decompressGzip(data: ByteArray): ByteArray {
        val bais = ByteArrayInputStream(data)
        val gis = GZIPInputStream(bais)
        val baos = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        var len: Int
        while (gis.read(buf).also { len = it } != -1) {
            baos.write(buf, 0, len)
        }
        return baos.toByteArray()
    }

    @Test
    fun `fixture is actually gzipped`() {
        val raw = loadGzippedFixture()
        assertEquals("gzip magic byte 0", 0x1F, raw[0].toInt() and 0xFF)
        assertEquals("gzip magic byte 1", 0x8B, raw[1].toInt() and 0xFF)
    }

    @Test
    fun `extractPolygons works with pre-decompressed data (OkHttp path)`() {
        val gzipped = loadGzippedFixture()
        val decompressed = decompressGzip(gzipped)

        // This is what OkHttp delivers — no gzip header
        assertNotEquals("decompressed should not start with gzip magic",
            0x1F, decompressed[0].toInt() and 0xFF)

        val rings = MvtParser.extractPolygons(decompressed, "squadrats", 10, 549, 335)
        assertEquals("should extract 3 rings from decompressed data", 3, rings.size)
    }

    @Test
    fun `hasFeatures works with pre-decompressed data`() {
        val decompressed = decompressGzip(loadGzippedFixture())
        assertTrue(MvtParser.hasFeatures(decompressed, "squadrats"))
    }

    @Test
    fun `extractPolygons gives same result for gzipped and decompressed input`() {
        val gzipped = loadGzippedFixture()
        val decompressed = decompressGzip(gzipped)

        val ringsFromGzip = MvtParser.extractPolygons(gzipped, "squadrats", 10, 549, 335)
        val ringsFromRaw = MvtParser.extractPolygons(decompressed, "squadrats", 10, 549, 335)

        assertEquals(ringsFromGzip.size, ringsFromRaw.size)
        for (i in ringsFromGzip.indices) {
            assertEquals("ring $i point count", ringsFromGzip[i].points.size, ringsFromRaw[i].points.size)
            for (j in ringsFromGzip[i].points.indices) {
                assertEquals("ring $i point $j lon",
                    ringsFromGzip[i].points[j].first, ringsFromRaw[i].points[j].first, 1e-9)
                assertEquals("ring $i point $j lat",
                    ringsFromGzip[i].points[j].second, ringsFromRaw[i].points[j].second, 1e-9)
            }
        }
    }

    // -- Full sync simulation --

    @Test
    fun `full sync pipeline finds collected z14 tiles`() {
        val decompressed = decompressGzip(loadGzippedFixture())

        // Step 1: Extract polygons (simulating what sync() does after HTTP fetch)
        val allRings = MvtParser.extractPolygons(decompressed, "squadrats", 10, 549, 335)
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
