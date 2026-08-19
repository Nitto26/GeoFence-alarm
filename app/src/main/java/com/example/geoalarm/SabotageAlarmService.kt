package com.example.geoalarm

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.pm.ServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat

/**
 * SabotageAlarmService — the definitive solution for showing a full-screen alarm
 * when location is turned off, on ALL Android versions (10, 12, 14, 15, 16),
 * regardless of whether the screen is on or off, locked or unlocked, or on home screen.
 *
 * Strategy:
 *  - Uses WindowManager TYPE_APPLICATION_OVERLAY to draw a full-screen view
 *    directly into the system window layer. This CANNOT be blocked by Android's
 *    background activity launch restrictions because it is NOT an Activity launch —
 *    it is a system window draw. It works over home screen, other apps, everything.
 *  - Requires SYSTEM_ALERT_WINDOW ("Display over other apps") permission.
 *  - Also posts a fullScreenIntent notification as a backup for lock screen / screen off.
 *  - Plays the alarm sound continuously until stopped.
 */
class SabotageAlarmService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var mediaPlayer: MediaPlayer? = null

    private val locationRestoreReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == LocationManager.PROVIDERS_CHANGED_ACTION) {
                val lm = context?.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                val isOn = lm?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true ||
                        lm?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true
                if (isOn) {
                    Toast.makeText(this@SabotageAlarmService, "✓ Location restored!", Toast.LENGTH_LONG).show()
                    // Cancel the persistent notification and stop this service
                    (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(999)
                    stopSelf()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            // Must call startForeground() within 5 seconds — with type matching manifest on API 29+
            val notification = buildForegroundNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(998, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(998, notification)
            }

            // Post full-screen intent notification (covers lock screen / screen OFF)
            postFullScreenNotification()

            // Listen for location being restored
            try {
                registerReceiver(
                    locationRestoreReceiver,
                    IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION)
                )
            } catch (e: Exception) {
                Log.e("SabotageAlarmService", "Receiver register failed: ${e.message}")
            }

            // Play alarm sound continuously
            playAlarmSound()

            // Show WindowManager overlay when screen is ON (home screen, other apps)
            if (Settings.canDrawOverlays(this)) {
                showWindowOverlay()
            } else {
                Log.w("SabotageAlarmService", "SYSTEM_ALERT_WINDOW not granted — relying on notification only")
            }

        } catch (e: Exception) {
            Log.e("SabotageAlarmService", "CRASH in onStartCommand: ${e.message}", e)
        }

        return START_STICKY
    }

    private fun showWindowOverlay() {
        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

            val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ALERT

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayType,
                // FLAG_SHOW_WHEN_LOCKED  — appear over lock screen
                // FLAG_DISMISS_KEYGUARD  — dismiss keyguard if possible
                // FLAG_KEEP_SCREEN_ON    — prevent screen from sleeping
                // FLAG_TURN_SCREEN_ON    — wake the screen if off
                // (NOT FLAG_NOT_FOCUSABLE — we need touch/button clicks to work)
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
                PixelFormat.OPAQUE
            )

            val inflater = LayoutInflater.from(this)
            overlayView = inflater.inflate(R.layout.activity_alarm, null)

            overlayView?.let { v ->
                // Configure for sabotage mode
                v.findViewById<TextView>(R.id.tvWakeUp)?.text = "Location is Off"
                v.findViewById<TextView>(R.id.tvSubtext)?.text =
                    "Worker Tracker needs your location to automatically track your attendance and keep you safe."

                // Show the "TURN ON LOCATION" button
                v.findViewById<Button>(R.id.btnTurnOnLocation)?.apply {
                    visibility = View.VISIBLE
                    setOnClickListener {
                        val settingsIntent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).also { i ->
                            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(settingsIntent)
                    }
                }
            }

            windowManager?.addView(overlayView, params)
            Log.d("SabotageAlarmService", "WindowManager overlay added successfully")

        } catch (e: Exception) {
            Log.e("SabotageAlarmService", "Failed to show overlay: ${e.message}")
        }
    }

    private fun playAlarmSound() {
        try {
            val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(this@SabotageAlarmService, soundUri)
                isLooping = true  // Loop continuously until stopped
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.e("SabotageAlarmService", "Could not play alarm sound: ${e.message}")
        }
    }

    private fun buildForegroundNotification() = run {
        val channelId = "SABOTAGE_SERVICE_CHANNEL"
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (nm.getNotificationChannel(channelId) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(channelId, "Alarm Service", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
        NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("GeoAlarm: Location Off!")
            .setContentText("Alarm is sounding — location was turned off.")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun postFullScreenNotification() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "SABOTAGE_ALARM_CHANNEL"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (nm.getNotificationChannel(channelId) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(channelId, "Location Off Alarm", NotificationManager.IMPORTANCE_HIGH).apply {
                        description = "Full-screen alarm when location is disabled while armed"
                        enableLights(true)
                        enableVibration(true)
                    }
                )
            }
        }

        val alarmIntent = Intent(this, AlarmActivity::class.java).also { i ->
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            i.putExtra("IS_SABOTAGE", true)
        }
        val pi = PendingIntent.getActivity(
            this, 999, alarmIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // AlarmManager.setAlarmClock() — the most reliable way to fire a full-screen alarm
        try {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.setAlarmClock(
                AlarmManager.AlarmClockInfo(System.currentTimeMillis() + 500L, pi),
                pi
            )
            Log.d("SabotageAlarmService", "Sabotage alarm clock set successfully")
        } catch (e: Exception) {
            Log.e("SabotageAlarmService", "setAlarmClock failed: ${e.message}")
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("⚠ LOCATION IS OFF!")
            .setContentText("Location turned off while alarm is armed!")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setFullScreenIntent(pi, true)  // Fires AlarmActivity on lock screen / screen off
            .setContentIntent(pi)
            .build()

        nm.notify(999, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(locationRestoreReceiver) } catch (_: Exception) {}
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        try {
            if (overlayView != null) {
                windowManager?.removeView(overlayView)
                overlayView = null
            }
        } catch (e: Exception) {
            Log.e("SabotageAlarmService", "removeView failed: ${e.message}")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
