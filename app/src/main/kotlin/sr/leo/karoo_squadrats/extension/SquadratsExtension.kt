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
import sr.leo.karoo_squadrats.data.SquadratsPreferences
import sr.leo.karoo_squadrats.data.TileRepository
import sr.leo.karoo_squadrats.grid.SquadratGrid
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
    private lateinit var prefs: SquadratsPreferences
    private lateinit var tileRepo: TileRepository
    private var serviceJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        karooSystem = KarooSystemService(this)
        prefs = SquadratsPreferences(this)
        tileRepo = TileRepository(prefs)
        tileRepo.loadCachedTiles()
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
            tileRepo.loadCachedTiles()

            // Seed location flow with last-known position so tiles render before first GPS fix
            val locationFlow = karooSystem.consumerFlow<OnLocationChanged>()
            val seededLocationFlow = if (prefs.centerLat != 0.0 || prefs.centerLon != 0.0) {
                kotlinx.coroutines.flow.flow {
                    emit(OnLocationChanged(prefs.centerLat, prefs.centerLon, null))
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
                    Log.d(TAG, "Redraw: ${visibleTiles.size} visible tiles, ${tileRepo.collectedCount} collected")
                    val newPolylineIds = mutableSetOf<String>()

                    // Draw outlines for uncollected tiles
                    for (tile in visibleTiles) {
                        if (tileRepo.isCollected(tile)) continue

                        val id = "sq_${tile.x}_${tile.y}"
                        newPolylineIds.add(id)

                        if (!drawnPolylines.contains(id)) {
                            val bounds = SquadratGrid.tileBounds(tile)
                            val encoded = PolylineEncoder.encodeSquare(bounds)
                            emitter.onNext(
                                ShowPolyline(
                                    id = id,
                                    encodedPolyline = encoded,
                                    color = OVERLAY_COLOR,
                                    width = OVERLAY_WIDTH,
                                ),
                            )
                        }
                    }

                    // Remove polylines that are no longer visible
                    val toRemove = drawnPolylines - newPolylineIds
                    for (id in toRemove) {
                        emitter.onNext(HidePolyline(id))
                    }

                    drawnPolylines.clear()
                    drawnPolylines.addAll(newPolylineIds)
                    Log.d(TAG, "Drew ${newPolylineIds.size} uncollected, removed ${toRemove.size}")
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
        // Semi-transparent purple (Squadrats brand) for uncollected tile borders
        const val OVERLAY_COLOR = 0x80663399.toInt()
        const val OVERLAY_WIDTH = 3
        // ~0.5 tile widths at z=14 - triggers redraw when GPS moves ~25% of the 3x buffer
        const val REDRAW_THRESHOLD_DEG = 0.012
    }
}
