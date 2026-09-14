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

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val existing = nm.getNotificationChannel(CHANNEL_ID)
            if (existing == null) {
                val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
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

    fun showClockInNotification(context: Context, jobId: String, isAuto: Boolean = false) {
        val title = if (isAuto) "🟢 Work Time Started!" else "🟢 Clocked In — Work Time Started"
        val message = "Shift active for $jobId. Attendance & payroll timer is now running."
        showNotification(context, NOTIF_ID_CLOCK_IN, title, message)
    }

    fun showClockOutNotification(context: Context, jobId: String, durationStr: String = "") {
        val title = "🔴 Clocked Out — Shift Ended"
        val message = if (durationStr.isNotEmpty()) {
            "Shift ended for $jobId. Worked: $durationStr. Payroll recorded."
        } else {
            "Shift ended for $jobId. Attendance and payroll recorded."
        }
        showNotification(context, NOTIF_ID_CLOCK_OUT, title, message)
    }

    fun showGeofenceEntryNotification(context: Context, jobId: String) {
        val title = "🟢 Inside Assigned Worksite"
        val message = "You have entered $jobId. Shift tracking and attendance active."
        showNotification(context, NOTIF_ID_GEOFENCE_ENTRY, title, message)
    }

    fun showGeofenceExitNotification(context: Context, jobId: String) {
        val title = "⚠️ Left Worksite Boundary"
        val message = "You have moved outside the assigned worksite perimeter ($jobId)."
        showNotification(context, NOTIF_ID_GEOFENCE_EXIT, title, message)
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
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setSound(soundUri)
                .setVibrate(longArrayOf(0, 300, 200, 300))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()

            nm.notify(notificationId, notification)
            Log.d(TAG, "Posted OS-level notification [$notificationId]: $title")
        } catch (e: Exception) {
            Log.e(TAG, "Error posting notification: ${e.message}")
        }
    }
}
