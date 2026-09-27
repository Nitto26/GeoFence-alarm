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

    @Volatile
    var currentInsideJobId: String? = null
        private set

    @Volatile
    var lastNonPingEventType: String? = null
        private set

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
        longitude: Double = 0.0,
        eventTimestamp: Long? = null
    ) {
        val now = System.currentTimeMillis()
        val normalizedJobId = jobId?.trim()?.ifEmpty { null }
        val sharedPrefs = context.getSharedPreferences("GeoAlarmPrefs", Context.MODE_PRIVATE)

        val persistedInsideJobId = sharedPrefs.getString("CURRENT_INSIDE_JOB_ID", null) ?: sharedPrefs.getString("ACTIVE_JOB_ID", null)
        val effectiveInsideJobId = currentInsideJobId ?: persistedInsideJobId
        val effectiveLastEventType = lastNonPingEventType ?: sharedPrefs.getString("LAST_NON_PING_EVENT_TYPE", null)

        // STRICT STATE TRANSITION VALIDATION & DEDUPLICATION:
        if (eventType != "ping") {
            // Rule 1: Cannot EXIT if already outside worksite AND last event was already exit
            if (eventType == "exit") {
                if (effectiveInsideJobId == null && effectiveLastEventType == "exit") {
                    Log.d(TAG, "Suppressed redundant EXIT event: already outside worksite.")
                    return
                }
            }

            // Rule 2: Cannot ENTRY if already recorded inside this exact worksite
            if (eventType == "entry") {
                if (effectiveInsideJobId == normalizedJobId && effectiveLastEventType == "entry") {
                    Log.d(TAG, "Suppressed redundant ENTRY event: already inside $normalizedJobId.")
                    return
                }
            }

            // Rule 3: Debounce identical non-ping events within 15 seconds
            val eventKey = "$eventType-${normalizedJobId ?: ""}"
            val lastTime = lastReportedEvents[eventKey] ?: 0L
            if (now - lastTime < 15_000L) {
                Log.d(TAG, "Suppressed rapid duplicate event: $eventKey within 15s")
                return
            }
            lastReportedEvents[eventKey] = now
        }

        // Update state tracking in memory and persist across process kills
        if (eventType != "ping") {
            lastNonPingEventType = eventType
            when (eventType) {
                "entry", "clock_in" -> currentInsideJobId = normalizedJobId
                "exit", "clock_out" -> currentInsideJobId = null
                "accommodation_entry" -> currentInsideJobId = "accommodation"
                "accommodation_exit" -> currentInsideJobId = null
            }

            sharedPrefs.edit()
                .putString("CURRENT_INSIDE_JOB_ID", currentInsideJobId)
                .putString("LAST_NON_PING_EVENT_TYPE", lastNonPingEventType)
                .apply()
        }

        val eventDate = if (eventTimestamp != null && eventTimestamp > 0L) Date(eventTimestamp) else Date()
        val isoTimestamp = getIsoTimestamp(eventDate)
        val displayJob = normalizedJobId ?: "Device"
        addLocalLog("Event recorded: $eventType ($displayJob)")

        try {
            // 1. ALWAYS persist event to 3-day local SQLite database first
            val isCurrentlyOffline = !SyncEngine.isOnlineNow(context)
            val db = LocalEventDatabaseHelper.getInstance(context)
            val recordId = db.insertEvent(
                eventType = eventType,
                jobId = normalizedJobId,
                latitude = latitude,
                longitude = longitude,
                timestamp = isoTimestamp,
                isSynced = false,
                isOffline = isCurrentlyOffline
            )
            Log.d(TAG, "Persisted event $recordId locally ($eventType, offline=$isCurrentlyOffline, ts=$isoTimestamp). Triggering auto-sync...")

            // 2. Trigger auto-sync engine (flushes immediately if online, holds safely if offline)
            SyncEngine.triggerSync(context)

        } catch (e: Exception) {
            Log.e(TAG, "Error recording local event: ${e.message}", e)
        }
    }

    private fun getIsoTimestamp(date: Date = Date()): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        sdf.timeZone = TimeZone.getDefault()
        return sdf.format(date)
    }
}
