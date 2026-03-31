package sr.leo.karoo_squadrats

import sr.leo.karoo_squadrats.grid.SquadratGrid
import sr.leo.karoo_squadrats.map.PolylineEncoder
import org.junit.Assert.*
import org.junit.Test

class PolylineEncoderTest {

    /**
     * Reference example from Google's Encoded Polyline Algorithm documentation:
     * https://developers.google.com/maps/documentation/utilities/polylinealgorithm
     */
    @Test
    fun `encode reproduces Google documentation example`() {
        val points = listOf(
            38.5 to -120.2,
            40.7 to -120.95,
            43.252 to -126.453,
        )
        assertEquals("_p~iF~ps|U_ulLnnqC_mqNvxq`@", PolylineEncoder.encode(points))
    }

    @Test
    fun `encode single point from Google example`() {
        val points = listOf(38.5 to -120.2)
        assertEquals("_p~iF~ps|U", PolylineEncoder.encode(points))
    }

    @Test
    fun `encode empty list returns empty string`() {
        assertEquals("", PolylineEncoder.encode(emptyList()))
    }

    @Test
    fun `encode origin point`() {
        // (0, 0) -> all deltas are zero, each encodes as char(0 + 63) = '?'
        assertEquals("??", PolylineEncoder.encode(listOf(0.0 to 0.0)))
    }

    @Test
    fun `encode negative coordinates`() {
        assertEquals("~~umEca|y[", PolylineEncoder.encode(listOf(-33.8688 to 151.2093)))
    }

    @Test
    fun `encode preserves delta encoding across points`() {
        // Two identical points: second delta is (0,0) -> "??"
        val points = listOf(
            38.5 to -120.2,
            38.5 to -120.2,
        )
        assertEquals("_p~iF~ps|U??", PolylineEncoder.encode(points))
    }

    @Test
    fun `encodeSquare small tile`() {
        val bounds = SquadratGrid.TileBounds(
            latMin = 52.0, latMax = 52.5,
            lonMin = 13.0, lonMax = 13.5,
        )
        assertEquals("_|l_I_ajnA?_t`B~s`B??~s`B_t`B?", PolylineEncoder.encodeSquare(bounds))
    }

    @Test
    fun `encodeSquare large tile`() {
        val bounds = SquadratGrid.TileBounds(
            latMin = 10.0, latMax = 20.0,
            lonMin = 30.0, lonMax = 40.0,
        )
        assertEquals("_gayB_kbvD?_c`|@~b`|@??~b`|@_c`|@?", PolylineEncoder.encodeSquare(bounds))
    }
}
