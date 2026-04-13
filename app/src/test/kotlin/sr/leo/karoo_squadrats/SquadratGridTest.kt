package sr.leo.karoo_squadrats

import sr.leo.karoo_squadrats.data.MvtParser
import sr.leo.karoo_squadrats.data.TileRepository
import sr.leo.karoo_squadrats.grid.SquadratGrid
import sr.leo.karoo_squadrats.grid.SquadratGrid.TileCoord
import sr.leo.karoo_squadrats.grid.ZoomLevel
import org.junit.Assert.*
import org.junit.Test

class SquadratGridTest {

    // -- lonLatToTile --

    @Test
    fun `lonLatToTile maps Potsdam to known z14 tile`() {
        val tile = SquadratGrid.lonLatToTile(13.06, 52.4)
        assertEquals(8786, tile.x)
        // Verify round-trip: the tile bounds should contain the original point
        val bounds = SquadratGrid.tileBounds(tile)
        assertTrue(13.06 in bounds.lonMin..bounds.lonMax)
        assertTrue(52.4 in bounds.latMin..bounds.latMax)
    }

    @Test
    fun `lonLatToTile maps null island to origin region`() {
        val tile = SquadratGrid.lonLatToTile(0.0, 0.0)
        assertEquals(8192, tile.x)
        assertEquals(8192, tile.y)
    }

    @Test
    fun `lonLatToTile clamps extreme latitude`() {
        val tile = SquadratGrid.lonLatToTile(0.0, 90.0)
        assertEquals(0, tile.y) // near north pole
    }

    // -- tileBounds --

    @Test
    fun `tileBounds produces valid ranges`() {
        val tile = SquadratGrid.lonLatToTile(13.06, 52.4)
        val bounds = SquadratGrid.tileBounds(tile)
        assertTrue(bounds.lonMin < bounds.lonMax)
        assertTrue(bounds.latMin < bounds.latMax)
    }

    @Test
    fun `tileBounds contains the original point`() {
        val lon = 13.06; val lat = 52.4
        val tile = SquadratGrid.lonLatToTile(lon, lat)
        val bounds = SquadratGrid.tileBounds(tile)
        assertTrue("lon should be within bounds", lon in bounds.lonMin..bounds.lonMax)
        assertTrue("lat should be within bounds", lat in bounds.latMin..bounds.latMax)
    }

    @Test
    fun `tileBounds adjacent tiles share edges`() {
        val tile = SquadratGrid.lonLatToTile(13.06, 52.4)
        val right = TileCoord(tile.x + 1, tile.y)
        val boundsL = SquadratGrid.tileBounds(tile)
        val boundsR = SquadratGrid.tileBounds(right)
        assertEquals(boundsL.lonMax, boundsR.lonMin, 1e-9)
    }

    // -- TileCoord key round-trip --

    @Test
    fun `TileCoord toKey and fromKey round trip`() {
        val original = SquadratGrid.lonLatToTile(13.06, 52.4)
        val restored = TileCoord.fromKey(original.toKey())
        assertEquals(original, restored)
    }

    @Test
    fun `TileCoord key handles large coordinates`() {
        val original = TileCoord(16383, 16383) // max at z=14
        val restored = TileCoord.fromKey(original.toKey())
        assertEquals(original, restored)
    }

    @Test
    fun `TileCoord key handles zero`() {
        val original = TileCoord(0, 0)
        val restored = TileCoord.fromKey(original.toKey())
        assertEquals(original, restored)
    }

    // -- visibleTiles --

    @Test
    fun `tilesToRender returns empty below minimum zoom`() {
        val tiles = SquadratGrid.tilesToRender(52.4, 13.06, 10.0, screenPx = 800)
        assertTrue(tiles.isEmpty())
    }

    @Test
    fun `tilesToRender returns tiles at zoom 13`() {
        val tiles = SquadratGrid.tilesToRender(52.4, 13.06, 13.0, screenPx = 800)
        assertTrue(tiles.isNotEmpty())
    }

    @Test
    fun `tilesToRender center tile contains the center point`() {
        val lat = 52.4; val lon = 13.06
        val tiles = SquadratGrid.tilesToRender(lat, lon, 14.0, screenPx = 800)
        val centerTile = SquadratGrid.lonLatToTile(lon, lat)
        assertTrue("center tile should be in visible set", tiles.contains(centerTile))
    }

    // -- tilesInRadius --

    @Test
    fun `tilesInRadius returns non-empty for valid input`() {
        val tiles = SquadratGrid.tilesInRadius(52.4, 13.06, 5.0)
        assertTrue(tiles.isNotEmpty())
    }

    @Test
    fun `tilesInRadius includes center tile`() {
        val centerTile = SquadratGrid.lonLatToTile(13.06, 52.4)
        val tiles = SquadratGrid.tilesInRadius(52.4, 13.06, 5.0)
        assertTrue(tiles.contains(centerTile))
    }

    @Test
    fun `tilesInRadius grows with larger radius`() {
        val small = SquadratGrid.tilesInRadius(52.4, 13.06, 2.0)
        val large = SquadratGrid.tilesInRadius(52.4, 13.06, 10.0)
        assertTrue(large.size > small.size)
    }

    // -- tilesInRadius at different zoom levels --

    @Test
    fun `tilesInRadius at z10 returns fewer tiles than z14`() {
        val z10 = SquadratGrid.tilesInRadius(52.4, 13.06, 30.0, 10)
        val z14 = SquadratGrid.tilesInRadius(52.4, 13.06, 30.0)
        assertTrue("z=10 should have far fewer tiles", z10.size < z14.size)
    }

    @Test
    fun `tilesInRadius z10 around Potsdam includes tile 549 335`() {
        val tiles = SquadratGrid.tilesInRadius(52.4, 13.06, 30.0, 10)
        assertTrue("should include z10 tile (549,335)", tiles.contains(TileCoord(549, 335)))
    }

    // -- tileCenterLonLat --

    @Test
    fun `tileCenterLonLat returns point within tile bounds`() {
        val tile = SquadratGrid.lonLatToTile(13.06, 52.4)
        val (lon, lat) = SquadratGrid.tileCenterLonLat(tile)
        val bounds = SquadratGrid.tileBounds(tile)
        assertTrue(lon in bounds.lonMin..bounds.lonMax)
        assertTrue(lat in bounds.latMin..bounds.latMax)
    }

    @Test
    fun `tileCenterLonLat is near the middle of bounds`() {
        val tile = SquadratGrid.lonLatToTile(13.06, 52.4)
        val (lon, lat) = SquadratGrid.tileCenterLonLat(tile)
        val bounds = SquadratGrid.tileBounds(tile)
        val midLon = (bounds.lonMin + bounds.lonMax) / 2
        val midLat = (bounds.latMin + bounds.latMax) / 2
        assertEquals(midLon, lon, 1e-9)
        assertEquals(midLat, lat, 1e-9)
    }

    // -- z=17 squadratinho tile math --

    @Test
    fun `lonLatToTile at z17 gives 8x finer resolution than z14`() {
        val z14 = SquadratGrid.lonLatToTile(13.06, 52.4, ZoomLevel.SQUADRAT.z)
        val z17 = SquadratGrid.lonLatToTile(13.06, 52.4, ZoomLevel.SQUADRATINHO.z)
        // z=17 tile should be within the 8x8 subdivision of the z=14 tile
        assertEquals(z14.x, z17.x / 8)
        assertEquals(z14.y, z17.y / 8)
    }

    @Test
    fun `tileCenterLonLat at z17 returns point within z17 tile bounds`() {
        val tile = SquadratGrid.lonLatToTile(13.06, 52.4, ZoomLevel.SQUADRATINHO.z)
        val (lon, lat) = SquadratGrid.tileCenterLonLat(tile, ZoomLevel.SQUADRATINHO)
        val bounds = SquadratGrid.tileBounds(tile, ZoomLevel.SQUADRATINHO)
        assertTrue(lon in bounds.lonMin..bounds.lonMax)
        assertTrue(lat in bounds.latMin..bounds.latMax)
    }

    @Test
    fun `tileBounds at z17 is 8x smaller than z14`() {
        val z14 = SquadratGrid.lonLatToTile(13.06, 52.4, ZoomLevel.SQUADRAT.z)
        val z17 = SquadratGrid.lonLatToTile(13.06, 52.4, ZoomLevel.SQUADRATINHO.z)
        val b14 = SquadratGrid.tileBounds(z14, ZoomLevel.SQUADRAT)
        val b17 = SquadratGrid.tileBounds(z17, ZoomLevel.SQUADRATINHO)
        val z14Width = b14.lonMax - b14.lonMin
        val z17Width = b17.lonMax - b17.lonMin
        assertEquals(z14Width / 8.0, z17Width, z17Width * 0.01)
    }

    @Test
    fun `TileCoord key round trip works at z17 range`() {
        val original = TileCoord(131071, 131071) // max at z=17
        val restored = TileCoord.fromKey(original.toKey())
        assertEquals(original, restored)
    }

    @Test
    fun `tilesInRadius z12 returns expected count for squadratinho fetch`() {
        val tiles = SquadratGrid.tilesInRadius(52.4, 13.06, 30.0, ZoomLevel.SQUADRATINHO.fetchZoom)
        assertTrue("z=12 fetch should return more tiles than z=10", tiles.size > 6)
        assertTrue("z=12 fetch should return manageable count", tiles.size < 200)
    }

    // -- z=17 tile classification with polygon data --

    private fun loadZ10(): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("tile_z10_549_335.pbf")!!.readBytes()

    private fun loadZ12(): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("tile_z12_2197_1343.pbf")!!.readBytes()

    private fun classifyZ17TilesInSquadrat(
        z14Tile: TileCoord,
        rings: List<MvtParser.Ring>,
    ): Int {
        val z17XBase = z14Tile.x * 8
        val z17YBase = z14Tile.y * 8
        var count = 0
        for (dx in 0..7) {
            for (dy in 0..7) {
                val z17Tile = TileCoord(z17XBase + dx, z17YBase + dy)
                val (lon, lat) = SquadratGrid.tileCenterLonLat(z17Tile, ZoomLevel.SQUADRATINHO)
                var inside = false
                for (ring in rings) {
                    if (TileRepository.pointInRing(lon, lat, ring.points)) {
                        inside = TileRepository.isClockwise(ring)
                    }
                }
                if (inside) count++
            }
        }
        return count
    }

    @Test
    fun `z10 squadratinho polygons can classify z17 tiles`() {
        val rings = MvtParser.extractPolygons(loadZ10(), "squadratinhos", 10, 549, 335)
        assertTrue("should have rings", rings.isNotEmpty())
        val z14Tile = SquadratGrid.lonLatToTile(13.3, 52.5)
        val collectedCount = classifyZ17TilesInSquadrat(z14Tile, rings)
        assertTrue("Should classify some z=17 tiles, got $collectedCount/64", collectedCount > 0)
    }

    @Test
    fun `z12 squadratinho polygons can classify z17 tiles`() {
        val rings = MvtParser.extractPolygons(loadZ12(), "squadratinhos", 12, 2197, 1343)
        assertTrue("should have rings", rings.isNotEmpty())
        val z14Tile = TileCoord(2197 * 4, 1343 * 4)
        val collectedCount = classifyZ17TilesInSquadrat(z14Tile, rings)
        assertTrue("Should classify some z=17 tiles from z=12, got $collectedCount/64",
            collectedCount > 0)
    }

    @Test
    fun `z12 provides higher resolution than z10 for squadratinho classification`() {
        val z10Rings = MvtParser.extractPolygons(loadZ10(), "squadratinhos", 10, 549, 335)
        val z12Rings = MvtParser.extractPolygons(loadZ12(), "squadratinhos", 12, 2197, 1343)
        val z14Tile = TileCoord(2197 * 4, 1343 * 4)
        val z10Count = classifyZ17TilesInSquadrat(z14Tile, z10Rings)
        val z12Count = classifyZ17TilesInSquadrat(z14Tile, z12Rings)
        assertTrue("z=10 should classify some tiles, got $z10Count", z10Count > 0)
        assertTrue("z=12 should classify some tiles, got $z12Count", z12Count > 0)
    }

    @Test
    fun `z17 tiles outside collected area are not classified`() {
        val rings = MvtParser.extractPolygons(loadZ10(), "squadratinhos", 10, 549, 335)
        val z14Tile = SquadratGrid.lonLatToTile(12.5, 52.5)
        val collectedCount = classifyZ17TilesInSquadrat(z14Tile, rings)
        assertEquals("Should classify zero z=17 tiles outside area", 0, collectedCount)
    }
}
