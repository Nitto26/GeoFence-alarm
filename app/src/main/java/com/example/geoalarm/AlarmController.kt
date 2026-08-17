package com.example.geoalarm

import android.app.ActivityOptions
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.core.app.NotificationCompat

object AlarmController {

    private const val TAG = "AlarmController"
    private const val NOTIFICATION_ID = 999
    private const val CHANNEL_ID = "SABOTAGE_ALARM_CHANNEL"

    private var mediaPlayer: MediaPlayer? = null
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    var isAlarmActive = false
        private set

    fun triggerAlarm(context: Context, isSabotage: Boolean) {
        val appContext = context.applicationContext
        isAlarmActive = true

        Log.d(TAG, "Triggering alarm: isSabotage=$isSabotage")

        // 1. Play loud alarm siren immediately
        playAudio(appContext)

        // 2. Try drawing Full-Screen WindowManager Overlay (Bypasses background activity restrictions!)
        showFullScreenOverlay(appContext, isSabotage)

        // 3. Post FullScreenIntent Notification (Handles lock screen and notification shade)
        postFullScreenNotification(appContext, isSabotage)

        // 4. Try starting AlarmActivity directly
        tryLaunchAlarmActivity(appContext, isSabotage)
    }

    private fun playAudio(context: Context) {
        try {
            if (mediaPlayer != null && mediaPlayer?.isPlaying == true) {
                return
            }
            try {
                mediaPlayer?.release()
            } catch (_: Exception) {}

            val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(context, soundUri)
                isLooping = true
                prepare()
                start()
            }
            Log.d(TAG, "Alarm sound started playing successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play audio: ${e.message}", e)
        }
    }

    private fun showFullScreenOverlay(context: Context, isSabotage: Boolean) {
        try {
            if (!Settings.canDrawOverlays(context)) {
                Log.w(TAG, "Cannot draw overlay: SYSTEM_ALERT_WINDOW not granted")
                return
            }

            if (overlayView != null) {
                return
            }

            windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.CENTER
            }

            // Wrap with Theme so MaterialButton in activity_alarm.xml inflates properly!
            val themedContext = ContextThemeWrapper(context, R.style.Theme_GeoAlarm)
            val inflater = LayoutInflater.from(themedContext)
            val view = inflater.inflate(R.layout.activity_alarm, null)

            val tvWakeUp = view.findViewById<TextView>(R.id.tvWakeUp)
            val tvSubtext = view.findViewById<TextView>(R.id.tvSubtext)
            val btnTurnOnLocation = view.findViewById<Button>(R.id.btnTurnOnLocation)
            val btnStopAlarm = view.findViewById<Button>(R.id.btnStopAlarm)

            if (isSabotage) {
                tvWakeUp.text = "LOCATION IS OFF!"
                tvSubtext.text = "Location service was turned off while alarm is armed!\nTurn it back on to resume tracking."
                btnTurnOnLocation.visibility = View.VISIBLE

                btnTurnOnLocation.setOnClickListener {
                    val settingsIntent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(settingsIntent)
                }
            } else {
                tvWakeUp.text = "WAKE UP"
                tvSubtext.text = "Destination Reached!"
                btnTurnOnLocation.visibility = View.GONE
            }

            btnStopAlarm.setOnClickListener {
                val sharedPrefs = context.getSharedPreferences("GeoAlarmPrefs", Context.MODE_PRIVATE)
                sharedPrefs.edit().putBoolean("IS_SYSTEM_ARMED", false).apply()

                stopAlarm(context)
            }

            windowManager?.addView(view, params)
            overlayView = view
            Log.d(TAG, "Full-screen overlay added to WindowManager successfully.")

        } catch (e: Exception) {
            Log.e(TAG, "Error showing overlay: ${e.message}", e)
        }
    }

    private fun postFullScreenNotification(context: Context, isSabotage: Boolean) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                var channel = nm.getNotificationChannel(CHANNEL_ID)
                if (channel == null) {
                    channel = NotificationChannel(
                        CHANNEL_ID,
                        "Alarm Alerts",
                        NotificationManager.IMPORTANCE_HIGH
                    ).apply {
                        description = "Full-screen alarm alerts"
                        enableLights(true)
                        enableVibration(true)
                        setBypassDnd(true)
                        lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                    }
                    nm.createNotificationChannel(channel)
                }
            }

            val alarmIntent = Intent(context, AlarmActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("IS_SABOTAGE", isSabotage)
            }

            val options = ActivityOptions.makeBasic()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                options.setPendingIntentBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                )
            }

            val pendingIntent = PendingIntent.getActivity(
                context, NOTIFICATION_ID, alarmIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                options.toBundle()
            )

            val title = if (isSabotage) "⚠ LOCATION IS OFF!" else "Destination Reached!"
            val content = if (isSabotage) "Location is off! Tap to turn on location." else "Wake up! You have arrived."

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(title)
                .setContentText(content)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setAutoCancel(false)
                .setFullScreenIntent(pendingIntent, true)
                .setContentIntent(pendingIntent)
                .build()

            nm.notify(NOTIFICATION_ID, notification)

            // AlarmClock via AlarmManager
            try {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val alarmClockInfo = AlarmManager.AlarmClockInfo(System.currentTimeMillis(), pendingIntent)
                alarmManager.setAlarmClock(alarmClockInfo, pendingIntent)
            } catch (e: Exception) {
                Log.e(TAG, "AlarmManager setAlarmClock error: ${e.message}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error posting notification: ${e.message}", e)
        }
    }

    private fun tryLaunchAlarmActivity(context: Context, isSabotage: Boolean) {
        try {
            val alarmIntent = Intent(context, AlarmActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("IS_SABOTAGE", isSabotage)
            }
            val options = ActivityOptions.makeBasic()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                options.setPendingIntentBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                )
            }
            context.startActivity(alarmIntent, options.toBundle())
            Log.d(TAG, "Direct startActivity executed.")
        } catch (e: Exception) {
            Log.e(TAG, "Direct startActivity failed: ${e.message}")
        }
    }

    fun stopAlarm(context: Context) {
        isAlarmActive = false
        Log.d(TAG, "Stopping alarm...")

        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping media player: ${e.message}")
        }

        try {
            if (overlayView != null) {
                windowManager?.removeView(overlayView)
                overlayView = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing overlay view: ${e.message}")
        }

        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(NOTIFICATION_ID)
        } catch (e: Exception) {
            Log.e(TAG, "Error cancelling notification: ${e.message}")
        }
    }
}
