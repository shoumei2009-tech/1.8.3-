package com.gpswalker.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.gpswalker.app.GPSWalkerApp
import com.gpswalker.app.R
import com.gpswalker.app.ui.screens.MainActivity
import com.gpswalker.app.util.CloudFitnessManager
import com.gpswalker.app.util.HealthConnectManager
import com.gpswalker.app.util.MovementEngine
import kotlinx.coroutines.*
import java.util.Timer
import java.util.TimerTask

class MockLocationService : Service() {
    
    companion object {
        const val ACTION_START = "com.gpswalker.app.START"
        const val ACTION_PAUSE = "com.gpswalker.app.PAUSE"
        const val ACTION_RESUME = "com.gpswalker.app.RESUME"
        const val ACTION_STOP = "com.gpswalker.app.STOP"
        const val ACTION_TELEPORT = "com.gpswalker.app.TELEPORT"
        const val EXTRA_CENTER_LAT = "center_lat"
        const val EXTRA_CENTER_LNG = "center_lng"
        const val EXTRA_RADIUS = "radius"
        const val EXTRA_SHAPE = "shape"
        const val EXTRA_HEALTH_CONNECT_ENABLED = "health_connect"
        const val EXTRA_START_PAUSED = "start_paused"
        
        @Volatile var isRunning = false
            private set
        @Volatile var isPaused = false
            private set
        @Volatile var totalDistance = 0.0
            private set
        @Volatile var totalSteps = 0L
            private set
        @Volatile var startTime = 0L
            private set
        @Volatile var currentLat = 0.0
            private set
        @Volatile var currentLng = 0.0
            private set
        @Volatile var healthSynced = false
            private set
        @Volatile var cloudSynced = false
            private set
            
        private const val CLOUD_UPLOAD_INTERVAL_MS = 30_000L
            
        var onUpdate: ((Double, Double, Double, Long, Long) -> Unit)? = null
    }
    
    private var movementEngine: MovementEngine? = null
    private var timer: Timer? = null
    private var healthConnectManager: HealthConnectManager? = null
    private var healthEnabled = true
    private var serviceScope: CoroutineScope? = null
    private var locationManager: LocationManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var stepLength = 0.75
    
    private var pendingCloudSteps = 0L
    private var pendingCloudDistance = 0.0
    private var pendingCloudStartMs = 0L
    private var lastCloudUploadMs = 0L
    private var cloudUploading = false
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (isRunning) return START_STICKY
                
                val centerLat = intent.getDoubleExtra(EXTRA_CENTER_LAT, 25.033964)
                val centerLng = intent.getDoubleExtra(EXTRA_CENTER_LNG, 121.564468)
                val radius = intent.getDoubleExtra(EXTRA_RADIUS, 500.0)
                val shapeOrdinal = intent.getIntExtra(EXTRA_SHAPE, 0)
                healthEnabled = intent.getBooleanExtra(EXTRA_HEALTH_CONNECT_ENABLED, true)
                val startPaused = intent.getBooleanExtra(EXTRA_START_PAUSED, false)
                
                val shape = MovementEngine.ZoneShape.entries.getOrElse(shapeOrdinal) { 
                    MovementEngine.ZoneShape.CIRCLE 
                }
                startMocking(centerLat, centerLng, radius, shape, startPaused)
            }
            ACTION_TELEPORT -> {
                val newLat = intent.getDoubleExtra(EXTRA_CENTER_LAT, currentLat)
                val newLng = intent.getDoubleExtra(EXTRA_CENTER_LNG, currentLng)
                currentLat = newLat
                currentLng = newLng
                movementEngine?.updateCenter(newLat, newLng)
                setMockLocation(newLat, newLng)
                isPaused = true
            }
            ACTION_PAUSE -> {
                isPaused = true
            }
            ACTION_RESUME -> {
                isPaused = false
            }
            ACTION_STOP -> {
                stopMocking()
            }
        }
        return START_STICKY
    }
    
    private fun startMocking(centerLat: Double, centerLng: Double, radius: Double, shape: MovementEngine.ZoneShape, startPaused: Boolean = false) {
        isRunning = true
        isPaused = startPaused
        stepLength = getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getFloat("step_length", 0.75f).toDouble()
        totalDistance = 0.0
        totalSteps = 0
        startTime = System.currentTimeMillis()
        currentLat = centerLat
        currentLng = centerLng
        
        // 使用高效能背景協程與 Dispatchers.IO 確保切換至背景時不被系統凍結
        serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        movementEngine = MovementEngine(centerLat, centerLng, radius, shape)
        
        if (healthEnabled) {
            healthConnectManager = HealthConnectManager(this)
            healthConnectManager?.initialize()
        }
        
        pendingCloudSteps = 0
        pendingCloudDistance = 0.0
        pendingCloudStartMs = System.currentTimeMillis()
        lastCloudUploadMs = System.currentTimeMillis()
        cloudSynced = false
        
        // 取得強力 WakeLock 防止 CPU 進入休眠導致背景主角原地踏步 - 解決問題 9
        acquireWakeLock()
        setupMockProvider()
        startForeground(1, createNotification())
        
        timer = Timer("MockLocationTimer", true)
        timer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                if (!isRunning) return
                
                try {
                    if (isPaused) {
                        setMockLocation(currentLat, currentLng)
                        
                        val distThisSecond = 5.56
                        totalDistance += distThisSecond
                        val stepsThisSecond = (distThisSecond / stepLength).toLong()
                        totalSteps += stepsThisSecond
                        
                        if (healthEnabled) {
                            serviceScope?.launch {
                                try {
                                    val success = healthConnectManager?.writeSecondData(distThisSecond, stepLength) ?: false
                                    healthSynced = success
                                } catch (_: Exception) {
                                    healthSynced = false
                                }
                            }
                        }
                        
                        accumulateCloud(stepsThisSecond, distThisSecond)
                        
                        val elapsed = System.currentTimeMillis() - startTime
                        onUpdate?.invoke(currentLat, currentLng, totalDistance, totalSteps, elapsed)
                    } else {
                        val position = movementEngine?.advance(1.0) ?: return
                        
                        val prevLat = currentLat
                        val prevLng = currentLng
                        currentLat = position.latitude
                        currentLng = position.longitude
                        
                        // 強制更新模擬座標，確保切到背景皮克敏時路徑不中斷
                        setMockLocation(position.latitude, position.longitude)
                        
                        val distThisSecond = if (prevLat != 0.0 && prevLng != 0.0) {
                            MovementEngine.haversineDistance(prevLat, prevLng, position.latitude, position.longitude)
                        } else {
                            5.56
                        }
                        
                        totalDistance += distThisSecond
                        val stepsThisSecond = (distThisSecond / stepLength).toLong()
                        totalSteps += stepsThisSecond
                        
                        if (healthEnabled) {
                            serviceScope?.launch {
                                try {
                                    val success = healthConnectManager?.writeSecondData(distThisSecond, stepLength) ?: false
                                    healthSynced = success
                                } catch (_: Exception) {
                                    healthSynced = false
                                }
                            }
                        }
                        
                        accumulateCloud(stepsThisSecond, distThisSecond)
                        
                        val elapsed = System.currentTimeMillis() - startTime
                        onUpdate?.invoke(position.latitude, position.longitude, totalDistance, totalSteps, elapsed)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }, 0, 1000)
    }
    
    private fun accumulateCloud(steps: Long, distance: Double) {
        pendingCloudSteps += steps
        pendingCloudDistance += distance
        
        val now = System.currentTimeMillis()
        if (now - lastCloudUploadMs < CLOUD_UPLOAD_INTERVAL_MS) return
        if (cloudUploading) return
        if (!CloudFitnessManager.isSignedIn(this)) {
            cloudSynced = false
            return
        }
        
        val stepsToUpload = pendingCloudSteps
        val distToUpload = pendingCloudDistance
        val segStart = pendingCloudStartMs
        val segEnd = now
        
        pendingCloudSteps = 0
        pendingCloudDistance = 0.0
        pendingCloudStartMs = now
        lastCloudUploadMs = now
        cloudUploading = true
        
        serviceScope?.launch {
            try {
                val ok = CloudFitnessManager.uploadSteps(
                    this@MockLocationService,
                    stepsToUpload.toInt(), distToUpload, segStart, segEnd
                )
                cloudSynced = ok
            } catch (_: Exception) {
                cloudSynced = false
            } finally {
                cloudUploading = false
            }
        }
    }
    
    private fun setupMockProvider() {
        try {
            locationManager?.let { lm ->
                try { lm.removeTestProvider(LocationManager.GPS_PROVIDER) } catch (_: Exception) {}
                lm.addTestProvider(
                    LocationManager.GPS_PROVIDER,
                    false, false, false, false, true, true, true,
                    ProviderProperties.POWER_USAGE_LOW,
                    ProviderProperties.ACCURACY_FINE
                )
                lm.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
                
                try { lm.removeTestProvider(LocationManager.NETWORK_PROVIDER) } catch (_: Exception) {}
                try {
                    lm.addTestProvider(
                        LocationManager.NETWORK_PROVIDER,
                        false, false, false, false, true, true, true,
                        ProviderProperties.POWER_USAGE_LOW,
                        ProviderProperties.ACCURACY_FINE
                    )
                    lm.setTestProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private fun setMockLocation(lat: Double, lng: Double) {
        val time = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtimeNanos()
        val alt = 10.0 + (Math.random() * 2 - 1)
        val acc = 3.0f + (Math.random() * 2).toFloat()
        // 確保速度持續大於 0，防止遊戲判定主角停止
        val spd = if (isPaused) 0.5f else (20.0f / 3.6f) * (0.95f + (Math.random() * 0.1).toFloat())
        
        try {
            val gpsLocation = Location(LocationManager.GPS_PROVIDER).apply {
                latitude = lat
                longitude = lng
                altitude = alt
                accuracy = acc
                speed = spd
                bearing = 0f
                this.time = time
                elapsedRealtimeNanos = elapsed
            }
            locationManager?.setTestProviderLocation(LocationManager.GPS_PROVIDER, gpsLocation)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        try {
            val netLocation = Location(LocationManager.NETWORK_PROVIDER).apply {
                latitude = lat
                longitude = lng
                altitude = alt
                accuracy = acc
                speed = spd
                bearing = 0f
                this.time = time
                elapsedRealtimeNanos = elapsed
            }
            locationManager?.setTestProviderLocation(LocationManager.NETWORK_PROVIDER, netLocation)
        } catch (_: Exception) {}
    }
    
    private fun stopMocking() {
        isRunning = false
        isPaused = false
        
        timer?.cancel()
        timer = null
        
        if (pendingCloudSteps > 0 && CloudFitnessManager.isSignedIn(this)) {
            val steps = pendingCloudSteps.toInt()
            val dist = pendingCloudDistance
            val segStart = pendingCloudStartMs
            val segEnd = System.currentTimeMillis()
            pendingCloudSteps = 0
            pendingCloudDistance = 0.0
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    CloudFitnessManager.uploadSteps(this@MockLocationService, steps, dist, segStart, segEnd)
                } catch (_: Exception) {}
            }
        }
        
        try { locationManager?.removeTestProvider(LocationManager.GPS_PROVIDER) } catch (_: Exception) {}
        try { locationManager?.removeTestProvider(LocationManager.NETWORK_PROVIDER) } catch (_: Exception) {}
        
        healthConnectManager?.resetFractionalSteps()
        serviceScope?.cancel()
        serviceScope = null
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    
    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "GPSWalker::BackgroundWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }
    
    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {}
        wakeLock = null
    }
    
    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val builder = NotificationCompat.Builder(this, GPSWalkerApp.CHANNEL_ID)
            .setContentTitle(getString(R.string.gps_walker_running))
            .setContentText("背景持續運行中 ｜ 確保主角穩定移動")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
        
        try {
            val fitIntent = packageManager.getLaunchIntentForPackage("com.google.android.apps.fitness")
            if (fitIntent != null) {
                val fitPending = PendingIntent.getActivity(
                    this, 1, fitIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                builder.addAction(android.R.drawable.ic_popup_sync, "同步 Fit", fitPending)
            }
        } catch (_: Exception) {}
        
        return builder.build()
    }
    
    override fun onDestroy() {
        stopMocking()
        super.onDestroy()
    }
}
