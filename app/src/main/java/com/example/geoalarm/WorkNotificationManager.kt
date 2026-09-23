package com.example.geoalarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat

object WorkNotificationManager {

    private const val TAG = "WorkNotificationMgr"
    const val CHANNEL_ID = "WORK_ATTENDANCE_CHANNEL"
    const val NOTIF_ID_CLOCK_IN = 2001
    const val NOTIF_ID_CLOCK_OUT = 2002
    const val NOTIF_ID_GEOFENCE_ENTRY = 2003
    const val NOTIF_ID_GEOFENCE_EXIT = 2004
    const val NOTIF_ID_SHIFT_SCHEDULE = 2005

    // Deduplication tracker: prevents repeating identical notifications on periodic pings
    @Volatile
    private var lastNotifiedStateKey: String = ""

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val existing = nm.getNotificationChannel(CHANNEL_ID)
            if (existing == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Work & Attendance Alerts",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Real-time notifications for Clock In, Clock Out, and Worksite Geofences"
                    enableLights(true)
                    lightColor = Color.GREEN
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 300, 200, 300)
                    lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    @Synchronized
    fun showClockInNotification(context: Context, jobId: String, isAuto: Boolean = false) {
        val key = "CLOCK_IN"
        if (lastNotifiedStateKey == key) {
            Log.d(TAG, "Suppressed duplicate Clock In notification")
            return
        }
        lastNotifiedStateKey = key

        val title = if (isAuto) "Shift Auto-Started" else "Clocked In"
        val message = "Shift tracking active for $jobId. Attendance & payroll running."
        showNotification(context, NOTIF_ID_CLOCK_IN, title, message)
    }

    @Synchronized
    fun showClockOutNotification(context: Context, jobId: String, durationStr: String = "") {
        val key = "CLOCK_OUT"
        lastNotifiedStateKey = key

        val title = "Clocked Out — Shift Ended"
        val message = if (durationStr.isNotEmpty()) {
            "Shift ended for $jobId. Worked: $durationStr. Attendance recorded."
        } else {
            "Shift ended for $jobId. Attendance recorded."
        }
        showNotification(context, NOTIF_ID_CLOCK_OUT, title, message)
    }

    @Synchronized
    fun showGeofenceEntryNotification(context: Context, jobId: String) {
        val key = "ENTRY_$jobId"
        if (lastNotifiedStateKey == key) {
            Log.d(TAG, "Suppressed duplicate Entry notification for $jobId")
            return
        }
        lastNotifiedStateKey = key

        val title = "Entered Worksite"
        val message = "You have arrived inside $jobId perimeter."
        showNotification(context, NOTIF_ID_GEOFENCE_ENTRY, title, message)
    }

    @Synchronized
    fun showGeofenceExitNotification(context: Context, jobId: String) {
        val key = "EXIT_$jobId"
        if (lastNotifiedStateKey == key) {
            Log.d(TAG, "Suppressed duplicate Exit notification for $jobId")
            return
        }
        lastNotifiedStateKey = key

        val title = "Left Worksite Perimeter"
        val message = "You have exited $jobId boundary."
        showNotification(context, NOTIF_ID_GEOFENCE_EXIT, title, message)
    }

    @Synchronized
    fun showShiftScheduleNotification(context: Context, jobName: String, timeText: String) {
        val title = "Shift Started: $jobName"
        showNotification(context, NOTIF_ID_SHIFT_SCHEDULE, title, timeText)
    }

    fun resetNotificationState() {
        lastNotifiedStateKey = ""
    }

    private fun showNotification(context: Context, notificationId: Int, title: String, message: String) {
        try {
            createNotificationChannel(context)
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                notificationId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setSound(soundUri)
                .setVibrate(longArrayOf(0, 300, 200, 300))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()

            nm.notify(notificationId, notification)
            Log.d(TAG, "Posted notification [$notificationId]: $title")
        } catch (e: Exception) {
            Log.e(TAG, "Error posting notification: ${e.message}")
        }
    }
}
