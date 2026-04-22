package sr.leo.karoo_squadrats.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "squadrats_settings")

class Settings(context: Context) {
    private val dataStore = context.dataStore

    suspend fun getUserToken(): String =
        dataStore.data.first()[USER_TOKEN] ?: ""

    suspend fun setUserToken(value: String) {
        dataStore.edit { it[USER_TOKEN] = value }
    }

    suspend fun getTileTimestamp(): String =
        dataStore.data.first()[TILE_TIMESTAMP] ?: ""

    suspend fun setTileTimestamp(value: String) {
        dataStore.edit { it[TILE_TIMESTAMP] = value }
    }

    suspend fun getCenterLat(): Double =
        dataStore.data.first()[CENTER_LAT] ?: 0.0

    suspend fun setCenterLat(value: Double) {
        dataStore.edit { it[CENTER_LAT] = value }
    }

    suspend fun getCenterLon(): Double =
        dataStore.data.first()[CENTER_LON] ?: 0.0

    suspend fun setCenterLon(value: Double) {
        dataStore.edit { it[CENTER_LON] = value }
    }

    suspend fun getSyncRadiusKm(): Int =
        dataStore.data.first()[SYNC_RADIUS] ?: 30

    suspend fun setSyncRadiusKm(value: Int) {
        dataStore.edit { it[SYNC_RADIUS] = value }
    }

    suspend fun getSquadratinhosEnabled(): Boolean =
        dataStore.data.first()[SQUADRATINHOS_ENABLED] ?: false

    suspend fun setSquadratinhosEnabled(value: Boolean) {
        dataStore.edit { it[SQUADRATINHOS_ENABLED] = value }
    }

    suspend fun getTileUrlTemplate(): String {
        val data = dataStore.data.first()
        val token = data[USER_TOKEN] ?: ""
        val ts = data[TILE_TIMESTAMP] ?: ""
        if (token.isEmpty() || ts.isEmpty()) return ""
        return "https://tiles-beta.squadrats.com/$token/trophies-earth/$ts/{z}/{x}/{y}.pbf"
    }

    companion object {
        private val USER_TOKEN = stringPreferencesKey("user_token")
        private val TILE_TIMESTAMP = stringPreferencesKey("tile_timestamp")
        private val CENTER_LAT = doublePreferencesKey("center_lat")
        private val CENTER_LON = doublePreferencesKey("center_lon")
        private val SYNC_RADIUS = intPreferencesKey("sync_radius_km")
        private val SQUADRATINHOS_ENABLED = booleanPreferencesKey("squadratinhos_enabled")
    }
}
