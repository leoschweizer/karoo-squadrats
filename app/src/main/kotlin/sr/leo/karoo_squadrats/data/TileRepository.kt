package sr.leo.karoo_squadrats.data

import android.util.Log
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.HttpResponseState
import io.hammerhead.karooext.models.OnHttpResponse
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import sr.leo.karoo_squadrats.extension.consumerFlow
import sr.leo.karoo_squadrats.grid.SquadratGrid
import sr.leo.karoo_squadrats.grid.SquadratGrid.TileCoord
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

class TileRepository(private val prefs: SquadratsPreferences) {

    @Volatile
    private var collectedTiles: Set<Long> = emptySet()

    fun loadCachedTiles() {
        collectedTiles = prefs.loadCollectedTiles()
    }

    fun isCollected(tile: TileCoord): Boolean {
        return collectedTiles.contains(tile.toKey())
    }

    val collectedCount: Int
        get() = collectedTiles.size

    interface SyncCallback {
        fun onProgress(fetched: Int, total: Int)
        fun onComplete(collected: Int, total: Int)
        fun onError(message: String)
    }

    /**
     * Sync tile data from the Squadrats server.
     *
     * 1. Fetch vector tiles at z=FETCH_ZOOM covering the sync area
     * 2. Extract collected-area polygons from the "squadrats" layer
     * 3. Test each z=14 tile center against those polygons
     * 4. Store the set of collected z=14 tile keys
     */
    suspend fun sync(
        karooSystem: KarooSystemService,
        centerLat: Double,
        centerLon: Double,
        radiusKm: Double,
        callback: SyncCallback,
    ) {
        val tileUrlTemplate = prefs.tileUrlTemplate
        if (tileUrlTemplate.isEmpty()) {
            callback.onError("Enter your user token and timestamp first")
            return
        }

        val fetchZoom = SquadratGrid.FETCH_ZOOM
        val fetchTiles = SquadratGrid.tilesInRadiusAtZoom(centerLat, centerLon, radiusKm, fetchZoom)
        if (fetchTiles.isEmpty()) {
            callback.onError("No tiles in the specified area")
            return
        }

        Log.d(TAG, "Sync: ${fetchTiles.size} fetch tiles at z=$fetchZoom, url=$tileUrlTemplate")

        // Phase 1: Fetch server tiles and collect polygons
        val allRings = mutableListOf<MvtParser.Ring>()
        var fetched = 0
        var errors = 0
        var httpErrors = 0
        val totalFetch = fetchTiles.size

        for (tile in fetchTiles) {
            try {
                val url = tileUrlTemplate
                    .replace("{z}", fetchZoom.toString())
                    .replace("{x}", tile.x.toString())
                    .replace("{y}", tile.y.toString())

                val response = karooSystem.consumerFlow<OnHttpResponse>(
                    OnHttpResponse.MakeHttpRequest(
                        method = "GET",
                        url = url,
                        headers = mapOf("Accept-Encoding" to "gzip"),
                        waitForConnection = false,
                    ),
                ).mapNotNull { it.state as? HttpResponseState.Complete }
                    .first()

                if (response.error != null) {
                    errors++
                    Log.w(TAG, "Tile ${tile.x}/${tile.y}: error=${response.error}")
                } else if (response.statusCode in 200..299) {
                    val body = response.body?.let { decompressIfGzipped(it) }
                    Log.d(TAG, "Tile ${tile.x}/${tile.y}: HTTP ${response.statusCode}, body=${body?.size ?: 0} bytes")
                    if (body != null && body.isNotEmpty()) {
                        val rings = MvtParser.extractPolygons(
                            body, "squadrats", fetchZoom, tile.x, tile.y
                        )
                        Log.d(TAG, "  -> extracted ${rings.size} rings")
                        allRings.addAll(rings)
                    }
                } else {
                    httpErrors++
                    Log.w(TAG, "Tile ${tile.x}/${tile.y}: HTTP ${response.statusCode}")
                }
            } catch (e: Exception) {
                errors++
                Log.w(TAG, "Tile ${tile.x}/${tile.y}: ${e.javaClass.simpleName}: ${e.message}")
            }
            fetched++
            callback.onProgress(fetched, totalFetch + 1) // +1 for the computation step
        }

        if (allRings.isEmpty()) {
            // No collected area found - store empty set
            Log.w(TAG, "No rings found. errors=$errors, httpErrors=$httpErrors, fetched=$fetched")
            collectedTiles = emptySet()
            prefs.saveCollectedTiles(emptySet())
            if (errors == totalFetch) {
                callback.onError("All $errors tile requests failed. Check internet connection.")
            } else if (errors + httpErrors > 0) {
                callback.onError("No collected areas found ($errors network errors, $httpErrors HTTP errors out of $totalFetch tiles)")
            } else {
                callback.onComplete(0, 0)
            }
            return
        }

        // Phase 2: Determine which z=14 tiles are collected
        val displayTiles = SquadratGrid.tilesInRadius(centerLat, centerLon, radiusKm)

        // Precompute bounding boxes for each ring to speed up point-in-polygon checks
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
            RingWithBounds(ring, minLon, maxLon, minLat, maxLat, isClockwise(ring))
        }

        // Exterior rings (clockwise in MVT) add area, interior rings (ccw) subtract
        val collected = mutableSetOf<Long>()
        for (tile in displayTiles) {
            val (lon, lat) = SquadratGrid.tileCenterLonLat(tile)
            var inside = false
            for (rb in ringsWithBounds) {
                // Quick bounding-box rejection
                if (lon < rb.minLon || lon > rb.maxLon || lat < rb.minLat || lat > rb.maxLat) continue
                if (pointInRing(lon, lat, rb.ring.points)) {
                    inside = rb.clockwise
                }
            }
            if (inside) {
                collected.add(tile.toKey())
            }
        }

        collectedTiles = collected
        prefs.saveCollectedTiles(collected)
        Log.d(TAG, "Sync done: ${collected.size} collected out of ${displayTiles.size} z14 tiles, from ${allRings.size} polygon rings")
        callback.onProgress(totalFetch + 1, totalFetch + 1)
        callback.onComplete(collected.size, displayTiles.size)
    }

    companion object {
        private const val TAG = "SquadratSync"

        /**
         * Detect GZIP magic bytes and decompress if needed.
         * The karoo-ext HTTP mechanism may not transparently decompress gzip responses.
         */
        fun decompressIfGzipped(data: ByteArray): ByteArray {
            if (data.size >= 2 && data[0] == 0x1f.toByte() && data[1] == 0x8b.toByte()) {
                return GZIPInputStream(ByteArrayInputStream(data)).readBytes()
            }
            return data
        }

        /**
         * Ray-casting point-in-polygon test.
         */
        fun pointInRing(px: Double, py: Double, ring: List<Pair<Double, Double>>): Boolean {
            var inside = false
            var j = ring.size - 1
            for (i in ring.indices) {
                val (xi, yi) = ring[i]
                val (xj, yj) = ring[j]
                if (((yi > py) != (yj > py)) &&
                    (px < (xj - xi) * (py - yi) / (yj - yi) + xi)
                ) {
                    inside = !inside
                }
                j = i
            }
            return inside
        }

        /**
         * Determine if a ring is clockwise (exterior in MVT spec) by computing signed area.
         */
        fun isClockwise(ring: MvtParser.Ring): Boolean {
            var area = 0.0
            val pts = ring.points
            for (i in pts.indices) {
                val (x1, y1) = pts[i]
                val (x2, y2) = pts[(i + 1) % pts.size]
                area += (x2 - x1) * (y2 + y1)
            }
            return area > 0
        }
    }
}
