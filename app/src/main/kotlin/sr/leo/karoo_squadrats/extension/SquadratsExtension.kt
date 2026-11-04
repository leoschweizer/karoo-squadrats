package sr.leo.karoo_squadrats.extension

import android.util.Log
import sr.leo.karoo_squadrats.BuildConfig
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.HidePolyline
import io.hammerhead.karooext.models.MapEffect
import io.hammerhead.karooext.models.OnLocationChanged
import io.hammerhead.karooext.models.OnMapZoomLevel
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.ShowPolyline
import sr.leo.karoo_squadrats.data.Settings
import sr.leo.karoo_squadrats.data.TileRepository
import sr.leo.karoo_squadrats.data.db.SquadratsDatabase
import sr.leo.karoo_squadrats.grid.SquadratGrid
import sr.leo.karoo_squadrats.grid.ZoomLevel
import sr.leo.karoo_squadrats.map.ContourExtractor
import sr.leo.karoo_squadrats.map.PolylineEncoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

// Karoo identifies extensions by this id (see res/xml/extension_info.xml), not by Android
// package name.
class SquadratsExtension : KarooExtension("karoo-squadrats", BuildConfig.VERSION_NAME) {
    private lateinit var karooSystem: KarooSystemService
    private lateinit var settings: Settings
    private lateinit var tileRepo: TileRepository
    private var serviceJob: Job? = null
    private var rideStateJob: Job? = null

    // Live collection requires an actively recording ride - Paused is excluded, since Karoo
    // doesn't write trackpoints to the FIT file while paused.
    @Volatile
    private var isRideRecording = false

    override fun onCreate() {
        super.onCreate()
        karooSystem = KarooSystemService(this)
        settings = Settings(this)
        val db = SquadratsDatabase.getInstance(this)
        tileRepo = TileRepository(db.collectedSquadratDao(), db.collectedSquadratinhoDao())
        serviceJob = CoroutineScope(Dispatchers.IO).launch {
            karooSystem.connect()
        }
        rideStateJob = CoroutineScope(Dispatchers.IO).launch {
            observeRideState()
        }
    }

    override fun onDestroy() {
        rideStateJob?.cancel()
        rideStateJob = null
        serviceJob?.cancel()
        serviceJob = null
        karooSystem.disconnect()
        super.onDestroy()
    }

    /**
     * Tracks whether a ride is actively recording (see [isRideRecording]) - live collection only
     * runs then, since there's no FIT file as proof otherwise.
     */
    private suspend fun observeRideState() {
        karooSystem.consumerFlow<RideState>().collect { state ->
            isRideRecording = when (state) {
                is RideState.Recording -> true
                is RideState.Idle, is RideState.Paused -> false
            }
        }
    }

    /**
     * Record the tile(s) between the previous and current position as entered, if not already
     * cached as collected (see [tilesToMark]). Called on every location update, independent of
     * the redraw throttle in [startMap], so a quick tile crossing between two redraws is never
     * missed.
     *
     * Tracked only in memory (see [recentlyEnteredSquadrats]) - never written to the durable
     * cache, since a ride may never actually be uploaded to or processed by Squadrats.
     *
     * @return true if a tile was newly recorded as entered (wasn't already tracked).
     */
    private suspend fun trackEnteredTiles(
        lat: Double,
        lon: Double,
        prevLat: Double,
        prevLon: Double,
        squadratinhosEnabled: Boolean,
    ): Boolean {
        var changed = false
        for (sq in tilesToMark(lat, lon, prevLat, prevLon, ZoomLevel.SQUADRAT.z)) {
            val key = sq.toKey()
            if (key in recentlyEnteredSquadrats) continue
            if (tileRepo.collectedSquadratsInBounds(sq.x, sq.x, sq.y, sq.y).isEmpty()) {
                if (recentlyEnteredSquadrats.add(key)) {
                    changed = true
                }
            }
        }
        if (squadratinhosEnabled) {
            for (sh in tilesToMark(lat, lon, prevLat, prevLon, ZoomLevel.SQUADRATINHO.z)) {
                val key = sh.toKey()
                if (key in recentlyEnteredSquadratinhos) continue
                if (tileRepo.collectedSquadratinhosInBounds(sh.x, sh.x, sh.y, sh.y).isEmpty()) {
                    if (recentlyEnteredSquadratinhos.add(key)) {
                        changed = true
                    }
                }
            }
        }
        return changed
    }

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    override fun startMap(emitter: Emitter<MapEffect>) {
        val drawnPolylines = mutableSetOf<String>()
        var lastDrawLat = Double.NaN
        var lastDrawLon = Double.NaN
        var lastDrawZoom = Double.NaN
        // Last position live-tracking ran for (distinct from lastDraw*) - reset to NaN when
        // tracking is skipped, so a segment is never interpolated across a tracking gap.
        var lastTrackedLat = Double.NaN
        var lastTrackedLon = Double.NaN

        val job = CoroutineScope(Dispatchers.IO).launch {

            // Seed location flow with last-known position so tiles render before first GPS fix
            val locationFlow = karooSystem.consumerFlow<OnLocationChanged>()
            val centerLat = settings.getCenterLat()
            val centerLon = settings.getCenterLon()
            val seededLocationFlow = if (centerLat != 0.0 || centerLon != 0.0) {
                kotlinx.coroutines.flow.flow {
                    emit(OnLocationChanged(centerLat, centerLon, null))
                    locationFlow.collect { emit(it) }
                }
            } else {
                locationFlow
            }

            var isFirstLocation = true

            combine(
                seededLocationFlow,
                karooSystem.consumerFlow<OnMapZoomLevel>(),
            ) { location, mapZoom ->
                Pair(location, mapZoom)
            }
                // debounce(300) would starve entirely under continuous fast updates -
                // sample(300) redraws on a fixed cadence regardless of update rate.
                .sample(300)
                .collect { (location, mapZoom) ->
                    val lat = location.lat
                    val lon = location.lng
                    val zoom = mapZoom.zoomLevel

                    Log.d(TAG, "Map update: lat=$lat lon=$lon zoom=$zoom")
                    if (lat == 0.0 && lon == 0.0) return@collect

                    // The first location can be the synthetic seed above, not a real position -
                    // never treat it as a live tile entry.
                    val isSeed = isFirstLocation && (centerLat != 0.0 || centerLon != 0.0)
                    isFirstLocation = false

                    // Track tile entry on every sample(300) tick, independent of the
                    // movement/zoom redraw skip below - otherwise a tile could be crossed
                    // between redraws without being recorded. Requires an actively recording
                    // ride - see isRideRecording.
                    val trackingEnabled = settings.getLiveCollectionEnabled() && isRideRecording
                    // Rendering gate, distinct from trackingEnabled - entered tiles keep their
                    // green styling past ride-end until a clear or sync resolves them.
                    val showEnteredTiles = settings.getLiveCollectionEnabled()
                    val enteredNewTile = if (trackingEnabled && !isSeed) {
                        val result = trackEnteredTiles(lat, lon, lastTrackedLat, lastTrackedLon, settings.getSquadratinhosEnabled())
                        lastTrackedLat = lat
                        lastTrackedLon = lon
                        result
                    } else {
                        lastTrackedLat = Double.NaN
                        lastTrackedLon = Double.NaN
                        false
                    }

                    // Only redraw on zoom/movement changes, unless a tile was just entered -
                    // that needs to show immediately, not wait for the next qualifying redraw.
                    if (!enteredNewTile && !lastDrawZoom.isNaN() && zoom == lastDrawZoom) {
                        val cosLat = cos(Math.toRadians(lat)).coerceAtLeast(0.01)
                        val dLat = abs(lat - lastDrawLat)
                        val dLon = abs(lon - lastDrawLon) * cosLat
                        val threshold = REDRAW_THRESHOLD_DEG / 2.0.pow(zoom - 14.0)
                        if (dLat < threshold && dLon < threshold) return@collect
                    }

                    lastDrawLat = lat
                    lastDrawLon = lon
                    lastDrawZoom = zoom

                    val screenPx = resources.displayMetrics.let { maxOf(it.widthPixels, it.heightPixels) }

                    // --- Squadrats (z=14) ---
                    val sqTiles = SquadratGrid.tilesToRender(lat, lon, zoom, screenPx = screenPx)
                    if (sqTiles.isEmpty()) return@collect

                    val width = lineWidth(zoom)
                    val newPolylineIds = mutableSetOf<String>()

                    val sqTileKeys = sqTiles.mapTo(mutableSetOf()) { it.toKey() }
                    val sqCollected = tileRepo.collectedSquadratsInBounds(
                        sqTiles.minOf { it.x }, sqTiles.maxOf { it.x },
                        sqTiles.minOf { it.y }, sqTiles.maxOf { it.y },
                    )
                    val sqEntered = if (showEnteredTiles) recentlyEnteredSquadrats.intersect(sqTileKeys) else emptySet<Long>()
                    // Cache-only: excluding sqEntered here would let a live-tracked tile
                    // manufacture a boundary the synced cache doesn't actually back.
                    val sqUncollected = sqTileKeys - sqCollected
                    val sqGrid = ContourExtractor.extract(sqUncollected)
                    emitGridLines(sqGrid, "sq", GridStyle.SQUADRAT, width, emitter, newPolylineIds)
                    if (sqEntered.isNotEmpty()) {
                        val sqEnteredGrid = insetTileContours(sqEntered, ZoomLevel.SQUADRAT, zoom, ENTERED_INSET_PX)
                        emitGridLines(sqEnteredGrid, "sqe", GridStyle.ENTERED, width, emitter, newPolylineIds)
                    }

                    Log.d(TAG, "Squadrats: ${sqTiles.size} render tiles, ${sqCollected.size} collected, ${sqEntered.size} entered")

                    // --- Squadratinhos (z=17) ---
                    if (zoom >= ZoomLevel.SQUADRATINHO.minMapZoom && settings.getSquadratinhosEnabled()) {
                        val shTiles = SquadratGrid.tilesToRender(lat, lon, zoom, ZoomLevel.SQUADRATINHO, screenPx)
                        val shTileKeys = shTiles.mapTo(mutableSetOf()) { it.toKey() }
                        val shCollected = tileRepo.collectedSquadratinhosInBounds(
                            shTiles.minOf { it.x }, shTiles.maxOf { it.x },
                            shTiles.minOf { it.y }, shTiles.maxOf { it.y },
                        )
                        val shEntered = if (showEnteredTiles) recentlyEnteredSquadratinhos.intersect(shTileKeys) else emptySet<Long>()
                        // Only render if we have synced data or live-entered tiles here -
                        // otherwise an unsynced area would show a full orange grid.
                        if (shCollected.isNotEmpty() || shEntered.isNotEmpty()) {
                            // Cache-only, see sqUncollected above.
                            val shUncollected = shTileKeys - shCollected
                            val shGrid = ContourExtractor.extract(shUncollected, ZoomLevel.SQUADRATINHO)
                            emitGridLines(shGrid, "sh", GridStyle.SQUADRATINHO, width, emitter, newPolylineIds)
                            if (shEntered.isNotEmpty()) {
                                val shEnteredGrid = insetTileContours(shEntered, ZoomLevel.SQUADRATINHO, zoom, ENTERED_INSET_PX)
                                emitGridLines(shEnteredGrid, "she", GridStyle.ENTERED, width, emitter, newPolylineIds)
                            }
                            Log.d(TAG, "Squadratinhos: ${shTiles.size} render tiles, ${shUncollected.size} uncollected, ${shEntered.size} entered")
                        }
                    }

                    // Remove polylines that are no longer visible
                    val toRemove = drawnPolylines - newPolylineIds
                    for (id in toRemove) {
                        emitter.onNext(HidePolyline(id))
                    }

                    drawnPolylines.clear()
                    drawnPolylines.addAll(newPolylineIds)
                    Log.d(TAG, "Drew ${newPolylineIds.size} polylines, removed ${toRemove.size}")
                }
        }

        emitter.setCancellable {
            job.cancel()
            for (id in drawnPolylines) {
                emitter.onNext(HidePolyline(id))
            }
            drawnPolylines.clear()
        }
    }

    companion object {
        private const val TAG = "SquadratMap"

        // Tiles entered live during a ride - in memory only, never written to the durable
        // cache, since a ride may never actually be uploaded to or processed by Squadrats.
        private val recentlyEnteredSquadrats = Collections.newSetFromMap(ConcurrentHashMap<Long, Boolean>())
        private val recentlyEnteredSquadratinhos = Collections.newSetFromMap(ConcurrentHashMap<Long, Boolean>())

        /** Clears live-collection state to match a cleared tile cache. */
        fun clearRecentlyEntered() {
            recentlyEnteredSquadrats.clear()
            recentlyEnteredSquadratinhos.clear()
        }

        /**
         * Drops only the live-entered tiles the latest sync confirms as collected, leaving the
         * rest marked entered. A tile can come back unconfirmed because Squadrats hasn't
         * processed that ride yet, not because it wasn't actually ridden - clearing it anyway
         * would flip it back to the plain uncollected look until some later sync catches up.
         */
        suspend fun retainUnconfirmedEntered(tileRepo: TileRepository) {
            if (recentlyEnteredSquadrats.isNotEmpty()) {
                val coords = recentlyEnteredSquadrats.map { SquadratGrid.TileCoord.fromKey(it) }
                val confirmed = tileRepo.collectedSquadratsInBounds(
                    coords.minOf { it.x }, coords.maxOf { it.x },
                    coords.minOf { it.y }, coords.maxOf { it.y },
                )
                recentlyEnteredSquadrats.removeAll(confirmed)
            }
            if (recentlyEnteredSquadratinhos.isNotEmpty()) {
                val coords = recentlyEnteredSquadratinhos.map { SquadratGrid.TileCoord.fromKey(it) }
                val confirmed = tileRepo.collectedSquadratinhosInBounds(
                    coords.minOf { it.x }, coords.maxOf { it.x },
                    coords.minOf { it.y }, coords.maxOf { it.y },
                )
                recentlyEnteredSquadratinhos.removeAll(confirmed)
            }
        }

        private fun emitGridLines(
            gridLines: ContourExtractor.GridLines,
            idPrefix: String,
            style: GridStyle,
            baseWidth: Int,
            emitter: Emitter<MapEffect>,
            polylineIds: MutableSet<String>,
        ) {
            val contourWidth = style.contourWidth(baseWidth)
            val gridWidth = style.gridWidth(baseWidth)
            for ((index, contour) in gridLines.contours.withIndex()) {
                val encoded = PolylineEncoder.encode(contour.points)
                if (style.shadeColor != null) {
                    val shadeId = "${idPrefix}_s_$index"
                    polylineIds.add(shadeId)
                    emitter.onNext(ShowPolyline(
                        id = shadeId,
                        encodedPolyline = encoded,
                        color = style.shadeColor,
                        width = contourWidth + style.shadeExtraWidth,
                    ))
                }
                val contourId = "${idPrefix}_c_$index"
                polylineIds.add(contourId)
                emitter.onNext(ShowPolyline(
                    id = contourId,
                    encodedPolyline = encoded,
                    color = style.contourColor,
                    width = contourWidth,
                ))
            }
            for ((index, edge) in gridLines.innerEdges.withIndex()) {
                val encoded = PolylineEncoder.encode(edge.points)
                val gridId = "${idPrefix}_g_$index"
                polylineIds.add(gridId)
                emitter.onNext(ShowPolyline(
                    id = gridId,
                    encodedPolyline = encoded,
                    color = style.gridColor,
                    width = gridWidth,
                ))
            }
        }

        // Target screen-pixel gap between an entered tile's inset box and its neighbor's
        // boundary line, kept constant in screen pixels so the two lines stay just barely
        // separated at any zoom level.
        const val ENTERED_INSET_PX = 3.0

        /**
         * Draws each entered tile as its own inset box (rather than one merged contour) so
         * adjacent entered/uncollected tiles stay visually distinct. [mapZoom] converts the
         * fixed pixel inset into a lat/lon fraction so the on-screen gap stays roughly
         * constant at any zoom.
         */
        private fun insetTileContours(
            tileKeys: Set<Long>,
            level: ZoomLevel,
            mapZoom: Double,
            insetPx: Double,
        ): ContourExtractor.GridLines {
            val pixelsPerTile = 256.0 * 2.0.pow(mapZoom - level.z)
            val insetFraction = (insetPx / pixelsPerTile).coerceIn(0.0, 0.4)
            val contours = tileKeys.map { key ->
                val bounds = SquadratGrid.tileBounds(SquadratGrid.TileCoord.fromKey(key), level)
                val latInset = (bounds.latMax - bounds.latMin) * insetFraction
                val lonInset = (bounds.lonMax - bounds.lonMin) * insetFraction
                val latMin = bounds.latMin + latInset
                val latMax = bounds.latMax - latInset
                val lonMin = bounds.lonMin + lonInset
                val lonMax = bounds.lonMax - lonInset
                ContourExtractor.Contour(listOf(
                    latMin to lonMin,
                    latMax to lonMin,
                    latMax to lonMax,
                    latMin to lonMax,
                    latMin to lonMin,
                ))
            }
            return ContourExtractor.GridLines(contours, emptyList())
        }

        // ~0.5 tile widths at z=14 - triggers redraw when GPS moves ~25% of the scroll buffer
        const val REDRAW_THRESHOLD_DEG = 0.012

        /** Adaptive line width: bolder at higher zoom levels where tiles are larger on screen. */
        fun lineWidth(zoom: Double): Int = when {
            zoom < 12 -> 2
            zoom < 14 -> 3
            zoom < 15 -> 4
            else -> 5
        }

        /**
         * Tiles entered at this location update: the current tile, plus - when the previous
         * tracked position is chebyshev-adjacent (distance <= 1) - any tile the straight segment
         * between them crosses, to catch a tile only clipped at a corner. Beyond that distance
         * the gap isn't trusted, since assuming a straight path across it produced false
         * positives when cross-checked against squadrats.com.
         *
         * [prevLat]/[prevLon] are NaN for the first tracked location of a ride.
         */
        fun tilesToMark(
            lat: Double,
            lon: Double,
            prevLat: Double,
            prevLon: Double,
            zoom: Int,
        ): Set<SquadratGrid.TileCoord> {
            val current = SquadratGrid.lonLatToTile(lon, lat, zoom)
            if (prevLat.isNaN() || prevLon.isNaN()) return setOf(current)

            val prev = SquadratGrid.lonLatToTile(prevLon, prevLat, zoom)
            val chebyshevDistance = maxOf(abs(current.x - prev.x), abs(current.y - prev.y))
            if (chebyshevDistance > 1) return setOf(current)

            val (x0, y0) = SquadratGrid.lonLatToTileFraction(prevLon, prevLat, zoom)
            val (x1, y1) = SquadratGrid.lonLatToTileFraction(lon, lat, zoom)
            return SquadratGrid.tilesAlongSegment(x0, y0, x1, y1).toSet()
        }
    }
}
