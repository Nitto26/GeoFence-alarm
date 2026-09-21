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
import com.example.geoalarm.network.AccommodationItem
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

    companion object {
        private const val TAG = "RadarService"
        private const val CHANNEL_ID = "RADAR_CHANNEL"
        private const val NOTIFICATION_ID = 1
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

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

        val title = if (isClockedIn) "Shift Active • $activeJobId" else "Travel Radar Active"
        val text = if (isClockedIn) "Attendance & payroll tracking running in background" else "Monitoring departure and worksite arrival..."

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
                val currentJobId = sharedPrefs.getString("ACTIVE_JOB_ID", "") ?: ""

                val jobs = loadCachedJobs()
                val accommodation = loadCachedAccommodation()

                for (location in locationResult.locations) {
                    val lat = location.latitude
                    val lng = location.longitude
                    val now = System.currentTimeMillis()

                    // 1. Process Background Worksite Arrival and Stay Departure Radar
                    processLocationAgainstJobsAndStay(lat, lng, jobs, accommodation)

                    // 2. Periodic Silent Ping Dispatching every 30 seconds if Clocked In (NO NOTIFICATION)
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
                Log.d(TAG, "RadarService location updates active (3s interval, high accuracy)")
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException requesting location updates: ${e.message}")
            }
        }
    }


    private fun processLocationAgainstJobsAndStay(
        lat: Double, 
        lng: Double, 
        jobs: List<JobItem>, 
        accommodation: AccommodationItem?
    ) {
        val sharedPrefs = getSharedPreferences("GeoAlarmPrefs", Context.MODE_PRIVATE)
        val isClockedIn = sharedPrefs.getBoolean("IS_CLOCKED_IN", false)

        // 1. Evaluate Stay / Accommodation Departure
        if (accommodation != null && accommodation.location.isNotEmpty()) {
            val stayDist = FloatArray(1)
            val stayPt = accommodation.location[0]
            android.location.Location.distanceBetween(lat, lng, stayPt.latitude, stayPt.longitude, stayDist)
            
            val isInsideStay = stayDist[0] <= 80f
            val wasInsideStay = sharedPrefs.getBoolean("WAS_INSIDE_STAY", false)

            if (isInsideStay) {
                if (!wasInsideStay) {
                    sharedPrefs.edit().putBoolean("WAS_INSIDE_STAY", true).apply()
                }

                // 2. RETURNING TO ACCOMMODATION AFTER WORK -> AUTO CLOCK OUT / CHECK OUT!
                if (isClockedIn) {
                    val activeJobId = sharedPrefs.getString("ACTIVE_JOB_ID", jobs.firstOrNull()?.jobId ?: "Assigned Worksite") ?: "Assigned Worksite"
                    val clockInTime = sharedPrefs.getLong("CLOCK_IN_TIMESTAMP", 0L)
                    val formattedDuration = if (clockInTime > 0L) {
                        val dur = System.currentTimeMillis() - clockInTime
                        val h = dur / 3600000
                        val m = (dur % 3600000) / 60000
                        String.format("%02dh %02dm", h, m)
                    } else ""

                    lastInsideJobId = null
                    sharedPrefs.edit()
                        .putBoolean("IS_CLOCKED_IN", false)
                        .putBoolean("IS_SYSTEM_ARMED", false)
                        .commit()

                    updateForegroundNotification()
                    WorkNotificationManager.showClockOutNotification(this, activeJobId, formattedDuration)

                    EventReporter.reportEvent(
                        context = this,
                        eventType = "clock_out",
                        jobId = activeJobId,
                        latitude = lat,
                        longitude = lng
                    )
                    EventReporter.addLocalLog("Shift Auto-Ended: Returned to accommodation. Payroll paused.")

                    val stateIntent = Intent(GeofenceBroadcastReceiver.ACTION_WORK_STATE_CHANGED).apply {
                        putExtra("IS_CLOCKED_IN", false)
                        putExtra("ACTIVE_JOB_ID", activeJobId)
                        setPackage(packageName)
                    }
                    sendBroadcast(stateIntent)
                }
            } else {
                // Outside stay
                if (wasInsideStay) {
                    // DEPARTED STAY / ACCOMMODATION -> AUTO CLOCK IN ONLY IF WITHIN SHIFT SCHEDULE!
                    sharedPrefs.edit().putBoolean("WAS_INSIDE_STAY", false).apply()

                    if (!isClockedIn) {
                        if (ShiftScheduleHelper.isAnyJobWithinShiftHours(jobs)) {
                            val now = System.currentTimeMillis()
                            val primaryJobId = jobs.firstOrNull()?.jobId ?: "Assigned Worksite"
                            sharedPrefs.edit()
                                .putBoolean("IS_CLOCKED_IN", true)
                                .putBoolean("IS_SYSTEM_ARMED", true)
                                .putLong("CLOCK_IN_TIMESTAMP", now)
                                .putString("ACTIVE_JOB_ID", primaryJobId)
                                .commit()

                            updateForegroundNotification()
                            WorkNotificationManager.showClockInNotification(this, primaryJobId, isAuto = true)

                            EventReporter.reportEvent(
                                context = this,
                                eventType = "clock_in",
                                jobId = primaryJobId,
                                latitude = lat,
                                longitude = lng
                            )
                            EventReporter.addLocalLog("Shift Auto-Started: Exited accommodation. Payroll timer active.")

                            val stateIntent = Intent(GeofenceBroadcastReceiver.ACTION_WORK_STATE_CHANGED).apply {
                                putExtra("IS_CLOCKED_IN", true)
                                putExtra("CLOCK_IN_TIMESTAMP", now)
                                putExtra("ACTIVE_JOB_ID", primaryJobId)
                                setPackage(packageName)
                            }
                            sendBroadcast(stateIntent)
                        } else {
                            Log.d(TAG, "Stay departure detected, but current time is outside shift schedule. Auto clock-in skipped.")
                        }
                    }
                }
            }
        }

        // 2. Evaluate Assigned Worksite Geofences
        var matchedJob: JobItem? = null
        for (job in jobs) {
            if (isCoordinateInsideJob(lat, lng, job)) {
                matchedJob = job
                break
            }
        }

        val isManualClockedOut = sharedPrefs.getBoolean("MANUAL_CLOCKED_OUT", false)

        if (matchedJob != null) {
            val jobId = matchedJob.jobId
            // Only trigger state change if we newly entered this worksite
            if (lastInsideJobId != jobId) {
                lastInsideJobId = jobId

                if (!isClockedIn && !isManualClockedOut) {
                    // Auto Clock In upon arrival at worksite ONLY if current time & date is within shift hours
                    if (ShiftScheduleHelper.isJobWithinShiftHours(matchedJob)) {
                        val now = System.currentTimeMillis()
                        sharedPrefs.edit()
                            .putBoolean("IS_CLOCKED_IN", true)
                            .putBoolean("IS_SYSTEM_ARMED", true)
                            .putLong("CLOCK_IN_TIMESTAMP", now)
                            .putString("ACTIVE_JOB_ID", jobId)
                            .commit()

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
                        EventReporter.addLocalLog("Background Arrival: Work time started at $jobId.")

                        val stateIntent = Intent(GeofenceBroadcastReceiver.ACTION_WORK_STATE_CHANGED).apply {
                            putExtra("IS_CLOCKED_IN", true)
                            putExtra("CLOCK_IN_TIMESTAMP", now)
                            putExtra("ACTIVE_JOB_ID", jobId)
                            setPackage(packageName)
                        }
                        sendBroadcast(stateIntent)
                    } else {
                        Log.d(TAG, "Worksite arrival detected at $jobId, but outside shift schedule. Auto clock-in skipped.")
                    }
                } else {
                    // Already clocked in, newly crossed into this worksite
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

                if (isManualClockedOut) {
                    sharedPrefs.edit()
                        .putBoolean("MANUAL_CLOCKED_OUT", false)
                        .remove("MANUAL_CLOCKED_OUT_JOB_ID")
                        .apply()
                }

                if (isClockedIn) {
                    WorkNotificationManager.showGeofenceExitNotification(this, exitedJobId)
                    EventReporter.reportEvent(
                        context = this,
                        eventType = "exit",
                        jobId = exitedJobId,
                        latitude = lat,
                        longitude = lng
                    )
                    EventReporter.addLocalLog("Worksite boundary exit ($exitedJobId)")
                }
            }
        }
    }

    private fun isCoordinateInsideJob(lat: Double, lng: Double, job: JobItem): Boolean {
        if (job.location.isEmpty()) return false
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
                if (dist[0] <= 35f) {
                    return true
                }
            }
            return false
        } else {
            for (coord in points) {
                val dist = FloatArray(1)
                android.location.Location.distanceBetween(lat, lng, coord.latitude, coord.longitude, dist)
                if (dist[0] <= 60f) {
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

    private fun loadCachedAccommodation(): AccommodationItem? {
        val geoPrefs = getSharedPreferences("GeoPrefs", Context.MODE_PRIVATE)
        val json = geoPrefs.getString("CACHED_ACCOMMODATION_JSON", null) ?: return null
        return try {
            Gson().fromJson(json, AccommodationItem::class.java)
        } catch (e: Exception) {
            null
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
        } catch (_: Exception) {}
    }
}
