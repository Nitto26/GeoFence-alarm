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
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
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
            unsyncedCount == 0 -> "Online: Synced"
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
     * Flushes local SQLite events to POST /api/v1/mobile/events/batch according to the contract
     */
    fun triggerSync(
        context: Context,
        isForceSync: Boolean = false,
        onComplete: ((Boolean, String?) -> Unit)? = null
    ) {
        val appCtx = context.applicationContext
        if (appContext == null) {
            init(appCtx)
        }
        if (isCurrentlySyncing) {
            onComplete?.invoke(false, "Sync already in progress")
            return
        }

        syncJob?.cancel()
        syncJob = scope.launch {
            isCurrentlySyncing = true
            withContext(Dispatchers.Main) { notifyStateChange(appCtx) }

            val db = LocalEventDatabaseHelper.getInstance(appCtx)
            var lastErrorMessage: String? = null

            try {
                var totalSyncedThisRound = 0
                while (true) {
                    val unsyncedRecords = db.getUnsyncedEvents(limit = 100)
                    if (unsyncedRecords.isEmpty()) {
                        break
                    }

                    val currentlyOnline = isNetworkAvailable || isOnlineNow(appCtx)
                    if (!isForceSync && !currentlyOnline) {
                        Log.d(TAG, "Device appears offline. Holding ${unsyncedRecords.size} events in local DB.")
                        lastErrorMessage = "Device is offline. Events queued locally."
                        break
                    }

                    Log.d(TAG, "Flushing batch of ${unsyncedRecords.size} events to /api/v1/mobile/events/batch...")

                    val eventItems = unsyncedRecords.map { record ->
                        val rawType = record.eventType.uppercase().trim()
                        val isAccommodation = rawType.contains("ACCOMMODATION") || rawType.contains("CAMP") || record.jobId.equals("ACCOMMODATION", ignoreCase = true)
                        val isMock = rawType.contains("MOCK") || record.eventType.equals("MOCK_LOCATION_DETECTED", ignoreCase = true)

                        val safeEventType = when {
                            rawType == "LOCATION_DISABLED" || rawType.contains("OFF") -> "LOCATION_DISABLED"
                            rawType == "LOCATION_ENABLED" || rawType.contains("ON") -> "LOCATION_ENABLED"
                            rawType in listOf("ENTRY", "CLOCK_IN", "ACCOMMODATION_ENTRY", "CAMP_ENTRY") -> "ENTRY"
                            rawType in listOf("EXIT", "CLOCK_OUT", "ACCOMMODATION_EXIT", "CAMP_EXIT") -> "EXIT"
                            else -> "PING"
                        }

                        val cachedAccId = getCachedAccommodationId(appCtx)
                        val finalAccommodationId: String? = if (isAccommodation) {
                            sanitizeUuid(record.jobId) ?: cachedAccId
                        } else null

                        val finalJobId: String? = if (isAccommodation) {
                            null
                        } else {
                            sanitizeUuid(record.jobId)
                        }

                        // Resolve coordinates if 0.0
                        var lat: Double? = if (record.latitude != 0.0 || record.longitude != 0.0) record.latitude else null
                        var lng: Double? = if (record.latitude != 0.0 || record.longitude != 0.0) record.longitude else null

                        if (lat == null || lng == null) {
                            val fallback = getFallbackCoordinates(appCtx, record.jobId, isAccommodation)
                            if (fallback != null) {
                                lat = fallback.first
                                lng = fallback.second
                            }
                        }

                        // Coordinates boundary check
                        if (lat != null && (lat < -90.0 || lat > 90.0)) lat = null
                        if (lng != null && (lng < -180.0 || lng > 180.0)) lng = null

                        val sanitizedEventId = sanitizeUuid(record.eventUuid) ?: UUID.randomUUID().toString()
                        val isoTimestamp = ensureIsoUtc(record.timestamp)

                        MobileEventItem(
                            eventId = sanitizedEventId,
                            eventType = safeEventType,
                            eventTimestampUtc = isoTimestamp,
                            locationCapturedAtUtc = if (lat != null && lng != null) isoTimestamp else null,
                            latitude = lat,
                            longitude = lng,
                            accuracyMeters = 5.0f,
                            isMockLocation = isMock,
                            sequenceNumber = record.id.toInt(),
                            jobId = finalJobId,
                            jobItemId = null,
                            accommodationId = finalAccommodationId
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
                        isNetworkAvailable = true
                        val body = response.body()!!
                        val recordIds = unsyncedRecords.map { it.id }
                        db.markEventsAsSynced(recordIds)
                        totalSyncedThisRound += recordIds.size
                        Log.d(TAG, "Synced batch of ${recordIds.size} events successfully (server cursor: ${body.latestCursor})")

                        body.results.forEach { res ->
                            if (res.status.equals("REJECTED", ignoreCase = true)) {
                                Log.w(TAG, "Server rejected event ${res.eventId}: ${res.reason}")
                            }
                        }

                        val geoPrefs = appCtx.getSharedPreferences(PREFS_GEO, Context.MODE_PRIVATE)
                        val storedCursor = geoPrefs.getLong(KEY_CURSOR, 0L)
                        if (body.latestCursor > storedCursor) {
                            pullDeltaChanges(appCtx)
                        }
                    } else {
                        val code = response.code()
                        val errBody = try { response.errorBody()?.string() } catch (ex: Exception) { null }
                        Log.e(TAG, "Server responded with error HTTP $code on event batch: $errBody")

                        if (code == 422) {
                            // Hard schema mismatch on these specific records: purge from queue so sync does not stall forever
                            val badIds = unsyncedRecords.map { it.id }
                            db.markEventsAsSynced(badIds)
                            EventReporter.addLocalLog("Schema rejected ${badIds.size} events (HTTP 422). Cleared queue.")
                            continue
                        } else if (code == 403) {
                            lastErrorMessage = "Server access denied (HTTP 403). Tracking paused."
                            break
                        } else {
                            lastErrorMessage = "Server error (HTTP $code). Retrying later."
                            break
                        }
                    }
                }

                if (totalSyncedThisRound > 0) {
                    EventReporter.addLocalLog("Synced $totalSyncedThisRound events to server")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Sync error: ${e.message}")
                lastErrorMessage = if (e is java.net.UnknownHostException || e is java.net.ConnectException || e is java.net.SocketTimeoutException) {
                    "Cannot connect to server. Check your internet connection."
                } else {
                    "Sync error: ${e.localizedMessage ?: "Unknown error"}"
                }
            } finally {
                isCurrentlySyncing = false
                withContext(Dispatchers.Main) {
                    notifyStateChange(appCtx)
                    val (_, _, remaining) = db.getStats()
                    onComplete?.invoke(remaining == 0, lastErrorMessage)
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
            isNetworkAvailable = true
            withContext(Dispatchers.Main) { notifyStateChange(appCtx) }
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
            isNetworkAvailable = true
            withContext(Dispatchers.Main) { notifyStateChange(appCtx) }
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
                    isNetworkAvailable = true
                    withContext(Dispatchers.Main) { notifyStateChange(context) }
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

                        val phoneVal = p.phone?.ifBlank { null } ?: try {
                            if (payload.isJsonObject) {
                                val obj = payload.asJsonObject
                                when {
                                    obj.has("phone") && !obj.get("phone").isJsonNull -> obj.get("phone").asString
                                    obj.has("phone_number") && !obj.get("phone_number").isJsonNull -> obj.get("phone_number").asString
                                    obj.has("mobile") && !obj.get("mobile").isJsonNull -> obj.get("mobile").asString
                                    else -> null
                                }
                            } else null
                        } catch (e: Exception) { null }

                        val emailVal = p.email?.ifBlank { null } ?: try {
                            if (payload.isJsonObject && payload.asJsonObject.has("email") && !payload.asJsonObject.get("email").isJsonNull) {
                                payload.asJsonObject.get("email").asString
                            } else null
                        } catch (e: Exception) { null }

                        val editor = userPrefs.edit()
                            .putString("WORKER_ID", p.tallyId)
                            .putString("WORKER_TALLY_NO", p.tallyId)
                            .putString("WORKER_NAME", p.name)
                            .putString("WORKER_DESIGNATION", p.staffGradeDescription ?: p.role)
                            .putString("SUPERVISOR_NAME", p.supervisor?.name ?: "")
                            .putString("SUPERVISOR_PHONE", p.supervisor?.phone ?: "")
                            .putString("WEEKLY_OFF_DAY", p.weeklyOffDay ?: "Friday")
                            .putBoolean("IS_DRIVER", p.isDriver)

                        if (!phoneVal.isNullOrBlank()) {
                            editor.putString("WORKER_PHONE", phoneVal)
                            editor.putString("PHONE", phoneVal)
                        }
                        if (!emailVal.isNullOrBlank()) {
                            editor.putString("WORKER_EMAIL", emailVal)
                            editor.putString("EMAIL", emailVal)
                        }
                        editor.apply()
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
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        try {
            val activeNetwork = cm.activeNetwork
            if (activeNetwork != null) {
                val capabilities = cm.getNetworkCapabilities(activeNetwork)
                if (capabilities != null) {
                    if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                        return true
                    }
                }
            }
            for (network in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(network) ?: continue
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    return true
                }
            }
            @Suppress("DEPRECATION")
            val networkInfo = cm.activeNetworkInfo
            if (networkInfo != null && (networkInfo.isConnected || networkInfo.isConnectedOrConnecting)) {
                return true
            }
        } catch (e: Exception) {
            return true
        }
        return false
    }

    fun sanitizeUuid(value: String?): String? {
        if (value.isNullOrBlank()) return null
        return try {
            UUID.fromString(value.trim()).toString()
        } catch (e: Exception) {
            null
        }
    }

    fun ensureIsoUtc(ts: String?): String {
        if (ts.isNullOrBlank()) return EventReporter.getIsoTimestamp(Date())
        return try {
            if (ts.endsWith("Z") && ts.length == 20) return ts
            val millis = ts.toLongOrNull()
            if (millis != null) {
                return EventReporter.getIsoTimestamp(Date(millis))
            }
            val clean = ts.replace("Z", "").take(19)
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val d = sdf.parse(clean) ?: Date()
            EventReporter.getIsoTimestamp(d)
        } catch (e: Exception) {
            EventReporter.getIsoTimestamp(Date())
        }
    }

    fun getFallbackCoordinates(context: Context, jobId: String?, isAccommodation: Boolean): Pair<Double, Double>? {
        val geoPrefs = context.getSharedPreferences(PREFS_GEO, Context.MODE_PRIVATE)
        if (isAccommodation) {
            val json = geoPrefs.getString("CACHED_ACCOMMODATION_JSON", null) ?: return null
            return try {
                val item = gson.fromJson(json, AccommodationItem::class.java)
                if (item.location.isNotEmpty()) {
                    Pair(item.location[0].latitude, item.location[0].longitude)
                } else if (item.geofenceCircle?.center != null) {
                    Pair(item.geofenceCircle.center.lat, item.geofenceCircle.center.lng)
                } else null
            } catch (e: Exception) { null }
        } else if (!jobId.isNullOrBlank()) {
            val json = geoPrefs.getString("CACHED_JOBS_JSON", null) ?: return null
            return try {
                val type = object : TypeToken<List<JobItem>>() {}.type
                val jobs: List<JobItem> = gson.fromJson(json, type)
                val matching = jobs.find { it.jobId.equals(jobId, ignoreCase = true) }
                if (matching != null && matching.location.isNotEmpty()) {
                    Pair(matching.location[0].latitude, matching.location[0].longitude)
                } else null
            } catch (e: Exception) { null }
        }
        return null
    }

    fun getCachedAccommodationId(context: Context): String? {
        val geoPrefs = context.getSharedPreferences(PREFS_GEO, Context.MODE_PRIVATE)
        val json = geoPrefs.getString("CACHED_ACCOMMODATION_JSON", null) ?: return null
        return try {
            val item = gson.fromJson(json, AccommodationItem::class.java)
            sanitizeUuid(item.id)
        } catch (e: Exception) { null }
    }
}
