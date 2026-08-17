package com.example.geoalarm

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Geocoder
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
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
import com.google.android.gms.maps.model.Circle
import com.google.android.gms.maps.model.CircleOptions
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.material.slider.Slider
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern

class MainActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var mMap: GoogleMap
    private var alarmLocation: LatLng? = null

    private lateinit var tvRadiusLabel: TextView
    private lateinit var radiusSlider: Slider
    private lateinit var radiusCard: View

    private var currentRadius = 500.0
    private var mapCircle: Circle? = null
    private lateinit var btnSetAlarm: Button
    private lateinit var etSearch: EditText
    private lateinit var btnSearch: Button

    private val geocoder by lazy { Geocoder(this) }
    private var isSystemArmed = false

    private val locationStateReceiver = LocationStateReceiver()

    private lateinit var geofencingClient: GeofencingClient
    private val geofencePendingIntent: PendingIntent by lazy {
        val intent = Intent(this, GeofenceBroadcastReceiver::class.java)
        PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
                checkLocationSettings()
            } else {
                Log.e("GeoAlarm", "Location required for alarm")
            }
        }

    private val resolutionForResult =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                Log.d("GeoAlarm", "User enabled location services.")
                enableUserLocation()
            } else {
                Log.e("GeoAlarm", "User refused to enable location services.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        geofencingClient = LocationServices.getGeofencingClient(this)
        btnSetAlarm = findViewById(R.id.btnSetAlarm)

        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)

        requestPermissions()

        btnSetAlarm.setOnClickListener {
            if (isSystemArmed) {
                cancelAlarm()
            } else {
                alarmLocation?.let { target ->
                    addGeofence(target)
                }
            }
        }

        etSearch = findViewById(R.id.etSearch)
        btnSearch = findViewById(R.id.btnSearch)

        btnSearch.setOnClickListener {
            performSearch()
        }

        etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performSearch()
                true
            } else false
        }

        tvRadiusLabel = findViewById(R.id.tvRadiusLabel)
        radiusSlider = findViewById(R.id.radiusSlider)
        radiusCard = findViewById(R.id.radiusCard)

        radiusSlider.addOnChangeListener { _, value, _ ->
            currentRadius = value.toDouble()
            if (currentRadius >= 1000) {
                tvRadiusLabel.text = "Trigger Radius: ${currentRadius / 1000}km"
            } else {
                tvRadiusLabel.text = "Trigger Radius: ${currentRadius.toInt()}m"
            }
            mapCircle?.radius = currentRadius
        }

        // Listen for GPS toggles while MainActivity is alive
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
            Log.d("MainActivity", "LocationStateReceiver registered with RECEIVER_EXPORTED")
        } catch (e: Exception) {
            Log.e("MainActivity", "Receiver registration error: ${e.message}")
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

    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        checkLocationSettings()

        mMap.setOnMapLongClickListener { latLng ->
            setTargetLocation(latLng)
        }

        handleSharedIntent(intent)
        restoreArmedState()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSharedIntent(intent)
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
        // 1. Overlay Permission ("Display over other apps")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this)
                .setTitle("Permission Needed: Display Over Other Apps")
                .setMessage(
                    "To show the full-screen alarm immediately when location is turned off or when your destination is reached, please enable 'Display over other apps'."
                )
                .setPositiveButton("Open Settings") { _, _ ->
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                }
                .setNegativeButton("Later") { dialog, _ -> dialog.dismiss() }
                .setCancelable(false)
                .show()
            return
        }

        // 2. Full-Screen Intent Permission (Android 14+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (!notificationManager.canUseFullScreenIntent()) {
                AlertDialog.Builder(this)
                    .setTitle("Permission Needed: Full-Screen Alarms")
                    .setMessage(
                        "To wake your screen and sound the alarm over the lock screen, enable 'Allow full-screen intents' in settings."
                    )
                    .setPositiveButton("Open Settings") { _, _ ->
                        val intent = Intent(
                            Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                            Uri.parse("package:$packageName")
                        )
                        startActivity(intent)
                    }
                    .setNegativeButton("Later") { dialog, _ -> dialog.dismiss() }
                    .setCancelable(false)
                    .show()
            }
        }
    }

    private fun performSearch() {
        val query = etSearch.text.toString()
        if (query.isEmpty()) return

        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(etSearch.windowToken, 0)

        try {
            val addresses = geocoder.getFromLocationName(query, 1)
            if (!addresses.isNullOrEmpty()) {
                val location = addresses[0]
                val latLng = LatLng(location.latitude, location.longitude)
                mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 15f))
                setTargetLocation(latLng)
            } else {
                Toast.makeText(this, "Location not found", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("GeoAlarm", "Geocoding failed: ${e.message}")
        }
    }

    private fun cancelAlarm() {
        radiusCard.visibility = View.GONE

        // Update preference
        val alarmPrefs = getSharedPreferences("GeoAlarmPrefs", Context.MODE_PRIVATE)
        alarmPrefs.edit().putBoolean("IS_SYSTEM_ARMED", false).apply()

        // Remove Geofence
        geofencingClient.removeGeofences(geofencePendingIntent)

        // Stop Foreground Radar Service
        val serviceIntent = Intent(this, RadarService::class.java)
        stopService(serviceIntent)

        // Reset UI
        isSystemArmed = false
        mMap.clear()
        btnSetAlarm.visibility = View.GONE

        Toast.makeText(this, "Alarm Cancelled & Disarmed", Toast.LENGTH_SHORT).show()
    }

    private fun setTargetLocation(latLng: LatLng) {
        if (isSystemArmed) {
            cancelAlarm()
        }

        mMap.clear()
        alarmLocation = latLng

        mMap.addMarker(MarkerOptions().position(latLng).title("Sniper Trigger Zone"))

        mapCircle = mMap.addCircle(
            CircleOptions().center(latLng).radius(currentRadius)
                .strokeColor(Color.RED).fillColor(Color.argb(70, 255, 0, 0)).strokeWidth(5f)
        )

        radiusCard.visibility = View.VISIBLE
        btnSetAlarm.visibility = View.VISIBLE
        btnSetAlarm.isEnabled = true
        btnSetAlarm.text = "SET ALARM"
        btnSetAlarm.setBackgroundColor(Color.parseColor("#4CAF50"))
        btnSetAlarm.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
    }

    private fun restoreArmedState() {
        val alarmPrefs = getSharedPreferences("GeoAlarmPrefs", Context.MODE_PRIVATE)
        val currentlyArmed = alarmPrefs.getBoolean("IS_SYSTEM_ARMED", false)

        if (currentlyArmed) {
            isSystemArmed = true

            val geoPrefs = getSharedPreferences("GeoPrefs", Context.MODE_PRIVATE)
            val savedLat = geoPrefs.getFloat("TARGET_LAT", 0f).toDouble()
            val savedLng = geoPrefs.getFloat("TARGET_LNG", 0f).toDouble()
            currentRadius = alarmPrefs.getFloat("TARGET_RADIUS", 500f).toDouble()

            if (savedLat != 0.0 && savedLng != 0.0) {
                val savedLatLng = LatLng(savedLat, savedLng)
                alarmLocation = savedLatLng

                mMap.clear()
                mMap.addMarker(MarkerOptions().position(savedLatLng).title("Sniper Trigger Zone"))
                mapCircle = mMap.addCircle(
                    CircleOptions().center(savedLatLng).radius(currentRadius)
                        .strokeColor(Color.RED).fillColor(Color.argb(70, 255, 0, 0)).strokeWidth(5f)
                )

                mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(savedLatLng, 15f))

                radiusCard.visibility = View.VISIBLE
                radiusSlider.value = currentRadius.toFloat()
                if (currentRadius >= 1000) {
                    tvRadiusLabel.text = "Trigger Radius: ${currentRadius / 1000}km"
                } else {
                    tvRadiusLabel.text = "Trigger Radius: ${currentRadius.toInt()}m"
                }

                btnSetAlarm.visibility = View.VISIBLE
                btnSetAlarm.isEnabled = true
                btnSetAlarm.text = "CANCEL ALARM"
                btnSetAlarm.setBackgroundColor(Color.parseColor("#F44336"))
                btnSetAlarm.setCompoundDrawablesWithIntrinsicBounds(android.R.drawable.ic_menu_close_clear_cancel, 0, 0, 0)
            }
        }
    }

    private fun handleSharedIntent(intent: Intent) {
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return

            val urlMatcher = Pattern.compile("https?://\\S+").matcher(sharedText)
            if (urlMatcher.find()) {
                val shortUrl = urlMatcher.group()

                Thread {
                    try {
                        var currentUrl = shortUrl
                        var htmlContent = ""
                        var redirects = 0

                        while (redirects < 5) {
                            val connection = URL(currentUrl).openConnection() as HttpURLConnection
                            connection.setRequestProperty(
                                "User-Agent",
                                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                            )
                            connection.instanceFollowRedirects = false
                            connection.connect()

                            val responseCode = connection.responseCode
                            if (responseCode in 300..399) {
                                val location = connection.getHeaderField("Location")
                                if (location != null) {
                                    currentUrl = location
                                    redirects++
                                    connection.disconnect()
                                    continue
                                }
                            }

                            if (responseCode == 200) {
                                htmlContent = connection.inputStream.bufferedReader().readText()
                            }
                            connection.disconnect()
                            break
                        }

                        var targetLat: Double? = null
                        var targetLng: Double? = null

                        val urlRegexes = listOf(
                            Regex("!3d(-?\\d+\\.\\d+)!4d(-?\\d+\\.\\d+)"),
                            Regex("@(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)"),
                            Regex("[?&](?:q|query|ll)=(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)")
                        )

                        for (regex in urlRegexes) {
                            val match = regex.find(currentUrl)
                            if (match != null) {
                                targetLat = match.groupValues[1].toDouble()
                                targetLng = match.groupValues[2].toDouble()
                                break
                            }
                        }

                        if (targetLat == null) {
                            val deepLinkRegex = Regex("android-app://com\\.google\\.android\\.apps\\.maps/geo/0,0\\?q=(?:.*?%40|.*?@)?(-?\\d+\\.\\d+)[,%2C]+(-?\\d+\\.\\d+)")
                            val markerRegex = Regex("markers=(?:.*?%7C|.*?\\|)?(-?\\d+\\.\\d+)(?:%2C|,)(-?\\d+\\.\\d+)")
                            val metaLatRegex = Regex("content=[\"'](-?\\d+\\.\\d+)[\"']\\s*itemprop=[\"']latitude[\"']|itemprop=[\"']latitude[\"']\\s*content=[\"'](-?\\d+\\.\\d+)[\"']")
                            val metaLngRegex = Regex("content=[\"'](-?\\d+\\.\\d+)[\"']\\s*itemprop=[\"']longitude[\"']|itemprop=[\"']longitude[\"']\\s*content=[\"'](-?\\d+\\.\\d+)[\"']")

                            val deepLinkMatch = deepLinkRegex.find(htmlContent)
                            val markerMatch = markerRegex.find(htmlContent)
                            val latMatch = metaLatRegex.find(htmlContent)
                            val lngMatch = metaLngRegex.find(htmlContent)

                            if (deepLinkMatch != null) {
                                targetLat = deepLinkMatch.groupValues[1].toDouble()
                                targetLng = deepLinkMatch.groupValues[2].toDouble()
                            } else if (markerMatch != null) {
                                targetLat = markerMatch.groupValues[1].toDouble()
                                targetLng = markerMatch.groupValues[2].toDouble()
                            } else if (latMatch != null && lngMatch != null) {
                                val latStr = if (latMatch.groupValues[1].isNotEmpty()) latMatch.groupValues[1] else latMatch.groupValues[2]
                                val lngStr = if (lngMatch.groupValues[1].isNotEmpty()) lngMatch.groupValues[1] else lngMatch.groupValues[2]
                                targetLat = latStr.toDouble()
                                targetLng = lngStr.toDouble()
                            }
                        }

                        if (targetLat == null) {
                            val placeRegex = Regex("/place/([^/]+)/")
                            val placeMatch = placeRegex.find(currentUrl)

                            if (placeMatch != null) {
                                val rawPlace = placeMatch.groupValues[1]
                                val coordMatch = Regex("^(-?\\d+\\.\\d+)(?:%2C|,)(-?\\d+\\.\\d+)$").find(rawPlace)
                                if (coordMatch != null) {
                                    targetLat = coordMatch.groupValues[1].toDouble()
                                    targetLng = coordMatch.groupValues[2].toDouble()
                                } else {
                                    val cleanAddress = java.net.URLDecoder.decode(rawPlace.replace("+", " "), "UTF-8")
                                    val addresses = geocoder.getFromLocationName(cleanAddress, 1)
                                    if (!addresses.isNullOrEmpty()) {
                                        targetLat = addresses[0].latitude
                                        targetLng = addresses[0].longitude
                                    }
                                }
                            }
                        }

                        if (targetLat != null && targetLng != null) {
                            val sharedLatLng = LatLng(targetLat, targetLng)
                            runOnUiThread {
                                mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(sharedLatLng, 15f))
                                setTargetLocation(sharedLatLng)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("GeoAlarm", "Scraper crashed", e)
                    }
                }.start()
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
                    Log.e("GeoAlarm", "Error showing location prompt", sendEx)
                }
            }
        }
    }

    private fun addGeofence(latLng: LatLng) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Location permission required to arm alarm", Toast.LENGTH_SHORT).show()
            return
        }

        // 1. Save alarm configuration
        val sharedPrefs = getSharedPreferences("GeoAlarmPrefs", Context.MODE_PRIVATE)
        sharedPrefs.edit()
            .putFloat("TARGET_RADIUS", currentRadius.toFloat())
            .putBoolean("IS_SYSTEM_ARMED", true)
            .apply()

        val geoPrefs = getSharedPreferences("GeoPrefs", Context.MODE_PRIVATE)
        geoPrefs.edit()
            .putFloat("TARGET_LAT", latLng.latitude.toFloat())
            .putFloat("TARGET_LNG", latLng.longitude.toFloat())
            .apply()

        // 2. Start the Foreground Radar Service immediately!
        // This keeps the app running in the background with its LocationStateReceiver active
        val radarServiceIntent = Intent(this, RadarService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(radarServiceIntent)
        } else {
            startService(radarServiceIntent)
        }

        // 3. Register Geofence with Android OS
        geofencingClient.removeGeofences(geofencePendingIntent).addOnCompleteListener {
            val uniqueGeofenceId = "ALARM_ZONE_${System.currentTimeMillis()}"
            val tripwireRadius = currentRadius.toFloat() + 2000f

            val geofence = Geofence.Builder()
                .setRequestId(uniqueGeofenceId)
                .setCircularRegion(latLng.latitude, latLng.longitude, tripwireRadius)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
                .build()

            val geofencingRequest = GeofencingRequest.Builder()
                .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER or GeofencingRequest.INITIAL_TRIGGER_DWELL)
                .addGeofence(geofence)
                .build()

            geofencingClient.addGeofences(geofencingRequest, geofencePendingIntent).run {
                addOnSuccessListener {
                    Log.d("GeoAlarm", "Geofence armed successfully!")
                    isSystemArmed = true
                    btnSetAlarm.isEnabled = true
                    btnSetAlarm.text = "CANCEL ALARM"
                    btnSetAlarm.setBackgroundColor(Color.parseColor("#F44336"))
                    btnSetAlarm.setCompoundDrawablesWithIntrinsicBounds(android.R.drawable.ic_menu_close_clear_cancel, 0, 0, 0)
                    Toast.makeText(this@MainActivity, "✓ Alarm Armed! System is watching.", Toast.LENGTH_SHORT).show()
                }
                addOnFailureListener {
                    Log.e("GeoAlarm", "Failed to register geofence: ${it.message}")
                    // Even if geofence failed, RadarService is actively tracking
                    isSystemArmed = true
                    btnSetAlarm.isEnabled = true
                    btnSetAlarm.text = "CANCEL ALARM"
                    btnSetAlarm.setBackgroundColor(Color.parseColor("#F44336"))
                    btnSetAlarm.setCompoundDrawablesWithIntrinsicBounds(android.R.drawable.ic_menu_close_clear_cancel, 0, 0, 0)
                    Toast.makeText(this@MainActivity, "✓ Alarm Armed via Radar Service!", Toast.LENGTH_SHORT).show()
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