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
        longitude: Double = 0.0
    ) {
        val now = System.currentTimeMillis()
        val normalizedJobId = jobId?.trim()?.ifEmpty { null }

        // STRICT STATE TRANSITION VALIDATION & DEDUPLICATION:
        if (eventType != "ping") {
            // Rule 1: Cannot EXIT if not currently inside a worksite OR if last reported event was already EXIT
            if (eventType == "exit") {
                if (currentInsideJobId == null || lastNonPingEventType == "exit") {
                    Log.d(TAG, "Suppressed redundant EXIT event: already outside worksite.")
                    return
                }
            }

            // Rule 2: Cannot ENTRY if already recorded inside this exact worksite
            if (eventType == "entry") {
                if (currentInsideJobId == normalizedJobId && lastNonPingEventType == "entry") {
                    Log.d(TAG, "Suppressed redundant ENTRY event: already inside $normalizedJobId.")
                    return
                }
            }

            // Rule 3: Debounce identical non-ping events within 20 seconds
            val eventKey = "$eventType-${normalizedJobId ?: ""}"
            val lastTime = lastReportedEvents[eventKey] ?: 0L
            if (now - lastTime < 20_000L) {
                Log.d(TAG, "Suppressed rapid duplicate event: $eventKey within 20s")
                return
            }
            lastReportedEvents[eventKey] = now
        }

        // Update state tracking
        if (eventType != "ping") {
            lastNonPingEventType = eventType
            when (eventType) {
                "entry", "clock_in" -> currentInsideJobId = normalizedJobId
                "exit", "clock_out" -> currentInsideJobId = null
                "accommodation_entry" -> currentInsideJobId = "accommodation"
                "accommodation_exit" -> currentInsideJobId = null
            }
        }

        val isoTimestamp = getIsoTimestamp()
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
