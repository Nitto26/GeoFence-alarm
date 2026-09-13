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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList

data class SyncState(
    val isOnline: Boolean,
    val isSyncing: Boolean,
    val unsyncedCount: Int,
    val badgeText: String
)

object SyncEngine {

    private const val TAG = "SyncEngine"
    private val scope = CoroutineScope(Dispatchers.IO)
    private var syncJob: Job? = null

    private val listeners = CopyOnWriteArrayList<(SyncState) -> Unit>()
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
                        Log.d(TAG, "Periodic sync timer triggered for $unsynced pending events.")
                        triggerSync(ctx)
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
                    Log.d(TAG, "Network LOST. Switched to Offline cache.")
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

    fun triggerSync(context: Context, onComplete: ((Boolean) -> Unit)? = null) {
        val appCtx = context.applicationContext
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

                    Log.d(TAG, "Flushing batch of ${unsyncedRecords.size} unsynced local events to backend...")

                    val userPrefs = appCtx.getSharedPreferences("UserPrefs", Context.MODE_PRIVATE)
                    val activeWorkerId = userPrefs.getString("WORKER_ID", "TL-8801")

                    val items = unsyncedRecords.map { record ->
                        LocationEventItem(
                            workerId = activeWorkerId,
                            jobId = record.jobId,
                            location = LocationCoordinate(record.latitude, record.longitude),
                            timestamp = record.timestamp,
                            eventType = record.eventType
                        )
                    }

                    val request = LocationEventRequest(events = items)
                    ApiClient.init(appCtx)
                    val response = ApiClient.apiService.postLocationEvents(request)

                    if (response.isSuccessful) {
                        val recordIds = unsyncedRecords.map { it.id }
                        db.markEventsAsSynced(recordIds)
                        totalSyncedThisRound += recordIds.size
                        Log.d(TAG, "âœ“ Batch of ${recordIds.size} events synced successfully!")
                    } else {
                        Log.e(TAG, "Server responded with error HTTP ${response.code()}")
                        break
                    }
                }

                if (totalSyncedThisRound > 0) {
                    EventReporter.addLocalLog("âœ“ Flushed $totalSyncedThisRound events to server")
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

    fun isOnlineNow(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val activeNetwork = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
