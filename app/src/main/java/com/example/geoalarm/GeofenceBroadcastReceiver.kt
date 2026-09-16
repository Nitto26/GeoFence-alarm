package com.example.geoalarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.geoalarm.network.EventReporter
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent

class GeofenceBroadcastReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "GeofenceReceiver"
        private const val CHANNEL_ID = "WORK_TIME_CHANNEL"
        private const val NOTIFICATION_ID_ENTRY = 1001
        private const val NOTIFICATION_ID_EXIT = 1002
        const val ACTION_WORK_STATE_CHANGED = "com.example.geoalarm.ACTION_WORK_STATE_CHANGED"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val userPrefs = context.getSharedPreferences("UserPrefs", Context.MODE_PRIVATE)
        val workerId = userPrefs.getString("WORKER_ID", "")
        if (workerId.isNullOrEmpty()) {
            Log.d(TAG, "No active worker logged in. Ignoring geofence event.")
            return
        }

        val geofencingEvent = GeofencingEvent.fromIntent(intent) ?: return

        if (geofencingEvent.hasError()) {
            Log.e(TAG, "Geofence error code: ${geofencingEvent.errorCode}")
            return
        }

        val transition = geofencingEvent.geofenceTransition
        val triggeringGeofences = geofencingEvent.triggeringGeofences ?: emptyList()
        val triggeringLocation = geofencingEvent.triggeringLocation

        // Guard: check cached accommodation if present
        val geoPrefs = context.getSharedPreferences("GeoPrefs", Context.MODE_PRIVATE)
        val accJson = geoPrefs.getString("CACHED_ACCOMMODATION_JSON", null)
        val cachedAcc = if (accJson != null) {
            try {
                com.google.gson.Gson().fromJson(accJson, com.example.geoalarm.network.AccommodationItem::class.java)
            } catch (_: Exception) { null }
        } else null

        val lat = triggeringLocation?.latitude ?: (cachedAcc?.location?.firstOrNull()?.latitude ?: 0.0)
        val lng = triggeringLocation?.longitude ?: (cachedAcc?.location?.firstOrNull()?.longitude ?: 0.0)

        if (cachedAcc != null && cachedAcc.location.isNotEmpty()) {
            val stayPt = cachedAcc.location[0]
            val stayDist = FloatArray(1)
            android.location.Location.distanceBetween(lat, lng, stayPt.latitude, stayPt.longitude, stayDist)
            if (stayDist[0] <= 70f) {
                Log.d(TAG, "Triggering location is inside Stay Accommodation (${stayDist[0].toInt()}m). Ignoring worksite entry.")
                return
            }
        }

        val sharedPrefs = context.getSharedPreferences("GeoAlarmPrefs", Context.MODE_PRIVATE)
        val isAlreadyClockedIn = sharedPrefs.getBoolean("IS_CLOCKED_IN", false)

        for (geofence in triggeringGeofences) {
            val jobId = geofence.requestId

            if (transition == Geofence.GEOFENCE_TRANSITION_ENTER) {
                Log.d(TAG, "🟢 Worksite ENTER detected for $jobId at ($lat, $lng)")

                val now = System.currentTimeMillis()

                if (!isAlreadyClockedIn) {
                    // 1. AUTOMATICALLY START WORK & PAYROLL TIME
                    sharedPrefs.edit()
                        .putBoolean("IS_CLOCKED_IN", true)
                        .putBoolean("IS_SYSTEM_ARMED", true)
                        .putLong("CLOCK_IN_TIMESTAMP", now)
                        .putString("ACTIVE_JOB_ID", jobId)
                        .apply()

                    Log.d(TAG, "✓ Work time & Payroll timer automatically started at $now")

                    // 2. Dispatch events to Backend Telemetry (Cloud & Local SQLite)
                    EventReporter.reportEvent(
                        context = context,
                        eventType = "clock_in",
                        jobId = jobId,
                        latitude = lat,
                        longitude = lng
                    )
                    EventReporter.reportEvent(
                        context = context,
                        eventType = "entry",
                        jobId = jobId,
                        latitude = lat,
                        longitude = lng
                    )
                    EventReporter.addLocalLog("✓ Work time started ($jobId). Payroll timer running.")

                    // 3. Post OS-Level Notification to Worker
                    WorkNotificationManager.showClockInNotification(context, jobId, isAuto = true)

                    // 4. Start Foreground Radar Service for continuous tracking
                    val serviceIntent = Intent(context, RadarService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }

                    // 5. Broadcast state change to update MainActivity UI in real time
                    val stateIntent = Intent(ACTION_WORK_STATE_CHANGED).apply {
                        putExtra("IS_CLOCKED_IN", true)
                        putExtra("CLOCK_IN_TIMESTAMP", now)
                        putExtra("ACTIVE_JOB_ID", jobId)
                        setPackage(context.packageName)
                    }
                    context.sendBroadcast(stateIntent)

                } else {
                    // Worker was already clocked in, log entry event
                    EventReporter.reportEvent(
                        context = context,
                        eventType = "entry",
                        jobId = jobId,
                        latitude = lat,
                        longitude = lng
                    )
                    EventReporter.addLocalLog("Worksite Entry: Inside $jobId.")

                    WorkNotificationManager.showGeofenceEntryNotification(context, jobId)
                }

            } else if (transition == Geofence.GEOFENCE_TRANSITION_EXIT) {
                Log.d(TAG, "🟡 Worksite EXIT detected for $jobId at ($lat, $lng)")

                if (isAlreadyClockedIn) {
                    EventReporter.reportEvent(
                        context = context,
                        eventType = "exit",
                        jobId = jobId,
                        latitude = lat,
                        longitude = lng
                    )
                    EventReporter.addLocalLog("⚠️ Worksite boundary exit ($jobId)")

                    WorkNotificationManager.showGeofenceExitNotification(context, jobId)
                }
            }
        }
    }
}