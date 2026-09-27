package com.example.geoalarm

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority

class AlarmActivity : AppCompatActivity() {

    private var isSabotageMode = false

    private val resolutionForResult = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            checkLocationStatusAndDismissIfResolved()
        }
    }

    private val locationStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == LocationManager.PROVIDERS_CHANGED_ACTION || intent?.action == "android.location.MODE_CHANGED") {
                checkLocationStatusAndDismissIfResolved()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Wake screen, show over lockscreen, dismiss keyguard
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        }
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )

        setContentView(R.layout.activity_alarm)

        // Disable back button so the worker cannot bypass the warning alarm screen
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Do nothing to prevent dismissing when location is off
            }
        })

        isSabotageMode = intent.getBooleanExtra("IS_SABOTAGE", false)

        val tvWakeUp = findViewById<TextView>(R.id.tvWakeUp)
        val tvSubtext = findViewById<TextView>(R.id.tvSubtext)
        val btnTurnOnLocation = findViewById<Button>(R.id.btnTurnOnLocation)

        // Configure UI texts to match Location warning intent
        tvWakeUp.text = "Location is Off"
        tvSubtext.text = "Worker Tracker needs your location to automatically track your attendance and keep you safe."

        btnTurnOnLocation.setOnClickListener {
            requestEnableLocation()
        }

        // Trigger alarm siren if not already active
        AlarmController.triggerAlarm(this, isSabotageMode)

        // Automatically prompt with one-tap location dialog
        requestEnableLocation()

        // Listen for location being restored
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
            Log.e("AlarmActivity", "Receiver register error: ${e.message}")
        }
    }

    private fun requestEnableLocation() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000).build()
        val builder = LocationSettingsRequest.Builder()
            .addLocationRequest(locationRequest)
            .setAlwaysShow(true)
        val client = LocationServices.getSettingsClient(this)

        client.checkLocationSettings(builder.build()).addOnSuccessListener {
            checkLocationStatusAndDismissIfResolved()
        }.addOnFailureListener { exception ->
            if (exception is ResolvableApiException) {
                try {
                    val intentSenderRequest = IntentSenderRequest.Builder(exception.resolution).build()
                    resolutionForResult.launch(intentSenderRequest)
                } catch (sendEx: Exception) {
                    val settingsIntent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                    startActivity(settingsIntent)
                }
            } else {
                val settingsIntent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                startActivity(settingsIntent)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkLocationStatusAndDismissIfResolved()
    }

    private fun isLocationServiceOn(): Boolean {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    private fun checkLocationStatusAndDismissIfResolved() {
        val sharedPrefs = getSharedPreferences("GeoAlarmPrefs", Context.MODE_PRIVATE)
        val wasInsideStay = sharedPrefs.getBoolean("WAS_INSIDE_STAY", false)
        val isClockedIn = sharedPrefs.getBoolean("IS_CLOCKED_IN", false)

        if (isLocationServiceOn()) {
            Toast.makeText(this, "✓ Location restored! Resuming tracking...", Toast.LENGTH_LONG).show()
            AlarmController.stopAlarm(this)
            finish()
        } else if (wasInsideStay || !isClockedIn) {
            Log.d("AlarmActivity", "Worker at accommodation or clocked out. Dismissing alarm.")
            AlarmController.stopAlarm(this)
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(locationStateReceiver)
        } catch (_: Exception) {}
    }
}