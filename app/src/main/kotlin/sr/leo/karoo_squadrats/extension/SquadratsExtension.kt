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
import io.hammerhead.karooext.models.ShowPolyline
import sr.leo.karoo_squadrats.data.SquadratsSettings
import sr.leo.karoo_squadrats.data.TileRepository
import sr.leo.karoo_squadrats.data.db.SquadratsDatabase
import sr.leo.karoo_squadrats.grid.SquadratGrid
import sr.leo.karoo_squadrats.map.ContourExtractor
import sr.leo.karoo_squadrats.map.PolylineEncoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow

class SquadratsExtension : KarooExtension("karoo-squadrats", BuildConfig.VERSION_NAME) {
    private lateinit var karooSystem: KarooSystemService
    private lateinit var settings: SquadratsSettings
    private lateinit var tileRepo: TileRepository
    private var serviceJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        karooSystem = KarooSystemService(this)
        settings = SquadratsSettings(this)
        val db = SquadratsDatabase.getInstance(this)
        tileRepo = TileRepository(db.collectedSquadratDao())
        serviceJob = CoroutineScope(Dispatchers.IO).launch {
            karooSystem.connect()
        }
    }

    override fun onDestroy() {
        serviceJob?.cancel()
        serviceJob = null
        karooSystem.disconnect()
        super.onDestroy()
    }

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    override fun startMap(emitter: Emitter<MapEffect>) {
        val drawnPolylines = mutableSetOf<String>()
        var lastDrawLat = Double.NaN
        var lastDrawLon = Double.NaN
        var lastDrawZoom = Double.NaN

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

            combine(
                seededLocationFlow,
                karooSystem.consumerFlow<OnMapZoomLevel>(),
            ) { location, mapZoom ->
                Pair(location, mapZoom)
            }
                .debounce(300)
                .collect { (location, mapZoom) ->
                    val lat = location.lat
                    val lon = location.lng
                    val zoom = mapZoom.zoomLevel

                    Log.d(TAG, "Map update: lat=$lat lon=$lon zoom=$zoom")
                    if (lat == 0.0 && lon == 0.0) return@collect

                    // Only redraw when zoom changed or GPS moved enough (~25% of visible area)
                    if (!lastDrawZoom.isNaN() && zoom == lastDrawZoom) {
                        val cosLat = cos(Math.toRadians(lat)).coerceAtLeast(0.01)
                        val dLat = abs(lat - lastDrawLat)
                        val dLon = abs(lon - lastDrawLon) * cosLat
                        val threshold = REDRAW_THRESHOLD_DEG / 2.0.pow(zoom - 14.0)
                        if (dLat < threshold && dLon < threshold) return@collect
                    }

                    lastDrawLat = lat
                    lastDrawLon = lon
                    lastDrawZoom = zoom

                    val visibleTiles = SquadratGrid.visibleTiles(lat, lon, zoom)
                    if (visibleTiles.isEmpty()) return@collect

                    // Query collected tiles within the visible bounding box
                    val xMin = visibleTiles.minOf { it.x }
                    val xMax = visibleTiles.maxOf { it.x }
                    val yMin = visibleTiles.minOf { it.y }
                    val yMax = visibleTiles.maxOf { it.y }
                    val collectedKeys = tileRepo.collectedInBounds(xMin, xMax, yMin, yMax)
                    Log.d(TAG, "Redraw: ${visibleTiles.size} visible tiles, ${collectedKeys.size} collected in bounds")

                    // Collect uncollected tile keys within visible area
                    val uncollectedKeys = mutableSetOf<Long>()
                    for (tile in visibleTiles) {
                        val key = tile.toKey()
                        if (!collectedKeys.contains(key)) {
                            uncollectedKeys.add(key)
                        }
                    }

                    // Extract contour polylines around uncollected regions
                    val gridLines = ContourExtractor.extract(uncollectedKeys)
                    val width = lineWidth(zoom)
                    val newPolylineIds = mutableSetOf<String>()

                    for ((index, contour) in gridLines.contours.withIndex()) {
                        val encoded = PolylineEncoder.encode(contour.points)

                        // Shade line (wider, semi-transparent halo)
                        val shadeId = "sq_s_$index"
                        newPolylineIds.add(shadeId)
                        emitter.onNext(
                            ShowPolyline(
                                id = shadeId,
                                encodedPolyline = encoded,
                                color = SHADE_COLOR,
                                width = width + SHADE_EXTRA_WIDTH,
                            ),
                        )

                        // Main contour line
                        val contourId = "sq_c_$index"
                        newPolylineIds.add(contourId)
                        emitter.onNext(
                            ShowPolyline(
                                id = contourId,
                                encodedPolyline = encoded,
                                color = OVERLAY_COLOR,
                                width = width,
                            ),
                        )
                    }

                    // Internal grid lines between adjacent uncollected tiles
                    for ((index, edge) in gridLines.innerEdges.withIndex()) {
                        val encoded = PolylineEncoder.encode(edge.points)
                        val gridId = "sq_g_$index"
                        newPolylineIds.add(gridId)
                        emitter.onNext(
                            ShowPolyline(
                                id = gridId,
                                encodedPolyline = encoded,
                                color = GRID_COLOR,
                                width = width,
                            ),
                        )
                    }

                    // Remove polylines that are no longer visible
                    val toRemove = drawnPolylines - newPolylineIds
                    for (id in toRemove) {
                        emitter.onNext(HidePolyline(id))
                    }

                    drawnPolylines.clear()
                    drawnPolylines.addAll(newPolylineIds)
                    Log.d(TAG, "Drew ${gridLines.contours.size} contours, ${gridLines.innerEdges.size} grid lines (${newPolylineIds.size} polylines), removed ${toRemove.size}")
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
        // Semi-transparent purple (Squadrats brand) for contour lines
        const val OVERLAY_COLOR = 0x80663399.toInt()
        // Lighter grid lines for internal tile boundaries
        const val GRID_COLOR = 0x40663399
        // Subtle halo around contour lines to highlight boundaries (especially collected holes)
        const val SHADE_COLOR = 0x33663399
        const val SHADE_EXTRA_WIDTH = 6
        // ~0.5 tile widths at z=14 - triggers redraw when GPS moves ~25% of the 3x buffer
        const val REDRAW_THRESHOLD_DEG = 0.012

        /** Adaptive line width: bolder at higher zoom levels where tiles are larger on screen. */
        fun lineWidth(zoom: Double): Int = when {
            zoom < 12 -> 2
            zoom < 14 -> 3
            zoom < 15 -> 4
            else -> 5
        }
    }
}
