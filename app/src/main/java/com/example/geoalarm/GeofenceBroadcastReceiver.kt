package com.example.geoalarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.geoalarm.network.EventReporter
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent

class GeofenceBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val geofencingEvent = GeofencingEvent.fromIntent(intent) ?: return

        if (geofencingEvent.hasError()) {
            Log.e("GeofenceReceiver", "Geofence error: ${geofencingEvent.errorCode}")
            return
        }

        val transition = geofencingEvent.geofenceTransition
        val triggeringGeofences = geofencingEvent.triggeringGeofences ?: emptyList()
        val triggeringLocation = geofencingEvent.triggeringLocation

        val lat = triggeringLocation?.latitude ?: 0.0
        val lng = triggeringLocation?.longitude ?: 0.0

        for (geofence in triggeringGeofences) {
            val jobId = geofence.requestId

            if (transition == Geofence.GEOFENCE_TRANSITION_ENTER) {
                Log.d("GeofenceReceiver", "Geofence ENTER: $jobId ($lat, $lng)")

                // Dispatch 'entry' event to Admin Panel
                EventReporter.reportEvent(
                    context = context,
                    eventType = "entry",
                    jobId = jobId,
                    latitude = lat,
                    longitude = lng
                )

                // Start Foreground Radar Service
                val serviceIntent = Intent(context, RadarService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } else if (transition == Geofence.GEOFENCE_TRANSITION_EXIT) {
                Log.d("GeofenceReceiver", "Geofence EXIT: $jobId ($lat, $lng)")

                // Dispatch 'exit' event to Admin Panel
                EventReporter.reportEvent(
                    context = context,
                    eventType = "exit",
                    jobId = jobId,
                    latitude = lat,
                    longitude = lng
                )
            }
        }
    }
}