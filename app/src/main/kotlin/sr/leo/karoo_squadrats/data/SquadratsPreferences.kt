package sr.leo.karoo_squadrats.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

class SquadratsPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("squadrats_prefs", Context.MODE_PRIVATE)

    var userToken: String
        get() = prefs.getString(KEY_USER_TOKEN, "") ?: ""
        set(value) = prefs.edit { putString(KEY_USER_TOKEN, value) }

    var tileTimestamp: String
        get() = prefs.getString(KEY_TILE_TIMESTAMP, "") ?: ""
        set(value) = prefs.edit { putString(KEY_TILE_TIMESTAMP, value) }

    val tileUrlTemplate: String
        get() {
            val token = userToken
            val ts = tileTimestamp
            if (token.isEmpty() || ts.isEmpty()) return ""
            return "https://tiles2.squadrats.com/$token/trophies/$ts/{z}/{x}/{y}.pbf"
        }

    var centerLat: Double
        get() = java.lang.Double.longBitsToDouble(
            prefs.getLong(KEY_CENTER_LAT, java.lang.Double.doubleToLongBits(0.0)),
        )
        set(value) = prefs.edit {
            putLong(
                KEY_CENTER_LAT,
                java.lang.Double.doubleToLongBits(value)
            )
        }

    var centerLon: Double
        get() = java.lang.Double.longBitsToDouble(
            prefs.getLong(KEY_CENTER_LON, java.lang.Double.doubleToLongBits(0.0)),
        )
        set(value) = prefs.edit {
            putLong(
                KEY_CENTER_LON,
                java.lang.Double.doubleToLongBits(value)
            )
        }

    var syncRadiusKm: Int
        get() = prefs.getInt(KEY_SYNC_RADIUS, 30)
        set(value) = prefs.edit { putInt(KEY_SYNC_RADIUS, value) }

    fun saveCollectedTiles(tiles: Set<Long>) {
        // Store as comma-separated tile keys
        val csv = tiles.joinToString(",")
        prefs.edit { putString(KEY_COLLECTED_TILES, csv) }
    }

    fun loadCollectedTiles(): Set<Long> {
        val csv = prefs.getString(KEY_COLLECTED_TILES, "") ?: ""
        if (csv.isEmpty()) return emptySet()
        return csv.split(",").mapNotNull { it.toLongOrNull() }.toHashSet()
    }

    companion object {
        private const val KEY_USER_TOKEN = "user_token"
        private const val KEY_TILE_TIMESTAMP = "tile_timestamp"
        private const val KEY_CENTER_LAT = "center_lat"
        private const val KEY_CENTER_LON = "center_lon"
        private const val KEY_SYNC_RADIUS = "sync_radius_km"
        private const val KEY_COLLECTED_TILES = "collected_tiles"
    }
}
