package com.example.geoalarm.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.geoalarm.storage.LocalEventDatabaseHelper
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

data class SyncState(
    val isOnline: Boolean,
    val isSyncing: Boolean,
    val unsyncedCount: Int,
    val badgeText: String
)

object SyncEngine {

    private const val TAG = "SyncEngine"
    private const val PREFS_GEO = "GeoPrefs"
    private const val PREFS_USER = "UserPrefs"
    private const val KEY_CURSOR = "LAST_SYNCED_CURSOR"

    private val scope = CoroutineScope(Dispatchers.IO)
    private var syncJob: Job? = null
    private val gson = Gson()

    private val listeners = CopyOnWriteArrayList<(SyncState) -> Unit>()
    var onDataUpdatedListener: (() -> Unit)? = null
    private var isNetworkAvailable = false
    private var isCurrentlySyncing = false
    private val mainHandler = Handler(Looper.getMainLooper())

    // Periodic Background Sync Timer (Every 15 Seconds)
    private var appContext: Context? = null
    private val periodicSyncRunnable = object : Runnable {
        override fun run() {
            appContext?.let { ctx ->
                if (isOnlineNow(ctx)) {
                    val db = LocalEventDatabaseHelper.getInstance(ctx)
                    val (_, _, unsynced) = db.getStats()
                    if (unsynced > 0) {
                        Log.d(TAG, "Periodic sync ticker: flushing $unsynced pending events.")
                        triggerSync(ctx)
                    } else {
                        // Periodic heartbeat check
                        performHeartbeat(ctx)
                    }
                }
            }
            mainHandler.postDelayed(this, 15000)
        }
    }

    fun init(context: Context) {
        val appCtx = context.applicationContext
        appContext = appCtx

        val cm = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        isNetworkAvailable = isOnlineNow(appCtx)

        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            cm?.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.d(TAG, "Network AVAILABLE. Triggering auto-sync.")
                    isNetworkAvailable = true
                    triggerSync(appCtx)
                }

                override fun onLost(network: Network) {
                    Log.d(TAG, "Network LOST. Switched to offline cache.")
                    isNetworkAvailable = false
                    notifyStateChange(appCtx)
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback: ${e.message}")
        }

        // Start periodic sync ticker
        mainHandler.removeCallbacks(periodicSyncRunnable)
        mainHandler.postDelayed(periodicSyncRunnable, 5000)

        // Initial sync check
        notifyStateChange(appCtx)
        triggerSync(appCtx)
    }

    fun addListener(listener: (SyncState) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (SyncState) -> Unit) {
        listeners.remove(listener)
    }

    fun getCurrentState(context: Context): SyncState {
        val db = LocalEventDatabaseHelper.getInstance(context)
        val (_, _, unsyncedCount) = db.getStats()
        val online = isNetworkAvailable || isOnlineNow(context)

        val badgeText = when {
            online && unsyncedCount == 0 -> "Online: Synced"
            online && isCurrentlySyncing -> "Online: Syncing..."
            online && unsyncedCount > 0 -> "Online: Syncing ($unsyncedCount)"
            else -> "Offline: Not Sync ($unsyncedCount)"
        }

        return SyncState(
            isOnline = online,
            isSyncing = isCurrentlySyncing,
            unsyncedCount = unsyncedCount,
            badgeText = badgeText
        )
    }

    private fun notifyStateChange(context: Context) {
        val state = getCurrentState(context)
        for (listener in listeners) {
            listener.invoke(state)
        }
    }

    /**
     * Flushes local SQLite events to POST /api/v1/mobile/events/batch
     */
    fun triggerSync(context: Context, onComplete: ((Boolean) -> Unit)? = null) {
        val appCtx = context.applicationContext
        if (appContext == null) {
            init(appCtx)
        }
        if (isCurrentlySyncing) {
            onComplete?.invoke(false)
            return
        }

        syncJob?.cancel()
        syncJob = scope.launch {
            isCurrentlySyncing = true
            withContext(Dispatchers.Main) { notifyStateChange(appCtx) }

            val db = LocalEventDatabaseHelper.getInstance(appCtx)

            try {
                var totalSyncedThisRound = 0
                while (true) {
                    val unsyncedRecords = db.getUnsyncedEvents(limit = 100)
                    if (unsyncedRecords.isEmpty()) {
                        break
                    }

                    if (!isOnlineNow(appCtx)) {
                        Log.d(TAG, "Device is offline. Holding ${unsyncedRecords.size} events in local DB.")
                        break
                    }

                    Log.d(TAG, "Flushing batch of ${unsyncedRecords.size} events to /api/v1/mobile/events/batch...")

                    val eventItems = unsyncedRecords.map { record ->
                        val sanitizedEventId = sanitizeUuid(record.eventUuid) ?: UUID.randomUUID().toString()
                        val sanitizedJobId = sanitizeUuid(record.jobId)

                        MobileEventItem(
                            eventId = sanitizedEventId,
                            eventType = record.eventType.uppercase(),
                            eventTimestampUtc = record.timestamp,
                            locationCapturedAtUtc = record.timestamp,
                            latitude = if (record.latitude != 0.0 || record.longitude != 0.0) record.latitude else null,
                            longitude = if (record.latitude != 0.0 || record.longitude != 0.0) record.longitude else null,
                            accuracyMeters = 5.0f,
                            isMockLocation = record.eventType.equals("MOCK_LOCATION_DETECTED", ignoreCase = true),
                            sequenceNumber = record.id.toInt(),
                            jobId = sanitizedJobId,
                            jobItemId = null,
                            accommodationId = null
                        )
                    }

                    val request = EventBatchRequest(
                        batchId = UUID.randomUUID().toString(),
                        deviceClockUtc = EventReporter.getIsoTimestamp(Date()),
                        events = eventItems
                    )

                    ApiClient.init(appCtx)
                    val reqJson = gson.toJson(request)
                    Log.d(TAG, "EventBatchRequest payload: $reqJson")
                    val response = ApiClient.apiService.postLocationEvents(request)

                    if (response.isSuccessful && response.body() != null) {
                        val body = response.body()!!
                        val recordIds = unsyncedRecords.map { it.id }
                        db.markEventsAsSynced(recordIds)
                        totalSyncedThisRound += recordIds.size
                        Log.d(TAG, "Synced batch of ${recordIds.size} events successfully (server cursor: ${body.latestCursor})")

                        val geoPrefs = appCtx.getSharedPreferences(PREFS_GEO, Context.MODE_PRIVATE)
                        val storedCursor = geoPrefs.getLong(KEY_CURSOR, 0L)
                        if (body.latestCursor > storedCursor) {
                            pullDeltaChanges(appCtx)
                        }
                    } else {
                        val errBody = try { response.errorBody()?.string() } catch (ex: Exception) { null }
                        Log.e(TAG, "Server responded with error HTTP ${response.code()} on event batch: $errBody")
                        break
                    }
                }

                if (totalSyncedThisRound > 0) {
                    EventReporter.addLocalLog("Synced $totalSyncedThisRound events to server")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Sync error: ${e.message}")
            } finally {
                isCurrentlySyncing = false
                withContext(Dispatchers.Main) {
                    notifyStateChange(appCtx)
                    val (_, _, remaining) = db.getStats()
                    onComplete?.invoke(remaining == 0)
                }
            }
        }
    }

    /**
     * Pulls full snapshot from GET /api/v1/mobile/sync/snapshot
     */
    suspend fun pullSnapshot(context: Context): Boolean {
        val appCtx = context.applicationContext
        try {
            Log.d(TAG, "Pulling full sync snapshot from /api/v1/mobile/sync/snapshot...")
            ApiClient.init(appCtx)
            val response = ApiClient.apiService.getSyncSnapshot()

            if (!response.isSuccessful || response.body() == null) {
                Log.e(TAG, "Snapshot pull failed with HTTP ${response.code()}")
                return false
            }

            val snapshot = response.body()!!
            applyEntities(appCtx, snapshot.entities)

            val geoPrefs = appCtx.getSharedPreferences(PREFS_GEO, Context.MODE_PRIVATE)
            geoPrefs.edit().putLong(KEY_CURSOR, snapshot.cursor).apply()

            // Acknowledge applied cursor with server
            try {
                ApiClient.apiService.ackSync(SyncAckRequest(snapshot.cursor))
                Log.d(TAG, "Acknowledged snapshot cursor ${snapshot.cursor} with backend")
            } catch (ackEx: Exception) {
                Log.w(TAG, "Failed to ack snapshot cursor: ${ackEx.message}")
            }

            return true
        } catch (e: Exception) {
            Log.e(TAG, "Snapshot pull exception: ${e.message}")
            return false
        }
    }

    /**
     * Pulls delta changes from GET /api/v1/mobile/sync/changes?cursor=N
     */
    suspend fun pullDeltaChanges(context: Context): Boolean {
        val appCtx = context.applicationContext
        try {
            val geoPrefs = appCtx.getSharedPreferences(PREFS_GEO, Context.MODE_PRIVATE)
            val currentCursor = geoPrefs.getLong(KEY_CURSOR, 0L)

            Log.d(TAG, "Pulling delta changes from cursor $currentCursor...")
            ApiClient.init(appCtx)
            val response = ApiClient.apiService.getSyncChanges(cursor = currentCursor, limit = 200)

            if (!response.isSuccessful || response.body() == null) {
                Log.e(TAG, "Delta changes pull failed with HTTP ${response.code()}")
                return false
            }

            val body = response.body()!!
            if (body.resyncRequired) {
                Log.w(TAG, "Server signaled resync_required=true. Pulling fresh snapshot.")
                return pullSnapshot(appCtx)
            }

            if (body.changes.isNotEmpty()) {
                val snapshotEntities = body.changes.mapNotNull { change ->
                    if (change.op.equals("DELETE", ignoreCase = true)) {
                        null
                    } else {
                        SnapshotEntity(
                            entityType = change.entityType,
                            entityId = change.entityId,
                            entityVersion = change.entityVersion,
                            payload = change.payload
                        )
                    }
                }
                applyEntities(appCtx, snapshotEntities)
            }

            geoPrefs.edit().putLong(KEY_CURSOR, body.nextCursor).apply()

            try {
                ApiClient.apiService.ackSync(SyncAckRequest(body.nextCursor))
                Log.d(TAG, "Acknowledged delta cursor ${body.nextCursor} with backend")
            } catch (ackEx: Exception) {
                Log.w(TAG, "Failed to ack delta cursor: ${ackEx.message}")
            }

            return true
        } catch (e: Exception) {
            Log.e(TAG, "Delta changes exception: ${e.message}")
            return false
        }
    }

    /**
     * Performs a heartbeat check with POST /api/v1/mobile/heartbeat
     */
    private fun performHeartbeat(context: Context) {
        scope.launch {
            try {
                val geoPrefs = context.getSharedPreferences(PREFS_GEO, Context.MODE_PRIVATE)
                val currentCursor = geoPrefs.getLong(KEY_CURSOR, 0L)

                ApiClient.init(context)
                val request = MobileHeartbeatRequest(
                    appVersion = "2.2",
                    cursor = currentCursor,
                    locationPermissionState = "ALWAYS"
                )
                val response = ApiClient.apiService.heartbeat(request)
                if (response.isSuccessful && response.body() != null) {
                    val body = response.body()!!
                    if (body.hasPendingChanges) {
                        Log.d(TAG, "Heartbeat signaled pending changes. Pulling deltas...")
                        pullDeltaChanges(context)
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Heartbeat error: ${e.message}")
            }
        }
    }

    /**
     * Parses and applies synced snapshot entities into local SharedPreferences and models
     */
    fun applyEntities(context: Context, entities: List<SnapshotEntity>) {
        val userPrefs = context.getSharedPreferences(PREFS_USER, Context.MODE_PRIVATE)
        val geoPrefs = context.getSharedPreferences(PREFS_GEO, Context.MODE_PRIVATE)

        val rawJobs = mutableListOf<JobPayload>()
        val dayPlans = mutableListOf<DayPlanPayload>()
        val scheduleItems = mutableListOf<ScheduleItemPayload>()
        var accommodationPayload: AccommodationPayload? = null

        for (item in entities) {
            val payload = item.payload ?: continue
            try {
                when (item.entityType.uppercase()) {
                    "PROFILE" -> {
                        val p = gson.fromJson(payload, ProfilePayload::class.java)
                        userPrefs.edit()
                            .putString("WORKER_ID", p.tallyId)
                            .putString("WORKER_TALLY_NO", p.tallyId)
                            .putString("WORKER_NAME", p.name)
                            .putString("WORKER_DESIGNATION", p.staffGradeDescription ?: p.role)
                            .putString("SUPERVISOR_NAME", p.supervisor?.name ?: "")
                            .putString("SUPERVISOR_PHONE", p.supervisor?.phone ?: "")
                            .putString("WEEKLY_OFF_DAY", p.weeklyOffDay ?: "Friday")
                            .putBoolean("IS_DRIVER", p.isDriver)
                            .apply()
                        EventReporter.addLocalLog("Profile synced: ${p.name} (${p.tallyId})")
                    }

                    "SETTINGS" -> {
                        val s = gson.fromJson(payload, SettingsPayload::class.java)
                        geoPrefs.edit()
                            .putInt("GEOFENCE_RADIUS_M", s.geofenceCircleRadiusM)
                            .putInt("APPROACH_POLLING_S", s.approachPollingIntervalS)
                            .putInt("CONFIRM_POLLING_S", s.confirmationPollingIntervalS)
                            .putInt("ALARM_LEAD_MINUTES", s.alarmLeadMinutes)
                            .putString("TIMEZONE", s.timezone)
                            .apply()
                    }

                    "ACCOMMODATION" -> {
                        accommodationPayload = gson.fromJson(payload, AccommodationPayload::class.java)
                    }

                    "JOB" -> {
                        val j = gson.fromJson(payload, JobPayload::class.java)
                        rawJobs.add(j)
                    }

                    "DAY_PLAN" -> {
                        val dp = gson.fromJson(payload, DayPlanPayload::class.java)
                        dayPlans.add(dp)
                    }

                    "SCHEDULE_ITEM" -> {
                        val si = gson.fromJson(payload, ScheduleItemPayload::class.java)
                        scheduleItems.add(si)
                    }
                }
            } catch (parseEx: Exception) {
                Log.w(TAG, "Error parsing entity ${item.entityType}: ${parseEx.message}")
            }
        }

        // 1. Process Accommodation
        if (accommodationPayload != null) {
            val accCoords = mutableListOf<LocationCoordinate>()
            val boundary = accommodationPayload.boundary
            if (boundary?.coordinates != null && boundary.coordinates.isNotEmpty()) {
                val outerRing = boundary.coordinates[0]
                for (pt in outerRing) {
                    if (pt.size >= 2) {
                        accCoords.add(LocationCoordinate(latitude = pt[1], longitude = pt[0]))
                    }
                }
            } else if (accommodationPayload.geofenceCircle?.center != null) {
                accCoords.add(LocationCoordinate(
                    latitude = accommodationPayload.geofenceCircle.center.lat,
                    longitude = accommodationPayload.geofenceCircle.center.lng
                ))
            }

            val accommodationItem = AccommodationItem(
                id = accommodationPayload.accommodationId,
                code = accommodationPayload.accommodationId,
                name = accommodationPayload.name,
                address = accommodationPayload.address ?: "Living Quarters",
                location = accCoords,
                geofenceCircle = accommodationPayload.geofenceCircle
            )

            geoPrefs.edit()
                .putString("CACHED_ACCOMMODATION_JSON", gson.toJson(accommodationItem))
                .apply()
        }

        // 2. Process Day Plans & Resolve Work Hours
        if (dayPlans.isNotEmpty()) {
            geoPrefs.edit()
                .putString("CACHED_DAY_PLANS_JSON", gson.toJson(dayPlans))
                .apply()
        }

        // Cache Schedule Items
        if (scheduleItems.isNotEmpty()) {
            geoPrefs.edit()
                .putString("CACHED_SCHEDULE_ITEMS_JSON", gson.toJson(scheduleItems))
                .apply()
        }

        val todayDate = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(Date())
        val todayPlan = dayPlans.find { it.workDate == todayDate } ?: dayPlans.firstOrNull()

        val weeklyOffDayStr = userPrefs.getString("WEEKLY_OFF_DAY", "Friday") ?: "Friday"
        val allDaysShort = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        val dynamicWorkingDays = allDaysShort.filter { d ->
            !d.startsWith(weeklyOffDayStr.trim().take(3), ignoreCase = true)
        }.ifEmpty { listOf("Mon", "Tue", "Wed", "Thu", "Sat", "Sun") }

        // 3. Process Worksite Jobs
        val jobItems = mutableListOf<JobItem>()
        if (rawJobs.isNotEmpty()) {
            jobItems.addAll(rawJobs.map { rawJob ->
                val coords = mutableListOf<LocationCoordinate>()
                val boundary = rawJob.boundary
                if (boundary?.coordinates != null && boundary.coordinates.isNotEmpty()) {
                    val outerRing = boundary.coordinates[0]
                    for (pt in outerRing) {
                        if (pt.size >= 2) {
                            coords.add(LocationCoordinate(latitude = pt[1], longitude = pt[0]))
                        }
                    }
                } else if (rawJob.geofenceCircle?.center != null) {
                    coords.add(LocationCoordinate(
                        latitude = rawJob.geofenceCircle.center.lat,
                        longitude = rawJob.geofenceCircle.center.lng
                    ))
                }

                JobItem(
                    jobId = rawJob.jobId,
                    jobTitle = rawJob.jobName ?: rawJob.jobNumber ?: "Worksite",
                    address = rawJob.jobNumber ?: "",
                    location = coords,
                    days = dynamicWorkingDays,
                    startTime = todayPlan?.planned?.firstSiteStart ?: "06:00",
                    endTime = todayPlan?.planned?.lastSiteEnd ?: "15:30",
                    breakDurationMinutes = todayPlan?.planned?.breakMinutes ?: 30,
                    siteType = "site",
                    isStartingPoint = false,
                    schedulePeriod = todayPlan?.workDate,
                    scheduledDate = todayPlan?.workDate,
                    date = todayPlan?.workDate,
                    geofenceCircle = rawJob.geofenceCircle
                )
            })
        } else if (scheduleItems.isNotEmpty()) {
            // Synthesize from schedule items if rawJobs was empty
            val groupedByJob = scheduleItems.groupBy { it.jobId }
            for ((jobId, items) in groupedByJob) {
                val firstItem = items.firstOrNull()
                jobItems.add(
                    JobItem(
                        jobId = jobId,
                        jobTitle = firstItem?.jobItemSummary?.staffGradeDescription ?: "Worksite ($jobId)",
                        address = "Assigned Scheduled Work",
                        location = emptyList(),
                        days = dynamicWorkingDays,
                        startTime = todayPlan?.planned?.firstSiteStart ?: "06:00",
                        endTime = todayPlan?.planned?.lastSiteEnd ?: "15:30",
                        breakDurationMinutes = todayPlan?.planned?.breakMinutes ?: 30,
                        siteType = "site",
                        isStartingPoint = false,
                        schedulePeriod = todayPlan?.workDate,
                        scheduledDate = todayPlan?.workDate,
                        date = todayPlan?.workDate,
                        geofenceCircle = null
                    )
                )
            }
        }

        // ALWAYS save CACHED_JOBS_JSON (even if empty, so unassignments take effect)
        geoPrefs.edit()
            .putString("CACHED_JOBS_JSON", gson.toJson(jobItems))
            .apply()

        // Notify active activities to refresh UI with fresh server data immediately
        mainHandler.post {
            onDataUpdatedListener?.invoke()
        }
    }

    fun isOnlineNow(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val activeNetwork = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun sanitizeUuid(value: String?): String? {
        if (value.isNullOrBlank()) return null
        return try {
            UUID.fromString(value.trim()).toString()
        } catch (e: Exception) {
            null
        }
    }
}
