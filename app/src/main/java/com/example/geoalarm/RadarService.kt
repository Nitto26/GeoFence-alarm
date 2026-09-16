package com.example.geoalarm

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.geoalarm.network.EventReporter
import com.example.geoalarm.network.JobItem
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class RadarService : Service() {

    private val locationStateReceiver = LocationStateReceiver()
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var lastPingTimestamp = 0L
    private var lastInsideJobId: String? = null
    private val accommodationLat = 10.5182
    private val accommodationLng = 76.2090

    companion object {
        private const val TAG = "RadarService"
        private const val CHANNEL_ID = "RADAR_CHANNEL"
        private const val NOTIFICATION_ID = 1
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // Dynamically register the location state receiver for Sabotage detection
        try {
            val filter = IntentFilter().apply {
                addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
                addAction("android.location.MODE_CHANGED")
            }
            ContextCompat.registerReceiver(
                this,
                locationStateReceiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED
            )
            Log.d(TAG, "LocationStateReceiver registered in Foreground Service")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register receiver: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val userPrefs = getSharedPreferences("UserPrefs", Context.MODE_PRIVATE)
        val workerId = userPrefs.getString("WORKER_ID", "")

        if (workerId.isNullOrEmpty()) {
            Log.w(TAG, "No active worker session. Stopping RadarService.")
            stopSelf()
            return START_NOT_STICKY
        }

        val hasFine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (!hasFine && !hasCoarse) {
            Log.w(TAG, "Location permissions not granted yet. Stopping RadarService.")
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            updateForegroundNotification()
            startActiveRadar()
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException starting foreground service: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }

        return START_STICKY
    }

    private fun updateForegroundNotification() {
        val manager = getSystemService(NotificationManager::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val existing = manager.getNotificationChannel(CHANNEL_ID)
            if (existing == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "GeoAlarm Tracking Service",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Continuous background location radar and worksite arrival detection"
                }
                manager.createNotificationChannel(channel)
            }
        }

        val sharedPrefs = getSharedPreferences("GeoAlarmPrefs", Context.MODE_PRIVATE)
        val isClockedIn = sharedPrefs.getBoolean("IS_CLOCKED_IN", false)
        val activeJobId = sharedPrefs.getString("ACTIVE_JOB_ID", "Assigned Worksite") ?: "Assigned Worksite"

        val title = if (isClockedIn) "🟢 Shift Active — $activeJobId" else "🚗 Travel Radar Active"
        val text = if (isClockedIn) "Attendance & payroll tracking running in background" else "Monitoring worksite arrival..."

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startActiveRadar() {
        if (::locationCallback.isInitialized) {
            try {
                fusedLocationClient.removeLocationUpdates(locationCallback)
            } catch (_: Exception) {}
        }

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000)
            .setMinUpdateDistanceMeters(2f)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                val sharedPrefs = getSharedPreferences("GeoAlarmPrefs", Context.MODE_PRIVATE)
                val isClockedIn = sharedPrefs.getBoolean("IS_CLOCKED_IN", false)
                val currentJobId = sharedPrefs.getString("ACTIVE_JOB_ID", "JOB-1001") ?: "JOB-1001"

                val jobs = loadCachedJobs()

                for (location in locationResult.locations) {
                    val lat = location.latitude
                    val lng = location.longitude
                    val now = System.currentTimeMillis()

                    // 1. Process Background Worksite Arrival Radar
                    processLocationAgainstJobs(lat, lng, jobs)

                    // 2. Periodic Ping Dispatching every 30 seconds if Clocked In
                    if (isClockedIn && (now - lastPingTimestamp >= 30_000L)) {
                        lastPingTimestamp = now
                        EventReporter.reportEvent(
                            context = this@RadarService,
                            eventType = "ping",
                            jobId = currentJobId,
                            latitude = lat,
                            longitude = lng
                        )
                    }
                }
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            try {
                fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
                Log.d(TAG, "✓ RadarService location updates active (3s interval, high accuracy)")
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException requesting location updates: ${e.message}")
            }
        }
    }

    private fun processLocationAgainstJobs(lat: Double, lng: Double, jobs: List<JobItem>) {
        val sharedPrefs = getSharedPreferences("GeoAlarmPrefs", Context.MODE_PRIVATE)
        val isClockedIn = sharedPrefs.getBoolean("IS_CLOCKED_IN", false)

        val stayJob = jobs.find { it.siteType == "accommodation" || it.isStartingPoint == true }
        val workJobs = jobs.filter { it.siteType != "accommodation" && it.isStartingPoint != true }

        val stayLat = stayJob?.location?.firstOrNull()?.latitude ?: accommodationLat
        val stayLng = stayJob?.location?.firstOrNull()?.longitude ?: accommodationLng

        val stayDist = FloatArray(1)
        android.location.Location.distanceBetween(lat, lng, stayLat, stayLng, stayDist)
        val isCurrentlyInsideStay = stayDist[0] <= 80f
        val wasInsideStay = sharedPrefs.getBoolean("WAS_INSIDE_STAY", false)

        if (isCurrentlyInsideStay) {
            if (!wasInsideStay) {
                sharedPrefs.edit().putBoolean("WAS_INSIDE_STAY", true).apply()
            }
        } else {
            // Worker is outside stay
            if (wasInsideStay) {
                // EXITED STAY -> TRIGGER AUTO CLOCK IN!
                sharedPrefs.edit().putBoolean("WAS_INSIDE_STAY", false).apply()

                if (!isClockedIn) {
                    val now = System.currentTimeMillis()
                    val primaryJobId = workJobs.firstOrNull()?.jobId ?: "Assigned Worksite"
                    sharedPrefs.edit()
                        .putBoolean("IS_CLOCKED_IN", true)
                        .putBoolean("IS_SYSTEM_ARMED", true)
                        .putLong("CLOCK_IN_TIMESTAMP", now)
                        .putString("ACTIVE_JOB_ID", primaryJobId)
                        .apply()

                    updateForegroundNotification()
                    WorkNotificationManager.showClockInNotification(this, primaryJobId, isAuto = true)

                    EventReporter.reportEvent(
                        context = this,
                        eventType = "clock_in",
                        jobId = primaryJobId,
                        latitude = lat,
                        longitude = lng
                    )
                    EventReporter.addLocalLog("🚀 Background Shift Started: Exited stay accommodation. Payroll tracking active.")

                    val stateIntent = Intent(GeofenceBroadcastReceiver.ACTION_WORK_STATE_CHANGED).apply {
                        putExtra("IS_CLOCKED_IN", true)
                        putExtra("CLOCK_IN_TIMESTAMP", now)
                        putExtra("ACTIVE_JOB_ID", primaryJobId)
                        setPackage(packageName)
                    }
                    sendBroadcast(stateIntent)
                }
            }
        }

        var matchedJob: JobItem? = null
        for (job in workJobs) {
            if (isCoordinateInsideJob(lat, lng, job)) {
                matchedJob = job
                break
            }
        }

        if (matchedJob != null) {
            val jobId = matchedJob.jobId
            if (lastInsideJobId != jobId || !isClockedIn) {
                lastInsideJobId = jobId

                if (!isClockedIn) {
                    // AUTO CLOCK IN ON ARRIVAL
                    val now = System.currentTimeMillis()
                    sharedPrefs.edit()
                        .putBoolean("IS_CLOCKED_IN", true)
                        .putBoolean("IS_SYSTEM_ARMED", true)
                        .putLong("CLOCK_IN_TIMESTAMP", now)
                        .putString("ACTIVE_JOB_ID", jobId)
                        .apply()

                    updateForegroundNotification()
                    WorkNotificationManager.showClockInNotification(this, jobId, isAuto = true)

                    EventReporter.reportEvent(
                        context = this,
                        eventType = "clock_in",
                        jobId = jobId,
                        latitude = lat,
                        longitude = lng
                    )
                    EventReporter.reportEvent(
                        context = this,
                        eventType = "entry",
                        jobId = jobId,
                        latitude = lat,
                        longitude = lng
                    )
                    EventReporter.addLocalLog("✓ Background Arrival: Work time started at $jobId. Payroll active.")

                    // Broadcast to MainActivity if active
                    val stateIntent = Intent(GeofenceBroadcastReceiver.ACTION_WORK_STATE_CHANGED).apply {
                        putExtra("IS_CLOCKED_IN", true)
                        putExtra("CLOCK_IN_TIMESTAMP", now)
                        putExtra("ACTIVE_JOB_ID", jobId)
                        setPackage(packageName)
                    }
                    sendBroadcast(stateIntent)
                } else {
                    WorkNotificationManager.showGeofenceEntryNotification(this, jobId)
                    EventReporter.reportEvent(
                        context = this,
                        eventType = "entry",
                        jobId = jobId,
                        latitude = lat,
                        longitude = lng
                    )
                    EventReporter.addLocalLog("Worksite Entry: Inside $jobId.")
                }
            }
        } else {
            // Coordinate is outside all worksites
            if (lastInsideJobId != null) {
                val exitedJobId = lastInsideJobId ?: "Worksite"
                lastInsideJobId = null

                WorkNotificationManager.showGeofenceExitNotification(this, exitedJobId)
                EventReporter.reportEvent(
                    context = this,
                    eventType = "exit",
                    jobId = exitedJobId,
                    latitude = lat,
                    longitude = lng
                )
                EventReporter.addLocalLog("⚠️ Worksite boundary exit ($exitedJobId)")
            }
        }
    }

    private fun isCoordinateInsideJob(lat: Double, lng: Double, job: JobItem): Boolean {
        if (job.location.isEmpty()) return false

        // Guard: Stay / Accommodation Safety Zone
        val stayDist = FloatArray(1)
        android.location.Location.distanceBetween(lat, lng, accommodationLat, accommodationLng, stayDist)
        if (stayDist[0] <= 120f) {
            return false
        }

        val points = job.location
        if (points.size >= 3) {
            var inside = false
            var j = points.size - 1
            for (i in points.indices) {
                val pi = points[i]
                val pj = points[j]
                if ((pi.longitude > lng) != (pj.longitude > lng) &&
                    lat < (pj.latitude - pi.latitude) * (lng - pi.longitude) / (pj.longitude - pi.longitude) + pi.latitude) {
                    inside = !inside
                }
                j = i
            }
            if (inside) return true

            for (coord in points) {
                val dist = FloatArray(1)
                android.location.Location.distanceBetween(lat, lng, coord.latitude, coord.longitude, dist)
                if (dist[0] <= 40f) {
                    return true
                }
            }
            return false
        } else {
            for (coord in points) {
                val dist = FloatArray(1)
                android.location.Location.distanceBetween(lat, lng, coord.latitude, coord.longitude, dist)
                if (dist[0] <= 75f) {
                    return true
                }
            }
            return false
        }
    }

    private fun loadCachedJobs(): List<JobItem> {
        val geoPrefs = getSharedPreferences("GeoPrefs", Context.MODE_PRIVATE)
        val json = geoPrefs.getString("CACHED_JOBS_JSON", null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<JobItem>>() {}.type
            Gson().fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        if (::locationCallback.isInitialized) {
            try {
                fusedLocationClient.removeLocationUpdates(locationCallback)
            } catch (_: Exception) {}
        }
        try {
            unregisterReceiver(locationStateReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Receiver unregister error: ${e.message}")
        }
    }
}