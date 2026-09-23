package com.example.geoalarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.geoalarm.network.JobItem
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object ShiftAlarmScheduler {

    private const val TAG = "ShiftAlarmScheduler"
    private const val PREFS_NAME = "GeoPrefs"
    private const val KEY_JOBS = "CACHED_JOBS_JSON"
    const val REQUEST_CODE_SHIFT_ALARM = 8881

    /**
     * Examines all cached jobs from server API and schedules the earliest upcoming
     * shift start alarm with AlarmManager. Works completely offline.
     */
    fun scheduleNextShiftAlarm(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_JOBS, null) ?: return

        val jobs: List<JobItem> = try {
            val type = object : TypeToken<List<JobItem>>() {}.type
            Gson().fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }

        if (jobs.isEmpty()) return

        var nextAlarmMillis: Long = Long.MAX_VALUE
        var nextAlarmJobName = "Work Shift"

        val now = System.currentTimeMillis()

        // Scan upcoming 7 days to find the earliest upcoming shift start
        for (dayOffset in 0..6) {
            val checkCal = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, dayOffset)
            }
            val checkDate = checkCal.time
            val dateYmd = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(checkDate)
            val dayShort = SimpleDateFormat("EEE", Locale.US).format(checkDate) // "Mon"
            val dayLong = SimpleDateFormat("EEEE", Locale.US).format(checkDate) // "Monday"

            for (job in jobs) {
                if (job.siteType.equals("accommodation", ignoreCase = true) || job.isStartingPoint == true) {
                    continue
                }

                // Check date match if configured
                val dateField = job.schedulePeriod ?: job.scheduledDate ?: job.date
                if (!dateField.isNullOrBlank()) {
                    val trimmed = dateField.trim()
                    if (trimmed.contains(" to ") || trimmed.contains(" - ")) {
                        val delimiter = if (trimmed.contains(" to ")) " to " else " - "
                        val parts = trimmed.split(delimiter)
                        if (parts.size >= 2) {
                            val startStr = parts[0].trim()
                            val endStr = parts[1].trim()
                            if (dateYmd < startStr || dateYmd > endStr) continue
                        }
                    } else if (trimmed.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) {
                        if (trimmed != dateYmd) continue
                    }
                }

                // Check day of week match if configured
                if (job.days.isNotEmpty()) {
                    val matchesDay = job.days.any { d ->
                        val trimmedDay = d.trim()
                        trimmedDay.equals(dayShort, ignoreCase = true) ||
                        trimmedDay.equals(dayLong, ignoreCase = true) ||
                        (trimmedDay.length >= 3 && dayShort.startsWith(trimmedDay, ignoreCase = true))
                    }
                    if (!matchesDay) continue
                }

                val startTimeStr = job.startTime ?: continue
                val parts = startTimeStr.split(":")
                if (parts.size < 2) continue

                val hour = parts[0].trim().toIntOrNull() ?: continue
                val minute = parts[1].trim().toIntOrNull() ?: continue

                val shiftStartCal = Calendar.getInstance().apply {
                    time = checkDate
                    set(Calendar.HOUR_OF_DAY, hour)
                    set(Calendar.MINUTE, minute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }

                val triggerTime = shiftStartCal.timeInMillis
                if (triggerTime > now && triggerTime < nextAlarmMillis) {
                    nextAlarmMillis = triggerTime
                    nextAlarmJobName = job.jobTitle ?: job.jobId
                }
            }

            if (nextAlarmMillis != Long.MAX_VALUE && dayOffset == 0) {
                // Found an alarm for today later in the day
                break
            }
        }

        if (nextAlarmMillis != Long.MAX_VALUE) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(context, ShiftAlarmReceiver::class.java).apply {
                action = ShiftAlarmReceiver.ACTION_SHIFT_START_ALARM
                putExtra("JOB_NAME", nextAlarmJobName)
                putExtra("SCHEDULED_TIME_MILLIS", nextAlarmMillis)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE_SHIFT_ALARM,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            )

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextAlarmMillis, pendingIntent)
                } else {
                    alarmManager.setExact(AlarmManager.RTC_WAKEUP, nextAlarmMillis, pendingIntent)
                }
                val formattedTime = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(nextAlarmMillis))
                Log.d(TAG, "✓ Scheduled Offline Shift Start Alarm for $nextAlarmJobName at $formattedTime")
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException scheduling exact shift alarm: ${e.message}")
            }
        } else {
            Log.d(TAG, "No upcoming shifts found to schedule.")
        }
    }
}
