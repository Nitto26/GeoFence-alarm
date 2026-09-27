package com.example.geoalarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.example.geoalarm.network.EventReporter
import com.example.geoalarm.network.JobItem
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class LocationStateReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "LocationStateReceiver"
        private val handler = Handler(Looper.getMainLooper())
        private var pendingOffRunnable: Runnable? = null
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != LocationManager.PROVIDERS_CHANGED_ACTION && action != "android.location.MODE_CHANGED") return

        val sharedPrefs = context.getSharedPreferences("GeoAlarmPrefs", Context.MODE_PRIVATE)
        val isClockedIn = sharedPrefs.getBoolean("IS_CLOCKED_IN", false)

        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        val isLocationOn = isGpsEnabled || isNetworkEnabled

        Log.d(TAG, "Location state changed: isLocationOn=$isLocationOn, isClockedIn=$isClockedIn")

        // 1. DEDUPLICATION: Prevent repeated GPS OFF / ON messages
        val lastReportedState = if (sharedPrefs.contains("LAST_LOCATION_STATE")) {
            sharedPrefs.getBoolean("LAST_LOCATION_STATE", true)
        } else {
            null
        }

        if (isLocationOn) {
            // Cancel any pending debounced "GPS OFF" sabotage check
            pendingOffRunnable?.let { handler.removeCallbacks(it) }
            pendingOffRunnable = null

            // Stop alarm if it was sounding
            AlarmController.stopAlarm(context)

            // Only report location_service_on if state actually transitioned from OFF to ON
            if (lastReportedState == false) {
                sharedPrefs.edit().putBoolean("LAST_LOCATION_STATE", true).apply()
                Log.d(TAG, "Location restored from OFF -> ON. Dispatching Restored Event.")
                EventReporter.reportEvent(
                    context = context,
                    eventType = "location_service_on"
                )
            } else if (lastReportedState == null) {
                sharedPrefs.edit().putBoolean("LAST_LOCATION_STATE", true).apply()
            }

            // Immediately poke RadarService to sample location and auto clock-in / enter worksite if inside!
            try {
                val serviceIntent = Intent(context, RadarService::class.java).apply {
                    setAction("com.example.geoalarm.ACTION_LOCATION_RESTORED")
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Could not notify RadarService of location restoration: ${e.message}")
            }
            return
        }

        // Location is currently OFF
        // If worker is clocked out, never sound alarm
        if (!isClockedIn) {
            Log.d(TAG, "Worker is Clocked Out (Sleep Mode). Ignoring location OFF.")
            sharedPrefs.edit().putBoolean("LAST_LOCATION_STATE", false).apply()
            return
        }

        // 2. ACCOMMODATION & OFF-DUTY CHECK
        // If worker is at accommodation or outside shift schedule, allow turning GPS off freely!
        val wasInsideStay = sharedPrefs.getBoolean("WAS_INSIDE_STAY", false)
        val geoPrefs = context.getSharedPreferences("GeoPrefs", Context.MODE_PRIVATE)
        val cachedJobsJson = geoPrefs.getString("CACHED_JOBS_JSON", null)
        val cachedJobs: List<JobItem> = if (cachedJobsJson != null) {
            try {
                val type = object : TypeToken<List<JobItem>>() {}.type
                Gson().fromJson(cachedJobsJson, type) ?: emptyList()
            } catch (_: Exception) { emptyList() }
        } else emptyList()

        val isWithinShift = if (cachedJobs.isNotEmpty()) {
            ShiftScheduleHelper.isAnyJobWithinShiftHours(cachedJobs)
        } else {
            true // default to true if no schedule
        }

        if (wasInsideStay || !isWithinShift) {
            Log.d(TAG, "Worker turned GPS off while at accommodation or outside shift hours. Auto-ending shift cleanly. No alarm.")
            sharedPrefs.edit()
                .putBoolean("IS_CLOCKED_IN", false)
                .putBoolean("IS_SYSTEM_ARMED", false)
                .putBoolean("LAST_LOCATION_STATE", false)
                .apply()
            AlarmController.stopAlarm(context)
            EventReporter.addLocalLog("GPS switched off at accommodation/off-duty. Shift auto-ended cleanly.")
            return
        }

        // If already reported OFF, don't repeat alarm or message
        if (lastReportedState == false) {
            Log.d(TAG, "Location is already OFF and reported. Suppressing duplicate alert.")
            return
        }

        // 3. DEBOUNCE GPS OFF (2.5 seconds): Prevent false alarms during brief handover or network glitch
        pendingOffRunnable?.let { handler.removeCallbacks(it) }
        pendingOffRunnable = Runnable {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val stillGps = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
            val stillNet = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            val stillOn = stillGps || stillNet

            if (!stillOn) {
                val stillClockedIn = sharedPrefs.getBoolean("IS_CLOCKED_IN", false)
                val stillInsideStay = sharedPrefs.getBoolean("WAS_INSIDE_STAY", false)

                if (stillClockedIn && !stillInsideStay) {
                    Log.e(TAG, "Confirmed Location is OFF while Clocked In! Triggering Alarm & Event...")
                    sharedPrefs.edit().putBoolean("LAST_LOCATION_STATE", false).apply()

                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(context, "⚠ Location is OFF! Please enable GPS.", Toast.LENGTH_LONG).show()
                    }

                    // Instant Siren & Lockscreen Full-Screen Alarm
                    AlarmController.triggerAlarm(context, isSabotage = true)

                    // Dispatch sabotage event to Admin Panel
                    EventReporter.reportEvent(
                        context = context,
                        eventType = "location_service_off"
                    )
                }
            } else {
                Log.d(TAG, "Location flicker resolved within debounce window. No alarm sounded.")
            }
        }
        handler.postDelayed(pendingOffRunnable!!, 2500L)
    }
}