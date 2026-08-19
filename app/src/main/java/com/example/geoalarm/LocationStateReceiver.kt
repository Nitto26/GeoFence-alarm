package com.example.geoalarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.example.geoalarm.network.EventReporter

class LocationStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != LocationManager.PROVIDERS_CHANGED_ACTION && action != "android.location.MODE_CHANGED") return

        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        val isLocationOn = isGpsEnabled || isNetworkEnabled

        Log.d("LocationStateReceiver", "Location toggle received: action=$action, isLocationOn=$isLocationOn")

        if (!isLocationOn) {
            Log.e("LocationStateReceiver", "Location is OFF! Triggering AlarmController & Dispatching Sabotage Event...")
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "⚠ Location is OFF! Sounding Alarm...", Toast.LENGTH_LONG).show()
            }
            // 1. Instant Siren & Lockscreen Full-Screen Alarm
            AlarmController.triggerAlarm(context, isSabotage = true)

            // 2. Dispatch live sabotage event to Admin Panel
            EventReporter.reportEvent(
                context = context,
                eventType = "location_service_off"
            )
        } else {
            Log.d("LocationStateReceiver", "Location is ON. Stopping AlarmController & Dispatching Restored Event...")
            // 1. Stop siren
            AlarmController.stopAlarm(context)

            // 2. Dispatch restored event to Admin Panel
            EventReporter.reportEvent(
                context = context,
                eventType = "location_service_on"
            )
        }
    }
}