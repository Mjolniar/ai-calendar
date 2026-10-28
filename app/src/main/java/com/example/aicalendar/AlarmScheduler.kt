package com.example.aicalendar

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import java.util.concurrent.TimeUnit
import kotlin.math.absoluteValue

object AlarmScheduler {
    const val EXTRA_EVENT_ID = "event_id"
    const val EXTRA_REMINDER_KIND = "reminder_kind"
    const val KIND_DAY_BEFORE = "offset_1440"
    const val KIND_CRITICAL_NOW = "offset_0"
    const val KIND_TONIGHT = ReminderSchedule.KIND_TONIGHT
    const val KIND_SNOOZE = "snooze"

    private val snoozeMillis = TimeUnit.MINUTES.toMillis(10)
    private val staleSnoozeMillis = TimeUnit.HOURS.toMillis(24)
    private val legacyKinds = setOf(KIND_DAY_BEFORE, KIND_CRITICAL_NOW, "day_before", "critical_now")

    /** Full reconcile (boot, app update, permission change, app start). Keeps valid snoozes. */
    fun scheduleAll(context: Context, events: List<PlannerEvent>) {
        events.forEach { scheduleEvent(context, it) }
        // Snoozes for events that no longer exist.
        val known = events.mapTo(HashSet()) { it.id }
        snoozePrefs(context).all.keys.filter { it !in known }.forEach { clearSnooze(context, it) }
        // Every legacy (pre-0.9.2) PendingIntent has now been replaced; stop probing for them.
        metaPrefs(context).edit().putBoolean(KEY_LEGACY_CLEANED, true).persist()
    }

    /**
     * Reconcile after a sync: only events that are new, changed in an alarm-relevant way, or have no
     * alarm record are touched. Removed/completed/archived events and deliveries lose alarms, snoozes
     * and any posted notification.
     */
    fun reconcile(context: Context, merge: RemoteMerge) {
        merge.removed.forEach { cancelEvent(context, it.id) }
        val scheduled = scheduledPrefs(context)
        merge.events.forEach { event ->
            val previous = merge.previous[event.id]
            when {
                event.neverAlerts() -> {
                    if (previous == null || !previous.neverAlerts() || scheduled.contains(event.id)) {
                        cancelEvent(context, event.id)
                    }
                }
                previous == null ||
                    previous.alarmSignature() != event.alarmSignature() ||
                    !scheduled.contains(event.id) -> scheduleEvent(context, event)
            }
        }
    }

    /** Replace the offset alarms for [event]. A pending snooze is preserved (and re-armed if needed). */
    fun scheduleEvent(context: Context, event: PlannerEvent) {
        if (event.neverAlerts()) {
            // Skip the (costly) cancel when nothing was ever armed for it.
            if (scheduledPrefs(context).contains(event.id) || snoozePrefs(context).contains(event.id)) {
                cancelEvent(context, event.id)
            }
            return
        }
        cancelOffsetAlarms(context, event.id)

        val now = System.currentTimeMillis()
        val kinds = mutableSetOf<String>()
        // The plan keeps advance notices and end-of-day deadlines out of the night.
        ReminderSchedule.plan(event.startAtMillis, event.alertOffsets()).forEach { alarm ->
            if (alarm.triggerAtMillis > now) {
                if (event.critical) {
                    scheduleExactOrFallback(context, event.id, alarm.kind, alarm.triggerAtMillis)
                } else {
                    scheduleInexact(context, event.id, alarm.kind, alarm.triggerAtMillis)
                }
                kinds += alarm.kind
            }
        }
        scheduledPrefs(context).edit().putStringSet(event.id, kinds).persist()

        val snoozedUntil = snoozePrefs(context).getLong(event.id, 0L)
        if (snoozedUntil > 0L) {
            when {
                snoozedUntil < now - staleSnoozeMillis -> clearSnooze(context, event.id)
                // Missed while the phone was off: deliver it shortly instead of dropping it.
                else -> scheduleExactOrFallback(context, event.id, KIND_SNOOZE, maxOf(snoozedUntil, now + 5_000))
            }
        }
    }

    fun scheduleSnooze(context: Context, eventId: String) {
        val triggerAt = System.currentTimeMillis() + snoozeMillis
        snoozePrefs(context).edit().putLong(eventId, triggerAt).persist()
        scheduleExactOrFallback(context, eventId, KIND_SNOOZE, triggerAt)
    }

    /** The snooze alarm fired; it no longer needs to survive reboots or syncs. */
    fun onSnoozeDelivered(context: Context, eventId: String) {
        snoozePrefs(context).edit().remove(eventId).persist()
    }

    /** Stop every reminder for an event: offset alarms, snooze, and any notification already posted. */
    fun cancelEvent(context: Context, eventId: String) {
        cancelOffsetAlarms(context, eventId)
        clearSnooze(context, eventId)
        scheduledPrefs(context).edit().remove(eventId).persist()
        NotificationHelper.clear(context, eventId)
    }

    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return alarmManager(context).canScheduleExactAlarms()
    }

    /** Nothing may stay armed for it: finished, filed, or a delivery (listed, but silent by design). */
    private fun PlannerEvent.neverAlerts(): Boolean = done || archived || isDelivery()

    private fun cancelOffsetAlarms(context: Context, eventId: String) {
        val dynamicKinds = scheduledPrefs(context).getStringSet(eventId, emptySet()).orEmpty()
        val kinds = if (legacyCleaned(context)) dynamicKinds else dynamicKinds + legacyKinds
        kinds.forEach { kind -> cancelAlarm(context, eventId, kind) }
    }

    private fun clearSnooze(context: Context, eventId: String) {
        cancelAlarm(context, eventId, KIND_SNOOZE)
        val prefs = snoozePrefs(context)
        if (prefs.contains(eventId)) prefs.edit().remove(eventId).persist()
    }

    private fun cancelAlarm(context: Context, eventId: String, kind: String) {
        // v0.9.1 and earlier created PendingIntents without a data URI; cancel both identities
        // until the first full reschedule after the upgrade has replaced them.
        val identities = if (legacyCleaned(context)) listOf(false) else listOf(false, true)
        identities.forEach { legacy ->
            pendingIntent(context, eventId, kind, PendingIntent.FLAG_NO_CREATE, legacy)?.let {
                alarmManager(context).cancel(it)
                it.cancel()
            }
        }
    }

    private fun scheduleInexact(context: Context, eventId: String, kind: String, triggerAtMillis: Long) {
        val pendingIntent = pendingIntent(context, eventId, kind, 0) ?: return
        alarmManager(context).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
    }

    private fun scheduleExactOrFallback(context: Context, eventId: String, kind: String, triggerAtMillis: Long) {
        val pendingIntent = pendingIntent(context, eventId, kind, 0) ?: return
        val manager = alarmManager(context)
        if (canScheduleExact(context)) {
            try {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                return
            } catch (_: SecurityException) {
                // Permission revoked between the check and the call (Android 14+); fall back below.
            }
        }
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
    }

    private fun pendingIntent(
        context: Context,
        eventId: String,
        kind: String,
        extraFlag: Int,
        legacy: Boolean = false,
    ): PendingIntent? {
        val intent = Intent(context, ReminderReceiver::class.java)
            .setAction("com.example.aicalendar.REMINDER_$kind")
            .putExtra(EXTRA_EVENT_ID, eventId)
            .putExtra(EXTRA_REMINDER_KIND, kind)
        if (!legacy) {
            // The data URI makes each event/kind a distinct PendingIntent even if request codes collide.
            intent.data = Uri.Builder().scheme("aicalendar").authority("reminder")
                .appendPath(eventId).appendPath(kind).build()
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode(eventId, kind),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE or extraFlag,
        )
    }

    private fun requestCode(eventId: String, kind: String): Int =
        "$eventId:$kind".hashCode().absoluteValue

    private fun alarmManager(context: Context): AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun scheduledPrefs(context: Context) =
        context.getSharedPreferences("scheduled_alarm_kinds", Context.MODE_PRIVATE)

    private fun snoozePrefs(context: Context) =
        context.getSharedPreferences("snoozed_until", Context.MODE_PRIVATE)

    private fun metaPrefs(context: Context) =
        context.getSharedPreferences("alarm_meta", Context.MODE_PRIVATE)

    private fun legacyCleaned(context: Context): Boolean = metaPrefs(context).getBoolean(KEY_LEGACY_CLEANED, false)

    private const val KEY_LEGACY_CLEANED = "legacy_pending_intents_cleaned_v18"
}
