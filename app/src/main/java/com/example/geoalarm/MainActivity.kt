package com.example.geoalarm

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialog
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.widget.ViewFlipper
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.geoalarm.network.ApiClient
import com.example.geoalarm.network.EventReporter
import com.example.geoalarm.network.JobItem
import com.example.geoalarm.network.SyncEngine
import com.example.geoalarm.network.SyncState
import com.example.geoalarm.storage.LocalEventDatabaseHelper
import com.example.geoalarm.storage.LocalEventRecord
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.PolygonOptions
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), OnMapReadyCallback {

    private val TAG = "MainActivity"

    private lateinit var viewFlipper: ViewFlipper
    private var mMap: GoogleMap? = null
    private var isMapReady = false

    // Bottom Nav Tabs
    private lateinit var tabHome: LinearLayout
    private lateinit var tabMap: LinearLayout
    private lateinit var tabWork: LinearLayout
    private lateinit var tabProfile: LinearLayout

    private lateinit var ivNavHome: ImageView
    private lateinit var tvNavHome: TextView
    private lateinit var ivNavMap: ImageView
    private lateinit var tvNavMap: TextView
    private lateinit var ivNavWork: ImageView
    private lateinit var tvNavWork: TextView
    private lateinit var ivNavProfile: ImageView
    private lateinit var tvNavProfile: TextView

    // Live Jobs from Admin Panel Backend
    private var liveJobs: List<JobItem> = emptyList()

    // Logged in Worker Profile
    private var loggedInWorkerId: String = ""
    private var loggedInWorkerName: String = ""
    private var loggedInWorkerDesignation: String = ""
    private var loggedInWorkerPhone: String = ""

    // Map Markers Cache
    private val worksiteMarkers = mutableMapOf<String, Marker>()
    private var accommodationMarker: Marker? = null

    // Accommodation Location
    private val accommodationLatLng = LatLng(10.5182, 76.2090)

    // Handlers for Clock and Shift Timer
    private val mainHandler = Handler(Looper.getMainLooper())
    private var startTimeMillis = System.currentTimeMillis() - (5 * 3600 * 1000 + 42 * 60000 + 18000)

    // Secret 3-Tap Version Counter
    private var versionClickCount = 0
    private var lastVersionClickTime = 0L

    // Sync State Listener
    private val syncListener: (SyncState) -> Unit = { state ->
        runOnUiThread {
            updateSyncBadgeUi(state)
        }
    }

    // Live Clock & Shift Ticker
    private val clockTicker = object : Runnable {
        override fun run() {
            // 1. Update Home Current Time Clock (always live)
            val tvTime = findViewById<TextView>(R.id.tvLiveCurrentTime)
            tvTime?.text = SimpleDateFormat("hh:mm:ss a", Locale.getDefault()).format(Date())

            // 2. Update Hours Worked Shift Timer (only advances when Clocked In)
            val sharedPrefs = getSharedPreferences("GeoAlarmPrefs", Context.MODE_PRIVATE)
            val isClockedIn = sharedPrefs.getBoolean("IS_CLOCKED_IN", false)

            val tvTimer = findViewById<TextView>(R.id.tvHoursWorkedTimer)
            if (tvTimer != null) {
                if (isClockedIn) {
                    val elapsed = System.currentTimeMillis() - startTimeMillis
                    val hours = elapsed / 3600000
                    val minutes = (elapsed % 3600000) / 60000
                    val seconds = (elapsed % 60000) / 1000
                    tvTimer.text = String.format("%02dh %02dm %02ds", hours, minutes, seconds)
                } else {
                    tvTimer.text = "00h 00m 00s (Paused)"
                }
            }

            mainHandler.postDelayed(this, 1000)
        }
    }

    // OS Level Sabotage & Geofence Engine
    private val locationStateReceiver = LocationStateReceiver()
    private lateinit var geofencingClient: GeofencingClient
    private val geofencePendingIntent: PendingIntent by lazy {
        val intent = Intent(this, GeofenceBroadcastReceiver::class.java)
        PendingIntent.getBroadcast(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
            val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true

            if (fineGranted || coarseGranted) {
                checkLocationSettings()
                enableUserLocation()
                val isClockedIn = getSharedPreferences("GeoAlarmPrefs", Context.MODE_PRIVATE)
                    .getBoolean("IS_CLOCKED_IN", false)
                if (isClockedIn) {
                    startRadarServiceSafely()
                }
            } else {
                Log.e(TAG, "Location permission denied by user")
            }
        }

    private val resolutionForResult =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                Log.d(TAG, "User enabled location services.")
                enableUserLocation()
            } else {
                Log.e(TAG, "User refused location services.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize API Client & Sync Engine
        ApiClient.init(this)
        SyncEngine.init(this)
        SyncEngine.addListener(syncListener)

        geofencingClient = LocationServices.getGeofencingClient(this)
        viewFlipper = findViewById(R.id.viewFlipperMain)

        // Load Active Worker Session
        val userPrefs = getSharedPreferences("UserPrefs", Context.MODE_PRIVATE)
        val savedWorkerId = intent.getStringExtra("EXTRA_WORKER_ID") ?: userPrefs.getString("WORKER_ID", null)
        if (savedWorkerId.isNullOrEmpty()) {
            val intent = Intent(this, LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
            return
        }

        loggedInWorkerId = savedWorkerId
        loggedInWorkerName = intent.getStringExtra("EXTRA_WORKER_NAME") ?: userPrefs.getString("WORKER_NAME", "Worker") ?: "Worker"
        loggedInWorkerDesignation = userPrefs.getString("WORKER_DESIGNATION", "Field Technician") ?: "Field Technician"
        loggedInWorkerPhone = userPrefs.getString("WORKER_PHONE", "") ?: ""

        updateWorkerProfileViews()

        // Initialize UI Tabs and Navigation
        setupBottomNavigation()
        setupHomeInteractions()
        setupProfileInteractions()
        setupWorkCalendar()
        setupMapDetailsCard()

        // Sync Clock In / Out UI State from SharedPreferences
        val isClockedIn = getSharedPreferences("GeoAlarmPrefs", Context.MODE_PRIVATE)
            .getBoolean("IS_CLOCKED_IN", false)
        updateClockInOutUi(isClockedIn)

        // Sync Initial Badge Status
        updateSyncBadgeUi(SyncEngine.getCurrentState(this))

        // Initialize Google Maps fragment
        val mapFragment = supportFragmentManager.findFragmentById(R.id.mapFragment) as? SupportMapFragment
        mapFragment?.getMapAsync(this)

        // Request runtime permissions
        requestPermissions()

        // Only start background Radar service if worker is actively Clocked In
        if (isClockedIn && hasLocationPermission()) {
            startRadarServiceSafely()
        }

        // Register Location State Receiver for OS Sabotage Alarm
        try {
            val filter = IntentFilter().apply {
                addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
                addAction("android.location.MODE_CHANGED")
            }
            ContextCompat.registerReceiver(
                this,
                locationStateReceiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED
            )
        } catch (e: Exception) {
            Log.e(TAG, "Receiver registration error: ${e.message}")
        }

        // Start Live Clock and Shift Timer
        mainHandler.post(clockTicker)

        // Fetch Live Jobs from Admin Panel Backend
        fetchLiveJobsFromBackend()
    }

    // ==========================================
    // BACKEND INTEGRATION: FETCH JOBS
    // ==========================================
    private fun fetchLiveJobsFromBackend() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "Connecting to backend at: ${ApiClient.getBaseUrl()}api/mobile/jobs...")
                val response = ApiClient.apiService.getJobs(workerId = loggedInWorkerId)
                
                withContext(Dispatchers.Main) {
                    if (response.isSuccessful) {
                        val jobs = response.body()?.jobs ?: emptyList()
                        liveJobs = jobs
                        EventReporter.addLocalLog("Schedule fetched successfully (${jobs.size} jobs)")
                        updateHomeUiWithLiveJobs(jobs)
                        updateWorkCalendarWithJobs(jobs)
                        if (isMapReady) {
                            renderJobsOnMap(jobs)
                        }
                    } else {
                        EventReporter.addLocalLog("Failed to fetch schedule (HTTP ${response.code()})")
                        Toast.makeText(this@MainActivity, "Server connection failed", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error connecting to Admin Panel: ${e.message}")
                withContext(Dispatchers.Main) {
                    EventReporter.addLocalLog("Failed to fetch schedule: ${e.message}")
                    Toast.makeText(this@MainActivity, "Offline Mode", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

        private fun updateWorkerProfileViews() {
        val tvHomeUserName = findViewById<TextView>(R.id.tvHomeUserName)
        val tvHomeTallyNo = findViewById<TextView>(R.id.tvHomeTallyNo)
        val tvProfileName = findViewById<TextView>(R.id.tvProfileName)
        val tvProfileTallyNo = findViewById<TextView>(R.id.tvProfileTallyNo)
        val tvProfileDepartment = findViewById<TextView>(R.id.tvProfileDepartment)
        val tvProfilePhone = findViewById<TextView>(R.id.tvProfilePhone)

        tvHomeUserName?.text = loggedInWorkerName
        tvHomeTallyNo?.text = "Tally ID: $loggedInWorkerId"

        tvProfileName?.text = loggedInWorkerName
        tvProfileTallyNo?.text = "ID: $loggedInWorkerId"
        tvProfileDepartment?.text = loggedInWorkerDesignation
        if (loggedInWorkerPhone.isNotEmpty()) {
            tvProfilePhone?.text = loggedInWorkerPhone
        }
    }

    private fun updateHomeUiWithLiveJobs(jobs: List<JobItem>) {
        val tvWorksitesCountToday = findViewById<TextView>(R.id.tvWorksitesCountToday)
        val tvNextWorksiteTitle = findViewById<TextView>(R.id.tvNextWorksiteTitle)
        val tvNextWorksiteTime = findViewById<TextView>(R.id.tvNextWorksiteTime)

        tvWorksitesCountToday?.text = "${jobs.size} worksites today"

        if (jobs.isNotEmpty()) {
            val firstJob = jobs[0]
            tvNextWorksiteTitle?.text = firstJob.jobId
            val activeDays = firstJob.days.joinToString(", ")
            if (activeDays.isNotEmpty()) {
                tvNextWorksiteTime?.text = "Active: $activeDays"
            }
        }
    }

    // ==========================================
    // ONLINE / OFFLINE SYNC BADGE
    // ==========================================
    private fun updateSyncBadgeUi(state: SyncState) {
        val layoutBadge = findViewById<LinearLayout>(R.id.layoutSyncBadge) ?: return
        val dot = findViewById<View>(R.id.viewSyncStatusDot) ?: return
        val text = findViewById<TextView>(R.id.tvSyncBadgeText) ?: return

        text.text = state.badgeText

        when {
            state.isOnline && state.unsyncedCount == 0 -> {
                // Online & Fully Synced (Green)
                layoutBadge.background = ContextCompat.getDrawable(this, R.drawable.bg_pill_green)
                dot.backgroundTintList = ColorStateList.valueOf(getColor(R.color.status_green))
                text.setTextColor(getColor(R.color.status_green_text))
            }
            state.isOnline && state.unsyncedCount > 0 -> {
                // Online with Pending Synced Items (Amber/Blue)
                layoutBadge.background = ContextCompat.getDrawable(this, R.drawable.bg_pill_blue)
                dot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#F59E0B"))
                text.setTextColor(Color.parseColor("#D97706"))
            }
            else -> {
                // Offline (Soft Red/Gray)
                layoutBadge.background = ContextCompat.getDrawable(this, R.drawable.bg_circle_offday)
                dot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#EF4444"))
                text.setTextColor(Color.parseColor("#DC2626"))
            }
        }
    }

    // ==========================================
    // NOTIFICATION BELL: SYSTEM LOGS DIALOG
    // ==========================================
    private fun showLiveLogsDialog() {
        val context = this
        val logs = EventReporter.liveSystemLogs

        val builder = AlertDialog.Builder(context)
        builder.setTitle("Ã¢Å¡Â¡ System Logs & Activity")

        val scrollView = ScrollView(context)
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        if (logs.isEmpty()) {
            container.addView(TextView(context).apply {
                text = "No system logs recorded yet."
                textSize = 14f
                setPadding(0, 16, 0, 16)
            })
        } else {
            for (log in logs) {
                val logItem = TextView(context).apply {
                    text = log
                    textSize = 12.5f
                    typeface = Typeface.MONOSPACE
                    setPadding(0, 8, 0, 8)
                    setTextColor(if (log.contains("Ã¢Å¡Â ") || log.contains("location_off")) Color.parseColor("#E11D48") else Color.parseColor("#334155"))
                }
                container.addView(logItem)
            }
        }

        scrollView.addView(container)
        builder.setView(scrollView)
        builder.setPositiveButton("Close", null)
        builder.setNeutralButton("Clear Logs") { _, _ ->
            EventReporter.liveSystemLogs.clear()
            EventReporter.addLocalLog("Logs cleared.")
            Toast.makeText(context, "Logs Cleared", Toast.LENGTH_SHORT).show()
        }
        builder.show()
    }

    // ==========================================
    // SECRET 3-TAP: LOCAL STORAGE & AUDIT LOGS
    // ==========================================
    private fun showLocalStorageAuditDialog() {
        val context = this
        val db = LocalEventDatabaseHelper.getInstance(context)
        val dialog = BottomSheetDialog(context)
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_local_storage_audit, null)
        dialog.setContentView(dialogView)

        val tvStatTotal = dialogView.findViewById<TextView>(R.id.tvStatTotal)
        val tvStatSynced = dialogView.findViewById<TextView>(R.id.tvStatSynced)
        val tvStatPending = dialogView.findViewById<TextView>(R.id.tvStatPending)
        val tvAuditListHeader = dialogView.findViewById<TextView>(R.id.tvAuditListHeader)
        val btnForceSyncDialog = dialogView.findViewById<MaterialButton>(R.id.btnForceSyncDialog)
        val btnPurgeOldDialog = dialogView.findViewById<MaterialButton>(R.id.btnPurgeOldDialog)
        val ivCloseDialog = dialogView.findViewById<ImageView>(R.id.ivCloseDialog)
        val llAuditListContainer = dialogView.findViewById<LinearLayout>(R.id.llAuditListContainer)

        fun refreshAuditUi() {
            val (total, synced, unsynced) = db.getStats()
            val records = db.getAllRecordsForAudit(150)

            tvStatTotal?.text = total.toString()
            tvStatSynced?.text = synced.toString()
            tvStatPending?.text = unsynced.toString()
            tvAuditListHeader?.text = "Audit History (${records.size} records in last 3 days)"

            llAuditListContainer?.removeAllViews()

            if (records.isEmpty()) {
                val emptyView = TextView(context).apply {
                    text = "No local storage events recorded yet."
                    textSize = 13f
                    setTextColor(getColor(R.color.text_secondary))
                    gravity = Gravity.CENTER
                    setPadding(0, 32, 0, 32)
                }
                llAuditListContainer?.addView(emptyView)
            } else {
                for (rec in records) {
                    val itemView = LayoutInflater.from(context).inflate(R.layout.item_local_audit_record, llAuditListContainer, false)

                    val tvType = itemView.findViewById<TextView>(R.id.tvAuditEventType)
                    val tvJob = itemView.findViewById<TextView>(R.id.tvAuditJobId)
                    val tvStatus = itemView.findViewById<TextView>(R.id.tvAuditSyncStatus)
                    val tvCoords = itemView.findViewById<TextView>(R.id.tvAuditCoords)
                    val tvTime = itemView.findViewById<TextView>(R.id.tvAuditTimestamp)

                    tvType?.text = rec.eventType.uppercase()
                    tvJob?.text = rec.jobId ?: "WORKER"
                    tvCoords?.text = "${rec.latitude}, ${rec.longitude}"
                    tvTime?.text = rec.timestamp.replace("T", " ").take(19)

                    if (rec.isSynced) {
                        tvStatus?.text = "Ã¢Å“â€œ SYNCED"
                        tvStatus?.setTextColor(Color.parseColor("#16A34A"))
                    } else {
                        tvStatus?.text = "Ã¢ÂÂ³ PENDING"
                        tvStatus?.setTextColor(Color.parseColor("#D97706"))
                    }

                    llAuditListContainer?.addView(itemView)
                }
            }
        }

        refreshAuditUi()

        btnForceSyncDialog?.setOnClickListener {
            btnForceSyncDialog.isEnabled = false
            btnForceSyncDialog.text = "Syncing..."
            Toast.makeText(context, "Flushing offline storage to server...", Toast.LENGTH_SHORT).show()

            SyncEngine.triggerSync(context) { success ->
                runOnUiThread {
                    btnForceSyncDialog.isEnabled = true
                    btnForceSyncDialog.text = "Force Sync Now"
                    refreshAuditUi()
                    if (success) {
                        Toast.makeText(context, "Ã¢Å“â€œ Offline sync complete! All events delivered.", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Sync paused (check network connection)", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        btnPurgeOldDialog?.setOnClickListener {
            val deleted = db.purgeExpiredRecords()
            refreshAuditUi()
            Toast.makeText(context, "Cleaned $deleted expired records (> 3 days)", Toast.LENGTH_SHORT).show()
        }

        ivCloseDialog?.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    // ==========================================
    // BOTTOM NAVIGATION SYSTEM
    // ==========================================
    private fun setupBottomNavigation() {
        tabHome = findViewById(R.id.tabHome)
        tabMap = findViewById(R.id.tabMap)
        tabWork = findViewById(R.id.tabWork)
        tabProfile = findViewById(R.id.tabProfile)

        ivNavHome = findViewById(R.id.ivNavHome)
        tvNavHome = findViewById(R.id.tvNavHome)
        ivNavMap = findViewById(R.id.ivNavMap)
        tvNavMap = findViewById(R.id.tvNavMap)
        ivNavWork = findViewById(R.id.ivNavWork)
        tvNavWork = findViewById(R.id.tvNavWork)
        ivNavProfile = findViewById(R.id.ivNavProfile)
        tvNavProfile = findViewById(R.id.tvNavProfile)

        tabHome.setOnClickListener { switchTab(0) }
        tabMap.setOnClickListener { switchTab(1) }
        tabWork.setOnClickListener { switchTab(2) }
        tabProfile.setOnClickListener { switchTab(3) }
    }

    private fun switchTab(index: Int) {
        viewFlipper.displayedChild = index

        val activeColor = getColor(R.color.brand_blue)
        val inactiveColor = getColor(R.color.text_muted)

        // Reset all tabs
        ivNavHome.setColorFilter(inactiveColor)
        tvNavHome.setTextColor(inactiveColor)
        tvNavHome.paint.isFakeBoldText = false

        ivNavMap.setColorFilter(inactiveColor)
        tvNavMap.setTextColor(inactiveColor)
        tvNavMap.paint.isFakeBoldText = false

        ivNavWork.setColorFilter(inactiveColor)
        tvNavWork.setTextColor(inactiveColor)
        tvNavWork.paint.isFakeBoldText = false

        ivNavProfile.setColorFilter(inactiveColor)
        tvNavProfile.setTextColor(inactiveColor)
        tvNavProfile.paint.isFakeBoldText = false

        // Highlight selected tab
        when (index) {
            0 -> {
                ivNavHome.setColorFilter(activeColor)
                tvNavHome.setTextColor(activeColor)
                tvNavHome.paint.isFakeBoldText = true
            }
            1 -> {
                ivNavMap.setColorFilter(activeColor)
                tvNavMap.setTextColor(activeColor)
                tvNavMap.paint.isFakeBoldText = true
                if (isMapReady && liveJobs.isNotEmpty()) {
                    renderJobsOnMap(liveJobs)
                }
            }
            2 -> {
                ivNavWork.setColorFilter(activeColor)
                tvNavWork.setTextColor(activeColor)
                tvNavWork.paint.isFakeBoldText = true
            }
            3 -> {
                ivNavProfile.setColorFilter(activeColor)
                tvNavProfile.setTextColor(activeColor)
                tvNavProfile.paint.isFakeBoldText = true
            }
        }
    }

    // ==========================================
    // SCREEN 2: HOME INTERACTIONS & CLOCK IN/OUT
    // ==========================================
    private fun setupHomeInteractions() {
        val btnViewOnMapLink = findViewById<LinearLayout>(R.id.btnViewOnMapLink)
        val btnNextWorksiteArrow = findViewById<FrameLayout>(R.id.btnNextWorksiteArrow)
        val btnGoToMap = findViewById<MaterialButton>(R.id.btnGoToMap)
        val ivBell = findViewById<ImageView>(R.id.ivNotificationBell)
        val btnClockToggle = findViewById<MaterialButton>(R.id.btnClockToggle)
        val layoutSyncBadge = findViewById<LinearLayout>(R.id.layoutSyncBadge)

        val goToMapAction = View.OnClickListener {
            switchTab(1)
        }

        btnViewOnMapLink?.setOnClickListener(goToMapAction)
        btnNextWorksiteArrow?.setOnClickListener(goToMapAction)
        btnGoToMap?.setOnClickListener(goToMapAction)

        // Notification Bell Click triggers logs dialog
        ivBell?.setOnClickListener {
            showLiveLogsDialog()
        }

        // Tap Sync Badge to trigger immediate sync attempt
        layoutSyncBadge?.setOnClickListener {
            Toast.makeText(this, "Checking synchronization...", Toast.LENGTH_SHORT).show()
            SyncEngine.triggerSync(this)
        }

        // Clock In / Clock Out Toggle Button Handler
        btnClockToggle?.setOnClickListener {
            handleClockToggle()
        }
    }

    private fun handleClockToggle() {
        val sharedPrefs = getSharedPreferences("GeoAlarmPrefs", Context.MODE_PRIVATE)
        val currentlyClockedIn = sharedPrefs.getBoolean("IS_CLOCKED_IN", false)

        if (!currentlyClockedIn) {
            // CLOCK IN ACTION
            if (!hasLocationPermission()) {
                requestPermissions()
                Toast.makeText(this, "Location permissions needed to Clock In", Toast.LENGTH_SHORT).show()
                return
            }

            checkLocationSettings()

            sharedPrefs.edit()
                .putBoolean("IS_CLOCKED_IN", true)
                .putBoolean("IS_SYSTEM_ARMED", true)
                .putLong("CLOCK_IN_TIMESTAMP", System.currentTimeMillis())
                .apply()

            startTimeMillis = System.currentTimeMillis()

            // 1. Start background RadarService
            startRadarServiceSafely()

            // 2. Arm Geofences for active jobs
            if (liveJobs.isNotEmpty()) {
                liveJobs.forEach { job ->
                    if (job.location.isNotEmpty()) {
                        armJobGeofence(job.jobId, LatLng(job.location[0].latitude, job.location[0].longitude), 500f)
                    }
                }
            }

            // 3. Dispatch Clock In event (stored locally and synced)
            EventReporter.reportEvent(
                context = this,
                eventType = "clock_in",
                jobId = if (liveJobs.isNotEmpty()) liveJobs[0].jobId else "WORKER-1001",
                latitude = 10.5276,
                longitude = 76.2144
            )
            EventReporter.addLocalLog("Ã¢Å“â€œ Worker Clocked In. Background radar & geofences armed.")

            updateClockInOutUi(true)
            Toast.makeText(this, "Ã¢Å“â€œ Clocked In! Background tracking active & armed.", Toast.LENGTH_SHORT).show()

        } else {
            // CLOCK OUT ACTION
            sharedPrefs.edit()
                .putBoolean("IS_CLOCKED_IN", false)
                .putBoolean("IS_SYSTEM_ARMED", false)
                .apply()

            // 1. Stop background RadarService
            stopService(Intent(this, RadarService::class.java))

            // 2. Disarm & remove Geofences
            try {
                geofencingClient.removeGeofences(geofencePendingIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing geofences: ${e.message}")
            }

            // 3. Stop any ringing alarms
            AlarmController.stopAlarm(this)

            // 4. Dispatch Clock Out event (stored locally and synced)
            EventReporter.reportEvent(
                context = this,
                eventType = "clock_out",
                jobId = if (liveJobs.isNotEmpty()) liveJobs[0].jobId else "WORKER-1001",
                latitude = 10.5276,
                longitude = 76.2144
            )
            EventReporter.addLocalLog("Ã¢ÂÂ¸ Worker Clocked Out. System entered sleep mode.")

            updateClockInOutUi(false)
            Toast.makeText(this, "Ã¢ÂÂ¸ Clocked Out. App is now sleeping (Zero background usage).", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateClockInOutUi(isClockedIn: Boolean) {
        val tvAttendanceStatus = findViewById<TextView>(R.id.tvAttendanceStatus)
        val tvAttendanceSubtitle = findViewById<TextView>(R.id.tvAttendanceSubtitle)
        val ivAttendanceIcon = findViewById<ImageView>(R.id.ivAttendanceIcon)
        val btnClockToggle = findViewById<MaterialButton>(R.id.btnClockToggle)
        val tvHoursWorkedLabel = findViewById<TextView>(R.id.tvHoursWorkedLabel)

        if (isClockedIn) {
            tvAttendanceStatus?.text = "Clocked In"
            tvAttendanceStatus?.setTextColor(getColor(R.color.status_green))
            tvAttendanceSubtitle?.text = "Tracking active Ã¢â‚¬Â¢ Background armed"
            ivAttendanceIcon?.setColorFilter(getColor(R.color.status_green))

            btnClockToggle?.text = "Clock Out"
            btnClockToggle?.setTextColor(Color.parseColor("#DC2626"))
            btnClockToggle?.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FEE2E2"))
            btnClockToggle?.strokeColor = ColorStateList.valueOf(Color.parseColor("#EF4444"))
            btnClockToggle?.icon = ContextCompat.getDrawable(this, R.drawable.ic_check_circle)
            btnClockToggle?.iconTint = ColorStateList.valueOf(Color.parseColor("#DC2626"))

            tvHoursWorkedLabel?.text = "Active Shift Duration"
        } else {
            tvAttendanceStatus?.text = "Clocked Out"
            tvAttendanceStatus?.setTextColor(getColor(R.color.text_secondary))
            tvAttendanceSubtitle?.text = "App is sleeping Ã¢â‚¬Â¢ No background tracking"
            ivAttendanceIcon?.setColorFilter(getColor(R.color.text_muted))

            btnClockToggle?.text = "Clock In"
            btnClockToggle?.setTextColor(Color.WHITE)
            btnClockToggle?.backgroundTintList = ColorStateList.valueOf(getColor(R.color.brand_blue))
            btnClockToggle?.strokeColor = ColorStateList.valueOf(getColor(R.color.brand_blue))
            btnClockToggle?.icon = ContextCompat.getDrawable(this, R.drawable.ic_check_circle)
            btnClockToggle?.iconTint = ColorStateList.valueOf(Color.WHITE)

            tvHoursWorkedLabel?.text = "Shift Paused (Sleep Mode)"
        }
    }

    // ==========================================
    // SCREEN 3: MAP & SWIPABLE CARDS CAROUSEL
    // ==========================================
    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        isMapReady = true

        mMap?.uiSettings?.isZoomControlsEnabled = false
        mMap?.uiSettings?.isMyLocationButtonEnabled = false

        enableUserLocation()

        if (liveJobs.isNotEmpty()) {
            renderJobsOnMap(liveJobs)
        } else {
            val defaultLoc = LatLng(10.5276, 76.2144)
            mMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(defaultLoc, 15f))
        }

        // GPS Recenter Button
        val fabRecenter = findViewById<CardView>(R.id.fabRecenterLocation)
        fabRecenter?.setOnClickListener {
            enableUserLocation()
            checkLocationSettings()
            if (liveJobs.isNotEmpty() && liveJobs[0].location.isNotEmpty()) {
                val first = liveJobs[0].location[0]
                mMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(first.latitude, first.longitude), 16f))
            }
            Toast.makeText(this, "Centered Location", Toast.LENGTH_SHORT).show()
        }

        // View Details Button Toggle expand/collapse
        val btnMapDetails = findViewById<MaterialButton>(R.id.btnMapWorksiteDetails)
        val llExpandedDetails = findViewById<LinearLayout>(R.id.llExpandedDetails)
        btnMapDetails?.setOnClickListener {
            if (llExpandedDetails != null) {
                if (llExpandedDetails.visibility == View.VISIBLE) {
                    llExpandedDetails.visibility = View.GONE
                    btnMapDetails.text = "View Details"
                } else {
                    llExpandedDetails.visibility = View.VISIBLE
                    btnMapDetails.text = "Hide Details"
                }
            }
        }
    }

    private fun renderJobsOnMap(jobs: List<JobItem>) {
        val map = mMap ?: return
        map.clear()
        worksiteMarkers.clear()

        val boundsBuilder = LatLngBounds.Builder()
        var hasPoints = false

        val isClockedIn = getSharedPreferences("GeoAlarmPrefs", Context.MODE_PRIVATE)
            .getBoolean("IS_CLOCKED_IN", false)

        // Plot assigned jobs
        jobs.forEach { job ->
            val polygonPoints = job.location.map { LatLng(it.latitude, it.longitude) }

            if (polygonPoints.isNotEmpty()) {
                hasPoints = true
                polygonPoints.forEach { boundsBuilder.include(it) }

                // Draw Polygon Geofence Zone
                if (polygonPoints.size >= 3) {
                    map.addPolygon(
                        PolygonOptions()
                            .addAll(polygonPoints)
                            .strokeColor(Color.parseColor("#0052CC"))
                            .fillColor(Color.argb(45, 0, 82, 204))
                            .strokeWidth(5f)
                    )
                }

                // Add Marker Pin
                val centerPoint = polygonPoints[0]
                val marker = map.addMarker(
                    MarkerOptions()
                        .position(centerPoint)
                        .title(job.jobId)
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
                )
                if (marker != null) {
                    worksiteMarkers[job.jobId] = marker
                }

                // Arm geofence only if worker is Clocked In
                if (isClockedIn) {
                    armJobGeofence(job.jobId, centerPoint, 500f)
                }
            }
        }

        // Plot Worker Accommodation marker
        accommodationMarker = map.addMarker(
            MarkerOptions()
                .position(accommodationLatLng)
                .title("Accommodation")
                .snippet("Block A, Room 203")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE))
        )
        boundsBuilder.include(accommodationLatLng)

        if (hasPoints) {
            try {
                val bounds = boundsBuilder.build()
                map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 120))
            } catch (e: Exception) {
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(accommodationLatLng, 14f))
            }
        }

        // Render swipable top carousel cards
        populateMapCarousel(jobs)
    }

    private fun populateMapCarousel(jobs: List<JobItem>) {
        val container = findViewById<LinearLayout>(R.id.llCarouselContainer) ?: return
        container.removeAllViews()

        jobs.forEachIndexed { index, job ->
            val cardView = CardView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 24, 0)
                }
                radius = 12f
                cardElevation = 2f
                setContentPadding(16, 12, 16, 12)
                setCardBackgroundColor(Color.WHITE)
            }

            val cardLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }

            val titleText = TextView(this).apply {
                text = job.jobId
                setTextColor(getColor(R.color.text_primary))
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
            }

            val timeText = TextView(this).apply {
                text = "Time: 10:00 AM - 01:00 PM"
                setTextColor(getColor(R.color.brand_blue))
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
            }

            val durationText = TextView(this).apply {
                text = "Duration: 3 hours"
                setTextColor(getColor(R.color.text_secondary))
                textSize = 11f
            }

            cardLayout.addView(titleText)
            cardLayout.addView(timeText)
            cardLayout.addView(durationText)

            cardView.addView(cardLayout)

            // Click focuses map camera on job
            cardView.setOnClickListener {
                focusOnWorksite(job)
            }

            container.addView(cardView)
        }
    }

    private fun focusOnWorksite(job: JobItem) {
        if (job.location.isNotEmpty()) {
            val loc = job.location[0]
            val latLng = LatLng(loc.latitude, loc.longitude)
            mMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 16f))
            updateBottomCardDetails(job)
        }
    }

    private fun updateBottomCardDetails(job: JobItem) {
        val tvTitle = findViewById<TextView>(R.id.tvMapDetailTitle)
        val tvTime = findViewById<TextView>(R.id.tvMapDetailTime)
        val tvRole = findViewById<TextView>(R.id.tvMapDetailRole)
        val tvSupervisor = findViewById<TextView>(R.id.tvMapDetailSupervisor)

        tvTitle?.text = job.jobId
        tvTime?.text = "Active Days: " + job.days.joinToString(", ")
        tvRole?.text = "Role: Electrical Installation"
        tvSupervisor?.text = "Supervisor: Arun Kumar"
    }

    private fun setupMapDetailsCard() {
        // Toggle Expanded panel
        val detailsContainer = findViewById<LinearLayout>(R.id.llExpandedDetails)
        detailsContainer?.visibility = View.GONE
    }

    private fun armJobGeofence(jobId: String, latLng: LatLng, radius: Float) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        val sharedPrefs = getSharedPreferences("GeoAlarmPrefs", Context.MODE_PRIVATE)
        sharedPrefs.edit()
            .putFloat("TARGET_RADIUS", radius)
            .putBoolean("IS_SYSTEM_ARMED", true)
            .apply()

        val geoPrefs = getSharedPreferences("GeoPrefs", Context.MODE_PRIVATE)
        geoPrefs.edit()
            .putFloat("TARGET_LAT", latLng.latitude.toFloat())
            .putFloat("TARGET_LNG", latLng.longitude.toFloat())
            .apply()

        val geofence = Geofence.Builder()
            .setRequestId(jobId)
            .setCircularRegion(latLng.latitude, latLng.longitude, radius + 500f)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
            .build()

        val geofencingRequest = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER or GeofencingRequest.INITIAL_TRIGGER_DWELL)
            .addGeofence(geofence)
            .build()

        geofencingClient.addGeofences(geofencingRequest, geofencePendingIntent).run {
            addOnSuccessListener {
                Log.d(TAG, "Armed Geofence for $jobId")
            }
            addOnFailureListener {
                Log.e(TAG, "Geofence error: ${it.message}")
            }
        }
    }

    // ==========================================
    // SCREEN 4: WORK / SCHEDULE & CALENDAR
    // ==========================================
    private fun setupWorkCalendar() {
        val ivPrevMonth = findViewById<ImageView>(R.id.ivPrevMonth)
        val ivNextMonth = findViewById<ImageView>(R.id.ivNextMonth)

        ivPrevMonth?.setOnClickListener {
            Toast.makeText(this, "April 2026", Toast.LENGTH_SHORT).show()
        }
        ivNextMonth?.setOnClickListener {
            Toast.makeText(this, "June 2026", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateWorkCalendarWithJobs(jobs: List<JobItem>) {
        val grid = findViewById<GridLayout>(R.id.glCalendarDays) ?: return
        grid.removeAllViews()

        // Days Off list (Sundays and mock off days)
        val offDays = setOf(3, 10, 17, 24, 31, 7, 14)
        // Work Days list
        val workDays = setOf(1, 2, 5, 8, 12, 15, 18, 20, 22, 25, 29)

        val cal = Calendar.getInstance()
        val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)

        for (day in 1..daysInMonth) {
            val frameLayout = FrameLayout(this).apply {
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0
                    height = 110
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                }
            }

            val dayText = TextView(this).apply {
                text = day.toString()
                textSize = 13f
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(96, 96).apply {
                    gravity = Gravity.CENTER
                }
                setTypeface(null, Typeface.BOLD)

                // Color code dates: Workday (Blue), Off Day (Soft Red), Today (Dark Blue Circle)
                if (day == 20) {
                    background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_circle_today)
                    setTextColor(Color.WHITE)
                } else if (workDays.contains(day)) {
                    background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_circle_number)
                    setTextColor(getColor(R.color.brand_blue))
                } else if (offDays.contains(day)) {
                    background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_circle_offday)
                    setTextColor(Color.parseColor("#EF4444"))
                } else {
                    setTextColor(getColor(R.color.text_primary))
                }
            }

            frameLayout.addView(dayText)

            // Click listener selects day and updates schedule timeline above dynamically!
            frameLayout.setOnClickListener {
                selectCalendarDate(day, workDays.contains(day), offDays.contains(day))
            }

            grid.addView(frameLayout)
        }
    }

    private fun selectCalendarDate(day: Int, isWorkday: Boolean, isOffDay: Boolean) {
        val tvHeader = findViewById<TextView>(R.id.tvTimelineDateHeader)
        tvHeader?.text = "Schedule for May $day, 2026"

        val item1 = findViewById<RelativeLayout>(R.id.rlTimelineItem1)
        val item2 = findViewById<RelativeLayout>(R.id.rlTimelineItem2)
        val item3 = findViewById<RelativeLayout>(R.id.rlTimelineItem3)

        if (isOffDay) {
            item1?.visibility = View.GONE
            item2?.visibility = View.GONE
            item3?.visibility = View.GONE
            Toast.makeText(this, "Day Off: No worksites assigned.", Toast.LENGTH_SHORT).show()
        } else if (isWorkday) {
            item1?.visibility = View.VISIBLE
            item2?.visibility = View.VISIBLE
            item3?.visibility = View.VISIBLE
            Toast.makeText(this, "Workday: 3 worksites active.", Toast.LENGTH_SHORT).show()
        } else {
            item1?.visibility = View.VISIBLE
            item2?.visibility = View.GONE
            item3?.visibility = View.GONE
        }
    }

    // ==========================================
    // SCREEN 5: PROFILE, ACCOMMODATION & 3-TAP AUDIT
    // ==========================================
    private fun setupProfileInteractions() {
        val btnProfileAccommodationRow = findViewById<LinearLayout>(R.id.btnProfileAccommodationRow)
        
        btnProfileAccommodationRow?.setOnClickListener {
            // Navigate to Map tab (Tab Index 1)
            switchTab(1)

            // Focus on accommodation LatLng
            mMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(accommodationLatLng, 16f))
            accommodationMarker?.showInfoWindow()

            Toast.makeText(this, "Accommodation focused: Block A, Room 203", Toast.LENGTH_LONG).show()
        }

        // Secret 3-Tap Version Listener for Offline Local Storage & Sync Audit
        val tvVersion = findViewById<TextView>(R.id.tvAppVersion)
        tvVersion?.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - lastVersionClickTime < 1500L) {
                versionClickCount++
            } else {
                versionClickCount = 1
            }
            lastVersionClickTime = now

            if (versionClickCount >= 3) {
                versionClickCount = 0
                showLocalStorageAuditDialog()
            } else {
                val remaining = 3 - versionClickCount
                Log.d(TAG, "Version clicked ($versionClickCount/3). $remaining more taps to open Audit Log.")
            }
        }

        val btnLogout = findViewById<MaterialButton>(R.id.btnLogout)
        btnLogout?.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Log Out")
                .setMessage("Are you sure you want to log out?")
                .setPositiveButton("Log Out") { _, _ ->
                    val userPrefs = getSharedPreferences("UserPrefs", Context.MODE_PRIVATE)
                    userPrefs.edit().clear().apply()
                    // Stop background service on logout
                    stopService(Intent(this, RadarService::class.java))
                    AlarmController.stopAlarm(this)

                    val intent = Intent(this, LoginActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    startActivity(intent)
                    finish()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    // ==========================================
    // PERMISSIONS & GPS DETECTIONS
    // ==========================================
    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun startRadarServiceSafely() {
        val isClockedIn = getSharedPreferences("GeoAlarmPrefs", Context.MODE_PRIVATE)
            .getBoolean("IS_CLOCKED_IN", false)
        if (!isClockedIn) return

        if (!hasLocationPermission()) return
        try {
            val radarServiceIntent = Intent(this, RadarService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(radarServiceIntent)
            } else {
                startService(radarServiceIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not start RadarService: ${e.message}")
        }
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        requestPermissionLauncher.launch(permissions.toTypedArray())
        checkOverlayAndAlarmPermissions()
    }

    private fun checkOverlayAndAlarmPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this)
                .setTitle("Permission Needed: Display Over Other Apps")
                .setMessage("Enable display over other apps for lock screen alerts.")
                .setPositiveButton("Open Settings") { _, _ ->
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                }
                .setNegativeButton("Later", null)
                .show()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (!notificationManager.canUseFullScreenIntent()) {
                AlertDialog.Builder(this)
                    .setTitle("Permission Needed: Full-Screen Alarms")
                    .setMessage("Allow full-screen intents to display warnings on lock screen.")
                    .setPositiveButton("Open Settings") { _, _ ->
                        val intent = Intent(
                            Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                            Uri.parse("package:$packageName")
                        )
                        startActivity(intent)
                    }
                    .setNegativeButton("Later", null)
                    .show()
            }
        }
    }

    private fun checkLocationSettings() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000).build()
        val builder = LocationSettingsRequest.Builder().addLocationRequest(locationRequest)
        val client = LocationServices.getSettingsClient(this)

        client.checkLocationSettings(builder.build()).addOnSuccessListener {
            enableUserLocation()
        }.addOnFailureListener { exception ->
            if (exception is ResolvableApiException) {
                try {
                    val intentSenderRequest = IntentSenderRequest.Builder(exception.resolution).build()
                    resolutionForResult.launch(intentSenderRequest)
                } catch (sendEx: Exception) {
                    Log.e(TAG, "Error showing location prompt", sendEx)
                }
            }
        }
    }

    private fun enableUserLocation() {
        if (mMap == null) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            mMap?.isMyLocationEnabled = true
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        SyncEngine.removeListener(syncListener)
        mainHandler.removeCallbacks(clockTicker)
        try {
            unregisterReceiver(locationStateReceiver)
        } catch (e: Exception) {
            // Already unregistered
        }
    }
}