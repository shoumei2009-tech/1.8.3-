package com.gpswalker.app.ui.screens

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.gpswalker.app.R
import com.gpswalker.app.data.local.FavoritePlace
import com.gpswalker.app.data.local.FavoritesManager
import com.gpswalker.app.service.MockLocationService
import com.gpswalker.app.util.GeocodingHelper
import com.gpswalker.app.util.HealthConnectManager
import com.gpswalker.app.util.MovementEngine
import kotlinx.coroutines.*
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline

class MainActivity : AppCompatActivity() {
    
    private lateinit var mapView: MapView
    private lateinit var favoritesManager: FavoritesManager
    private lateinit var geocodingHelper: GeocodingHelper
    private lateinit var healthConnectManager: HealthConnectManager
    
    // 【第 11 題設定：預設中心點為宜蘭市復興路二段 61 巷 37 號】
    private var zoneCenter: GeoPoint = GeoPoint(24.750500, 121.745200)
    private var zoneRadius: Double = 500.0
    private var zoneShape: MovementEngine.ZoneShape = MovementEngine.ZoneShape.HEXAGON
    private var zoneOverlay: Polygon? = null
    private var pathOverlay: Polyline? = null
    private var positionMarker: Marker? = null
    private var isRunning = false
    private var healthConnectEnabled = true
    
    private var pendingAfterHcPermission: (() -> Unit)? = null
    
    private val hcPermissionLauncher = registerForActivityResult(
        androidx.health.connect.client.PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        val action = pendingAfterHcPermission
        pendingAfterHcPermission = null
        if (granted.containsAll(healthConnectManager.permissions)) {
            Toast.makeText(this, "Health Connect 已授權，步數將同步", Toast.LENGTH_SHORT).show()
            action?.invoke()
        } else {
            Toast.makeText(this, "未授權 Health Connect，步數將無法同步", Toast.LENGTH_LONG).show()
            action?.invoke()
        }
    }
    
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())
    
    private var tvSpeed: TextView? = null
    private var tvDistance: TextView? = null
    private var tvSteps: TextView? = null
    private var tvHealthStatus: TextView? = null
    private var tvRunningTime: TextView? = null
    private var btnStart: Button? = null
    private var btnStop: Button? = null
    private var btnPause: Button? = null
    private var btnResumeMove: Button? = null
    private var searchInput: EditText? = null
    private var searchResultsLayout: LinearLayout? = null
    private var controlPanel: View? = null
    private var runningPanel: View? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))
        setContentView(R.layout.activity_main)
        
        favoritesManager = FavoritesManager(this)
        geocodingHelper = GeocodingHelper()
        healthConnectManager = HealthConnectManager(this)
        healthConnectManager.initialize()
        
        setupMap()
        setupUI()
        requestPermissions()
        handleNavigateIntent(intent)
    }
    
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleNavigateIntent(it) }
    }
    
    private fun handleNavigateIntent(intent: Intent) {
        val navLat = intent.getDoubleExtra("navigate_lat", Double.NaN)
        val navLng = intent.getDoubleExtra("navigate_lng", Double.NaN)
        val navName = intent.getStringExtra("navigate_name")
        
        if (!navLat.isNaN() && !navLng.isNaN()) {
            zoneCenter = GeoPoint(navLat, navLng)
            mapView.controller.setZoom(16.0)
            mapView.controller.animateTo(zoneCenter)
            drawZone()
            teleportTo(navLat, navLng, navName ?: "已傳送")
        }
    }
    
    private fun setupMap() {
        mapView = findViewById(R.id.mapView)
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(15.0)
        mapView.controller.setCenter(zoneCenter)
        mapView.setOnLongClickListener { false }
        drawZone()
    }
    
    private fun setupUI() {
        tvSpeed = findViewById(R.id.tvSpeed)
        tvDistance = findViewById(R.id.tvDistance)
        tvSteps = findViewById(R.id.tvSteps)
        tvHealthStatus = findViewById(R.id.tvHealthStatus)
        tvRunningTime = findViewById(R.id.tvRunningTime)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        searchInput = findViewById(R.id.searchInput)
        searchResultsLayout = findViewById(R.id.searchResultsLayout)
        controlPanel = findViewById(R.id.controlPanel)
        runningPanel = findViewById(R.id.runningPanel)
        
        val seekRadius = findViewById<SeekBar>(R.id.seekRadius)
        val tvRadiusValue = findViewById<TextView>(R.id.tvRadiusValue)
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        zoneRadius = prefs.getFloat("zone_radius", 500.0f).toDouble()
        
        if (seekRadius != null) {
            seekRadius.progress = ((zoneRadius - 100.0) / (2000.0 - 100.0) * 100.0).toInt().coerceIn(0, 100)
            tvRadiusValue?.text = "${zoneRadius.toInt()} 公尺"
            
            seekRadius.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    zoneRadius = 100.0 + (progress / 100.0) * (2000.0 - 100.0)
                    tvRadiusValue?.text = "${zoneRadius.toInt()} 公尺"
                    drawZone()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    prefs.edit().putFloat("zone_radius", zoneRadius.toFloat()).apply()
                }
            })
        }
        
        findViewById<ImageButton>(R.id.btnCircle)?.setOnClickListener { zoneShape = MovementEngine.ZoneShape.CIRCLE; drawZone(); updateShapeButtons() }
        findViewById<ImageButton>(R.id.btnSquare)?.setOnClickListener { zoneShape = MovementEngine.ZoneShape.SQUARE; drawZone(); updateShapeButtons() }
        findViewById<ImageButton>(R.id.btnHexagon)?.setOnClickListener { zoneShape = MovementEngine.ZoneShape.HEXAGON; drawZone(); updateShapeButtons() }
        
        btnStart?.setOnClickListener { startWalking() }
        btnStop?.setOnClickListener { stopWalking() }
        btnPause = findViewById(R.id.btnPause)
        btnPause?.setOnClickListener { pauseMovement() }
        btnResumeMove = findViewById(R.id.btnResumeMove)
        btnResumeMove?.setOnClickListener { resumeMovement() }
        
        findViewById<Button>(R.id.btnSyncFit)?.setOnClickListener { openGoogleFit() }
        findViewById<Button>(R.id.btnOpenPikmin)?.setOnClickListener { openPikminBloom() }
        
        setupSearch()
        
        // 【第 11 題修復：點擊地圖導覽/回到原點按鈕時，優先回到當前模擬中心點，不強行跳回預設點】
        findViewById<View>(R.id.navMap)?.setOnClickListener {
            val targetPoint = if (MockLocationService.isRunning && MockLocationService.currentLat != 0.0) {
                GeoPoint(MockLocationService.currentLat, MockLocationService.currentLng)
            } else {
                zoneCenter
            }
            mapView.controller.animateTo(targetPoint)
            mapView.controller.setZoom(16.0)
            Toast.makeText(this, "已回到中心原點", Toast.LENGTH_SHORT).show()
        }
        
        findViewById<View>(R.id.navFavorites)?.setOnClickListener { showFavorites() }
        findViewById<View>(R.id.navSettings)?.setOnClickListener { showSettings() }
        
        mapView.overlays.add(object : org.osmdroid.views.overlay.Overlay() {
            override fun onLongPress(e: android.view.MotionEvent?, mapView: MapView?): Boolean {
                if (e != null && mapView != null && !isRunning) {
                    val projection = mapView.projection
                    val geoPoint = projection.fromPixels(e.x.toInt(), e.y.toInt()) as GeoPoint
                    zoneCenter = geoPoint
                    drawZone()
                    mapView.controller.animateTo(geoPoint)
                    Toast.makeText(this@MainActivity, "已設定區域中心", Toast.LENGTH_SHORT).show()
                    return true
                }
                return false
            }
        })
        updateShapeButtons()
    }
    private fun drawZone() {
        if (zoneOverlay != null) { mapView.overlays.remove(zoneOverlay) }
        val points = when (zoneShape) {
            MovementEngine.ZoneShape.CIRCLE -> MovementEngine.generateCirclePoints(zoneCenter, zoneRadius)
            MovementEngine.ZoneShape.SQUARE -> MovementEngine.generateSquarePoints(zoneCenter, zoneRadius)
            MovementEngine.ZoneShape.HEXAGON -> MovementEngine.generateHexagonPoints(zoneCenter, zoneRadius)
        }
        zoneOverlay = Polygon().apply {
            this.points = points
            fillColor = Color.argb(40, 0, 150, 255)
            strokeColor = Color.argb(200, 0, 150, 255)
            strokeWidth = 3f
        }
        mapView.overlays.add(0, zoneOverlay)
        mapView.invalidate()
    }
    
    private fun setupSearch() {
        searchInput?.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { performSearch(v.text.toString()); hideKeyboard(); true } else false
        }
        searchInput?.addTextChangedListener(object : TextWatcher {
            private var searchJob: Job? = null
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchJob?.cancel()
                val query = s?.toString()?.trim() ?: ""
                if (query.length >= 2) { searchJob = mainScope.launch { delay(500); performSearch(query) } }
                else { searchResultsLayout?.visibility = View.GONE }
            }
        })
    }
    
    private fun performSearch(query: String) {
        if (query.isBlank()) return
        mainScope.launch {
            try {
                val coords = geocodingHelper.parseCoordinates(query)
                if (coords != null) {
                    val result = geocodingHelper.reverseGeocode(coords.first, coords.second)
                    if (result != null) showSearchResult(result)
                    else showSearchResult(GeocodingHelper.GeoResult("位置", query, coords.first, coords.second))
                } else {
                    val results = geocodingHelper.searchPlace(query)
                    if (results.isNotEmpty()) showSearchResults(results)
                    else Toast.makeText(this@MainActivity, "找不到結果", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }
    
    private fun showSearchResults(results: List<GeocodingHelper.GeoResult>) {
        if (isFinishing || isDestroyed) return
        searchResultsLayout?.removeAllViews()
        searchResultsLayout?.visibility = View.VISIBLE
        results.take(5).forEach { result ->
            val itemView = layoutInflater.inflate(R.layout.item_search_result, searchResultsLayout, false)
            itemView.findViewById<TextView>(R.id.tvResultName)?.text = result.name
            itemView.findViewById<TextView>(R.id.tvResultAddress)?.text = result.address
            itemView.setOnClickListener { 
                searchResultsLayout?.visibility = View.GONE
                searchInput?.setText("") 
                showSearchResult(result) 
            }
            searchResultsLayout?.addView(itemView)
        }
    }
    
    private fun showSearchResult(result: GeocodingHelper.GeoResult) {
        if (isFinishing || isDestroyed) return
        try {
            searchResultsLayout?.visibility = View.GONE
            searchInput?.setText("")
            val point = GeoPoint(result.latitude, result.longitude)
            mapView.controller.animateTo(point)
            mapView.controller.setZoom(16.0)
            val marker = Marker(mapView)
            marker.position = point
            marker.title = result.name
            mapView.overlays.add(marker)
            mapView.invalidate()
            showLocationActionDialog(result)
        } catch (e: Exception) { e.printStackTrace() }
    }
    
    private fun showLocationActionDialog(result: GeocodingHelper.GeoResult) {
        AlertDialog.Builder(this)
            .setTitle(result.name)
            .setMessage("${result.address}\n\n${String.format("%.6f, %.6f", result.latitude, result.longitude)}")
            .setPositiveButton("傳送到此處") { _, _ ->
                zoneCenter = GeoPoint(result.latitude, result.longitude)
                drawZone()
                mapView.controller.animateTo(zoneCenter)
                teleportTo(result.latitude, result.longitude, result.name)
            }
            .setNeutralButton("加入最愛") { _, _ ->
                favoritesManager.add(FavoritePlace(name = result.name, latitude = result.latitude, longitude = result.longitude))
                Toast.makeText(this, "已加入最愛", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    private fun teleportTo(lat: Double, lng: Double, name: String) {
        if (isRunning) {
            val intent = Intent(this, MockLocationService::class.java).apply {
                action = MockLocationService.ACTION_TELEPORT
                putExtra(MockLocationService.EXTRA_CENTER_LAT, lat)
                putExtra(MockLocationService.EXTRA_CENTER_LNG, lng)
            }
            startService(intent)
        } else {
            startWalkingPaused()
        }
    }
    
    private fun ensureHealthPermissionsThen(action: () -> Unit) {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        if (!prefs.getBoolean("health_sync_enabled", true)) { action(); return }
        mainScope.launch {
            if (healthConnectManager.checkPermissions()) { action() }
            else {
                pendingAfterHcPermission = action
                try { hcPermissionLauncher.launch(healthConnectManager.permissions) } catch (e: Exception) { action() }
            }
        }
    }
    
    private fun startWalkingPaused() {
        if (isRunning) return
        ensureHealthPermissionsThen { doStartWalkingPaused() }
    }
    
    private fun doStartWalkingPaused() {
        if (isRunning) return
        isRunning = true
        val intent = Intent(this, MockLocationService::class.java).apply {
            action = MockLocationService.ACTION_START
            putExtra(MockLocationService.EXTRA_CENTER_LAT, zoneCenter.latitude)
            putExtra(MockLocationService.EXTRA_CENTER_LNG, zoneCenter.longitude)
            putExtra(MockLocationService.EXTRA_RADIUS, zoneRadius)
            putExtra(MockLocationService.EXTRA_SHAPE, zoneShape.ordinal)
            putExtra(MockLocationService.EXTRA_START_PAUSED, true)
        }
        startForegroundService(intent)
        controlPanel?.visibility = View.GONE
        runningPanel?.visibility = View.VISIBLE
    }
    
    private fun startWalking() {
        if (isRunning) return
        ensureHealthPermissionsThen { doStartWalking() }
    }
    
    private fun doStartWalking() {
        if (isRunning) return
        isRunning = true
        val intent = Intent(this, MockLocationService::class.java).apply {
            action = MockLocationService.ACTION_START
            putExtra(MockLocationService.EXTRA_CENTER_LAT, zoneCenter.latitude)
            putExtra(MockLocationService.EXTRA_CENTER_LNG, zoneCenter.longitude)
            putExtra(MockLocationService.EXTRA_RADIUS, zoneRadius)
            putExtra(MockLocationService.EXTRA_SHAPE, zoneShape.ordinal)
        }
        startForegroundService(intent)
        controlPanel?.visibility = View.GONE
        runningPanel?.visibility = View.VISIBLE
    }
    
    private fun stopWalking() {
        isRunning = false
        val intent = Intent(this, MockLocationService::class.java).apply { action = MockLocationService.ACTION_STOP }
        startService(intent)
        controlPanel?.visibility = View.VISIBLE
        runningPanel?.visibility = View.GONE
    }
    
    private fun pauseMovement() {
        val intent = Intent(this, MockLocationService::class.java).apply { action = MockLocationService.ACTION_PAUSE }
        startService(intent)
    }
    
    private fun resumeMovement() {
        val intent = Intent(this, MockLocationService::class.java).apply { action = MockLocationService.ACTION_RESUME }
        startService(intent)
    }
    
    private fun updateShapeButtons() {
        findViewById<ImageButton?>(R.id.btnCircle)?.alpha = if (zoneShape == MovementEngine.ZoneShape.CIRCLE) 1.0f else 0.5f
        findViewById<ImageButton?>(R.id.btnSquare)?.alpha = if (zoneShape == MovementEngine.ZoneShape.SQUARE) 1.0f else 0.5f
        findViewById<ImageButton?>(R.id.btnHexagon)?.alpha = if (zoneShape == MovementEngine.ZoneShape.HEXAGON) 1.0f else 0.5f
    }
    
    private fun requestPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 100)
        }
    }
    
    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromHiltOrNull(searchInput?.windowToken)
    }
    
    private fun InputMethodManager.hideSoftInputFromHiltOrNull(token: android.os.IBinder?) {
        if (token != null) hideSoftInputFromWindow(token, 0)
    }
    
    private fun openGoogleFit() {
        try {
            val intent = packageManager.getLaunchIntentForPackage("com.google.android.apps.fitness")
            if (intent != null) startActivity(intent) else Toast.makeText(this, "未安裝 Google Fit", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {}
    }
    
    private fun openPikminBloom() {
        try {
            val intent = packageManager.getLaunchIntentForPackage("com.nianticlabs.pikmin")
            if (intent != null) startActivity(intent) else Toast.makeText(this, "未安裝皮克ミン", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {}
    }
    
    private fun showFavorites() { startActivity(Intent(this, FavoritesActivity::class.java)) }
    private fun showSettings() { startActivity(Intent(this, SettingsActivity::class.java)) }
    
    override fun onDestroy() {
        mainScope.cancel()
        super.onDestroy()
    }
}
