package com.example.geoalarm.network

import android.content.Context
import android.util.Log
import com.example.geoalarm.storage.LocalEventDatabaseHelper
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

    // Deduplication tracker for non-ping state change events
    private val lastReportedEvents = java.util.concurrent.ConcurrentHashMap<String, Long>()

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
        val now = System.currentTimeMillis()
        val eventKey = "$eventType-${jobId ?: ""}"
        val lastTime = lastReportedEvents[eventKey] ?: 0L

        // Deduplicate non-ping events if reported within last 15 seconds
        if (eventType != "ping" && (now - lastTime < 15_000L)) {
            Log.d(TAG, "Suppressed duplicate event dispatch: $eventKey (already reported ${(now - lastTime)/1000}s ago)")
            return
        }
        lastReportedEvents[eventKey] = now

        val isoTimestamp = getIsoTimestamp()
        val displayJob = jobId ?: "Device"
        addLocalLog("Event recorded: $eventType ($displayJob)")

        try {
            // 1. ALWAYS persist event to 3-day local SQLite database first
            val isCurrentlyOffline = !SyncEngine.isOnlineNow(context)
            val db = LocalEventDatabaseHelper.getInstance(context)
            val recordId = db.insertEvent(
                eventType = eventType,
                jobId = jobId,
                latitude = latitude,
                longitude = longitude,
                timestamp = isoTimestamp,
                isSynced = false,
                isOffline = isCurrentlyOffline
            )
            Log.d(TAG, "Persisted event $recordId locally ($eventType, offline=$isCurrentlyOffline). Triggering auto-sync...")

            // 2. Trigger auto-sync engine (flushes immediately if online, holds safely if offline)
            SyncEngine.triggerSync(context)

        } catch (e: Exception) {
            Log.e(TAG, "Error recording local event: ${e.message}", e)
        }
    }

    private fun getIsoTimestamp(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        sdf.timeZone = TimeZone.getDefault()
        return sdf.format(Date())
    }
}
