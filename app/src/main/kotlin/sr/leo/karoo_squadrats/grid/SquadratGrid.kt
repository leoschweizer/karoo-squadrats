package sr.leo.karoo_squadrats.grid

import kotlin.math.*

/**
 * Handles Squadrat grid math.
 * Squadrats align with web mercator z=14 tiles (~1.6km / ~1 mile per side at mid-latitudes).
 */
object SquadratGrid {
    const val ZOOM = 14
    // Total number of tiles per axis at this zoom level (2^14 = 16384)
    private val TILES_PER_AXIS = 2.0.pow(ZOOM).toInt()
    const val FETCH_ZOOM = 10
    const val MIN_MAP_ZOOM = 11.0
    const val MAX_VISIBLE_TILES = 2000
    const val MIN_TILE_RADIUS = 4 // always draw at least 9x9 tiles around GPS

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

    fun lonLatToTile(lon: Double, lat: Double): TileCoord {
        // 85.0511° is the Web Mercator limit: arctan(sinh(π)). Beyond this the projection is undefined.
        val clampedLat = lat.coerceIn(-85.05112878, 85.05112878)
        val x = ((lon + 180.0) / 360.0 * TILES_PER_AXIS).toInt().coerceIn(0, TILES_PER_AXIS - 1)
        val latRad = Math.toRadians(clampedLat)
        val y = ((1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * TILES_PER_AXIS).toInt().coerceIn(0, TILES_PER_AXIS - 1)
        return TileCoord(x, y)
    }

    fun tileBounds(tile: TileCoord): TileBounds {
        val lonMin = tile.x.toDouble() / TILES_PER_AXIS * 360.0 - 180.0
        val lonMax = (tile.x + 1).toDouble() / TILES_PER_AXIS * 360.0 - 180.0
        val latMax = Math.toDegrees(atan(sinh(PI * (1 - 2.0 * tile.y / TILES_PER_AXIS))))
        val latMin = Math.toDegrees(atan(sinh(PI * (1 - 2.0 * (tile.y + 1) / TILES_PER_AXIS))))
        return TileBounds(latMin, latMax, lonMin, lonMax)
    }

    /**
     * Compute which z=14 tiles are visible given a center position and map zoom level.
     */
    fun visibleTiles(
        lat: Double,
        lon: Double,
        mapZoom: Double,
        screenWidth: Int = 480,  // Karoo screen width in pixels
        screenHeight: Int = 800, // Karoo screen height in pixels
    ): List<TileCoord> {
        if (mapZoom < MIN_MAP_ZOOM) return emptyList()

        // 156543.03 = Earth circumference in meters / 256 pixels (Web Mercator meters/pixel at z=0, equator)
        val metersPerPixel = 156543.03 * cos(Math.toRadians(lat)) / 2.0.pow(mapZoom)
        val cosLat = cos(Math.toRadians(lat)).coerceAtLeast(0.01)
        val visibleWidthDeg = (screenWidth * metersPerPixel) / (111320.0 * cosLat) // 111320 = meters per degree longitude at equator
        val visibleHeightDeg = (screenHeight * metersPerPixel) / 110540.0 // 110540 = meters per degree latitude (approx)

        // Large padding so tiles remain visible when user scrolls the map.
        // Scale padding down at lower zoom to stay within tile cap.
        val padding = if (mapZoom >= 13) 3.0 else if (mapZoom >= 12) 2.0 else 1.5
        val halfW = visibleWidthDeg / 2 * padding
        val halfH = visibleHeightDeg / 2 * padding

        val topLeft = lonLatToTile(lon - halfW, lat + halfH)
        val bottomRight = lonLatToTile(lon + halfW, lat - halfH)

        // At high zoom the computed range can be tiny (1-3 tiles).
        // Enforce a minimum radius so tiles are always visible when
        // the map center drifts from the GPS position.
        val center = lonLatToTile(lon, lat)
        val minX = min(topLeft.x, center.x - MIN_TILE_RADIUS)
        val maxX = max(bottomRight.x, center.x + MIN_TILE_RADIUS)
        val minY = min(topLeft.y, center.y - MIN_TILE_RADIUS)
        val maxY = max(bottomRight.y, center.y + MIN_TILE_RADIUS)

        val countX = maxX - minX + 1
        val countY = maxY - minY + 1
        if (countX * countY > MAX_VISIBLE_TILES) return emptyList()

        val tiles = mutableListOf<TileCoord>()
        for (x in minX..maxX) {
            for (y in minY..maxY) {
                tiles.add(TileCoord(x, y))
            }
        }
        return tiles
    }

    /**
     * Compute all z=14 tiles within a radius (km) of a center point.
     */
    fun tilesInRadius(centerLat: Double, centerLon: Double, radiusKm: Double): List<TileCoord> {
        val degLat = radiusKm / 110.54 // km per degree latitude
        val cosLat = cos(Math.toRadians(centerLat)).coerceAtLeast(0.01)
        val degLon = radiusKm / (111.32 * cosLat) // km per degree longitude at equator

        val topLeft = lonLatToTile(centerLon - degLon, centerLat + degLat)
        val bottomRight = lonLatToTile(centerLon + degLon, centerLat - degLat)

        val tiles = mutableListOf<TileCoord>()
        for (x in topLeft.x..bottomRight.x) {
            for (y in topLeft.y..bottomRight.y) {
                tiles.add(TileCoord(x, y))
            }
        }
        return tiles
    }

    /**
     * Compute tile coords at an arbitrary zoom level covering a radius.
     */
    fun tilesInRadiusAtZoom(centerLat: Double, centerLon: Double, radiusKm: Double, zoom: Int): List<TileCoord> {
        val degLat = radiusKm / 110.54 // km per degree latitude
        val cosLat = cos(Math.toRadians(centerLat)).coerceAtLeast(0.01)
        val degLon = radiusKm / (111.32 * cosLat) // km per degree longitude at equator
        val n = 2.0.pow(zoom).toInt()

        fun toTile(lon: Double, lat: Double): TileCoord {
            // 85.0511° is the Web Mercator limit: arctan(sinh(π))
            val clampedLat = lat.coerceIn(-85.05112878, 85.05112878)
            val x = ((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
            val latRad = Math.toRadians(clampedLat)
            val y = ((1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n).toInt().coerceIn(0, n - 1)
            return TileCoord(x, y)
        }

        val topLeft = toTile(centerLon - degLon, centerLat + degLat)
        val bottomRight = toTile(centerLon + degLon, centerLat - degLat)

        val tiles = mutableListOf<TileCoord>()
        for (x in topLeft.x..bottomRight.x) {
            for (y in topLeft.y..bottomRight.y) {
                tiles.add(TileCoord(x, y))
            }
        }
        return tiles
    }

    /**
     * Get the center lon/lat of a z=14 tile.
     */
    fun tileCenterLonLat(tile: TileCoord): Pair<Double, Double> {
        val b = tileBounds(tile)
        return (b.lonMin + b.lonMax) / 2.0 to (b.latMin + b.latMax) / 2.0
    }

    /**
     * Get the lon/lat of a tile grid corner point.
     * Corner (x, y) is the top-left corner of tile (x, y).
     */
    fun tileCornerLonLat(x: Int, y: Int): Pair<Double, Double> {
        val lon = x.toDouble() / TILES_PER_AXIS * 360.0 - 180.0
        val lat = Math.toDegrees(atan(sinh(PI * (1 - 2.0 * y / TILES_PER_AXIS))))
        return lon to lat
    }
}
