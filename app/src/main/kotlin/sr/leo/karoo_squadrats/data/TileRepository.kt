package sr.leo.karoo_squadrats.data

import android.util.Log
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.HttpResponseState
import io.hammerhead.karooext.models.OnHttpResponse
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import sr.leo.karoo_squadrats.data.db.CollectedSquadrat
import sr.leo.karoo_squadrats.data.db.CollectedSquadratDao
import sr.leo.karoo_squadrats.data.db.CollectedSquadratinho
import sr.leo.karoo_squadrats.data.db.CollectedSquadratinhoDao
import sr.leo.karoo_squadrats.extension.consumerFlow
import sr.leo.karoo_squadrats.grid.SquadratGrid
import sr.leo.karoo_squadrats.grid.SquadratGrid.TileCoord
import sr.leo.karoo_squadrats.grid.ZoomLevel
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

class TileRepository(
    private val squadratDao: CollectedSquadratDao,
    private val squadratinhoDao: CollectedSquadratinhoDao,
) {

    /**
     * Query collected squadrats within a bounding box.
     */
    suspend fun collectedSquadratsInBounds(
        xMin: Int, xMax: Int, yMin: Int, yMax: Int,
    ): Set<Long> {
        return squadratDao.findInBounds(xMin, xMax, yMin, yMax)
            .map { TileCoord(it.x, it.y).toKey() }
            .toHashSet()
    }

    /**
     * Query collected squadratinhos within a bounding box.
     */
    suspend fun collectedSquadratinhosInBounds(
        xMin: Int, xMax: Int, yMin: Int, yMax: Int,
    ): Set<Long> {
        return squadratinhoDao.findInBounds(xMin, xMax, yMin, yMax)
            .map { TileCoord(it.x, it.y).toKey() }
            .toHashSet()
    }

    suspend fun squadratCount(): Int = squadratDao.count()

    interface SyncCallback {
        fun onProgress(fetched: Int, total: Int)
        fun onComplete(collected: Int, total: Int)
        fun onError(message: String)
    }

    /**
     * Sync tile data from the Squadrats server.
     *
     * 1. Fetch vector tiles covering the sync area
     * 2. Extract collected-area polygons from the "squadrats" (and optionally "squadratinhos") layer
     * 3. Test each z=14 (and optionally z=17) tile center against those polygons
     * 4. Store the set of collected tile keys
     */
    suspend fun sync(
        karooSystem: KarooSystemService,
        tileUrlTemplate: String,
        centerLat: Double,
        centerLon: Double,
        radiusKm: Double,
        syncSquadratinhos: Boolean,
        callback: SyncCallback,
    ) {
        if (tileUrlTemplate.isEmpty()) {
            callback.onError("Enter your user token and timestamp first")
            return
        }

        // Use higher-resolution fetch zoom when syncing squadratinhos
        val gridLevel = if (syncSquadratinhos) ZoomLevel.SQUADRATINHO else ZoomLevel.SQUADRAT
        val fetchZoom = gridLevel.fetchZoom
        val fetchTiles = SquadratGrid.tilesInRadius(centerLat, centerLon, radiusKm, fetchZoom)
        if (fetchTiles.isEmpty()) {
            callback.onError("No tiles in the specified area")
            return
        }

        Log.d(TAG, "Sync: ${fetchTiles.size} fetch tiles at z=$fetchZoom, squadratinhos=$syncSquadratinhos, url=$tileUrlTemplate")

        // Phase 1: Fetch server tiles and collect polygons per layer
        val squadratRings = mutableListOf<MvtParser.Ring>()
        val squadratinhoRings = mutableListOf<MvtParser.Ring>()
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
                        val sRings = MvtParser.extractPolygons(
                            body, "squadrats", fetchZoom, tile.x, tile.y
                        )
                        Log.d(TAG, "  -> squadrats: ${sRings.size} rings")
                        squadratRings.addAll(sRings)

                        if (syncSquadratinhos) {
                            val shRings = MvtParser.extractPolygons(
                                body, "squadratinhos", fetchZoom, tile.x, tile.y
                            )
                            Log.d(TAG, "  -> squadratinhos: ${shRings.size} rings")
                            squadratinhoRings.addAll(shRings)
                        }
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

        val allRings = squadratRings + squadratinhoRings

        if (allRings.isEmpty()) {
            // No collected area found - clear the synced region
            Log.w(TAG, "No rings found. errors=$errors, httpErrors=$httpErrors, fetched=$fetched")
            val displayTiles = SquadratGrid.tilesInRadius(centerLat, centerLon, radiusKm)
            if (displayTiles.isNotEmpty()) {
                squadratDao.deleteInBounds(
                    displayTiles.minOf { it.x }, displayTiles.maxOf { it.x },
                    displayTiles.minOf { it.y }, displayTiles.maxOf { it.y },
                )
            }
            if (syncSquadratinhos) {
                val shTiles = SquadratGrid.tilesInRadius(
                    centerLat, centerLon, radiusKm, ZoomLevel.SQUADRATINHO.z
                )
                if (shTiles.isNotEmpty()) {
                    squadratinhoDao.deleteInBounds(
                        shTiles.minOf { it.x }, shTiles.maxOf { it.x },
                        shTiles.minOf { it.y }, shTiles.maxOf { it.y },
                    )
                }
            }
            if (errors == totalFetch) {
                callback.onError("All $errors tile requests failed. Check internet connection.")
            } else if (errors + httpErrors > 0) {
                callback.onError("No collected areas found ($errors network errors, $httpErrors HTTP errors out of $totalFetch tiles)")
            } else {
                callback.onComplete(0, 0)
            }
            return
        }

        // Phase 2: Classify tiles using polygon hit-testing

        // Precompute bounding boxes for each ring to speed up point-in-polygon checks
        data class RingWithBounds(
            val ring: MvtParser.Ring,
            val minLon: Double, val maxLon: Double,
            val minLat: Double, val maxLat: Double,
            val clockwise: Boolean,
        )

        fun prepareRings(rings: List<MvtParser.Ring>): List<RingWithBounds> = rings.map { ring ->
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

        fun isCollectedTile(lon: Double, lat: Double, ringsWithBounds: List<RingWithBounds>): Boolean {
            var inside = false
            for (rb in ringsWithBounds) {
                if (lon < rb.minLon || lon > rb.maxLon || lat < rb.minLat || lat > rb.maxLat) continue
                if (pointInRing(lon, lat, rb.ring.points)) {
                    inside = rb.clockwise
                }
            }
            return inside
        }

        // -- Squadrats (z=14) --
        val squadratRingsPrepped = prepareRings(squadratRings)
        val displayTiles = SquadratGrid.tilesInRadius(centerLat, centerLon, radiusKm)
        val now = System.currentTimeMillis()

        val collected = mutableListOf<CollectedSquadrat>()
        for (tile in displayTiles) {
            val (lon, lat) = SquadratGrid.tileCenterLonLat(tile)
            if (isCollectedTile(lon, lat, squadratRingsPrepped)) {
                collected.add(CollectedSquadrat(tile.x, tile.y, now))
            }
        }

        // Incremental merge: clear the synced region, then insert fresh data
        val xMin = displayTiles.minOf { it.x }
        val xMax = displayTiles.maxOf { it.x }
        val yMin = displayTiles.minOf { it.y }
        val yMax = displayTiles.maxOf { it.y }
        squadratDao.deleteInBounds(xMin, xMax, yMin, yMax)
        squadratDao.insertAll(collected)

        Log.d(TAG, "Sync: ${collected.size} collected out of ${displayTiles.size} z14 tiles, from ${squadratRings.size} polygon rings")

        // -- Squadratinhos (z=17) --
        var shCollectedCount: Int
        var shTotalCount: Int
        if (syncSquadratinhos && squadratinhoRings.isNotEmpty()) {
            val shRingsPrepped = prepareRings(squadratinhoRings)
            val shTiles = SquadratGrid.tilesInRadius(
                centerLat, centerLon, radiusKm, ZoomLevel.SQUADRATINHO.z
            )
            shTotalCount = shTiles.size

            val shCollected = mutableListOf<CollectedSquadratinho>()
            for (tile in shTiles) {
                val (lon, lat) = SquadratGrid.tileCenterLonLat(tile, ZoomLevel.SQUADRATINHO)
                if (isCollectedTile(lon, lat, shRingsPrepped)) {
                    shCollected.add(CollectedSquadratinho(tile.x, tile.y, now))
                }
            }
            shCollectedCount = shCollected.size

            val shXMin = shTiles.minOf { it.x }
            val shXMax = shTiles.maxOf { it.x }
            val shYMin = shTiles.minOf { it.y }
            val shYMax = shTiles.maxOf { it.y }
            squadratinhoDao.deleteInBounds(shXMin, shXMax, shYMin, shYMax)
            squadratinhoDao.insertAll(shCollected)

            Log.d(TAG, "Sync: $shCollectedCount collected out of $shTotalCount z17 tiles, from ${squadratinhoRings.size} polygon rings")
        }

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
