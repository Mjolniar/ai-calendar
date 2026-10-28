package com.example.aicalendar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val eventId = intent.getStringExtra(AlarmScheduler.EXTRA_EVENT_ID) ?: return
        val repository = EventRepository(context)
        when (intent.action) {
            ACTION_DONE -> {
                // Durable offline completion: local state and the outbox entry are written together;
                // WorkManager delivers it (with retries) even if the process dies or the phone is offline.
                val changed = repository.markDoneAndQueue(eventId)
                AlarmScheduler.cancelEvent(context, eventId)
                if (changed?.remote == true) BridgeSyncWorker.enqueueOutbox(context)
            }
            ACTION_SNOOZE -> {
                NotificationHelper.clear(context, eventId)
                AlarmScheduler.scheduleSnooze(context, eventId)
            }
            ACTION_IGNORE -> NotificationHelper.clear(context, eventId)
            else -> {
                val kind = intent.getStringExtra(AlarmScheduler.EXTRA_REMINDER_KIND)
                    ?: AlarmScheduler.KIND_DAY_BEFORE
                if (kind == AlarmScheduler.KIND_SNOOZE) AlarmScheduler.onSnoozeDelivered(context, eventId)
                val event = repository.find(eventId) ?: return
                // A delivery never alerts, even when an alarm armed before it became one still fires.
                if (!event.done && !event.archived && !event.isDelivery()) {
                    NotificationHelper.showReminder(context, event, kind)
                }
            }
        }
    }

    companion object {
        const val ACTION_DONE = "com.example.aicalendar.ACTION_DONE"
        const val ACTION_SNOOZE = "com.example.aicalendar.ACTION_SNOOZE"
        const val ACTION_IGNORE = "com.example.aicalendar.ACTION_IGNORE"
    }
}
