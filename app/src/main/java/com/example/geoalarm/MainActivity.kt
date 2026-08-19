package com.example.geoalarm

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.ViewFlipper
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
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
import com.google.android.gms.maps.model.CircleOptions
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.material.button.MaterialButton

class MainActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var viewFlipper: ViewFlipper
    private lateinit var mMap: GoogleMap
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

    // Sample Worksites
    private val worksiteA = LatLng(10.5276, 76.2144)
    private val worksiteB = LatLng(10.5310, 76.2190)
    private val worksiteC = LatLng(10.5230, 76.2100)

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
                startRadarServiceSafely()
            } else {
                Log.e("WorkerTracker", "Location permission denied by user")
            }
        }

    private val resolutionForResult =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                Log.d("WorkerTracker", "User enabled location services.")
                enableUserLocation()
            } else {
                Log.e("WorkerTracker", "User refused location services.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        geofencingClient = LocationServices.getGeofencingClient(this)
        viewFlipper = findViewById(R.id.viewFlipperMain)

        // Initialize UI Tabs and Navigation
        setupBottomNavigation()
        setupHomeInteractions()
        setupProfileInteractions()
        setupWorkCalendar()

        // Initialize Google Maps fragment
        val mapFragment = supportFragmentManager.findFragmentById(R.id.mapFragment) as? SupportMapFragment
        mapFragment?.getMapAsync(this)

        // Request runtime permissions
        requestPermissions()

        // If permissions already exist, start Radar service
        if (hasLocationPermission()) {
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
            Log.d("WorkerTracker", "LocationStateReceiver registered successfully")
        } catch (e: Exception) {
            Log.e("WorkerTracker", "Receiver registration error: ${e.message}")
        }
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun startRadarServiceSafely() {
        if (!hasLocationPermission()) return
        try {
            val radarServiceIntent = Intent(this, RadarService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(radarServiceIntent)
            } else {
                startService(radarServiceIntent)
            }
        } catch (e: Exception) {
            Log.e("WorkerTracker", "Could not start RadarService: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(locationStateReceiver)
        } catch (e: Exception) {
            // Already unregistered
        }
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
                if (isMapReady) {
                    mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(worksiteA, 15f))
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
    // SCREEN 2: HOME INTERACTIONS
    // ==========================================
    private fun setupHomeInteractions() {
        val btnViewOnMapLink = findViewById<LinearLayout>(R.id.btnViewOnMapLink)
        val btnNextWorksiteArrow = findViewById<FrameLayout>(R.id.btnNextWorksiteArrow)
        val cardWorksite1 = findViewById<CardView>(R.id.cardWorksite1)
        val cardWorksite2 = findViewById<CardView>(R.id.cardWorksite2)
        val cardWorksite3 = findViewById<CardView>(R.id.cardWorksite3)

        val goToMapAction = View.OnClickListener {
            switchTab(1)
        }

        btnViewOnMapLink?.setOnClickListener(goToMapAction)
        btnNextWorksiteArrow?.setOnClickListener(goToMapAction)
        cardWorksite1?.setOnClickListener(goToMapAction)
        cardWorksite2?.setOnClickListener(goToMapAction)
        cardWorksite3?.setOnClickListener(goToMapAction)
    }

    // ==========================================
    // SCREEN 3: MAP & GEOFENCE OS ALARM SETUP
    // ==========================================
    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        isMapReady = true

        // Configure Map UI
        mMap.uiSettings.isZoomControlsEnabled = false
        mMap.uiSettings.isMyLocationButtonEnabled = false

        // Plot Worksite Markers & Geofence Circles
        plotWorksiteMarkers()

        // Enable GPS location dot
        enableUserLocation()

        // Move to default worksite A
        mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(worksiteA, 15f))

        // GPS Recenter Button
        val fabRecenter = findViewById<CardView>(R.id.fabRecenterLocation)
        fabRecenter?.setOnClickListener {
            enableUserLocation()
            checkLocationSettings()
            mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(worksiteA, 16f))
            Toast.makeText(this, "Centered on Current Location", Toast.LENGTH_SHORT).show()
        }

        // View Details Button in Bottom Map Card
        val btnMapDetails = findViewById<MaterialButton>(R.id.btnMapWorksiteDetails)
        btnMapDetails?.setOnClickListener {
            Toast.makeText(this, "Construction Site A: Geofence Active (500m)", Toast.LENGTH_SHORT).show()
        }

        // Arm geofence at Worksite A
        armWorksiteGeofence(worksiteA, 500f)
    }

    private fun plotWorksiteMarkers() {
        if (!::mMap.isInitialized) return

        mMap.clear()

        // Worksite A: Construction Site A
        mMap.addMarker(
            MarkerOptions()
                .position(worksiteA)
                .title("Construction Site A")
                .snippet("10:00 AM – 1:00 PM")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
        )
        mMap.addCircle(
            CircleOptions()
                .center(worksiteA)
                .radius(500.0)
                .strokeColor(Color.parseColor("#0052CC"))
                .fillColor(Color.argb(35, 0, 82, 204))
                .strokeWidth(4f)
        )

        // Worksite B: Warehouse Renovation
        mMap.addMarker(
            MarkerOptions()
                .position(worksiteB)
                .title("Warehouse Renovation")
                .snippet("2:00 PM – 5:00 PM")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
        )
        mMap.addCircle(
            CircleOptions()
                .center(worksiteB)
                .radius(400.0)
                .strokeColor(Color.parseColor("#0052CC"))
                .fillColor(Color.argb(25, 0, 82, 204))
                .strokeWidth(3f)
        )

        // Worksite C: Site Office Work
        mMap.addMarker(
            MarkerOptions()
                .position(worksiteC)
                .title("Site Office Work")
                .snippet("5:30 PM – 6:30 PM")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
        )
        mMap.addCircle(
            CircleOptions()
                .center(worksiteC)
                .radius(300.0)
                .strokeColor(Color.parseColor("#0052CC"))
                .fillColor(Color.argb(25, 0, 82, 204))
                .strokeWidth(3f)
        )
    }

    private fun armWorksiteGeofence(latLng: LatLng, radius: Float) {
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

        val uniqueGeofenceId = "WORKSITE_A_ZONE"
        val geofence = Geofence.Builder()
            .setRequestId(uniqueGeofenceId)
            .setCircularRegion(latLng.latitude, latLng.longitude, radius + 1000f)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
            .build()

        val geofencingRequest = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER or GeofencingRequest.INITIAL_TRIGGER_DWELL)
            .addGeofence(geofence)
            .build()

        geofencingClient.addGeofences(geofencingRequest, geofencePendingIntent).run {
            addOnSuccessListener {
                Log.d("WorkerTracker", "OS Geofence active for Worksite A")
            }
            addOnFailureListener {
                Log.e("WorkerTracker", "Geofence registration error: ${it.message}")
            }
        }
    }

    // ==========================================
    // SCREEN 4: WORK / SCHEDULE
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

    // ==========================================
    // SCREEN 5: PROFILE
    // ==========================================
    private fun setupProfileInteractions() {
        val btnLogout = findViewById<MaterialButton>(R.id.btnLogout)
        btnLogout?.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Log Out")
                .setMessage("Are you sure you want to log out?")
                .setPositiveButton("Log Out") { _, _ ->
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
    // PERMISSIONS & OS ALARM OVERLAY CHECKS
    // ==========================================
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
        // Overlay Permission for Over-Home Screen Alarm
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this)
                .setTitle("Permission Needed: Display Over Other Apps")
                .setMessage(
                    "To trigger the full-screen alarm immediately when location is turned off or when entering worksites, please enable 'Display over other apps'."
                )
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

        // Full-Screen Intent Permission (Android 14+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (!notificationManager.canUseFullScreenIntent()) {
                AlertDialog.Builder(this)
                    .setTitle("Permission Needed: Full-Screen Alarms")
                    .setMessage(
                        "To sound the siren and wake your device over the lock screen, enable 'Allow full-screen intents' in settings."
                    )
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
                    Log.e("WorkerTracker", "Error showing location prompt", sendEx)
                }
            }
        }
    }

    private fun enableUserLocation() {
        if (!::mMap.isInitialized) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            mMap.isMyLocationEnabled = true
        }
    }
}