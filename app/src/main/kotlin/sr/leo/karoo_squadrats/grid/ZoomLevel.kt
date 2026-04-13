package sr.leo.karoo_squadrats.grid

import kotlin.math.ceil
import kotlin.math.pow

enum class ZoomLevel(val z: Int, val fetchZoom: Int, val minMapZoom: Double, val scrollBuffer: Double) {
    SQUADRAT(14, 10, 11.0, 0.5),
    SQUADRATINHO(17, 12, 14.0, 0.25),
    ;

    /**
     * Number of tiles to render in each direction from the GPS tile.
     */
    fun renderRadius(mapZoom: Double, screenPx: Int): Int {
        val pixelsPerTile = 256.0 * 2.0.pow(mapZoom - z)
        val visibleTiles = ceil(screenPx / (2.0 * pixelsPerTile)).toInt()
        return visibleTiles + ceil(visibleTiles * scrollBuffer).toInt().coerceAtLeast(1)
    }
}
