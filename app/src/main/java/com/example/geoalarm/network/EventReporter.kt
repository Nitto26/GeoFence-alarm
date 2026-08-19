package com.example.geoalarm.network

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArrayList

object EventReporter {

    private const val TAG = "EventReporter"

    // Thread-safe repository for live system logs shown on Notification Dialog click
    val liveSystemLogs = CopyOnWriteArrayList<String>()

    init {
        addLocalLog("System initialized. Monitoring active.")
    }

    fun addLocalLog(message: String) {
        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        liveSystemLogs.add(0, "[$timeStr] $message")
        if (liveSystemLogs.size > 50) {
            liveSystemLogs.removeAt(liveSystemLogs.size - 1)
        }
    }

    fun reportEvent(
        context: Context,
        eventType: String,
        jobId: String? = null,
        latitude: Double = 0.0,
        longitude: Double = 0.0
    ) {
        ApiClient.init(context)

        val isoTimestamp = getIsoTimestamp()
        val eventItem = LocationEventItem(
            jobId = jobId,
            location = LocationCoordinate(latitude, longitude),
            timestamp = isoTimestamp,
            eventType = eventType
        )

        val request = LocationEventRequest(events = listOf(eventItem))
        
        val displayJob = jobId ?: "Device"
        addLocalLog("Event triggered: $eventType ($displayJob)")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d(TAG, "Dispatching event: $eventType for job: $jobId ($latitude, $longitude)")
                val response = ApiClient.apiService.postLocationEvents(request)
                if (response.isSuccessful) {
                    val body = response.body()
                    Log.d(TAG, "✓ Event accepted by Admin Panel! TX: ${body?.transactionId}")
                    addLocalLog("✓ Event logged: $eventType. TX: ${body?.transactionId?.take(8)}")
                } else {
                    Log.e(TAG, "Event rejected with code: ${response.code()} - ${response.errorBody()?.string()}")
                    addLocalLog("⚠ Event rejected: $eventType (HTTP ${response.code()})")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send event to backend: ${e.message}")
                addLocalLog("⚠ Failed to send event to backend: ${e.message}")
            }
        }
    }

    private fun getIsoTimestamp(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        sdf.timeZone = TimeZone.getDefault()
        return sdf.format(Date())
    }
}
