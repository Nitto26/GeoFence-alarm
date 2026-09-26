package com.example.geoalarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Dedicated System Broadcast Receiver for device boot completion.
 * Protected by Android OS: only the system framework can send ACTION_BOOT_COMPLETED.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d(TAG, "Device rebooted. Rescheduling shift alarms from cached API schedule.")
            ShiftAlarmScheduler.scheduleNextShiftAlarm(context)
        }
    }
}
