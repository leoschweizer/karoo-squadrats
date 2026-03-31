package sr.leo.karoo_squadrats

import sr.leo.karoo_squadrats.data.MvtParser
import org.junit.Assert.*
import org.junit.Test

class MvtParserTest {

    private fun loadFixture(): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("tile_z10_549_335.pbf")!!.readBytes()

    // -- extractPolygons on real tile --

    @Test
    fun `extractPolygons returns 3 rings for squadrats layer`() {
        val rings = MvtParser.extractPolygons(loadFixture(), "squadrats", 10, 549, 335)
        assertEquals(3, rings.size)
    }

    @Test
    fun `extractPolygons exterior ring has expected point count`() {
        val rings = MvtParser.extractPolygons(loadFixture(), "squadrats", 10, 549, 335)
        // Ring 0 (exterior) has 77 points (76 vertices + closing duplicate)
        assertEquals(77, rings[0].points.size)
    }

    @Test
    fun `extractPolygons exterior ring covers expected lon range`() {
        val rings = MvtParser.extractPolygons(loadFixture(), "squadrats", 10, 549, 335)
        val lons = rings[0].points.map { it.first }
        assertTrue("min lon should be near 13.0", lons.min() in 12.9..13.1)
        assertTrue("max lon should be near 13.37", lons.max() in 13.3..13.5)
    }

    @Test
    fun `extractPolygons exterior ring covers expected lat range`() {
        val rings = MvtParser.extractPolygons(loadFixture(), "squadrats", 10, 549, 335)
        val lats = rings[0].points.map { it.second }
        assertTrue("min lat should be near 52.48", lats.min() in 52.4..52.5)
        assertTrue("max lat should be near 52.70", lats.max() in 52.6..52.8)
    }

    @Test
    fun `extractPolygons hole rings are smaller rectangles`() {
        val rings = MvtParser.extractPolygons(loadFixture(), "squadrats", 10, 549, 335)
        // Rings 1 and 2 are holes with 5 points each (4 corners + close)
        assertEquals(5, rings[1].points.size)
        assertEquals(5, rings[2].points.size)
    }

    @Test
    fun `extractPolygons first point of exterior ring matches expected coords`() {
        val rings = MvtParser.extractPolygons(loadFixture(), "squadrats", 10, 549, 335)
        val (lon, lat) = rings[0].points[0]
        assertEquals(13.293, lon, 0.01)
        assertEquals(52.479, lat, 0.01)
    }

    // -- hasFeatures --

    @Test
    fun `hasFeatures returns true for squadrats layer`() {
        assertTrue(MvtParser.hasFeatures(loadFixture(), "squadrats"))
    }

    @Test
    fun `hasFeatures returns true for squadratinhos layer`() {
        assertTrue(MvtParser.hasFeatures(loadFixture(), "squadratinhos"))
    }

    @Test
    fun `hasFeatures returns false for nonexistent layer`() {
        assertFalse(MvtParser.hasFeatures(loadFixture(), "nonexistent"))
    }

    @Test
    fun `hasFeatures returns false for empty data`() {
        assertFalse(MvtParser.hasFeatures(ByteArray(0)))
    }

    // -- extractPolygons with wrong layer name --

    @Test
    fun `extractPolygons returns empty for nonexistent layer`() {
        val rings = MvtParser.extractPolygons(loadFixture(), "nonexistent", 10, 549, 335)
        assertTrue(rings.isEmpty())
    }

    @Test
    fun `extractPolygons returns empty for empty data`() {
        val rings = MvtParser.extractPolygons(ByteArray(0), "squadrats", 10, 549, 335)
        assertTrue(rings.isEmpty())
    }

    // -- edge cases --

    @Test
    fun `extractPolygons handles invalid protobuf gracefully`() {
        val garbage = byteArrayOf(0x0A, 0x03, 0x41, 0x42, 0x43)
        val rings = MvtParser.extractPolygons(garbage, "squadrats", 10, 549, 335)
        assertTrue(rings.isEmpty())
    }

    // -- rings are closed (first == last point) --

    @Test
    fun `all rings are closed`() {
        val rings = MvtParser.extractPolygons(loadFixture(), "squadrats", 10, 549, 335)
        for ((i, ring) in rings.withIndex()) {
            val first = ring.points.first()
            val last = ring.points.last()
            assertEquals("Ring $i should be closed (lon)", first.first, last.first, 1e-9)
            assertEquals("Ring $i should be closed (lat)", first.second, last.second, 1e-9)
        }
    }
}
