package sr.leo.karoo_squadrats

import sr.leo.karoo_squadrats.grid.SquadratGrid
import sr.leo.karoo_squadrats.grid.SquadratGrid.TileCoord
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
    fun `visibleTiles returns empty below minimum zoom`() {
        val tiles = SquadratGrid.visibleTiles(52.4, 13.06, 10.0)
        assertTrue(tiles.isEmpty())
    }

    @Test
    fun `visibleTiles returns tiles at zoom 13`() {
        val tiles = SquadratGrid.visibleTiles(52.4, 13.06, 13.0)
        assertTrue(tiles.isNotEmpty())
    }

    @Test
    fun `visibleTiles center tile contains the center point`() {
        val lat = 52.4; val lon = 13.06
        val tiles = SquadratGrid.visibleTiles(lat, lon, 14.0)
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

    // -- tilesInRadiusAtZoom --

    @Test
    fun `tilesInRadiusAtZoom at z10 returns fewer tiles than z14`() {
        val z10 = SquadratGrid.tilesInRadiusAtZoom(52.4, 13.06, 30.0, 10)
        val z14 = SquadratGrid.tilesInRadius(52.4, 13.06, 30.0)
        assertTrue("z=10 should have far fewer tiles", z10.size < z14.size)
    }

    @Test
    fun `tilesInRadiusAtZoom z10 around Potsdam includes tile 549 335`() {
        val tiles = SquadratGrid.tilesInRadiusAtZoom(52.4, 13.06, 30.0, 10)
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
}
