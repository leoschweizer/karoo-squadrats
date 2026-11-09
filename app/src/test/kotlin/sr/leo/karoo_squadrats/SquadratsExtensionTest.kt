package sr.leo.karoo_squadrats

import sr.leo.karoo_squadrats.extension.SquadratsExtension
import sr.leo.karoo_squadrats.grid.SquadratGrid.TileCoord
import sr.leo.karoo_squadrats.grid.ZoomLevel
import org.junit.Assert.*
import org.junit.Test

class SquadratsExtensionTest {

    // tilesToMark / chebyshev-gating rule - coordinates are real ride data, cross-checked
    // against squadrats.com's own tile classification.

    private val zoom = ZoomLevel.SQUADRATINHO.z

    @Test
    fun `tilesToMark with no previous position returns only the current tile`() {
        val result = SquadratsExtension.tilesToMark(
            lat = 49.466311605647206, lon = 11.098986603319645,
            prevLat = Double.NaN, prevLon = Double.NaN,
            zoom = zoom,
        )
        assertEquals(setOf(TileCoord(69577, 44753)), result)
    }

    @Test
    fun `tilesToMark adds the corner-clipped tile when endpoints are chebyshev-adjacent`() {
        // Chebyshev distance 1 - the straight path also clips the corner of (69577,44752).
        val result = SquadratsExtension.tilesToMark(
            lat = 49.466311605647206, lon = 11.098986603319645,
            prevLat = 49.4663483183831, prevLon = 11.098924996331334,
            zoom = zoom,
        )
        assertEquals(
            setOf(TileCoord(69576, 44752), TileCoord(69577, 44752), TileCoord(69577, 44753)),
            result,
        )
    }

    @Test
    fun `tilesToMark does not interpolate across a chebyshev distance 2 gap`() {
        // Chebyshev distance 2 (a 247m gap) - too large to trust a straight-line guess.
        val result = SquadratsExtension.tilesToMark(
            lat = 49.4898090005, lon = 11.0367541706,
            prevLat = 49.48758741, prevLon = 11.0366403182,
            zoom = zoom,
        )
        assertEquals(setOf(TileCoord(69554, 44739)), result)
        assertFalse("must not include the skipped-over tile", result.contains(TileCoord(69554, 44740)))
        assertFalse("must not include the previous tile either", result.contains(TileCoord(69554, 44741)))
    }

    @Test
    fun `tilesToMark returns single tile when previous position is in the same tile`() {
        val result = SquadratsExtension.tilesToMark(
            lat = 49.466311605647206, lon = 11.098986603319645,
            prevLat = 49.466311605647206, prevLon = 11.098986603319645,
            zoom = zoom,
        )
        assertEquals(setOf(TileCoord(69577, 44753)), result)
    }
}
