package com.example.geoalarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.util.Log
import com.example.geoalarm.network.EventReporter

class ShiftAlarmReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "ShiftAlarmReceiver"
        const val ACTION_SHIFT_START_ALARM = "com.example.geoalarm.ACTION_SHIFT_START_ALARM"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d(TAG, "ShiftAlarmReceiver triggered: action=$action")

        if (action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d(TAG, "Device rebooted. Rescheduling shift alarms from cached API schedule.")
            ShiftAlarmScheduler.scheduleNextShiftAlarm(context)
            return
        }

        val jobName = intent.getStringExtra("JOB_NAME") ?: "Work Shift"

        // 1. Verify if Location Services (GPS) are currently enabled
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        val isLocationOn = isGpsEnabled || isNetworkEnabled

        Log.d(TAG, "Shift start evaluation for $jobName: isLocationOn=$isLocationOn")

        if (!isLocationOn) {
            // Location is OFF when shift starts -> Trigger Turn On Location Siren & Full-Screen Alarm!
            Log.e(TAG, "⚠ Location is OFF at Shift Start Time! Sounding Turn-On-Location Alarm...")
            AlarmController.triggerAlarm(context, isSabotage = false)
            EventReporter.addLocalLog("Shift Start Alert: Location was OFF when $jobName started. Sounding Alarm.")
        } else {
            // Location is ON -> Post shift start notification to worker
            Log.d(TAG, "Location is ON. Posting Shift Start Notification for $jobName.")
            WorkNotificationManager.showShiftScheduleNotification(
                context = context,
                jobName = jobName,
                timeText = "Your scheduled shift has started! Tracking is active."
            )
            EventReporter.addLocalLog("Shift Started: $jobName. Location active.")
        }

        // 2. Schedule the next upcoming shift alarm
        ShiftAlarmScheduler.scheduleNextShiftAlarm(context)
    }
}
