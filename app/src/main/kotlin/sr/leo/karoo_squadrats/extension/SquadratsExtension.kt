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

class SquadratsExtension : KarooExtension(BuildConfig.EXTENSION_ID, BuildConfig.VERSION_NAME) {
    private lateinit var karooSystem: KarooSystemService
    private lateinit var settings: Settings
    private lateinit var tileRepo: TileRepository
    private var serviceJob: Job? = null
    private var rideStateJob: Job? = null

    // Live collection requires an active ride recording (Recording/Paused) - without one
    // there's no FIT file to serve as proof of the tiles actually being ridden.
    @Volatile
    private var isRideActive = false

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
            observeRideCompletion()
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
     * Tracks whether a ride is actively recording (see [isRideActive]) - live collection only
     * runs then, since there's no FIT file as proof otherwise.
     */
    private suspend fun observeRideCompletion() {
        karooSystem.consumerFlow<RideState>().collect { state ->
            isRideActive = when (state) {
                is RideState.Recording, is RideState.Paused -> true
                is RideState.Idle -> false
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
            if (tileRepo.collectedSquadratsInBounds(sq.x, sq.x, sq.y, sq.y).isEmpty()) {
                if (recentlyEnteredSquadrats.add(sq.toKey())) {
                    changed = true
                }
            }
        }
        if (squadratinhosEnabled) {
            for (sh in tilesToMark(lat, lon, prevLat, prevLon, ZoomLevel.SQUADRATINHO.z)) {
                if (tileRepo.collectedSquadratinhosInBounds(sh.x, sh.x, sh.y, sh.y).isEmpty()) {
                    if (recentlyEnteredSquadratinhos.add(sh.toKey())) {
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
        // Last position live-tracking actually ran for (distinct from lastDraw*, which is
        // gated by the redraw throttle below) - reset to NaN whenever live-tracking is skipped
        // for a tick, so a segment is never interpolated across a gap in live tracking itself
        // (e.g. across two separate rides, or while the toggle/RideState gate was off).
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
                // debounce(300) would never fire while updates arrive faster than every 300ms,
                // starving redraws entirely - sample(300) instead redraws on a fixed cadence no
                // matter how fast updates arrive. This matters for fast-forwarded ride replay:
                // this feature was tested using the "Karoo Ride Replay" extension
                // (https://github.com/lgaitano/karoo-ride-replay), which can mock GPS updates
                // faster than real-time.
                .sample(300)
                .collect { (location, mapZoom) ->
                    val lat = location.lat
                    val lon = location.lng
                    val zoom = mapZoom.zoomLevel

                    Log.d(TAG, "Map update: lat=$lat lon=$lon zoom=$zoom")
                    if (lat == 0.0 && lon == 0.0) return@collect

                    // The very first location can be the synthetic seed above (rendered so
                    // tiles show before a GPS fix) rather than a real position - never treat
                    // that one as a live tile entry.
                    val isSeed = isFirstLocation && (centerLat != 0.0 || centerLon != 0.0)
                    isFirstLocation = false

                    // Track tile entry on every location update, independent of the redraw
                    // throttle below - otherwise a tile can be crossed between two redraws
                    // (e.g. during fast playback) without ever being recorded as entered.
                    // Requires an active ride recording - see isRideActive.
                    val liveCollectionEnabled = settings.getLiveCollectionEnabled() && isRideActive
                    val enteredNewTile = if (liveCollectionEnabled && !isSeed) {
                        val result = trackEnteredTiles(lat, lon, lastTrackedLat, lastTrackedLon, settings.getSquadratinhosEnabled())
                        lastTrackedLat = lat
                        lastTrackedLon = lon
                        result
                    } else {
                        lastTrackedLat = Double.NaN
                        lastTrackedLon = Double.NaN
                        false
                    }

                    // Only redraw when zoom changed or GPS moved enough (~25% of visible area) -
                    // unless a tile was just newly entered, which needs to flip to "entered"
                    // styling immediately rather than waiting for the next qualifying redraw.
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
                    val sqEntered = if (liveCollectionEnabled) recentlyEnteredSquadrats.intersect(sqTileKeys) else emptySet<Long>()
                    val sqUncollected = sqTileKeys - sqCollected - sqEntered
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
                        // Only render if we have synced squadratinho data in this area
                        if (shCollected.isNotEmpty()) {
                            val shEntered = if (liveCollectionEnabled) recentlyEnteredSquadratinhos.intersect(shTileKeys) else emptySet<Long>()
                            val shUncollected = shTileKeys - shCollected - shEntered
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

        // Tiles entered live during a ride, tracked only in memory (see trackEnteredTiles) -
        // never written to the durable cache, since a ride may never actually be uploaded to or
        // processed by Squadrats.
        private val recentlyEnteredSquadrats = Collections.newSetFromMap(ConcurrentHashMap<Long, Boolean>())
        private val recentlyEnteredSquadratinhos = Collections.newSetFromMap(ConcurrentHashMap<Long, Boolean>())

        /** Clears live-collection state to match a cleared tile cache. */
        fun clearRecentlyEntered() {
            recentlyEnteredSquadrats.clear()
            recentlyEnteredSquadratinhos.clear()
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
         * Draws each entered tile as its own inset box rather than one contour around the
         * merged entered region ([ContourExtractor.extract]'s usual behavior). Two adjacent
         * entered tiles previously shared an invisible boundary (contour merges them into one
         * blob) - and a shared edge with a still-uncollected neighbor rendered as a single line
         * coincident with that neighbor's own boundary, making it hard to tell apart at a
         * glance whether it's worth riding further that way. Shrinking each box toward its own
         * tile center leaves a thin gap on every side, so a neighbor - entered or not - is
         * always visually distinct, while the two lines still read as touching.
         *
         * [mapZoom] is needed (rather than a fixed fraction of tile size) because a tile's
         * on-screen size varies hugely with zoom - converting a fixed pixel target into a
         * lat/lon fraction here keeps the on-screen gap roughly constant at any zoom.
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
         * Tiles to consider entered at the given zoom level for this location update: always the
         * tile containing the current position, plus - when the previous tracked position's tile
         * is chebyshev-adjacent (distance <= 1) to it - any tile the straight path between the
         * two fixes passes through as well. This catches a tile only clipped at a corner between
         * two GPS fixes (e.g. entering diagonally), probably matching squadrats.com's own
         * classification. This has been evaluated by checking a lot of real live observations
         * compared to squadrats.com interpretation.
         *
         * Beyond chebyshev distance 1, the "in-between" tiles a straight line would cross are not
         * trusted: they'd require assuming the real path ran perfectly straight across the entire
         * real-world gap between two fixes (e.g. a GPS dropout or sparse recording on a straight
         * stretch), which showed false additions when cross-checked against squadrats.com.
         *
         * [prevLat]/[prevLon] are NaN when there is no adjacent previous fix to interpolate from
         * (first live-tracked location of a ride).
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
