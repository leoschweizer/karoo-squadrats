package sr.leo.karoo_squadrats.map

import sr.leo.karoo_squadrats.grid.SquadratGrid
import kotlin.math.roundToInt

/**
 * Encodes lat/lon points into Google Encoded Polyline format.
 * See: https://developers.google.com/maps/documentation/utilities/polylinealgorithm
 */
object PolylineEncoder {

    fun encode(points: List<Pair<Double, Double>>): String {
        val result = StringBuilder()
        var prevLat = 0
        var prevLon = 0
        for ((lat, lon) in points) {
            val latE5 = (lat * 1e5).roundToInt()
            val lonE5 = (lon * 1e5).roundToInt()
            encodeValue(latE5 - prevLat, result)
            encodeValue(lonE5 - prevLon, result)
            prevLat = latE5
            prevLon = lonE5
        }
        return result.toString()
    }

    private fun encodeValue(value: Int, sb: StringBuilder) {
        var v = if (value < 0) (value shl 1).inv() else (value shl 1)
        while (v >= 0x20) {
            sb.append((((v and 0x1F) or 0x20) + 63).toChar())
            v = v ushr 5
        }
        sb.append((v + 63).toChar())
    }

    /**
     * Encode a square tile as a closed polyline (5 points: NW -> NE -> SE -> SW -> NW).
     */
    fun encodeSquare(bounds: SquadratGrid.TileBounds): String {
        return encode(
            listOf(
                bounds.latMax to bounds.lonMin, // NW
                bounds.latMax to bounds.lonMax, // NE
                bounds.latMin to bounds.lonMax, // SE
                bounds.latMin to bounds.lonMin, // SW
                bounds.latMax to bounds.lonMin, // close
            ),
        )
    }
}
