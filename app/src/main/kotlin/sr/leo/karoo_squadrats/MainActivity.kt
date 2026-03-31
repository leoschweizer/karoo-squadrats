package sr.leo.karoo_squadrats

import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.OnLocationChanged
import sr.leo.karoo_squadrats.data.SquadratsPreferences
import sr.leo.karoo_squadrats.data.TileRepository

class MainActivity : AppCompatActivity() {
    private lateinit var prefs: SquadratsPreferences
    private lateinit var tileRepo: TileRepository
    private val karooSystem by lazy { KarooSystemService(this) }
    private var locationConsumerId: String? = null

    private lateinit var editToken: EditText
    private lateinit var editTimestamp: EditText
    private lateinit var editCenterLat: EditText
    private lateinit var editCenterLon: EditText
    private lateinit var editSyncRadius: EditText
    private lateinit var btnSync: Button
    private lateinit var btnUseLocation: Button
    private lateinit var progressSync: ProgressBar
    private lateinit var txtSyncStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = SquadratsPreferences(this)
        tileRepo = TileRepository(prefs)

        editToken = findViewById(R.id.editToken)
        editTimestamp = findViewById(R.id.editTimestamp)
        editCenterLat = findViewById(R.id.editCenterLat)
        editCenterLon = findViewById(R.id.editCenterLon)
        editSyncRadius = findViewById(R.id.editSyncRadius)
        btnSync = findViewById(R.id.btnSync)
        btnUseLocation = findViewById(R.id.btnUseLocation)
        progressSync = findViewById(R.id.progressSync)
        txtSyncStatus = findViewById(R.id.txtSyncStatus)

        // Load saved values
        editToken.setText(prefs.userToken)
        editTimestamp.setText(prefs.tileTimestamp)
        if (prefs.centerLat != 0.0) {
            editCenterLat.setText(String.format("%.6f", prefs.centerLat))
        }
        if (prefs.centerLon != 0.0) {
            editCenterLon.setText(String.format("%.6f", prefs.centerLon))
        }
        editSyncRadius.setText(prefs.syncRadiusKm.toString())

        // Show cached tile count
        tileRepo.loadCachedTiles()
        if (tileRepo.collectedCount > 0) {
            txtSyncStatus.text = getString(R.string.cached_collected_tiles, tileRepo.collectedCount)
        }

        btnUseLocation.setOnClickListener { fetchCurrentLocation() }
        btnSync.setOnClickListener { startSync() }
    }

    override fun onStart() {
        super.onStart()
        karooSystem.connect()
    }

    // Dismiss the soft keyboard when the user taps outside an EditText
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) {
            val v = currentFocus
            if (v is EditText) {
                val outRect = android.graphics.Rect()
                v.getGlobalVisibleRect(outRect)
                if (!outRect.contains(ev.rawX.toInt(), ev.rawY.toInt())) {
                    v.clearFocus()
                    val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.hideSoftInputFromWindow(v.windowToken, 0)
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onStop() {
        locationConsumerId?.let { karooSystem.removeConsumer(it) }
        locationConsumerId = null
        karooSystem.disconnect()
        super.onStop()
    }

    private fun fetchCurrentLocation() {
        btnUseLocation.isEnabled = false
        btnUseLocation.text = getString(R.string.waiting_for_gps)

        var received = false
        locationConsumerId = karooSystem.addConsumer<OnLocationChanged> { event ->
            if (!received) {
                received = true
                runOnUiThread {
                    editCenterLat.setText(String.format("%.6f", event.lat))
                    editCenterLon.setText(String.format("%.6f", event.lng))
                    btnUseLocation.isEnabled = true
                    btnUseLocation.text = getString(R.string.btn_use_location)
                }
            }
        }

        // Timeout: restore button after 10 seconds if no location received
        btnUseLocation.postDelayed({
            if (!received) {
                btnUseLocation.isEnabled = true
                btnUseLocation.text = getString(R.string.btn_use_location)
                Toast.makeText(this, "No GPS fix. Enter coordinates manually.", Toast.LENGTH_SHORT).show()
            }
            locationConsumerId?.let { karooSystem.removeConsumer(it) }
            locationConsumerId = null
        }, 10000)
    }

    private fun startSync() {
        val token = editToken.text.toString().trim()
        val timestamp = editTimestamp.text.toString().trim()
        if (token.isEmpty()) {
            Toast.makeText(this, "Enter your user token", Toast.LENGTH_SHORT).show()
            return
        }
        if (timestamp.isEmpty()) {
            Toast.makeText(this, "Enter the timestamp", Toast.LENGTH_SHORT).show()
            return
        }

        val lat = editCenterLat.text.toString().toDoubleOrNull()
        val lon = editCenterLon.text.toString().toDoubleOrNull()
        if (lat == null || lon == null) {
            Toast.makeText(this, "Enter valid center coordinates", Toast.LENGTH_SHORT).show()
            return
        }

        if (lat < -90 || lat > 90 || lon < -180 || lon > 180) {
            Toast.makeText(this, "Coordinates out of range", Toast.LENGTH_SHORT).show()
            return
        }

        val radiusKm = editSyncRadius.text.toString().toIntOrNull() ?: 30
        if (radiusKm !in 1..200) {
            Toast.makeText(this, "Radius must be between 1 and 200 km", Toast.LENGTH_SHORT).show()
            return
        }

        // Save settings
        prefs.userToken = token
        prefs.tileTimestamp = timestamp
        prefs.centerLat = lat
        prefs.centerLon = lon
        prefs.syncRadiusKm = radiusKm

        // Start sync on background thread
        btnSync.isEnabled = false
        progressSync.visibility = View.VISIBLE
        progressSync.progress = 0

        Thread {
            tileRepo.sync(
                lat,
                lon,
                radiusKm.toDouble(),
                object : TileRepository.SyncCallback {
                    override fun onProgress(fetched: Int, total: Int) {
                        runOnUiThread {
                            progressSync.max = total
                            progressSync.progress = fetched
                            txtSyncStatus.text = getString(R.string.sync_status_running, fetched, total)
                        }
                    }

                    override fun onComplete(collected: Int, total: Int) {
                        runOnUiThread {
                            progressSync.visibility = View.GONE
                            btnSync.isEnabled = true
                            txtSyncStatus.text = getString(R.string.sync_status_done, collected, total)
                        }
                    }

                    override fun onError(message: String) {
                        runOnUiThread {
                            progressSync.visibility = View.GONE
                            btnSync.isEnabled = true
                            txtSyncStatus.text = getString(R.string.sync_status_error, message)
                        }
                    }
                },
            )
        }.start()
    }
}
