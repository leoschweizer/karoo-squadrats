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
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow

class SquadratsExtension : KarooExtension("karoo-squadrats", BuildConfig.VERSION_NAME) {
    private lateinit var karooSystem: KarooSystemService
    private lateinit var settings: Settings
    private lateinit var tileRepo: TileRepository
    private var serviceJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        karooSystem = KarooSystemService(this)
        settings = Settings(this)
        val db = SquadratsDatabase.getInstance(this)
        tileRepo = TileRepository(db.collectedSquadratDao(), db.collectedSquadratinhoDao())
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

                    val screenPx = resources.displayMetrics.let { maxOf(it.widthPixels, it.heightPixels) }

                    // --- Squadrats (z=14) ---
                    val sqTiles = SquadratGrid.tilesToRender(lat, lon, zoom, screenPx = screenPx)
                    if (sqTiles.isEmpty()) return@collect

                    val width = lineWidth(zoom)
                    val newPolylineIds = mutableSetOf<String>()

                    val sqCollected = tileRepo.collectedSquadratsInBounds(
                        sqTiles.minOf { it.x }, sqTiles.maxOf { it.x },
                        sqTiles.minOf { it.y }, sqTiles.maxOf { it.y },
                    )
                    val sqUncollected = sqTiles.mapTo(mutableSetOf()) { it.toKey() } - sqCollected
                    val sqGrid = ContourExtractor.extract(sqUncollected)
                    emitGridLines(sqGrid, "sq", GridStyle.SQUADRAT, width, emitter, newPolylineIds)

                    Log.d(TAG, "Squadrats: ${sqTiles.size} render tiles, ${sqCollected.size} collected")

                    // --- Squadratinhos (z=17) ---
                    if (zoom >= ZoomLevel.SQUADRATINHO.minMapZoom && settings.getSquadratinhosEnabled()) {
                        val shTiles = SquadratGrid.tilesToRender(lat, lon, zoom, ZoomLevel.SQUADRATINHO, screenPx)
                        val shCollected = tileRepo.collectedSquadratinhosInBounds(
                            shTiles.minOf { it.x }, shTiles.maxOf { it.x },
                            shTiles.minOf { it.y }, shTiles.maxOf { it.y },
                        )
                        // Only render if we have synced squadratinho data in this area
                        if (shCollected.isNotEmpty()) {
                            val shUncollected = shTiles.mapTo(mutableSetOf()) { it.toKey() } - shCollected
                            val shGrid = ContourExtractor.extract(shUncollected, ZoomLevel.SQUADRATINHO)
                            emitGridLines(shGrid, "sh", GridStyle.SQUADRATINHO, width, emitter, newPolylineIds)
                            Log.d(TAG, "Squadratinhos: ${shTiles.size} render tiles, ${shUncollected.size} uncollected")
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

        // ~0.5 tile widths at z=14 - triggers redraw when GPS moves ~25% of the scroll buffer
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
