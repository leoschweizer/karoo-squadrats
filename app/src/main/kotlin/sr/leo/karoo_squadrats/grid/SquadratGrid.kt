package sr.leo.karoo_squadrats.grid

import kotlin.math.*

/**
 * Handles Squadrat grid math.
 * Squadrats align with web mercator z=14 tiles (~1.6km / ~1 mile per side at mid-latitudes).
 */
object SquadratGrid {

    data class TileCoord(val x: Int, val y: Int) {
        /** Pack two Int32 tile coordinates into a single Long: x in upper 32 bits, y in lower 32 bits. */
        fun toKey(): Long = (x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL)

        companion object {
            fun fromKey(key: Long) = TileCoord(
                x = (key shr 32).toInt(),   // upper 32 bits
                y = key.toInt(),            // lower 32 bits
            )
        }
    }

    data class TileBounds(
        val latMin: Double,
        val latMax: Double,
        val lonMin: Double,
        val lonMax: Double,
    )

    fun lonLatToTile(lon: Double, lat: Double, zoom: Int = ZoomLevel.SQUADRAT.z): TileCoord {
        val n = 2.0.pow(zoom).toInt()
        val clampedLat = lat.coerceIn(-85.05112878, 85.05112878)
        val x = ((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
        val latRad = Math.toRadians(clampedLat)
        val y = ((1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n).toInt().coerceIn(0, n - 1)
        return TileCoord(x, y)
    }

    fun tileBounds(tile: TileCoord, zoom: ZoomLevel = ZoomLevel.SQUADRAT): TileBounds {
        val n = 2.0.pow(zoom.z).toInt()
        val lonMin = tile.x.toDouble() / n * 360.0 - 180.0
        val lonMax = (tile.x + 1).toDouble() / n * 360.0 - 180.0
        val latMax = Math.toDegrees(atan(sinh(PI * (1 - 2.0 * tile.y / n))))
        val latMin = Math.toDegrees(atan(sinh(PI * (1 - 2.0 * (tile.y + 1) / n))))
        return TileBounds(latMin, latMax, lonMin, lonMax)
    }

    /**
     * Compute tiles to render around the GPS position at a given map zoom level.
     * Returns tiles of the given zoom level within a radius that covers
     * the visible area plus a scroll buffer.
     */
    fun tilesToRender(
        lat: Double,
        lon: Double,
        mapZoom: Double,
        level: ZoomLevel = ZoomLevel.SQUADRAT,
        screenPx: Int,
    ): List<TileCoord> {
        if (mapZoom < level.minMapZoom) return emptyList()

        val center = lonLatToTile(lon, lat, level.z)
        val radius = level.renderRadius(mapZoom, screenPx)

        val tiles = mutableListOf<TileCoord>()
        for (x in (center.x - radius)..(center.x + radius)) {
            for (y in (center.y - radius)..(center.y + radius)) {
                tiles.add(TileCoord(x, y))
            }
        }
        return tiles
    }

    /**
     * Compute tile coords at a given zoom level covering a radius (km) around a center point.
     */
    fun tilesInRadius(centerLat: Double, centerLon: Double, radiusKm: Double, zoom: Int = ZoomLevel.SQUADRAT.z): List<TileCoord> {
        val degLat = radiusKm / 110.54 // km per degree latitude
        val cosLat = cos(Math.toRadians(centerLat)).coerceAtLeast(0.01)
        val degLon = radiusKm / (111.32 * cosLat) // km per degree longitude at equator

        val topLeft = lonLatToTile(centerLon - degLon, centerLat + degLat, zoom)
        val bottomRight = lonLatToTile(centerLon + degLon, centerLat - degLat, zoom)

        val tiles = mutableListOf<TileCoord>()
        for (x in topLeft.x..bottomRight.x) {
            for (y in topLeft.y..bottomRight.y) {
                tiles.add(TileCoord(x, y))
            }
        }
        return tiles
    }

    /**
     * Get the center lon/lat of a tile at a given zoom level.
     */
    fun tileCenterLonLat(tile: TileCoord, zoom: ZoomLevel = ZoomLevel.SQUADRAT): Pair<Double, Double> {
        val b = tileBounds(tile, zoom)
        return (b.lonMin + b.lonMax) / 2.0 to (b.latMin + b.latMax) / 2.0
    }

    /**
     * Get the lon/lat of a tile grid corner point at a given zoom level.
     * Corner (x, y) is the top-left corner of tile (x, y).
     */
    fun tileCornerLonLat(x: Int, y: Int, zoom: ZoomLevel = ZoomLevel.SQUADRAT): Pair<Double, Double> {
        val n = 2.0.pow(zoom.z).toInt()
        val lon = x.toDouble() / n * 360.0 - 180.0
        val lat = Math.toDegrees(atan(sinh(PI * (1 - 2.0 * y / n))))
        return lon to lat
    }
}
