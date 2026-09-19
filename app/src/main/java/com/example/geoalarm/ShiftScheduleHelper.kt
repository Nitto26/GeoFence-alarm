package com.example.geoalarm

import android.util.Log
import com.example.geoalarm.network.JobItem
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

object ShiftScheduleHelper {
    private const val TAG = "ShiftScheduleHelper"

    /**
     * Checks if current date & time falls within the shift schedule of the job.
     * 1. Evaluates Date / Schedule Period (if configured)
     * 2. Evaluates Day of the Week (if days list is configured)
     * 3. Evaluates Shift Hours (startTime - endTime)
     */
    fun isJobWithinShiftHours(job: JobItem, calendar: Calendar = Calendar.getInstance()): Boolean {
        // Filter out accommodation dummy items
        if (job.siteType == "accommodation" || job.isStartingPoint == true) {
            return false
        }

        val todayDate = calendar.time
        val todayYmd = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(todayDate)
        val todayDayShort = SimpleDateFormat("EEE", Locale.US).format(todayDate) // "Sat"
        val todayDayLong = SimpleDateFormat("EEEE", Locale.US).format(todayDate) // "Saturday"

        // 1. Date Check (if schedulePeriod / scheduledDate / date exists)
        val dateField = job.schedulePeriod ?: job.scheduledDate ?: job.date
        if (!dateField.isNullOrBlank()) {
            val trimmed = dateField.trim()
            if (trimmed.contains(" to ") || trimmed.contains(" - ")) {
                val delimiter = if (trimmed.contains(" to ")) " to " else " - "
                val parts = trimmed.split(delimiter)
                if (parts.size >= 2) {
                    val startStr = parts[0].trim()
                    val endStr = parts[1].trim()
                    if (todayYmd < startStr || todayYmd > endStr) {
                        Log.d(TAG, "Job ${job.jobId} date range ($startStr - $endStr) does not cover today ($todayYmd)")
                        return false
                    }
                }
            } else if (trimmed.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) {
                if (trimmed != todayYmd) {
                    Log.d(TAG, "Job ${job.jobId} scheduled date ($trimmed) does not match today ($todayYmd)")
                    return false
                }
            }
        }

        // 2. Day of Week Check
        if (job.days.isNotEmpty()) {
            val matchesDay = job.days.any { day ->
                val d = day.trim()
                d.equals(todayDayShort, ignoreCase = true) ||
                d.equals(todayDayLong, ignoreCase = true) ||
                (d.length >= 3 && todayDayShort.startsWith(d, ignoreCase = true))
            }
            if (!matchesDay) {
                Log.d(TAG, "Job ${job.jobId} days (${job.days}) does not include today ($todayDayShort)")
                return false
            }
        }

        // 3. Shift Time Window Check
        val startStr = job.startTime?.trim()
        val endStr = job.endTime?.trim()

        if (!startStr.isNullOrEmpty() && !endStr.isNullOrEmpty()) {
            val startMins = parseTimeToMinutes(startStr)
            val endMins = parseTimeToMinutes(endStr)

            if (startMins != null && endMins != null) {
                val currentMinutes = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
                val isWithin = if (startMins <= endMins) {
                    currentMinutes in startMins..endMins
                } else {
                    // Spans overnight (e.g. 22:00 to 06:00)
                    currentMinutes >= startMins || currentMinutes <= endMins
                }

                if (!isWithin) {
                    Log.d(TAG, "Current time ($currentMinutes mins) is OUTSIDE shift window ($startStr [$startMins] - $endStr [$endMins]) for job ${job.jobId}")
                    return false
                }
            }
        }

        return true
    }

    /**
     * Checks if any worksite in the list is currently within its scheduled shift hours & date.
     */
    fun isAnyJobWithinShiftHours(jobs: List<JobItem>, calendar: Calendar = Calendar.getInstance()): Boolean {
        if (jobs.isEmpty()) return false
        val workJobs = jobs.filter { it.siteType != "accommodation" && it.isStartingPoint != true }
        val targetList = if (workJobs.isNotEmpty()) workJobs else jobs
        return targetList.any { isJobWithinShiftHours(it, calendar) }
    }

    /**
     * Helper to parse time strings like "09:00", "18:00", "9:00 AM", "6:00 PM", "09:00:00"
     */
    fun parseTimeToMinutes(timeStr: String): Int? {
        val clean = timeStr.trim().uppercase(Locale.US)
        try {
            // Handle AM/PM
            if (clean.contains("AM") || clean.contains("PM")) {
                val isPm = clean.contains("PM")
                val noAmPm = clean.replace("AM", "").replace("PM", "").trim()
                val parts = noAmPm.split(":")
                if (parts.isNotEmpty()) {
                    var hours = parts[0].trim().toInt()
                    val minutes = if (parts.size > 1) parts[1].trim().toInt() else 0
                    if (isPm && hours < 12) hours += 12
                    if (!isPm && hours == 12) hours = 0
                    return hours * 60 + minutes
                }
            } else {
                // 24-hour format "09:00" or "09:00:00"
                val parts = clean.split(":")
                if (parts.size >= 2) {
                    val hours = parts[0].trim().toInt()
                    val minutes = parts[1].trim().toInt()
                    return hours * 60 + minutes
                } else if (parts.size == 1) {
                    return parts[0].trim().toInt() * 60
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing time '$timeStr': ${e.message}")
        }
        return null
    }
}
