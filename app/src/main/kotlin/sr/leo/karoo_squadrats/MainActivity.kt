package sr.leo.karoo_squadrats

import android.annotation.SuppressLint
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
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.tabs.TabLayout
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.OnLocationChanged
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import sr.leo.karoo_squadrats.data.CoordinateFormat
import sr.leo.karoo_squadrats.data.Settings
import sr.leo.karoo_squadrats.data.TileRepository
import sr.leo.karoo_squadrats.data.db.SquadratsDatabase

@SuppressLint("DefaultLocale", "SetTextI18n")
class MainActivity : AppCompatActivity() {
    private lateinit var settings: Settings
    private lateinit var tileRepo: TileRepository
    private lateinit var db: SquadratsDatabase
    private val karooSystem by lazy { KarooSystemService(this) }
    private var locationConsumerId: String? = null
    private var syncJob: Job? = null

    private lateinit var editToken: EditText
    private lateinit var editTimestamp: EditText
    private lateinit var editCenterLat: EditText
    private lateinit var editCenterLon: EditText
    private lateinit var editSyncRadius: EditText
    private lateinit var btnSync: Button
    private lateinit var btnUseLocation: Button
    private lateinit var progressSync: ProgressBar
    private lateinit var txtSyncStatus: TextView
    private lateinit var txtSquadratCount: TextView
    private lateinit var txtSquadratSync: TextView
    private lateinit var txtSquadratinhoCount: TextView
    private lateinit var txtSquadratinhoSync: TextView
    private lateinit var tabSettings: View
    private lateinit var tabCache: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        settings = Settings(this)
        db = SquadratsDatabase.getInstance(this)
        tileRepo = TileRepository(db.collectedSquadratDao(), db.collectedSquadratinhoDao())

        // Tabs: Settings (0), Cache (1)
        val tabLayout = findViewById<TabLayout>(R.id.tabLayout)
        tabSettings = findViewById(R.id.tabSettings)
        tabCache = findViewById(R.id.tabCache)
        tabLayout.addTab(tabLayout.newTab().setText(R.string.tab_settings))
        tabLayout.addTab(tabLayout.newTab().setText(R.string.tab_cache))
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                tabSettings.visibility = if (tab.position == 0) View.VISIBLE else View.GONE
                tabCache.visibility = if (tab.position == 1) View.VISIBLE else View.GONE
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        // Settings tab: credentials + display
        editToken = findViewById(R.id.editToken)
        editTimestamp = findViewById(R.id.editTimestamp)
        val switchSquadratinhos = findViewById<SwitchMaterial>(R.id.switchSquadratinhos)

        // Cache tab
        editCenterLat = findViewById(R.id.editCenterLat)
        editCenterLon = findViewById(R.id.editCenterLon)
        editSyncRadius = findViewById(R.id.editSyncRadius)
        btnSync = findViewById(R.id.btnSync)
        btnUseLocation = findViewById(R.id.btnUseLocation)
        progressSync = findViewById(R.id.progressSync)
        txtSyncStatus = findViewById(R.id.txtSyncStatus)
        txtSquadratCount = findViewById(R.id.txtSquadratCount)
        txtSquadratSync = findViewById(R.id.txtSquadratSync)
        txtSquadratinhoCount = findViewById(R.id.txtSquadratinhoCount)
        txtSquadratinhoSync = findViewById(R.id.txtSquadratinhoSync)

        // Load saved settings
        CoroutineScope(Dispatchers.Main).launch {
            editToken.setText(settings.getUserToken())
            editTimestamp.setText(settings.getTileTimestamp())
            switchSquadratinhos.isChecked = settings.getSquadratinhosEnabled()

            // Auto-save credentials on edit (attached after loading to avoid triggering on setText)
            editToken.doAfterTextChanged { text ->
                CoroutineScope(Dispatchers.IO).launch { settings.setUserToken(text.toString().trim()) }
            }
            editTimestamp.doAfterTextChanged { text ->
                CoroutineScope(Dispatchers.IO).launch { settings.setTileTimestamp(text.toString().trim()) }
            }
        }

        switchSquadratinhos.setOnCheckedChangeListener { _, isChecked ->
            CoroutineScope(Dispatchers.IO).launch {
                settings.setSquadratinhosEnabled(isChecked)
            }
        }

        // Load saved cache state
        CoroutineScope(Dispatchers.Main).launch {
            val lat = settings.getCenterLat()
            val lon = settings.getCenterLon()
            if (lat != 0.0) {
                editCenterLat.setText(CoordinateFormat.formatCoordinate(lat))
            }
            if (lon != 0.0) {
                editCenterLon.setText(CoordinateFormat.formatCoordinate(lon))
            }
            editSyncRadius.setText(settings.getSyncRadiusKm().toString())
            updateInfoSection(db)
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
        syncJob?.cancel()
        syncJob = null
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
                    editCenterLat.setText(CoordinateFormat.formatCoordinate(event.lat))
                    editCenterLon.setText(CoordinateFormat.formatCoordinate(event.lng))
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

        val lat = CoordinateFormat.parseCoordinate(editCenterLat.text.toString())
        val lon = CoordinateFormat.parseCoordinate(editCenterLon.text.toString())
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

        // Start sync on background thread
        btnSync.isEnabled = false
        txtSyncStatus.visibility = View.VISIBLE
        txtSyncStatus.text = ""
        progressSync.visibility = View.VISIBLE
        progressSync.progress = 0
        progressSync.post { (progressSync.parent.parent as? android.widget.ScrollView)?.fullScroll(View.FOCUS_DOWN) }

        syncJob = CoroutineScope(Dispatchers.IO).launch {
            settings.setCenterLat(lat)
            settings.setCenterLon(lon)
            settings.setSyncRadiusKm(radiusKm)

            val syncSquadratinhos = settings.getSquadratinhosEnabled()
            tileRepo.sync(
                karooSystem,
                settings.getTileUrlTemplate(),
                lat,
                lon,
                radiusKm.toDouble(),
                syncSquadratinhos,
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
                            txtSyncStatus.visibility = View.GONE
                            btnSync.isEnabled = true
                        }
                        CoroutineScope(Dispatchers.Main).launch { updateInfoSection(db) }
                    }

                    override fun onError(message: String) {
                        runOnUiThread {
                            progressSync.visibility = View.GONE
                            txtSyncStatus.text = getString(R.string.sync_status_error, message)
                            btnSync.isEnabled = true
                        }
                    }
                },
            )
        }
    }

    private suspend fun updateInfoSection(db: SquadratsDatabase) {
        val dateFmt = java.text.DateFormat.getDateTimeInstance(
            java.text.DateFormat.MEDIUM,
            java.text.DateFormat.SHORT,
        )

        val sqCount = db.collectedSquadratDao().count()
        val sqSync = db.collectedSquadratDao().maxSyncedAt()
        txtSquadratCount.text = if (sqCount > 0) getString(R.string.info_tile_count, sqCount) else getString(R.string.info_no_data)
        txtSquadratSync.text = if (sqSync != null) getString(R.string.info_last_sync, dateFmt.format(java.util.Date(sqSync))) else ""

        val shCount = db.collectedSquadratinhoDao().count()
        val shSync = db.collectedSquadratinhoDao().maxSyncedAt()
        txtSquadratinhoCount.text = if (shCount > 0) getString(R.string.info_tile_count, shCount) else getString(R.string.info_no_data)
        txtSquadratinhoSync.text = if (shSync != null) getString(R.string.info_last_sync, dateFmt.format(java.util.Date(shSync))) else ""
    }
}
