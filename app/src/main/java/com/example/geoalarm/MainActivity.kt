package com.example.geoalarm

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import androidx.viewpager2.widget.CompositePageTransformer
import androidx.viewpager2.widget.MarginPageTransformer
import androidx.recyclerview.widget.RecyclerView
import com.example.geoalarm.network.ApiClient
import com.example.geoalarm.network.ChangePasswordRequest
import com.example.geoalarm.network.EventReporter
import com.example.geoalarm.network.JobItem
import com.example.geoalarm.network.SyncEngine
import com.example.geoalarm.network.SyncState
import com.example.geoalarm.storage.LocalEventDatabaseHelper
import com.example.geoalarm.storage.LocalEventRecord
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
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
    // Dynamic Calendar State (Issue 16)
    private var currentCalendarMonth: Calendar = Calendar.getInstance()


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
    private var liveDayPlans: List<com.example.geoalarm.network.DayPlanPayload> = emptyList()
    private var liveScheduleItems: List<com.example.geoalarm.network.ScheduleItemPayload> = emptyList()
    private var liveAccommodation: com.example.geoalarm.network.AccommodationItem? = null

    // Logged in Worker Profile
    private var loggedInWorkerId: String = ""
    private var loggedInWorkerName: String = ""
    private var loggedInWorkerDesignation: String = ""
    private var loggedInWorkerPhone: String = ""
    private var loggedInWorkerTallyNo: String = ""

    // Map Markers Cache
    private val worksiteMarkers = mutableMapOf<String, Marker>()
    private var accommodationMarker: Marker? = null

    // Assigned Accommodation from Website (null if not assigned)
    private var assignedStayCenter: LatLng? = null
    private var currentGpsLocation: LatLng? = null

    // Handlers for Clock, Shift Timer, and Periodic Server Refresh
    private val mainHandler = Handler(Looper.getMainLooper())
    private var startTimeMillis = 0L
    private var activeHoursFilter = "Today"

    // 15-Second Automatic Server Sync Ticker (Profile & Worksites Real-Time Sync)
    private val autoSyncTicker = object : Runnable {
        override fun run() {
            syncAndRefreshServerData(showUserFeedback = false)
            mainHandler.postDelayed(this, 15_000L)
        }
    }

    // Secret 3-Tap Version Counter
    private var versionClickCount = 0
    private var lastVersionClickTime = 0L

    // Biometric Security Gate (GPay-style app lock on foreground resume)
    private var isAppLocked = false
    private var lastBackgroundTimestamp = 0L
    private var isInternalFlow = false
    private var lockOverlay: View? = null

    // Dynamic Selected Calendar Day
    private var selectedCalendarDay: Int = Calendar.getInstance().get(Calendar.DAY_OF_MONTH)

    // Sync State Listener
    private val syncListener: (SyncState) -> Unit = { state ->
        runOnUiThread {
            updateSyncBadgeUi(state)
        }
    }

    // Dynamic Work & Payroll State Receiver (Auto-clock-in from geofence arrival)
    private val workStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val isClockedIn = intent?.getBooleanExtra("IS_CLOCKED_IN", false) ?: false
            val clockInTime = intent?.getLongExtra("CLOCK_IN_TIMESTAMP", System.currentTimeMillis()) ?: System.currentTimeMillis()
            startTimeMillis = clockInTime
            updateClockInOutUi(isClockedIn)
            EventReporter.addLocalLog("Work State Receiver: Clocked In = $isClockedIn")
        }
    }

    fun getDailyAccumulatedWorkedMillis(sharedPrefs: SharedPreferences): Long {
        val todayYmd = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val savedDate = sharedPrefs.getString("DAILY_WORKED_DATE", "")
        return if (savedDate == todayYmd) {
            sharedPrefs.getLong("DAILY_WORKED_MILLIS", 0L)
        } else {
            0L
        }
    }

    private fun pauseAndAccumulateDailyWorkTime(sharedPrefs: SharedPreferences) {
        val clockInTime = sharedPrefs.getLong("CLOCK_IN_TIMESTAMP", 0L)
        val todayYmd = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val savedDate = sharedPrefs.getString("DAILY_WORKED_DATE", "")
        val prevAccumulated = if (savedDate == todayYmd) sharedPrefs.getLong("DAILY_WORKED_MILLIS", 0L) else 0L

        val sessionElapsed = if (clockInTime > 0L) {
            Math.max(0L, System.currentTimeMillis() - clockInTime)
        } else 0L

        sharedPrefs.edit()
            .putString("DAILY_WORKED_DATE", todayYmd)
            .putLong("DAILY_WORKED_MILLIS", prevAccumulated + sessionElapsed)
            .putLong("CLOCK_IN_TIMESTAMP", 0L)
            .apply()
    }

    private fun startOrResumeDailyWorkTime(sharedPrefs: SharedPreferences, now: Long) {
        val todayYmd = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val savedDate = sharedPrefs.getString("DAILY_WORKED_DATE", "")
        val editor = sharedPrefs.edit()
        if (savedDate != todayYmd) {
            editor.putString("DAILY_WORKED_DATE", todayYmd)
            editor.putLong("DAILY_WORKED_MILLIS", 0L)
        }
        editor.putLong("CLOCK_IN_TIMESTAMP", now)
        editor.apply()
    }

    // Live Clock & Shift Ticker (Cumulative Daily Work Timer)
    private val clockTicker = object : Runnable {
        override fun run() {
            // 1. Update Home Current Time Clock (always live)
            val tvTime = findViewById<TextView>(R.id.tvLiveCurrentTime)
            tvTime?.text = SimpleDateFormat("hh:mm:ss a", Locale.getDefault()).format(Date())

            // 2. Update Hours Worked Shift Timer (Cumulative Daily Timer)
            val sharedPrefs = getSharedPreferences("GeoAlarmPrefs", Context.MODE_PRIVATE)
            val isClockedIn = sharedPrefs.getBoolean("IS_CLOCKED_IN", false)

            val tvTimer = findViewById<TextView>(R.id.tvHoursWorkedTimer)
            if (tvTimer != null) {
                val accumulated = getDailyAccumulatedWorkedMillis(sharedPrefs)
                if (isClockedIn) {
                    val clockInTimestamp = sharedPrefs.getLong("CLOCK_IN_TIMESTAMP", 0L)
                    val baseTime = if (clockInTimestamp > 0L) clockInTimestamp else (if (startTimeMillis > 0L) startTimeMillis else System.currentTimeMillis())
                    val sessionElapsed = Math.max(0L, System.currentTimeMillis() - baseTime)
                    val totalWorked = accumulated + sessionElapsed
                    val hours = totalWorked / 3600000
                    val minutes = (totalWorked % 3600000) / 60000
                    val seconds = (totalWorked % 60000) / 1000
                    when (activeHoursFilter) {
                        "Today" -> tvTimer.text = String.format(Locale.US, "%02dh %02dm %02ds", hours, minutes, seconds)
                        "Last Month" -> tvTimer.text = "188h 00m"
                        else -> {
                            val monthlyHours = 160L + hours
                            tvTimer.text = String.format(Locale.US, "%02dh %02dm %02ds", monthlyHours, minutes, seconds)
                        }
                    }
                } else {
                    val hours = accumulated / 3600000
                    val minutes = (accumulated % 3600000) / 60000
                    when (activeHoursFilter) {
                        "Today" -> tvTimer.text = String.format(Locale.US, "%02dh %02dm (Paused)", hours, minutes)
                        "Last Month" -> tvTimer.text = "188h 00m"
                        else -> {
                            val monthlyHours = 160L + hours
                            tvTimer.text = String.format(Locale.US, "%02dh %02dm", monthlyHours, minutes)
                        }
                    }
                }
            }

            // Continuously verify and sync Clock In/Out button state
            val btnClockToggle = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnClockToggle)
            if (btnClockToggle != null) {
                val currentText = btnClockToggle.text?.toString() ?: ""
                if (isClockedIn && currentText != "Clock Out") {
                    updateClockInOutUi(true)
                } else if (!isClockedIn && currentText != "Clock In") {
                    updateClockInOutUi(false)
                }
            }

            // 3. Mandatory Location Enforcement while App is Open
            if (!isLocationServicesEnabled()) {
                if (!isLocationPromptShowing && (fallbackDialog == null || fallbackDialog?.isShowing == false)) {
                    isLocationPromptShowing = true
                    checkLocationSettings()
                }
            } else {
                isLocationPromptShowing = false
            }

            mainHandler.postDelayed(this, 1000)
        }
    }

    // Live Fused Location & Worksite Arrival Radar
    private lateinit var mainFusedLocationClient: FusedLocationProviderClient
    private var mainLocationCallback: LocationCallback? = null
    private var lastInsideJobId: String? = null
    private var consecutiveExitCount = 0

    // Mandatory Location Check & State Handling while App is Open
    private var isLocationPromptShowing = false
    private var fallbackDialog: AlertDialog? = null

    fun isLocationServicesEnabled(): Boolean {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            locationManager.isLocationEnabled
        } else {
            val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            val isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            isGpsEnabled || isNetworkEnabled
        }
    }

    private fun checkLocationEnabledOrPrompt() {
        if (!isLocationServicesEnabled()) {
            if (!isLocationPromptShowing && (fallbackDialog == null || fallbackDialog?.isShowing == false)) {
                isLocationPromptShowing = true
                checkLocationSettings()
            }
        } else {
            isLocationPromptShowing = false
            fallbackDialog?.dismiss()
            fallbackDialog = null
            enableUserLocation()
        }
    }

    private val foregroundLocationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            if (action == LocationManager.PROVIDERS_CHANGED_ACTION || action == "android.location.MODE_CHANGED") {
                Log.d(TAG, "Location provider changed while app is open. Checking location state...")
                checkLocationEnabledOrPrompt()
            }
        }
    }

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
                startRadarServiceSafely()
                requestBackgroundLocationIfNecessary()
            } else {
                Log.e(TAG, "Location permission denied by user")
            }
        }

    private val requestBgPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                Log.d(TAG, "Background location permission granted (Allow all the time)")
            }
        }

    private val resolutionForResult =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            isLocationPromptShowing = false
            if (result.resultCode == RESULT_OK) {
                Log.d(TAG, "User enabled location services.")
                enableUserLocation()
                startLiveLocationTracking()
            } else {
                Log.e(TAG, "User refused location services (clicked 'No thanks'). Closing app.")
                Toast.makeText(this, "Location is required to use SGS Field Tracker. Closing app...", Toast.LENGTH_LONG).show()
                finishAffinity()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize API Client & Sync Engine
        ApiClient.init(this)
        SyncEngine.init(this)
        SyncEngine.addListener(syncListener)
        SyncEngine.onDataUpdatedListener = {
            loadCachedDataOnLaunch()
            updateWorkerProfileViews()
            updateHomeUiWithLiveJobs(liveJobs)
            updateWorkCalendarWithJobs(liveJobs)
        }

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
        loggedInWorkerTallyNo = intent.getStringExtra("EXTRA_WORKER_TALLY_NO") ?: userPrefs.getString("WORKER_TALLY_NO", "") ?: ""

        updateWorkerProfileViews()

        // Initialize UI Tabs and Navigation
        // Apply WindowInsets padding to bottom navigation bar to prevent overlap with Android system navigation buttons (Issue 5)
        val bottomNav = findViewById<View>(R.id.bottomNavContainer)
        if (bottomNav != null) {
            ViewCompat.setOnApplyWindowInsetsListener(bottomNav) { view, insets ->
                val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                view.setPadding(0, 0, 0, systemBars.bottom)
                val baseHeight = (resources.displayMetrics.density * 64).toInt()
                view.layoutParams.height = baseHeight + systemBars.bottom
                view.requestLayout()
                insets
            }
        }

        setupBottomNavigation()
        setupHomeInteractions()
        setupProfileInteractions()
        setupWorkCalendar()
        setupMapDetailsCard()
        setupBiometricSecurity()

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

        // Start background Radar service for persistent worksite arrival tracking
        if (hasLocationPermission()) {
            startRadarServiceSafely()
        }

        // Register Foreground Location Receiver for Mandatory Location Enforcement while App is Open
        try {
            val filter = IntentFilter().apply {
                addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
                addAction("android.location.MODE_CHANGED")
            }
            ContextCompat.registerReceiver(
                this,
                foregroundLocationReceiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED
            )
        } catch (e: Exception) {
            Log.e(TAG, "Receiver registration error: ${e.message}")
        }

        // Register Work State Receiver for Automatic Geofence Clock-In
        try {
            val workFilter = IntentFilter(GeofenceBroadcastReceiver.ACTION_WORK_STATE_CHANGED)
            ContextCompat.registerReceiver(
                this,
                workStateReceiver,
                workFilter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } catch (e: Exception) {
            Log.e(TAG, "WorkStateReceiver registration error: ${e.message}")
        }

        // Start Live Clock and Shift Timer
        mainHandler.post(clockTicker)

        // Start 30-second background auto-sync ticker for profile & worksite updates
        mainHandler.postDelayed(autoSyncTicker, 30_000L)

        // Initialize Live Fused Location Tracking & Arrival Radar
        mainFusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        startLiveLocationTracking()

        // Initialize UI with locally cached worksites and accommodation immediately (Instant Offline Support)
        loadCachedDataOnLaunch()

        // Fetch Live Profile & Schedule from Admin Panel Backend
        syncAndRefreshServerData(showUserFeedback = false)
    }

    private fun loadCachedDataOnLaunch() {
        val geoPrefs = getSharedPreferences("GeoPrefs", Context.MODE_PRIVATE)
        val jobsJson = geoPrefs.getString("CACHED_JOBS_JSON", null)
        val accJson = geoPrefs.getString("CACHED_ACCOMMODATION_JSON", null)
        val dayPlansJson = geoPrefs.getString("CACHED_DAY_PLANS_JSON", null)
        val scheduleJson = geoPrefs.getString("CACHED_SCHEDULE_ITEMS_JSON", null)

        val cachedList: List<JobItem> = if (!jobsJson.isNullOrEmpty()) {
            try {
                val type = object : com.google.gson.reflect.TypeToken<List<JobItem>>() {}.type
                com.google.gson.Gson().fromJson<List<JobItem>>(jobsJson, type) ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }

        if (!dayPlansJson.isNullOrEmpty()) {
            try {
                val dpType = object : com.google.gson.reflect.TypeToken<List<com.example.geoalarm.network.DayPlanPayload>>() {}.type
                liveDayPlans = com.google.gson.Gson().fromJson(dayPlansJson, dpType) ?: emptyList()
            } catch (_: Exception) {
                liveDayPlans = emptyList()
            }
        }

        if (!scheduleJson.isNullOrEmpty()) {
            try {
                val siType = object : com.google.gson.reflect.TypeToken<List<com.example.geoalarm.network.ScheduleItemPayload>>() {}.type
                liveScheduleItems = com.google.gson.Gson().fromJson(scheduleJson, siType) ?: emptyList()
            } catch (_: Exception) {
                liveScheduleItems = emptyList()
            }
        }

        liveJobs = cachedList
        updateHomeUiWithLiveJobs(liveJobs)
        updateWorkCalendarWithJobs(liveJobs)

        if (liveJobs.isNotEmpty()) {
            ShiftAlarmScheduler.scheduleNextShiftAlarm(this)
            if (hasLocationPermission()) {
                armAllJobGeofences(liveJobs)
            }
        }

        if (!accJson.isNullOrEmpty()) {
            try {
                liveAccommodation = com.google.gson.Gson().fromJson(accJson, com.example.geoalarm.network.AccommodationItem::class.java)
                liveAccommodation?.location?.firstOrNull()?.let {
                    assignedStayCenter = LatLng(it.latitude, it.longitude)
                }
            } catch (_: Exception) {
                liveAccommodation = null
            }
        } else {
            liveAccommodation = null
        }
    }

    // ==========================================
    // BACKEND INTEGRATION: SYNC & REFRESH DATA
    // ==========================================
    private fun syncAndRefreshServerData(showUserFeedback: Boolean = false, onComplete: (() -> Unit)? = null) {
        if (showUserFeedback) {
            Toast.makeText(this, "Syncing latest data from server...", Toast.LENGTH_SHORT).show()
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val success = SyncEngine.pullSnapshot(this@MainActivity)
            withContext(Dispatchers.Main) {
                if (success) {
                    loadCachedDataOnLaunch()
                    val userPrefs = getSharedPreferences("UserPrefs", Context.MODE_PRIVATE)
                    loggedInWorkerName = userPrefs.getString("WORKER_NAME", loggedInWorkerName) ?: loggedInWorkerName
                    loggedInWorkerDesignation = userPrefs.getString("WORKER_DESIGNATION", loggedInWorkerDesignation) ?: loggedInWorkerDesignation
                    loggedInWorkerPhone = userPrefs.getString("WORKER_PHONE", loggedInWorkerPhone) ?: loggedInWorkerPhone
                    loggedInWorkerTallyNo = userPrefs.getString("WORKER_TALLY_NO", loggedInWorkerTallyNo) ?: loggedInWorkerTallyNo
                    updateWorkerProfileViews()
                    updateHomeUiWithLiveJobs(liveJobs)
                    updateWorkCalendarWithJobs(liveJobs)
                    EventReporter.addLocalLog("Schedule and Profile synced successfully (${liveJobs.size} worksites).")
                    if (showUserFeedback) {
                        Toast.makeText(this@MainActivity, "Data synced successfully (${liveJobs.size} worksites)!", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    EventReporter.addLocalLog("Sync snapshot failed.")
                    if (showUserFeedback) {
                        Toast.makeText(this@MainActivity, "Server sync unreachable", Toast.LENGTH_SHORT).show()
                    }
                }
                onComplete?.invoke()
            }
        }
    }

    private fun getDynamicGreeting(): String {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 5..11 -> "Good morning,"
            in 12..16 -> "Good afternoon,"
            in 17..21 -> "Good evening,"
            else -> "Good night,"
        }
    }

    private fun updateWorkerProfileViews() {
        val tvHomeGreeting = findViewById<TextView>(R.id.tvHomeGreeting)
        val tvHomeUserName = findViewById<TextView>(R.id.tvHomeUserName)
        val tvHomeDate = findViewById<TextView>(R.id.tvHomeDate)
        val tvHomeTallyNo = findViewById<TextView>(R.id.tvHomeTallyNo)
        val tvPayrollAmount = findViewById<TextView>(R.id.tvPayrollAmount)
        val tvProfileName = findViewById<TextView>(R.id.tvProfileName)
        val tvProfileTallyNo = findViewById<TextView>(R.id.tvProfileTallyNo)
        val tvProfileDepartment = findViewById<TextView>(R.id.tvProfileDepartment)
        val tvProfilePhone = findViewById<TextView>(R.id.tvProfilePhone)
        val tvProfileAccommodation = findViewById<TextView>(R.id.tvProfileAccommodation)
        val tvProfileCompany = findViewById<TextView>(R.id.tvProfileCompany)
        val tvProfileSupervisor = findViewById<TextView>(R.id.tvProfileSupervisor)
        val tvProfileWeeklyOff = findViewById<TextView>(R.id.tvProfileWeeklyOff)
        val flProfileAvatar = findViewById<FrameLayout>(R.id.flProfileAvatar)
        val tvProfileAvatarInitial = findViewById<TextView>(R.id.tvProfileAvatarInitial)

        val userPrefs = getSharedPreferences("UserPrefs", Context.MODE_PRIVATE)
        val supervisorName = userPrefs.getString("SUPERVISOR_NAME", "") ?: ""
        val supervisorPhone = userPrefs.getString("SUPERVISOR_PHONE", "") ?: ""
        val weeklyOff = userPrefs.getString("WEEKLY_OFF_DAY", "Friday") ?: "Friday"

        tvHomeGreeting?.text = getDynamicGreeting()
        tvHomeUserName?.text = loggedInWorkerName
        tvHomeDate?.text = SimpleDateFormat("EEE, d MMM yyyy", Locale.getDefault()).format(Date())
        val displayTally = if (loggedInWorkerTallyNo.isNotEmpty()) loggedInWorkerTallyNo else loggedInWorkerId
        tvHomeTallyNo?.text = "Tally No. $displayTally"
        updatePayrollDisplay()

        tvProfileName?.text = loggedInWorkerName

        val initialLetter = loggedInWorkerName.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "W"
        tvProfileAvatarInitial?.text = initialLetter

        val googleColors = listOf(
            Color.parseColor("#1A73E8"), // Google Blue
            Color.parseColor("#0F9D58"), // Google Green
            Color.parseColor("#D93025"), // Google Red
            Color.parseColor("#E37400"), // Google Orange
            Color.parseColor("#8E24AA"), // Google Purple
            Color.parseColor("#00897B"), // Google Teal
            Color.parseColor("#3949AB"), // Google Indigo
            Color.parseColor("#C2185B")  // Google Pink
        )
        val colorIndex = Math.abs(loggedInWorkerName.trim().hashCode()) % googleColors.size
        flProfileAvatar?.backgroundTintList = ColorStateList.valueOf(googleColors[colorIndex])
        tvProfileTallyNo?.text = "ID: $displayTally"
        tvProfileDepartment?.text = if (loggedInWorkerDesignation.isNotEmpty()) loggedInWorkerDesignation else "Field Worker"
        if (loggedInWorkerPhone.isNotEmpty()) {
            tvProfilePhone?.text = loggedInWorkerPhone
        }

        tvProfileSupervisor?.text = if (supervisorName.isNotEmpty()) {
            if (supervisorPhone.isNotEmpty()) "$supervisorName ($supervisorPhone)" else supervisorName
        } else "—"
        tvProfileWeeklyOff?.text = weeklyOff

        if (liveAccommodation != null) {
            tvProfileAccommodation?.text = liveAccommodation?.name ?: "Assigned Stay Accommodation"
        } else {
            tvProfileAccommodation?.text = "No Stay Accommodation Assigned"
        }

        val companyName = userPrefs.getString("COMPANY_NAME", "SGS Field Tracker") ?: "SGS Field Tracker"
        tvProfileCompany?.text = companyName
    }

    private fun updateHomeUiWithLiveJobs(jobs: List<JobItem>) {
        val tvWorksitesCountToday = findViewById<TextView>(R.id.tvWorksitesCountToday)
        val tvNextWorksiteTitle = findViewById<TextView>(R.id.tvNextWorksiteTitle)
        val tvNextWorksiteTime = findViewById<TextView>(R.id.tvNextWorksiteTime)
        val tvNextWorksiteRole = findViewById<TextView>(R.id.tvNextWorksiteRole)
        val tvNextSupervisor = findViewById<TextView>(R.id.tvNextSupervisor)

        val userPrefs = getSharedPreferences("UserPrefs", Context.MODE_PRIVATE)
        val supervisorName = userPrefs.getString("SUPERVISOR_NAME", "") ?: ""

        val workJobs = jobs.filter { it.siteType != "accommodation" && it.isStartingPoint != true }

        tvWorksitesCountToday?.text = "${workJobs.size} worksite${if (workJobs.size == 1) "" else "s"} today"

        if (workJobs.isNotEmpty()) {
            val firstJob = workJobs[0]
            tvNextWorksiteTitle?.text = firstJob.jobTitle ?: firstJob.jobId
            val activeDays = firstJob.days.joinToString(", ")
            val timeText = if (!firstJob.startTime.isNullOrEmpty() && !firstJob.endTime.isNullOrEmpty()) {
                "${firstJob.startTime} - ${firstJob.endTime}" + if (activeDays.isNotEmpty()) " ($activeDays)" else ""
            } else if (activeDays.isNotEmpty()) {
                "Active: $activeDays"
            } else {
                "Scheduled"
            }
            tvNextWorksiteTime?.text = timeText
            tvNextWorksiteRole?.text = firstJob.jobTitle ?: "Assigned Work"
            tvNextSupervisor?.text = if (supervisorName.isNotEmpty()) "Supervisor: $supervisorName" else "Supervisor: —"
        } else {
            tvNextWorksiteTitle?.text = "No Worksite Scheduled"
            tvNextWorksiteTime?.text = "Standby"
            tvNextWorksiteRole?.text = "—"
            tvNextSupervisor?.text = "—"
        }

        // Setup the card swiping structure just like in the user's reference image
        setupWorksitesSwiper(jobs)
    }

    private fun setupWorksitesSwiper(jobs: List<JobItem>) {
        val viewPager = findViewById<ViewPager2>(R.id.viewPagerWorksites) ?: return
        val emptyCard = findViewById<View>(R.id.cardWorksiteEmptyState)
        val dotsContainer = findViewById<LinearLayout>(R.id.llCarouselDots)
        val countBadge = findViewById<TextView>(R.id.tvWorksitesCountBadge)

        val workJobs = jobs.filter { it.siteType != "accommodation" && it.isStartingPoint != true }

        countBadge?.text = "${workJobs.size} Site${if (workJobs.size == 1) "" else "s"}"

        if (workJobs.isEmpty()) {
            viewPager.visibility = View.GONE
            emptyCard?.visibility = View.VISIBLE
            dotsContainer?.removeAllViews()
            return
        }

        viewPager.visibility = View.VISIBLE
        emptyCard?.visibility = View.GONE

        val userPrefs = getSharedPreferences("UserPrefs", Context.MODE_PRIVATE)
        val supervisorName = userPrefs.getString("SUPERVISOR_NAME", "") ?: ""

        val adapter = HomeWorksiteAdapter(workJobs, supervisorName) { job ->
            switchTab(1)
            focusOnWorksite(job)
        }

        viewPager.adapter = adapter
        viewPager.offscreenPageLimit = 3
        viewPager.clipToPadding = false
        viewPager.clipChildren = false
        (viewPager.getChildAt(0) as? RecyclerView)?.overScrollMode = RecyclerView.OVER_SCROLL_NEVER

        // Stacked Card Carousel PageTransformer (matches the user's reference image)
        val compositeTransformer = CompositePageTransformer().apply {
            addTransformer(MarginPageTransformer(0))
            addTransformer { page, position ->
                val absPos = Math.abs(position)
                if (position >= 0) {
                    page.scaleX = 0.88f + (1 - absPos) * 0.12f
                    page.scaleY = 0.88f + (1 - absPos) * 0.12f
                    page.translationX = -position * (page.width * 0.22f)
                    page.alpha = 0.60f + (1 - absPos) * 0.40f
                    page.elevation = (1 - absPos) * 12f
                } else {
                    page.scaleX = 0.88f + (1 - absPos) * 0.12f
                    page.scaleY = 0.88f + (1 - absPos) * 0.12f
                    page.translationX = 0f
                    page.alpha = 0.60f + (1 - absPos) * 0.40f
                    page.elevation = (1 - absPos) * 12f
                }
            }
        }
        viewPager.setPageTransformer(compositeTransformer)

        setupCarouselDots(dotsContainer, workJobs.size, 0)
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                setupCarouselDots(dotsContainer, workJobs.size, position)
            }
        })
    }

    private fun setupCarouselDots(container: LinearLayout?, count: Int, selectedIndex: Int) {
        if (container == null || count <= 1) {
            container?.removeAllViews()
            return
        }
        container.removeAllViews()
        for (i in 0 until count) {
            val dot = View(this).apply {
                val size = if (i == selectedIndex) 20 else 7
                val height = 7
                val layoutParams = LinearLayout.LayoutParams(
                    (size * resources.displayMetrics.density).toInt(),
                    (height * resources.displayMetrics.density).toInt()
                ).apply {
                    setMargins(6, 0, 6, 0)
                }
                this.layoutParams = layoutParams
                setBackgroundResource(
                    if (i == selectedIndex) R.drawable.bg_pill_blue else R.drawable.bg_circle_today
                )
                if (i != selectedIndex) {
                    backgroundTintList = ColorStateList.valueOf(Color.parseColor("#CBD5E1"))
                }
            }
            container.addView(dot)
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
        builder.setTitle("⚡ System Logs & Activity")

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
                    setTextColor(if (log.contains("⚠️") || log.contains("location_off")) Color.parseColor("#E11D48") else Color.parseColor("#334155"))
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
                        tvStatus?.text = "✓ SYNCED"
                        tvStatus?.setTextColor(Color.parseColor("#16A34A"))
                    } else {
                        tvStatus?.text = "⏳ PENDING"
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
                        Toast.makeText(context, "✓ Offline sync complete! All events delivered.", Toast.LENGTH_SHORT).show()
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
                updateWorkCalendarWithJobs(liveJobs)
            }
            3 -> {
                ivNavProfile.setColorFilter(activeColor)
                tvNavProfile.setTextColor(activeColor)
                tvNavProfile.paint.isFakeBoldText = true
                updateWorkerProfileViews()
            }
        }
    }

    // ==========================================
    // SCREEN 2: HOME INTERACTIONS & CLOCK IN/OUT
    // ==========================================
    private fun setupHomeInteractions() {
        val btnViewOnMapLink = findViewById<LinearLayout>(R.id.btnViewOnMapLink)
        val btnNextWorksiteArrow = findViewById<ImageView>(R.id.btnNextWorksiteArrow)
        val btnGoToMap = findViewById<View>(R.id.btnGoToMap)
        val btnClockToggle = findViewById<View>(R.id.btnClockToggle)
        val layoutSyncBadge = findViewById<LinearLayout>(R.id.layoutSyncBadge)

        val btnQuickPayroll = findViewById<LinearLayout>(R.id.btnQuickPayroll)
        val btnHeroPayroll = findViewById<LinearLayout>(R.id.btnHeroPayroll)
        val btnQuickViewMap = findViewById<LinearLayout>(R.id.btnQuickViewMap)
        val btnQuickClockToggle = findViewById<LinearLayout>(R.id.btnQuickClockToggle)
        val btnScheduleViewAll = findViewById<TextView>(R.id.btnScheduleViewAll)
        val btnViewAllWorksitesOnMap = findViewById<TextView>(R.id.btnViewAllWorksitesOnMap)
        val cardTodaySchedule = findViewById<FrameLayout>(R.id.cardTodaySchedule)
        val btnMonthDropdown = findViewById<LinearLayout>(R.id.btnMonthDropdown)

        val goToMapAction = View.OnClickListener {
            switchTab(1)
        }

        btnViewOnMapLink?.setOnClickListener(goToMapAction)
        btnNextWorksiteArrow?.setOnClickListener(goToMapAction)
        btnGoToMap?.setOnClickListener(goToMapAction)
        btnQuickViewMap?.setOnClickListener(goToMapAction)
        btnViewAllWorksitesOnMap?.setOnClickListener(goToMapAction)

        val openWorkAction = View.OnClickListener {
            val today = Calendar.getInstance()
            currentCalendarMonth = today.clone() as Calendar
            selectedCalendarDay = today.get(Calendar.DAY_OF_MONTH)
            switchTab(2)
        }
        btnScheduleViewAll?.setOnClickListener(openWorkAction)
        cardTodaySchedule?.setOnClickListener(openWorkAction)

        // Payroll card is purely an informative summary card; popup on tap removed per user request
        btnHeroPayroll?.setOnClickListener(null)
        btnHeroPayroll?.isClickable = false
        btnHeroPayroll?.isFocusable = false

        btnMonthDropdown?.setOnClickListener {
            showMonthFilterDialog()
        }

        val ivRefreshData = findViewById<ImageView>(R.id.ivRefreshData)

        ivRefreshData?.setOnClickListener {
            syncAndRefreshServerData(showUserFeedback = true)
        }

        // Tap Sync Badge to trigger immediate sync attempt
        layoutSyncBadge?.setOnClickListener {
            syncAndRefreshServerData(showUserFeedback = true)
        }

        // Clock In / Clock Out Toggle Button Handler
        btnClockToggle?.setOnClickListener {
            handleClockToggle()
        }
        btnQuickClockToggle?.setOnClickListener {
            handleClockToggle()
        }
    }

    private fun updatePayrollDisplay() {
        val tvPayrollPeriodLabel = findViewById<TextView>(R.id.tvPayrollPeriodLabel)
        val tvPayrollAmount = findViewById<TextView>(R.id.tvPayrollAmount)
        val tvPayrollBadge = findViewById<TextView>(R.id.tvPayrollBadge)
        val tvMonthFilterLabel = findViewById<TextView>(R.id.tvMonthFilterLabel)

        tvMonthFilterLabel?.text = "$activeHoursFilter ∨"

        val userPrefs = getSharedPreferences("UserPrefs", Context.MODE_PRIVATE)
        val baseSalaryStr = userPrefs.getString("WORKER_PAYROLL", "32500") ?: "32500"
        val cleanDigits = baseSalaryStr.filter { it.isDigit() }
        val baseMonthly = cleanDigits.toDoubleOrNull() ?: 32500.0

        when (activeHoursFilter) {
            "Today" -> {
                tvPayrollPeriodLabel?.text = "Today's Payroll"
                val dailyWage = (baseMonthly / 26.0).toInt().coerceAtLeast(1000)
                tvPayrollAmount?.text = String.format(Locale.US, "₹%,d", dailyWage)
                tvPayrollBadge?.text = "Daily Shift (8h Standard)"
            }
            "Last Month" -> {
                tvPayrollPeriodLabel?.text = "Last Month's Payroll"
                val lastMonthAmount = (baseMonthly * 0.96).toInt()
                tvPayrollAmount?.text = String.format(Locale.US, "₹%,d", lastMonthAmount)
                tvPayrollBadge?.text = "Disbursed & Completed"
            }
            else -> { // "This Month"
                tvPayrollPeriodLabel?.text = "This Month's Payroll"
                tvPayrollAmount?.text = String.format(Locale.US, "₹%,d", baseMonthly.toInt())
                tvPayrollBadge?.text = "+12% from last month"
            }
        }
    }

    private fun showMonthFilterDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_select_payroll_filter, null)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val cardFilterToday = dialogView.findViewById<View>(R.id.cardFilterToday)
        val cardFilterThisMonth = dialogView.findViewById<View>(R.id.cardFilterThisMonth)
        val cardFilterLastMonth = dialogView.findViewById<View>(R.id.cardFilterLastMonth)

        val ivRadioToday = dialogView.findViewById<ImageView>(R.id.ivRadioToday)
        val ivRadioThisMonth = dialogView.findViewById<ImageView>(R.id.ivRadioThisMonth)
        val ivRadioLastMonth = dialogView.findViewById<ImageView>(R.id.ivRadioLastMonth)

        val btnClose = dialogView.findViewById<View>(R.id.btnCloseFilterDialog)

        fun updateSelectionUi(selected: String) {
            val isToday = selected == "Today"
            val isThisMonth = selected == "This Month"
            val isLastMonth = selected == "Last Month"

            cardFilterToday.setBackgroundResource(if (isToday) R.drawable.bg_filter_option_selected else R.drawable.bg_filter_option_unselected)
            cardFilterThisMonth.setBackgroundResource(if (isThisMonth) R.drawable.bg_filter_option_selected else R.drawable.bg_filter_option_unselected)
            cardFilterLastMonth.setBackgroundResource(if (isLastMonth) R.drawable.bg_filter_option_selected else R.drawable.bg_filter_option_unselected)

            ivRadioToday.setImageResource(if (isToday) R.drawable.ic_check_circle else R.drawable.ic_radio_unselected)
            ivRadioToday.setColorFilter(if (isToday) ContextCompat.getColor(this, R.color.brand_blue) else Color.parseColor("#CBD5E1"))

            ivRadioThisMonth.setImageResource(if (isThisMonth) R.drawable.ic_check_circle else R.drawable.ic_radio_unselected)
            ivRadioThisMonth.setColorFilter(if (isThisMonth) ContextCompat.getColor(this, R.color.brand_blue) else Color.parseColor("#CBD5E1"))

            ivRadioLastMonth.setImageResource(if (isLastMonth) R.drawable.ic_check_circle else R.drawable.ic_radio_unselected)
            ivRadioLastMonth.setColorFilter(if (isLastMonth) ContextCompat.getColor(this, R.color.brand_blue) else Color.parseColor("#CBD5E1"))
        }

        updateSelectionUi(activeHoursFilter)

        cardFilterToday.setOnClickListener {
            activeHoursFilter = "Today"
            updatePayrollDisplay()
            mainHandler.post(clockTicker)
            dialog.dismiss()
        }

        cardFilterThisMonth.setOnClickListener {
            activeHoursFilter = "This Month"
            updatePayrollDisplay()
            mainHandler.post(clockTicker)
            dialog.dismiss()
        }

        cardFilterLastMonth.setOnClickListener {
            activeHoursFilter = "Last Month"
            updatePayrollDisplay()
            mainHandler.post(clockTicker)
            dialog.dismiss()
        }

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
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

            val now = System.currentTimeMillis()
            val activeJobId = if (liveJobs.isNotEmpty()) liveJobs[0].jobId else loggedInWorkerId
            startOrResumeDailyWorkTime(sharedPrefs, now)
            sharedPrefs.edit()
                .putBoolean("IS_CLOCKED_IN", true)
                .putBoolean("IS_SYSTEM_ARMED", true)
                .putBoolean("MANUAL_CLOCKED_OUT", false)
                .remove("MANUAL_CLOCKED_OUT_JOB_ID")
                .putString("ACTIVE_JOB_ID", activeJobId)
                .apply()

            startTimeMillis = now

            // 1. Start background RadarService
            startRadarServiceSafely()

            // 2. Arm Geofences for active jobs
            if (liveJobs.isNotEmpty()) {
                liveJobs.forEach { job ->
                    if (job.location.isNotEmpty()) {
                        armJobGeofence(job.jobId, LatLng(job.location[0].latitude, job.location[0].longitude), 100f)
                    }
                }
            }

            val eventLat = currentGpsLocation?.latitude ?: assignedStayCenter?.latitude ?: (if (liveJobs.isNotEmpty() && liveJobs[0].location.isNotEmpty()) liveJobs[0].location[0].latitude else 0.0)
            val eventLng = currentGpsLocation?.longitude ?: assignedStayCenter?.longitude ?: (if (liveJobs.isNotEmpty() && liveJobs[0].location.isNotEmpty()) liveJobs[0].location[0].longitude else 0.0)

            // 3. Dispatch Clock In event (stored locally and synced)
            EventReporter.reportEvent(
                context = this,
                eventType = "clock_in",
                jobId = activeJobId,
                latitude = eventLat,
                longitude = eventLng
            )
            EventReporter.addLocalLog("Worker Clocked In. Background radar & geofences armed.")

            // OS-Level Notification for Clock In
            WorkNotificationManager.showClockInNotification(this, activeJobId, isAuto = false)

            updateClockInOutUi(true)
            Toast.makeText(this, "Work Time Started! Background tracking active & armed.", Toast.LENGTH_SHORT).show()

        } else {
            // CLOCK OUT ACTION
            val activeJobId = sharedPrefs.getString("ACTIVE_JOB_ID", if (liveJobs.isNotEmpty()) liveJobs[0].jobId else loggedInWorkerId) ?: loggedInWorkerId
            val clockInTime = sharedPrefs.getLong("CLOCK_IN_TIMESTAMP", 0L)
            val dur = if (clockInTime > 0L) System.currentTimeMillis() - clockInTime else 0L
            val formattedDuration = if (dur > 0L) {
                val h = dur / 3600000
                val m = (dur % 3600000) / 60000
                String.format(Locale.US, "%02dh %02dm", h, m)
            } else ""

            pauseAndAccumulateDailyWorkTime(sharedPrefs)
            lastInsideJobId = activeJobId
            sharedPrefs.edit()
                .putBoolean("IS_CLOCKED_IN", false)
                .putBoolean("IS_SYSTEM_ARMED", false)
                .putBoolean("MANUAL_CLOCKED_OUT", true)
                .putString("MANUAL_CLOCKED_OUT_JOB_ID", activeJobId)
                .apply()

            // 1. Stop background RadarService
            stopService(Intent(this, RadarService::class.java))

            // 2. Keep Geofences actively armed for automatic next-shift worksite entry
            if (hasLocationPermission() && liveJobs.isNotEmpty()) {
                armAllJobGeofences(liveJobs)
            }

            // 3. Stop any ringing alarms
            AlarmController.stopAlarm(this)

            val eventLat = currentGpsLocation?.latitude ?: assignedStayCenter?.latitude ?: (if (liveJobs.isNotEmpty() && liveJobs[0].location.isNotEmpty()) liveJobs[0].location[0].latitude else 0.0)
            val eventLng = currentGpsLocation?.longitude ?: assignedStayCenter?.longitude ?: (if (liveJobs.isNotEmpty() && liveJobs[0].location.isNotEmpty()) liveJobs[0].location[0].longitude else 0.0)

            // 4. Dispatch Clock Out event (stored locally and synced)
            EventReporter.reportEvent(
                context = this,
                eventType = "clock_out",
                jobId = activeJobId,
                latitude = eventLat,
                longitude = eventLng
            )
            EventReporter.addLocalLog("Worker Clocked Out. System entered sleep mode.")

            // OS-Level Notification for Clock Out
            WorkNotificationManager.showClockOutNotification(this, activeJobId, formattedDuration)

            updateClockInOutUi(false)
            Toast.makeText(this, "Work Time Ended. App is now sleeping (Zero background usage).", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateClockInOutUi(isClockedIn: Boolean) {
        runOnUiThread {
            val tvAttendanceStatus = findViewById<TextView>(R.id.tvAttendanceStatus)
            val tvAttendanceSubtitle = findViewById<TextView>(R.id.tvAttendanceSubtitle)
            val ivAttendanceIcon = findViewById<ImageView>(R.id.ivAttendanceIcon)
            val btnClockToggle = findViewById<View>(R.id.btnClockToggle)
            val tvHoursWorkedLabel = findViewById<TextView>(R.id.tvHoursWorkedLabel)
            val tvQuickClockText = findViewById<TextView>(R.id.tvQuickClockText)
            val ivQuickClockIcon = findViewById<ImageView>(R.id.ivQuickClockIcon)

            if (isClockedIn) {
                tvAttendanceStatus?.text = "Work Time Started"
                tvAttendanceStatus?.setTextColor(getColor(R.color.status_green))
                tvAttendanceSubtitle?.text = "Tracking active • Background armed"
                ivAttendanceIcon?.setColorFilter(getColor(R.color.status_green))

                (btnClockToggle as? MaterialButton)?.text = "Clock Out"
                (btnClockToggle as? MaterialButton)?.setTextColor(Color.parseColor("#DC2626"))
                (btnClockToggle as? MaterialButton)?.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FEE2E2"))
                (btnClockToggle as? MaterialButton)?.strokeColor = ColorStateList.valueOf(Color.parseColor("#EF4444"))
                (btnClockToggle as? MaterialButton)?.icon = ContextCompat.getDrawable(this, R.drawable.ic_check_circle)
                (btnClockToggle as? MaterialButton)?.iconTint = ColorStateList.valueOf(Color.parseColor("#DC2626"))

                tvQuickClockText?.text = "Clock Out"
                tvQuickClockText?.setTextColor(Color.parseColor("#DC2626"))
                ivQuickClockIcon?.setColorFilter(Color.parseColor("#DC2626"))

                tvHoursWorkedLabel?.text = "Active Shift Duration"
            } else {
                tvAttendanceStatus?.text = "Work Time Ended"
                tvAttendanceStatus?.setTextColor(getColor(R.color.text_secondary))
                tvAttendanceSubtitle?.text = "App is sleeping • No background tracking"
                ivAttendanceIcon?.setColorFilter(getColor(R.color.text_muted))

                (btnClockToggle as? MaterialButton)?.text = "Clock In"
                (btnClockToggle as? MaterialButton)?.setTextColor(Color.WHITE)
                (btnClockToggle as? MaterialButton)?.backgroundTintList = ColorStateList.valueOf(getColor(R.color.brand_blue))
                (btnClockToggle as? MaterialButton)?.strokeColor = ColorStateList.valueOf(getColor(R.color.brand_blue))
                (btnClockToggle as? MaterialButton)?.icon = ContextCompat.getDrawable(this, R.drawable.ic_check_circle)
                (btnClockToggle as? MaterialButton)?.iconTint = ColorStateList.valueOf(Color.WHITE)

                tvQuickClockText?.text = "Clock In"
                tvQuickClockText?.setTextColor(Color.parseColor("#0F172A"))
                ivQuickClockIcon?.setColorFilter(Color.parseColor("#2563EB"))

                tvHoursWorkedLabel?.text = "Shift Paused (Sleep Mode)"
            }

            // Immediately refresh the live working hours timer on the Payroll card
            mainHandler.post(clockTicker)
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
            val defaultLoc = assignedStayCenter ?: (if (liveJobs.isNotEmpty() && liveJobs[0].location.isNotEmpty()) LatLng(liveJobs[0].location[0].latitude, liveJobs[0].location[0].longitude) else LatLng(0.0, 0.0))
            mMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(defaultLoc, 15f))
        }

        // GPS Recenter Button - Centers camera directly on user's live GPS location
        val fabRecenter = findViewById<CardView>(R.id.fabRecenterLocation)
        fabRecenter?.setOnClickListener {
            enableUserLocation()
            checkLocationSettings()
            if (currentGpsLocation != null) {
                mMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(currentGpsLocation!!, 17f))
                Toast.makeText(this, "Centered on Current Location", Toast.LENGTH_SHORT).show()
            } else {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    val fusedClient = LocationServices.getFusedLocationProviderClient(this)
                    fusedClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).addOnSuccessListener { loc ->
                        if (loc != null) {
                            val userLatLng = LatLng(loc.latitude, loc.longitude)
                            currentGpsLocation = userLatLng
                            mMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(userLatLng, 17f))
                            Toast.makeText(this, "Centered on Current Location", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this, "Acquiring GPS location...", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    Toast.makeText(this, "Location permission required to center GPS", Toast.LENGTH_SHORT).show()
                }
            }
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
        accommodationMarker = null

        val boundsBuilder = LatLngBounds.Builder()
        var hasPoints = false

        // 1. Plot Assigned Worksite Jobs (Blue Polygons and Azure Pins)
        jobs.forEach { job ->
            val polygonPoints = job.location.map { LatLng(it.latitude, it.longitude) }

            if (polygonPoints.isNotEmpty()) {
                hasPoints = true
                polygonPoints.forEach { boundsBuilder.include(it) }

                // Draw Worksite Polygon Zone
                if (polygonPoints.size >= 3) {
                    map.addPolygon(
                        PolygonOptions()
                            .addAll(polygonPoints)
                            .strokeColor(Color.parseColor("#0052CC"))
                            .fillColor(Color.argb(45, 0, 82, 204))
                            .strokeWidth(5f)
                    )
                }

                // Add Worksite Pin
                val centerPoint = polygonPoints[0]
                val marker = map.addMarker(
                    MarkerOptions()
                        .position(centerPoint)
                        .title(job.jobTitle ?: job.jobId)
                        .snippet("Worksite: ${job.jobId}")
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
                )
                if (marker != null) {
                    worksiteMarkers[job.jobId] = marker
                }

                armJobGeofence(job.jobId, centerPoint, 100f)
            }
        }

        // 2. Plot Worker Accommodation from Website ONLY (Orange Marker & Orange Perimeter)
        val acc = liveAccommodation
        if (acc != null && acc.location.isNotEmpty()) {
            val accPoints = acc.location.map { LatLng(it.latitude, it.longitude) }
            val accCenter = accPoints[0]
            assignedStayCenter = accCenter
            hasPoints = true
            accPoints.forEach { boundsBuilder.include(it) }

            // Draw Orange Perimeter for Accommodation
            if (accPoints.size >= 3) {
                map.addPolygon(
                    PolygonOptions()
                        .addAll(accPoints)
                        .strokeColor(Color.parseColor("#EA580C"))
                        .fillColor(Color.argb(40, 234, 88, 12))
                        .strokeWidth(5f)
                )
            }

            // Place Dedicated Orange Marker Pin
            accommodationMarker = map.addMarker(
                MarkerOptions()
                    .position(accCenter)
                    .title(acc.name ?: "Accommodation (Starting Point)")
                    .snippet("Stay / Starting Point (${acc.code ?: "Stay"})")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE))
            )
        }

        if (hasPoints) {
            try {
                val bounds = boundsBuilder.build()
                map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 120))
            } catch (e: Exception) {
                val stayCenter = assignedStayCenter
                if (stayCenter != null) {
                    map.moveCamera(CameraUpdateFactory.newLatLngZoom(stayCenter, 15f))
                } else if (jobs.isNotEmpty() && jobs[0].location.isNotEmpty()) {
                    val p = jobs[0].location[0]
                    map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(p.latitude, p.longitude), 15f))
                }
            }
        }

        // Render swipable top carousel cards (worksite jobs only)
        populateMapCarousel(jobs)
    }

    private fun populateMapCarousel(jobs: List<JobItem>) {
        val container = findViewById<LinearLayout>(R.id.llCarouselContainer) ?: return
        container.removeAllViews()

        val bottomCard = findViewById<View>(R.id.cardBottomWorksiteDetail)
        val carouselScroll = findViewById<View>(R.id.hsvWorksitesCarousel)

        if (jobs.isEmpty()) {
            bottomCard?.visibility = View.GONE
            carouselScroll?.visibility = View.GONE
            return
        }

        bottomCard?.visibility = View.VISIBLE
        carouselScroll?.visibility = View.VISIBLE
        updateBottomCardDetails(jobs.first())

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
                text = job.jobTitle ?: job.jobId
                setTextColor(getColor(R.color.text_primary))
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
            }

            val timeText = TextView(this).apply {
                val start = job.startTime ?: "Shift Start"
                val end = job.endTime ?: "Shift End"
                text = "Shift: $start - $end"
                setTextColor(getColor(R.color.brand_blue))
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
            }

            val durationText = TextView(this).apply {
                val breakM = job.breakDurationMinutes ?: 60
                text = "Break: ${breakM}m"
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
        val latLng = if (job.location.isNotEmpty()) {
            val loc = job.location[0]
            LatLng(loc.latitude, loc.longitude)
        } else if (job.geofenceCircle?.center != null) {
            LatLng(job.geofenceCircle.center.lat, job.geofenceCircle.center.lng)
        } else null

        if (latLng != null) {
            mMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 16f))
            updateBottomCardDetails(job)
        }
    }

    private fun updateBottomCardDetails(job: JobItem?) {
        val tvTitle = findViewById<TextView>(R.id.tvMapDetailTitle)
        val tvTime = findViewById<TextView>(R.id.tvMapDetailTime)
        val tvRole = findViewById<TextView>(R.id.tvMapDetailRole)
        val tvSupervisor = findViewById<TextView>(R.id.tvMapDetailSupervisor)

        if (job == null) {
            findViewById<View>(R.id.cardBottomWorksiteDetail)?.visibility = View.GONE
            return
        }

        findViewById<View>(R.id.cardBottomWorksiteDetail)?.visibility = View.VISIBLE
        tvTitle?.text = job.jobTitle ?: job.jobId
        val start = job.startTime ?: "Shift Start"
        val end = job.endTime ?: "Shift End"
        val breakM = job.breakDurationMinutes ?: 60
        tvTime?.text = "Shift Hours: $start – $end (Break: ${breakM}m)"
        tvRole?.text = if (!job.address.isNullOrEmpty()) "Location: ${job.address}" else "Geofenced Worksite"

        val userPrefs = getSharedPreferences("UserPrefs", Context.MODE_PRIVATE)
        val supervisorName = userPrefs.getString("SUPERVISOR_NAME", "") ?: ""
        tvSupervisor?.text = if (supervisorName.isNotEmpty()) "Supervisor: $supervisorName" else "Status: Assigned Shift Active"

        val geoPrefs = getSharedPreferences("GeoPrefs", Context.MODE_PRIVATE)
        val radius = geoPrefs.getInt("GEOFENCE_RADIUS_M", 300)
        val tvGeofenceStatus = findViewById<TextView>(R.id.tvMapDetailGeofenceStatus)
        tvGeofenceStatus?.text = "Status: Active Geofence Area (${radius}m)"
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

        val effectiveRadius = if (radius > 150f) 100f else radius

        val sharedPrefs = getSharedPreferences("GeoAlarmPrefs", Context.MODE_PRIVATE)
        sharedPrefs.edit()
            .putFloat("TARGET_RADIUS", effectiveRadius)
            .putBoolean("IS_SYSTEM_ARMED", true)
            .apply()

        val geoPrefs = getSharedPreferences("GeoPrefs", Context.MODE_PRIVATE)
        geoPrefs.edit()
            .putFloat("TARGET_LAT", latLng.latitude.toFloat())
            .putFloat("TARGET_LNG", latLng.longitude.toFloat())
            .apply()

        val geofence = Geofence.Builder()
            .setRequestId(jobId)
            .setCircularRegion(latLng.latitude, latLng.longitude, effectiveRadius)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
            .build()

        val geofencingRequest = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER or GeofencingRequest.INITIAL_TRIGGER_DWELL)
            .addGeofence(geofence)
            .build()

        try {
            geofencingClient.removeGeofences(listOf(jobId)).addOnCompleteListener {
                geofencingClient.addGeofences(geofencingRequest, geofencePendingIntent).addOnSuccessListener {
                    Log.d(TAG, "Armed Geofence for $jobId successfully (radius: ${effectiveRadius}m)")
                }.addOnFailureListener {
                    Log.e(TAG, "Geofence error: ${it.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Geofence registration error: ${e.message}")
        }
    }

    private fun armAllJobGeofences(jobs: List<JobItem>) {
        if (!hasLocationPermission() || jobs.isEmpty()) return

        val geofenceList = mutableListOf<Geofence>()
        for (job in jobs) {
            if (job.location.isNotEmpty()) {
                var latSum = 0.0
                var lngSum = 0.0
                for (pt in job.location) {
                    latSum += pt.latitude
                    lngSum += pt.longitude
                }
                val centerLat = latSum / job.location.size
                val centerLng = lngSum / job.location.size

                val geofence = Geofence.Builder()
                    .setRequestId(job.jobId)
                    .setCircularRegion(centerLat, centerLng, 100f)
                    .setExpirationDuration(Geofence.NEVER_EXPIRE)
                    .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
                    .build()
                geofenceList.add(geofence)
            }
        }

        if (geofenceList.isNotEmpty()) {
            val request = GeofencingRequest.Builder()
                .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER or GeofencingRequest.INITIAL_TRIGGER_DWELL)
                .addGeofences(geofenceList)
                .build()

            try {
                geofencingClient.addGeofences(request, geofencePendingIntent)
                    .addOnSuccessListener {
                        Log.d(TAG, "✓ Armed ${geofenceList.size} OS-level Geofences for background arrival tracking")
                    }
                    .addOnFailureListener {
                        Log.e(TAG, "Failed to register background geofences: ${it.message}")
                    }
            } catch (e: SecurityException) {
                Log.e(TAG, "Geofence security exception: ${e.message}")
            }
        }
    }

    // ==========================================
    // SCREEN 4: WORK / SCHEDULE & CALENDAR
    // ==========================================
    private fun setupWorkCalendar() {
        val ivPrev = findViewById<ImageView>(R.id.ivPrevMonth)
        val ivNext = findViewById<ImageView>(R.id.ivNextMonth)

        ivPrev?.setOnClickListener {
            currentCalendarMonth.add(Calendar.MONTH, -1)
            val todayCal = Calendar.getInstance()
            val isCurrentMonth = todayCal.get(Calendar.YEAR) == currentCalendarMonth.get(Calendar.YEAR) &&
                                 todayCal.get(Calendar.MONTH) == currentCalendarMonth.get(Calendar.MONTH)
            selectedCalendarDay = if (isCurrentMonth) todayCal.get(Calendar.DAY_OF_MONTH) else 1
            updateWorkCalendarWithJobs(liveJobs)
        }

        ivNext?.setOnClickListener {
            currentCalendarMonth.add(Calendar.MONTH, 1)
            val todayCal = Calendar.getInstance()
            val isCurrentMonth = todayCal.get(Calendar.YEAR) == currentCalendarMonth.get(Calendar.YEAR) &&
                                 todayCal.get(Calendar.MONTH) == currentCalendarMonth.get(Calendar.MONTH)
            selectedCalendarDay = if (isCurrentMonth) todayCal.get(Calendar.DAY_OF_MONTH) else 1
            updateWorkCalendarWithJobs(liveJobs)
        }
    }

    private fun updateWorkCalendarWithJobs(jobs: List<JobItem>) {
        val tvMonthTitle = findViewById<TextView>(R.id.tvCalendarMonthTitle)
        val monthFormat = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
        tvMonthTitle?.text = monthFormat.format(currentCalendarMonth.time)

        val grid = findViewById<GridLayout>(R.id.glCalendarDays) ?: return
        grid.removeAllViews()

        val cal = currentCalendarMonth.clone() as Calendar
        cal.set(Calendar.DAY_OF_MONTH, 1)
        val firstDayOfWeek = cal.get(Calendar.DAY_OF_WEEK) // 1=Sunday, 2=Monday, ...
        // Convert to Monday=0, Tuesday=1, ..., Sunday=6
        val offset = (firstDayOfWeek + 5) % 7
        val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)

        // Dynamic density-scaled dimensions so columns never collapse to 0
        val density = resources.displayMetrics.density
        val screenWidth = resources.displayMetrics.widthPixels
        val horizontalPadding = (76 * density).toInt()
        val cellWidth = Math.max((36 * density).toInt(), (screenWidth - horizontalPadding) / 7)
        val cellHeight = (44 * density).toInt()
        val circleSize = (34 * density).toInt()

        // Add empty cells for initial weekday offset
        for (i in 0 until offset) {
            val emptyCell = View(this).apply {
                layoutParams = GridLayout.LayoutParams().apply {
                    width = cellWidth
                    height = cellHeight
                }
            }
            grid.addView(emptyCell)
        }

        val userPrefs = getSharedPreferences("UserPrefs", Context.MODE_PRIVATE)
        val weeklyOffDayStr = userPrefs.getString("WEEKLY_OFF_DAY", "Friday") ?: "Friday"

        val todayCal = Calendar.getInstance()
        val isCurrentMonth = todayCal.get(Calendar.YEAR) == currentCalendarMonth.get(Calendar.YEAR) &&
                             todayCal.get(Calendar.MONTH) == currentCalendarMonth.get(Calendar.MONTH)
        val todayDay = if (isCurrentMonth) todayCal.get(Calendar.DAY_OF_MONTH) else -1

        val workJobs = jobs.filter { it.siteType != "accommodation" && it.isStartingPoint != true }
        val currentYear = currentCalendarMonth.get(Calendar.YEAR)
        val currentMonth = currentCalendarMonth.get(Calendar.MONTH)

        for (day in 1..daysInMonth) {
            val cellCal = currentCalendarMonth.clone() as Calendar
            cellCal.set(Calendar.DAY_OF_MONTH, day)
            val dayNameShort = SimpleDateFormat("EEE", Locale.US).format(cellCal.time)
            val dateStr = String.format(Locale.US, "%04d-%02d-%02d", currentYear, currentMonth + 1, day)

            val dayPlan = liveDayPlans.find { it.workDate == dateStr }
            val daySchedules = liveScheduleItems.filter { it.workDate == dateStr && it.status != "CANCELLED" }

            val isSelected = (day == selectedCalendarDay)
            val isToday = (day == todayDay)

            val isWeeklyOffFallback = dayNameShort.startsWith(weeklyOffDayStr.trim().take(3), ignoreCase = true)
            val isOffDay = (dayPlan != null && (dayPlan.status.equals("OFF", ignoreCase = true) || dayPlan.status.equals("WEEKLY_OFF", ignoreCase = true))) ||
                           (dayPlan == null && daySchedules.isEmpty() && isWeeklyOffFallback)

            val isHoliday = (dayPlan != null && dayPlan.status.equals("PUBLIC_HOLIDAY", ignoreCase = true))

            val hasWork = (daySchedules.isNotEmpty()) ||
                          (dayPlan != null && dayPlan.status.equals("WORKING", ignoreCase = true)) ||
                          (!isOffDay && !isHoliday && workJobs.isNotEmpty())

            val frameLayout = FrameLayout(this).apply {
                layoutParams = GridLayout.LayoutParams().apply {
                    width = cellWidth
                    height = cellHeight
                }
            }

            val dayText = TextView(this).apply {
                text = day.toString()
                textSize = 13f
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(circleSize, circleSize).apply {
                    gravity = Gravity.CENTER
                }
                setTypeface(null, Typeface.BOLD)

                when {
                    isSelected && isToday -> {
                        background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_circle_today_selected)
                        setTextColor(Color.WHITE)
                    }
                    isSelected -> {
                        background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_circle_selected)
                        setTextColor(Color.WHITE)
                    }
                    isToday -> {
                        background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_circle_today_green)
                        setTextColor(Color.WHITE)
                    }
                    isOffDay -> {
                        background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_circle_offday)
                        setTextColor(Color.parseColor("#EF4444"))
                    }
                    isHoliday -> {
                        background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_circle_holiday)
                        setTextColor(Color.parseColor("#D97706"))
                    }
                    hasWork -> {
                        background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_circle_number)
                        setTextColor(getColor(R.color.brand_blue))
                    }
                    else -> {
                        background = null
                        setTextColor(getColor(R.color.text_primary))
                    }
                }
            }

            frameLayout.addView(dayText)
            frameLayout.setOnClickListener {
                selectedCalendarDay = day
                updateWorkCalendarWithJobs(liveJobs)
            }
            grid.addView(frameLayout)
        }

        // Render dynamic schedule list in Work screen for selected date
        renderScheduleForSelectedDate(selectedCalendarDay, daysInMonth, workJobs)
    }

    private fun renderScheduleForSelectedDate(day: Int, daysInMonth: Int, workJobs: List<JobItem>) {
        val targetDay = Math.min(day, daysInMonth)
        val selectedCal = currentCalendarMonth.clone() as Calendar
        selectedCal.set(Calendar.DAY_OF_MONTH, targetDay)

        val year = currentCalendarMonth.get(Calendar.YEAR)
        val month = currentCalendarMonth.get(Calendar.MONTH)
        val dateStr = String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, targetDay)
        val dayFullName = SimpleDateFormat("EEEE", Locale.US).format(selectedCal.time)
        val dayShortName = SimpleDateFormat("EEE", Locale.US).format(selectedCal.time)
        val monthName = SimpleDateFormat("MMMM", Locale.getDefault()).format(currentCalendarMonth.time)

        val tvHeader = findViewById<TextView>(R.id.tvTimelineDateHeader)
        tvHeader?.text = "Schedule for $dayFullName, $monthName $targetDay, $year"

        val container = findViewById<LinearLayout>(R.id.llWorkTimelineContainer) ?: return
        container.removeAllViews()

        val userPrefs = getSharedPreferences("UserPrefs", Context.MODE_PRIVATE)
        val weeklyOffDayStr = userPrefs.getString("WEEKLY_OFF_DAY", "Friday") ?: "Friday"
        val isWeeklyOffFallback = dayShortName.startsWith(weeklyOffDayStr.trim().take(3), ignoreCase = true)

        val dayPlan = liveDayPlans.find { it.workDate == dateStr }
        val daySchedules = liveScheduleItems.filter { it.workDate == dateStr && it.status != "CANCELLED" }

        val isOffDay = (dayPlan != null && (dayPlan.status.equals("OFF", ignoreCase = true) || dayPlan.status.equals("WEEKLY_OFF", ignoreCase = true))) ||
                       (dayPlan == null && daySchedules.isEmpty() && isWeeklyOffFallback)

        val isHoliday = (dayPlan != null && dayPlan.status.equals("PUBLIC_HOLIDAY", ignoreCase = true))

        if (isOffDay) {
            val card = CardView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, 16)
                }
                radius = 16f
                cardElevation = 0f
                setCardBackgroundColor(Color.parseColor("#FEF2F2"))
                setContentPadding(20, 20, 20, 20)
            }

            val cardLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }

            val titleLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val title = TextView(this).apply {
                text = "Weekly Off / Rest Day"
                setTextColor(Color.parseColor("#991B1B"))
                textSize = 15f
                setTypeface(null, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val badge = TextView(this).apply {
                text = "Off Duty"
                setTextColor(Color.parseColor("#DC2626"))
                textSize = 11f
                setTypeface(null, Typeface.BOLD)
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_circle_offday)
                setPadding(16, 6, 16, 6)
            }

            titleLayout.addView(title)
            titleLayout.addView(badge)
            cardLayout.addView(titleLayout)

            val desc = TextView(this).apply {
                text = "No worksites scheduled for this date. Enjoy your rest day! Normal shift operations resume on your next working day."
                setTextColor(Color.parseColor("#7F1D1D"))
                textSize = 12f
                setPadding(0, 8, 0, 0)
            }
            cardLayout.addView(desc)
            card.addView(cardLayout)
            container.addView(card)
            return
        }

        if (isHoliday) {
            val card = CardView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, 16)
                }
                radius = 16f
                cardElevation = 0f
                setCardBackgroundColor(Color.parseColor("#FFFBEB"))
                setContentPadding(20, 20, 20, 20)
            }

            val cardLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }

            val titleLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val title = TextView(this).apply {
                text = "Official Public Holiday"
                setTextColor(Color.parseColor("#92400E"))
                textSize = 15f
                setTypeface(null, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val badge = TextView(this).apply {
                text = "Paid Leave"
                setTextColor(Color.parseColor("#D97706"))
                textSize = 11f
                setTypeface(null, Typeface.BOLD)
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_circle_holiday)
                setPadding(16, 6, 16, 6)
            }

            titleLayout.addView(title)
            titleLayout.addView(badge)
            cardLayout.addView(titleLayout)

            val desc = TextView(this).apply {
                text = "Recognized company public holiday. No operational field worksites scheduled."
                setTextColor(Color.parseColor("#78350F"))
                textSize = 12f
                setPadding(0, 8, 0, 0)
            }
            cardLayout.addView(desc)
            card.addView(cardLayout)
            container.addView(card)
            return
        }

        // Active Shifts for this date
        if (daySchedules.isNotEmpty()) {
            daySchedules.sortedBy { it.sequence }.forEach { schedule ->
                val matchingJob = workJobs.find { it.jobId == schedule.jobId }

                val card = CardView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, 0, 0, 16)
                    }
                    radius = 16f
                    cardElevation = 0f
                    setCardBackgroundColor(getColor(R.color.card_bg))
                    setContentPadding(18, 16, 18, 16)
                }

                val cardLayout = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                }

                val titleLayout = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }

                val dot = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(16, 16).apply {
                        setMargins(0, 0, 12, 0)
                    }
                    background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_circle_today)
                }

                val siteTitle = matchingJob?.jobTitle ?: schedule.jobItemSummary?.staffGradeDescription ?: "Worksite ${schedule.jobId.take(8)}"
                val title = TextView(this).apply {
                    text = siteTitle
                    setTextColor(getColor(R.color.text_primary))
                    textSize = 15f
                    setTypeface(null, Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }

                val badge = TextView(this).apply {
                    text = "Shift #${schedule.sequence}"
                    setTextColor(getColor(R.color.brand_blue))
                    textSize = 11f
                    setTypeface(null, Typeface.BOLD)
                    background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_pill_blue)
                    setPadding(16, 6, 16, 6)
                }

                titleLayout.addView(dot)
                titleLayout.addView(title)
                titleLayout.addView(badge)
                cardLayout.addView(titleLayout)

                val shiftStart = dayPlan?.planned?.firstSiteStart ?: matchingJob?.startTime ?: "06:00"
                val shiftEnd = dayPlan?.planned?.lastSiteEnd ?: matchingJob?.endTime ?: "15:30"
                val breakM = dayPlan?.planned?.breakMinutes ?: matchingJob?.breakDurationMinutes ?: 60

                val timeText = TextView(this).apply {
                    text = "Shift Hours: $shiftStart – $shiftEnd (Break: ${breakM}m) • ${schedule.assignedHours} hrs"
                    setTextColor(getColor(R.color.text_secondary))
                    textSize = 12f
                    setPadding(28, 6, 0, 0)
                }
                cardLayout.addView(timeText)

                val addr = matchingJob?.address ?: "Site Geofence Active (Coordinates Monitored)"
                val addrText = TextView(this).apply {
                    text = "Location: $addr"
                    setTextColor(getColor(R.color.text_secondary))
                    textSize = 12f
                    setPadding(28, 2, 0, 0)
                }
                cardLayout.addView(addrText)

                val roleDesc = schedule.jobItemSummary?.staffGradeDescription ?: loggedInWorkerDesignation
                if (roleDesc.isNotEmpty()) {
                    val roleText = TextView(this).apply {
                        text = "Designation: $roleDesc"
                        setTextColor(getColor(R.color.text_muted))
                        textSize = 11f
                        setPadding(28, 2, 0, 0)
                    }
                    cardLayout.addView(roleText)
                }

                if (matchingJob != null) {
                    val btnViewMap = TextView(this).apply {
                        text = "View on Map →"
                        setTextColor(getColor(R.color.brand_blue))
                        textSize = 12f
                        setTypeface(null, Typeface.BOLD)
                        setPadding(28, 10, 0, 0)
                        setOnClickListener {
                            switchTab(1)
                            focusOnWorksite(matchingJob)
                        }
                    }
                    cardLayout.addView(btnViewMap)
                }

                card.addView(cardLayout)
                container.addView(card)
            }
            return
        }

        // Fallback to workJobs if no specific schedule items but working day
        if (workJobs.isNotEmpty()) {
            workJobs.forEachIndexed { idx, job ->
                val card = CardView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, 0, 0, 16)
                    }
                    radius = 16f
                    cardElevation = 0f
                    setCardBackgroundColor(getColor(R.color.card_bg))
                    setContentPadding(18, 16, 18, 16)
                }

                val cardLayout = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                }

                val titleLayout = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }

                val dot = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(16, 16).apply {
                        setMargins(0, 0, 12, 0)
                    }
                    background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_circle_today)
                }

                val title = TextView(this).apply {
                    text = job.jobTitle ?: job.jobId
                    setTextColor(getColor(R.color.text_primary))
                    textSize = 15f
                    setTypeface(null, Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }

                val badge = TextView(this).apply {
                    text = "Site #${idx + 1}"
                    setTextColor(getColor(R.color.brand_blue))
                    textSize = 11f
                    setTypeface(null, Typeface.BOLD)
                    background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_pill_blue)
                    setPadding(16, 6, 16, 6)
                }

                titleLayout.addView(dot)
                titleLayout.addView(title)
                titleLayout.addView(badge)
                cardLayout.addView(titleLayout)

                val start = dayPlan?.planned?.firstSiteStart ?: job.startTime ?: "06:00"
                val end = dayPlan?.planned?.lastSiteEnd ?: job.endTime ?: "15:30"
                val breakM = dayPlan?.planned?.breakMinutes ?: job.breakDurationMinutes ?: 60

                val timeText = TextView(this).apply {
                    text = "Shift Hours: $start – $end (Break: ${breakM}m)"
                    setTextColor(getColor(R.color.text_secondary))
                    textSize = 12f
                    setPadding(28, 6, 0, 0)
                }
                cardLayout.addView(timeText)

                if (!job.address.isNullOrEmpty()) {
                    val addrText = TextView(this).apply {
                        text = "Location: ${job.address}"
                        setTextColor(getColor(R.color.text_secondary))
                        textSize = 12f
                        setPadding(28, 2, 0, 0)
                    }
                    cardLayout.addView(addrText)
                }

                val btnViewMap = TextView(this).apply {
                    text = "View on Map →"
                    setTextColor(getColor(R.color.brand_blue))
                    textSize = 12f
                    setTypeface(null, Typeface.BOLD)
                    setPadding(28, 10, 0, 0)
                    setOnClickListener {
                        switchTab(1)
                        focusOnWorksite(job)
                    }
                }
                cardLayout.addView(btnViewMap)

                card.addView(cardLayout)
                container.addView(card)
            }
            return
        }

        // If no work and not offday: Standby
        val emptyView = TextView(this).apply {
            text = "No shifts scheduled for this date.\nYou are currently on standby."
            setTextColor(getColor(R.color.text_secondary))
            textSize = 13f
            setPadding(16, 24, 16, 24)
            gravity = Gravity.CENTER
        }
        container.addView(emptyView)
    }

    // ==========================================
    // SCREEN 5: PROFILE, ACCOMMODATION & 3-TAP AUDIT
    // ==========================================
    private fun setupProfileInteractions() {
        val btnProfileAccommodationRow = findViewById<LinearLayout>(R.id.btnProfileAccommodationRow)
        
        btnProfileAccommodationRow?.setOnClickListener {
            if (liveAccommodation != null && assignedStayCenter != null) {
                switchTab(1)
                assignedStayCenter?.let { mMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(it, 16f)) }
                accommodationMarker?.showInfoWindow()
                Toast.makeText(this, "Accommodation: ${liveAccommodation?.name}", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "No stay accommodation currently assigned to your profile.", Toast.LENGTH_SHORT).show()
            }
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

        val btnChangePassword = findViewById<MaterialButton>(R.id.btnChangePassword)
        btnChangePassword?.setOnClickListener {
            showChangePasswordDialog()
        }

        val btnLogout = findViewById<MaterialButton>(R.id.btnLogout)
        btnLogout?.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Log Out")
                .setMessage("Are you sure you want to log out?")
                .setPositiveButton("Log Out") { _, _ ->
                    val userPrefs = getSharedPreferences("UserPrefs", Context.MODE_PRIVATE)
                    val secPrefs = getSharedPreferences("AppSecurityPrefs", Context.MODE_PRIVATE)
                    val geoPrefs = getSharedPreferences("GeoPrefs", Context.MODE_PRIVATE)
                    userPrefs.edit().clear().apply()
                    secPrefs.edit().clear().apply()
                    geoPrefs.edit().clear().apply()
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

    private fun showChangePasswordDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_change_password, null)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val etCurrentPassword = dialogView.findViewById<EditText>(R.id.etCurrentPassword)
        val etNewPassword = dialogView.findViewById<EditText>(R.id.etNewPassword)
        val etConfirmPassword = dialogView.findViewById<EditText>(R.id.etConfirmPassword)
        val tvError = dialogView.findViewById<TextView>(R.id.tvChangePasswordError)
        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnCancelChangePassword)
        val btnSubmit = dialogView.findViewById<MaterialButton>(R.id.btnSubmitChangePassword)

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnSubmit.setOnClickListener {
            val currentPwd = etCurrentPassword.text.toString().trim()
            val newPwd = etNewPassword.text.toString().trim()
            val confirmPwd = etConfirmPassword.text.toString().trim()

            tvError.visibility = View.GONE

            if (currentPwd.isEmpty()) {
                tvError.text = "Please enter your current password."
                tvError.visibility = View.VISIBLE
                etCurrentPassword.requestFocus()
                return@setOnClickListener
            }

            if (newPwd.isEmpty()) {
                tvError.text = "Please enter a new password."
                tvError.visibility = View.VISIBLE
                etNewPassword.requestFocus()
                return@setOnClickListener
            }

            if (newPwd.length < 8) {
                tvError.text = "New password must be at least 8 characters long."
                tvError.visibility = View.VISIBLE
                etNewPassword.requestFocus()
                return@setOnClickListener
            }

            if (newPwd == currentPwd) {
                tvError.text = "New password cannot be the same as your current password."
                tvError.visibility = View.VISIBLE
                etNewPassword.requestFocus()
                return@setOnClickListener
            }

            if (!newPwd.any { it.isLetter() } || !newPwd.any { it.isDigit() }) {
                tvError.text = "New password must contain both letters and numbers."
                tvError.visibility = View.VISIBLE
                etNewPassword.requestFocus()
                return@setOnClickListener
            }

            if (newPwd != confirmPwd) {
                tvError.text = "New passwords do not match."
                tvError.visibility = View.VISIBLE
                etConfirmPassword.requestFocus()
                return@setOnClickListener
            }

            btnSubmit.isEnabled = false
            btnSubmit.text = "Updating..."

            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val req = com.example.geoalarm.network.MobileChangePasswordRequest(
                        oldPassword = currentPwd,
                        newPassword = newPwd
                    )
                    val response = ApiClient.apiService.changePassword(req)
                    withContext(Dispatchers.Main) {
                        btnSubmit.isEnabled = true
                        btnSubmit.text = "Update Password"

                        if (response.isSuccessful) {
                            dialog.dismiss()
                            Toast.makeText(this@MainActivity, "✓ Password changed successfully in database!", Toast.LENGTH_LONG).show()
                            EventReporter.addLocalLog("Security: Password updated for $loggedInWorkerName")
                        } else {
                            var errMsg = "Failed to update password."
                            try {
                                val errBody = response.errorBody()?.string()
                                if (!errBody.isNullOrEmpty()) {
                                    val json = org.json.JSONObject(errBody)
                                    val detail = if (json.has("detail")) json.optString("detail") else null
                                    val message = if (json.has("message")) json.optString("message") else null
                                    val code = if (json.has("code")) json.optString("code") else null
                                    errMsg = when (code) {
                                        "INVALID_CURRENT_PASSWORD" -> "Current password is incorrect. Please check and try again."
                                        "CANNOT_REUSE_DEFAULT_PASSWORD" -> "New password cannot be the company default password."
                                        "PASSWORD_TOO_SHORT" -> "New password is too short. Must be at least 8 characters."
                                        "DEVICE_NOT_ACTIVE" -> "Device session expired or revoked. Please log in again."
                                        else -> detail?.takeIf { it.isNotEmpty() } ?: message?.takeIf { it.isNotEmpty() } ?: "Error (${response.code()}): Failed to update password."
                                    }
                                }
                            } catch (e: Exception) {
                                errMsg = "Server error (${response.code()})"
                            }
                            tvError.text = errMsg
                            tvError.visibility = View.VISIBLE
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        btnSubmit.isEnabled = true
                        btnSubmit.text = "Update Password"
                        tvError.text = "Network error: ${e.message ?: "Could not reach server"}"
                        tvError.visibility = View.VISIBLE
                    }
                }
            }
        }

        dialog.show()
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
        val userPrefs = getSharedPreferences("UserPrefs", Context.MODE_PRIVATE)
        val workerId = userPrefs.getString("WORKER_ID", "")
        if (workerId.isNullOrEmpty()) return

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

    private fun requestBackgroundLocationIfNecessary() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val hasBg = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasBg) {
                AlertDialog.Builder(this)
                    .setTitle("Allow Background Location")
                    .setMessage("To automatically start work time when arriving at your worksite with the app closed, please select 'Allow all the time' in location permissions.")
                    .setPositiveButton("Grant") { _, _ ->
                        requestBgPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    }
                    .setNegativeButton("Later", null)
                    .show()
            }
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
        if (!Settings.canDrawOverlays(this)) {
            val isAndroid13Plus = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            val message = if (isAndroid13Plus) {
                "Enable 'Display over other apps' to allow instant lock-screen shift and attendance alerts.\n\n" +
                "ℹ️ If the toggle is grayed out ('Restricted setting'):\n" +
                "1. Tap 'App Info (Unrestrict)' below\n" +
                "2. Tap the 3 dots (⋮) in the top-right corner\n" +
                "3. Tap 'Allow restricted settings'\n" +
                "4. Return and toggle 'Display over other apps' ON."
            } else {
                "Enable 'Display over other apps' for instant lock screen alerts and shift notifications."
            }

            AlertDialog.Builder(this)
                .setTitle("Display Over Other Apps")
                .setMessage(message)
                .setPositiveButton("Overlay Settings") { _, _ ->
                    openOverlaySettings()
                }
                .setNeutralButton("App Info (Unrestrict)") { _, _ ->
                    openAppInfoSettings()
                }
                .setNegativeButton("Skip", null)
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
                        isInternalFlow = true
                        try {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                                Uri.parse("package:$packageName")
                            )
                            startActivity(intent)
                        } catch (_: Exception) {
                            openAppInfoSettings()
                        }
                    }
                    .setNegativeButton("Later", null)
                    .show()
            }
        }
    }

    private fun openOverlaySettings() {
        isInternalFlow = true
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            } catch (_: Exception) {
                openAppInfoSettings()
            }
        }
    }

    private fun openAppInfoSettings() {
        isInternalFlow = true
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open App Info settings", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkLocationSettings() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000).build()
        val builder = LocationSettingsRequest.Builder()
            .addLocationRequest(locationRequest)
            .setAlwaysShow(true)
        val client = LocationServices.getSettingsClient(this)

        client.checkLocationSettings(builder.build()).addOnSuccessListener {
            isLocationPromptShowing = false
            enableUserLocation()
        }.addOnFailureListener { exception ->
            if (exception is ResolvableApiException) {
                try {
                    isInternalFlow = true
                    val intentSenderRequest = IntentSenderRequest.Builder(exception.resolution).build()
                    resolutionForResult.launch(intentSenderRequest)
                } catch (sendEx: Exception) {
                    Log.e(TAG, "Error showing location prompt", sendEx)
                    showFallbackLocationDialog()
                }
            } else {
                showFallbackLocationDialog()
            }
        }
    }

    private fun showFallbackLocationDialog() {
        if (isFinishing || isDestroyed) return
        if (fallbackDialog?.isShowing == true) return

        fallbackDialog = AlertDialog.Builder(this)
            .setTitle("Turn On Location")
            .setMessage("SGS Field Tracker requires device location to function. Please turn on Location.")
            .setCancelable(false)
            .setPositiveButton("Turn On") { _, _ ->
                isLocationPromptShowing = false
                isInternalFlow = true
                try {
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                } catch (_: Exception) {}
            }
            .setNegativeButton("No thanks") { _, _ ->
                isLocationPromptShowing = false
                Toast.makeText(this, "Location is required to use SGS Field Tracker. Closing app...", Toast.LENGTH_LONG).show()
                finishAffinity()
            }
            .show()
    }

    private fun enableUserLocation() {
        if (mMap == null) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            mMap?.isMyLocationEnabled = true
        }
    }

    private fun startLiveLocationTracking() {
        if (!hasLocationPermission()) return

        // Battery optimization: 5s interval, min 2m displacement
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000)
            .setMinUpdateDistanceMeters(2f)
            .setMinUpdateIntervalMillis(3000)
            .build()

        mainLocationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                for (loc in result.locations) {
                    // Anti-Spoofing: Reject Mock GPS providers immediately in Foreground
                    if (GeofenceHelper.isLocationSpoofed(loc)) {
                        Log.e(TAG, "🚨 Mock location detected in MainActivity! Dropping GPS fix.")
                        WorkNotificationManager.showMockLocationAlertNotification(this@MainActivity)
                        EventReporter.reportEvent(
                            context = this@MainActivity,
                            eventType = "mock_location_detected",
                            jobId = lastInsideJobId,
                            latitude = loc.latitude,
                            longitude = loc.longitude
                        )
                        EventReporter.addLocalLog("Security Violation: Mock GPS fix dropped in Foreground (${loc.latitude}, ${loc.longitude}). Attendance rejected.")
                        continue
                    }

                    if (!GeofenceHelper.isLocationAccurate(loc)) {
                        Log.d(TAG, "Skipping inaccurate GPS fix (${loc.accuracy}m)")
                        continue
                    }
                    currentGpsLocation = LatLng(loc.latitude, loc.longitude)
                    checkAndTriggerWorksiteArrival(loc.latitude, loc.longitude)
                }
            }
        }

        try {
            mainFusedLocationClient.requestLocationUpdates(
                locationRequest,
                mainLocationCallback!!,
                Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "Location tracking permission error: ${e.message}")
        }
    }

    private fun checkAndTriggerWorksiteArrival(lat: Double, lng: Double) {
        val sharedPrefs = getSharedPreferences("GeoAlarmPrefs", Context.MODE_PRIVATE)
        val isClockedIn = sharedPrefs.getBoolean("IS_CLOCKED_IN", false)
        val wasInsideStay = sharedPrefs.getBoolean("WAS_INSIDE_STAY", false)

        // 1. EVALUATE ASSIGNED WORKSITES FIRST (Worksite Priority)
        val matchedJob = GeofenceHelper.findMatchingJob(lat, lng, liveJobs)

        if (matchedJob != null) {
            val jobId = matchedJob.jobId
            consecutiveExitCount = 0 // Reset exit debounce immediately

            // When worker is at a worksite, they are definitely NOT at accommodation
            if (wasInsideStay) {
                sharedPrefs.edit().putBoolean("WAS_INSIDE_STAY", false).apply()
            }

            if (lastInsideJobId != jobId) {
                lastInsideJobId = jobId

                if (!isClockedIn) {
                    if (ShiftScheduleHelper.isJobWithinShiftHours(matchedJob)) {
                        val now = System.currentTimeMillis()
                        startOrResumeDailyWorkTime(sharedPrefs, now)
                        sharedPrefs.edit()
                            .putBoolean("IS_CLOCKED_IN", true)
                            .putBoolean("IS_SYSTEM_ARMED", true)
                            .putString("ACTIVE_JOB_ID", jobId)
                            .apply()

                        startTimeMillis = now
                        updateClockInOutUi(true)

                        WorkNotificationManager.showClockInNotification(this, jobId, isAuto = true)

                        EventReporter.reportEvent(
                            context = this,
                            eventType = "clock_in",
                            jobId = jobId,
                            latitude = lat,
                            longitude = lng
                        )
                        EventReporter.reportEvent(
                            context = this,
                            eventType = "entry",
                            jobId = jobId,
                            latitude = lat,
                            longitude = lng
                        )
                        EventReporter.addLocalLog("Work time auto-started at $jobId. Payroll active.")
                        startRadarServiceSafely()
                    } else {
                        Log.d(TAG, "Worksite arrival inside $jobId detected, but outside scheduled shift hours/date. Clock-in skipped.")
                    }
                } else {
                    // Already clocked in, entering this worksite perimeter
                    WorkNotificationManager.showGeofenceEntryNotification(this, jobId)
                    EventReporter.reportEvent(
                        context = this,
                        eventType = "entry",
                        jobId = jobId,
                        latitude = lat,
                        longitude = lng
                    )
                    EventReporter.addLocalLog("Worksite Entry: Inside $jobId.")
                }
            }
            // CRITICAL: Worker is inside worksite -> Do NOT evaluate accommodation or clock out!
            return
        }

        // 2. WORKER IS OUTSIDE ALL ASSIGNED WORKSITES (matchedJob == null)
        if (lastInsideJobId != null) {
            consecutiveExitCount++
            if (consecutiveExitCount < 3) {
                Log.d(TAG, "Worksite exit pending debounce: reading $consecutiveExitCount/3 outside $lastInsideJobId")
                return
            }
            consecutiveExitCount = 0
            val exitedJobId = lastInsideJobId ?: "Worksite"
            lastInsideJobId = null

            if (isClockedIn) {
                WorkNotificationManager.showGeofenceExitNotification(this, exitedJobId)
                EventReporter.reportEvent(
                    context = this,
                    eventType = "exit",
                    jobId = exitedJobId,
                    latitude = lat,
                    longitude = lng
                )
                EventReporter.addLocalLog("Worksite boundary exit ($exitedJobId)")
            }
        }

        // 3. EVALUATE STAY / ACCOMMODATION (Only when outside worksite)
        val acc = liveAccommodation
        if (acc != null && acc.location.isNotEmpty()) {
            val isInsideStay = GeofenceHelper.isCoordinateInsideAccommodation(lat, lng, acc)
            val distToStay = GeofenceHelper.getDistanceToAccommodation(lat, lng, acc)

            if (isInsideStay) {
                if (!wasInsideStay) {
                    sharedPrefs.edit().putBoolean("WAS_INSIDE_STAY", true).apply()
                    val accId = acc.name?.takeIf { it.isNotBlank() } ?: "Accommodation"
                    EventReporter.reportEvent(
                        context = this,
                        eventType = "accommodation_entry",
                        jobId = accId,
                        latitude = lat,
                        longitude = lng
                    )
                    EventReporter.addLocalLog("Arrived at Accommodation: $accId")
                }

                // Returning to Accommodation after shift -> AUTO CLOCK OUT
                // Guard: Must be clocked in for at least 60 seconds to avoid instant clock out loop
                if (isClockedIn) {
                    val clockInTime = sharedPrefs.getLong("CLOCK_IN_TIMESTAMP", 0L)
                    val elapsed = System.currentTimeMillis() - clockInTime
                    if (clockInTime > 0L && elapsed < 60_000L) {
                        Log.d(TAG, "Inside stay but clocked in recently (${elapsed / 1000}s ago). Grace period active.")
                        return
                    }

                    val activeJobId = sharedPrefs.getString("ACTIVE_JOB_ID", liveJobs.firstOrNull()?.jobId ?: loggedInWorkerId) ?: loggedInWorkerId
                    val dur = if (clockInTime > 0L) System.currentTimeMillis() - clockInTime else 0L
                    val formattedDuration = if (dur > 0L) {
                        val h = dur / 3600000
                        val m = (dur % 3600000) / 60000
                        String.format(Locale.US, "%02dh %02dm", h, m)
                    } else ""

                    pauseAndAccumulateDailyWorkTime(sharedPrefs)
                    lastInsideJobId = null
                    sharedPrefs.edit()
                        .putBoolean("IS_CLOCKED_IN", false)
                        .putBoolean("IS_SYSTEM_ARMED", false)
                        .apply()

                    startTimeMillis = 0L
                    updateClockInOutUi(false)
                    WorkNotificationManager.showClockOutNotification(this, activeJobId, formattedDuration)

                    EventReporter.reportEvent(
                        context = this,
                        eventType = "clock_out",
                        jobId = activeJobId,
                        latitude = lat,
                        longitude = lng
                    )
                    EventReporter.addLocalLog("Shift Auto-Ended: Returned to accommodation. Payroll paused.")
                }
                return
            }

            // Outside Stay Departure: Must be > 60m away from accommodation before triggering Stay Departure
            if (wasInsideStay && distToStay > 60f) {
                sharedPrefs.edit().putBoolean("WAS_INSIDE_STAY", false).apply()
                val accId = acc.name?.takeIf { it.isNotBlank() } ?: "Accommodation"
                EventReporter.reportEvent(
                    context = this,
                    eventType = "accommodation_exit",
                    jobId = accId,
                    latitude = lat,
                    longitude = lng
                )
                EventReporter.addLocalLog("Departed Accommodation: $accId")

                val userPrefs = getSharedPreferences("UserPrefs", Context.MODE_PRIVATE)
                val workerType = userPrefs.getString("WORKER_TYPE", "") ?: ""
                val workerDesig = userPrefs.getString("WORKER_DESIGNATION", loggedInWorkerDesignation) ?: loggedInWorkerDesignation
                val isDriver = workerType.contains("driver", ignoreCase = true) || workerDesig.contains("driver", ignoreCase = true)

                if (isDriver) {
                    // Driver: Payable hours start on departure from accommodation (Item 16)
                    if (!isClockedIn) {
                        if (ShiftScheduleHelper.isAnyJobWithinShiftHours(liveJobs)) {
                            val now = System.currentTimeMillis()
                            val primaryJobId = liveJobs.firstOrNull()?.jobId ?: loggedInWorkerId
                            startOrResumeDailyWorkTime(sharedPrefs, now)
                            sharedPrefs.edit()
                                .putBoolean("IS_CLOCKED_IN", true)
                                .putBoolean("IS_SYSTEM_ARMED", true)
                                .putString("ACTIVE_JOB_ID", primaryJobId)
                                .apply()

                            startTimeMillis = now
                            updateClockInOutUi(true)
                            WorkNotificationManager.showClockInNotification(this, primaryJobId, isAuto = true)
                            EventReporter.reportEvent(
                                context = this,
                                eventType = "clock_in",
                                jobId = primaryJobId,
                                latitude = lat,
                                longitude = lng
                            )
                            EventReporter.addLocalLog("Shift Auto-Started (Driver): Exited stay accommodation. Payroll tracking active.")
                            startRadarServiceSafely()
                            return
                        } else {
                            Log.d(TAG, "Driver stay departure detected, but outside scheduled shift hours. Clock-in skipped.")
                        }
                    }
                } else {
                    // Regular worker: Payable hours start on entering worksite, NOT on leaving accommodation (Item 15)
                    Log.d(TAG, "Regular worker departed accommodation. Logged movement event. Payroll will start upon worksite entry.")
                }
            }
        }
    }

    // --- Biometric Security Lifecycle Gating ---

    private fun setupBiometricSecurity() {
        lockOverlay = findViewById(R.id.llBiometricLockOverlay)
        findViewById<MaterialButton>(R.id.btnUnlockBiometric)?.setOnClickListener {
            triggerBiometricUnlock()
        }

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isAppLocked) {
                    moveTaskToBack(true)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
    }

    private fun triggerBiometricUnlock() {
        if (!com.example.geoalarm.security.BiometricAuthManager.canAuthenticate(this)) {
            unlockApp()
            return
        }

        isAppLocked = true
        lockOverlay?.visibility = View.VISIBLE

        com.example.geoalarm.security.BiometricAuthManager.promptBiometricUnlock(
            activity = this,
            title = "Unlock GeoAlarm",
            subtitle = "Verify fingerprint, face, or PIN to continue",
            onSuccess = {
                unlockApp()
            },
            onError = { _, _ ->
                lockOverlay?.visibility = View.VISIBLE
            }
        )
    }

    private fun unlockApp() {
        isAppLocked = false
        lockOverlay?.visibility = View.GONE

        val isClockedIn = getSharedPreferences("GeoAlarmPrefs", Context.MODE_PRIVATE)
            .getBoolean("IS_CLOCKED_IN", false)
        updateClockInOutUi(isClockedIn)
        checkLocationEnabledOrPrompt()
        startLiveLocationTracking()
        syncAndRefreshServerData(showUserFeedback = false)
        ShiftAlarmScheduler.scheduleNextShiftAlarm(this)
    }

    override fun onResume() {
        super.onResume()
        if (isInternalFlow) {
            isInternalFlow = false
            unlockApp()
            return
        }

        if (isAppLocked && com.example.geoalarm.security.BiometricAuthManager.canAuthenticate(this)) {
            triggerBiometricUnlock()
            return
        } else {
            unlockApp()
        }
    }

    override fun onStop() {
        super.onStop()
        lastBackgroundTimestamp = System.currentTimeMillis()
        if (!isInternalFlow && com.example.geoalarm.security.BiometricAuthManager.canAuthenticate(this)) {
            isAppLocked = true
            lockOverlay?.visibility = View.VISIBLE
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        SyncEngine.removeListener(syncListener)
        SyncEngine.onDataUpdatedListener = null
        mainHandler.removeCallbacks(clockTicker)
        mainHandler.removeCallbacks(autoSyncTicker)
        fallbackDialog?.dismiss()
        fallbackDialog = null
        if (mainLocationCallback != null) {
            try {
                mainFusedLocationClient.removeLocationUpdates(mainLocationCallback!!)
            } catch (_: Exception) {}
        }
        try {
            unregisterReceiver(foregroundLocationReceiver)
        } catch (e: Exception) {
            // Already unregistered
        }
        try {
            unregisterReceiver(workStateReceiver)
        } catch (e: Exception) {
            // Already unregistered
        }
    }
}

// ==========================================
// HOME WORKSITES VIEW PAGER ADAPTER
// ==========================================
class HomeWorksiteAdapter(
    private val jobs: List<JobItem>,
    private val supervisorName: String,
    private val onWorksiteClick: (JobItem) -> Unit
) : RecyclerView.Adapter<HomeWorksiteAdapter.WorksiteViewHolder>() {

    inner class WorksiteViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTitle: TextView = view.findViewById(R.id.tvWorksiteCardTitle)
        val tvRole: TextView = view.findViewById(R.id.tvWorksiteCardRole)
        val tvRadius: TextView = view.findViewById(R.id.tvWorksiteRadiusBadge)
        val tvTiming: TextView = view.findViewById(R.id.tvWorksiteTiming)
        val tvSupervisor: TextView = view.findViewById(R.id.tvWorksiteSupervisor)
        val tvCoords: TextView = view.findViewById(R.id.tvWorksiteCoords)
        val btnViewOnMap: com.google.android.material.button.MaterialButton = view.findViewById(R.id.btnCardViewOnMap)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WorksiteViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_home_worksite_card, parent, false)
        return WorksiteViewHolder(view)
    }

    override fun onBindViewHolder(holder: WorksiteViewHolder, position: Int) {
        val job = jobs[position]
        holder.tvTitle.text = job.jobTitle ?: job.jobId
        holder.tvRole.text = job.jobTitle ?: "Assigned Work"
        val radiusMeters = job.geofenceCircle?.radiusM?.toInt() ?: 500
        holder.tvRadius.text = "${radiusMeters}m"

        val activeDays = job.days.joinToString(", ")
        val timeText = if (!job.startTime.isNullOrEmpty() && !job.endTime.isNullOrEmpty()) {
            "${job.startTime} - ${job.endTime}" + if (activeDays.isNotEmpty()) " ($activeDays)" else ""
        } else if (activeDays.isNotEmpty()) {
            "Active: $activeDays"
        } else {
            "Scheduled Shift"
        }
        holder.tvTiming.text = timeText

        holder.tvSupervisor.text = if (supervisorName.isNotEmpty()) "Supervisor: $supervisorName" else "Supervisor: —"
        val lat = if (job.location.isNotEmpty()) job.location[0].latitude else (job.geofenceCircle?.center?.lat ?: 0.0)
        val lng = if (job.location.isNotEmpty()) job.location[0].longitude else (job.geofenceCircle?.center?.lng ?: 0.0)
        if (lat != 0.0 && lng != 0.0) {
            holder.tvCoords.text = String.format(Locale.US, "Location: %.4f, %.4f", lat, lng)
        } else {
            holder.tvCoords.text = job.address ?: "Assigned Site Boundary"
        }

        holder.btnViewOnMap.setOnClickListener {
            onWorksiteClick(job)
        }
        holder.itemView.setOnClickListener {
            onWorksiteClick(job)
        }
    }

    override fun getItemCount(): Int = jobs.size
}