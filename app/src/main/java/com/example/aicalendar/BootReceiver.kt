package com.example.aicalendar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlin.concurrent.thread

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in SUPPORTED_ACTIONS) return
        val appContext = context.applicationContext
        // Rescheduling a few hundred alarms and parsing the store is too slow for the main thread.
        val pending = goAsync()
        thread(name = "ai-calendar-reschedule") {
            try {
                NotificationHelper.createChannels(appContext)
                AlarmScheduler.scheduleAll(appContext, EventRepository(appContext).loadEvents())
                // Deliver anything queued before the reboot/update.
                if (!PendingChangeStore(appContext).isEmpty()) BridgeSyncWorker.enqueueOutbox(appContext)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private val SUPPORTED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
        )
    }
}
