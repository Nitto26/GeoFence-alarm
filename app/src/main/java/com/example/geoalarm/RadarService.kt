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
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

class RadarService : Service() {

    private val locationStateReceiver = LocationStateReceiver()
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var lastPingTimestamp = 0L

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // Dynamically register the location state receiver so it receives GPS state changes
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
            Log.d("RadarService", "LocationStateReceiver dynamically registered in Foreground Service")
        } catch (e: Exception) {
            Log.e("RadarService", "Failed to register receiver: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 1. Guard Check: Only run if worker is explicitly Clocked In
        val sharedPrefs = getSharedPreferences("GeoAlarmPrefs", Context.MODE_PRIVATE)
        val isClockedIn = sharedPrefs.getBoolean("IS_CLOCKED_IN", false)

        if (!isClockedIn) {
            Log.w("RadarService", "Worker is Clocked Out. Refusing to run background service.")
            stopSelf()
            return START_NOT_STICKY
        }

        val hasFine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (!hasFine && !hasCoarse) {
            Log.w("RadarService", "Location permissions not granted yet. Cannot start location FGS.")
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            startForegroundNotification()
            startActiveRadar()
        } catch (e: SecurityException) {
            Log.e("RadarService", "SecurityException starting foreground service: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }

        return START_STICKY
    }

    private fun startForegroundNotification() {
        val channelId = "RADAR_CHANNEL"
        val manager = getSystemService(NotificationManager::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val existing = manager.getNotificationChannel(channelId)
            if (existing == null) {
                val channel = NotificationChannel(
                    channelId,
                    "GeoAlarm Tracking Service",
                    NotificationManager.IMPORTANCE_LOW
                )
                manager.createNotificationChannel(channel)
            }
        }

        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Worker Tracker Active")
            .setContentText("Clocked In • Monitoring worksite geofences...")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                1,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(1, notification)
        }
    }

    private fun fireFullScreenAlarm() {
        Log.e("RadarService", "Destination reached! Triggering AlarmController...")
        AlarmController.triggerAlarm(this, isSabotage = false)
    }

    private fun startActiveRadar() {
        val prefs = getSharedPreferences("GeoPrefs", Context.MODE_PRIVATE)
        val targetLat = prefs.getFloat("TARGET_LAT", 0f).toDouble()
        val targetLng = prefs.getFloat("TARGET_LNG", 0f).toDouble()

        val alarmPrefs = getSharedPreferences("GeoAlarmPrefs", Context.MODE_PRIVATE)
        val dynamicTriggerRadius = alarmPrefs.getFloat("TARGET_RADIUS", 500f).toDouble()

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000)
            .setMinUpdateDistanceMeters(10f)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                // Ensure worker is still clocked in
                val isStillClockedIn = getSharedPreferences("GeoAlarmPrefs", Context.MODE_PRIVATE)
                    .getBoolean("IS_CLOCKED_IN", false)

                if (!isStillClockedIn) {
                    Log.d("RadarService", "Worker Clocked Out detected during location update. Halting.")
                    fusedLocationClient.removeLocationUpdates(this)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return
                }

                val currentJobId = getSharedPreferences("GeoAlarmPrefs", Context.MODE_PRIVATE)
                    .getString("ACTIVE_JOB_ID", "JOB-1001") ?: "JOB-1001"

                for (location in locationResult.locations) {
                    val now = System.currentTimeMillis()

                    // Periodic Ping Dispatching every 30 seconds to Admin Panel
                    if (now - lastPingTimestamp >= 30_000L) {
                        lastPingTimestamp = now
                        EventReporter.reportEvent(
                            context = this@RadarService,
                            eventType = "ping",
                            jobId = currentJobId,
                            latitude = location.latitude,
                            longitude = location.longitude
                        )
                    }

                    if (targetLat != 0.0 && targetLng != 0.0) {
                        val results = FloatArray(1)
                        android.location.Location.distanceBetween(
                            location.latitude, location.longitude,
                            targetLat, targetLng, results
                        )

                        val distance = results[0]
                        Log.d("RadarService", "Distance: ${distance.toInt()}m | Trigger at: ${dynamicTriggerRadius.toInt()}m")

                        if (distance <= dynamicTriggerRadius) {
                            Log.e("RadarService", "${dynamicTriggerRadius.toInt()}M RADIUS BREACHED! FIRING ALARM!")
                            fireFullScreenAlarm()
                            EventReporter.reportEvent(
                                context = this@RadarService,
                                eventType = "entry",
                                jobId = currentJobId,
                                latitude = location.latitude,
                                longitude = location.longitude
                            )
                            fusedLocationClient.removeLocationUpdates(this)
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        }
                    }
                }
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        if (::locationCallback.isInitialized) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }
        try {
            unregisterReceiver(locationStateReceiver)
        } catch (e: Exception) {
            Log.e("RadarService", "Receiver unregister error: ${e.message}")
        }
    }
}