package com.example.aicalendar

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.absoluteValue

object NotificationHelper {
    /** Extra on the tap intent: the reminder the app should select and scroll to. */
    const val EXTRA_OPEN_EVENT_ID = "open_event_id"

    private const val ACTION_OPEN_EVENT = "com.example.aicalendar.OPEN_EVENT"
    private const val CHANNEL_NORMAL = "planner_reminders"
    private const val CHANNEL_CRITICAL = "critical_planner_reminders"
    private val displayFormatter = DateTimeFormatter.ofPattern("EEE, MMM d 'at' h:mm a")

    fun createChannels(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val normal = NotificationChannel(
            CHANNEL_NORMAL,
            "Planner reminders",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Day-before and snoozed planner reminders"
        }
        val critical = NotificationChannel(
            CHANNEL_CRITICAL,
            "Critical planner reminders",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Exact alarms for flights, tests, appointments, and deadlines"
            enableVibration(true)
        }
        manager.createNotificationChannels(listOf(normal, critical))
    }

    @SuppressLint("MissingPermission")
    fun showReminder(context: Context, event: PlannerEvent, kind: String) {
        if (!canPostNotifications(context)) return

        val critical = kind == AlarmScheduler.KIND_CRITICAL_NOW || event.critical
        val eventCategory = event.resolvedCategory()
        val channel = if (critical) CHANNEL_CRITICAL else CHANNEL_NORMAL
        val offset = kind.removePrefix("offset_").toIntOrNull()
        val title = when {
            kind == AlarmScheduler.KIND_TONIGHT -> "Tonight: ${event.title}"
            offset == 1440 -> "Tomorrow: ${event.title}"
            offset != null && offset > 0 -> "In ${formatOffset(offset)}: ${event.title}"
            offset == 0 -> "Now: ${event.title}"
            kind == AlarmScheduler.KIND_SNOOZE -> event.title
            else -> "Now: ${event.title}"
        }
        val whenText = Instant.ofEpochMilli(event.startAtMillis)
            .atZone(ZoneId.systemDefault())
            .format(displayFormatter)
        val text = buildString {
            append(whenText)
            if (event.location.isNotBlank()) append(" - ").append(event.location)
        }
        val footer = if (critical) "${eventCategory.displayName} - Critical" else eventCategory.displayName
        // Balloon-tip look: warning sign for critical reminders and quizzes/exams, info sign otherwise.
        val icon = if (critical || event.assessmentKind() != null) R.drawable.xp_ic_warning else R.drawable.xp_ic_info

        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(eventCategory.colorArgb)
            .setSubText(eventCategory.displayName)
            // Plain title/text stay set for the lock screen, watches and accessibility services.
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(balloon(context, icon, title, text))
            .setCustomHeadsUpContentView(balloon(context, icon, title, text))
            .setCustomBigContentView(bigBalloon(context, icon, title, text, event.notes, footer))
            .setPriority(if (critical) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openEventIntent(context, event.id))
            .addAction(0, "Done", actionIntent(context, event.id, ReminderReceiver.ACTION_DONE))
            .addAction(0, "Snooze 10m", actionIntent(context, event.id, ReminderReceiver.ACTION_SNOOZE))
            .addAction(0, "Ignore", actionIntent(context, event.id, ReminderReceiver.ACTION_IGNORE))
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(notificationId(event.id), notification)
        }
    }

    fun clear(context: Context, eventId: String) {
        NotificationManagerCompat.from(context).cancel(notificationId(eventId))
    }

    private fun balloon(context: Context, icon: Int, title: String, text: String): RemoteViews =
        RemoteViews(context.packageName, R.layout.notification_balloon).apply {
            setImageViewResource(R.id.xp_icon, icon)
            setTextViewText(R.id.xp_title, title)
            setTextViewText(R.id.xp_text, text)
        }

    private fun bigBalloon(
        context: Context,
        icon: Int,
        title: String,
        text: String,
        notes: String,
        footer: String,
    ): RemoteViews = RemoteViews(context.packageName, R.layout.notification_balloon_big).apply {
        setImageViewResource(R.id.xp_icon, icon)
        setTextViewText(R.id.xp_title, title)
        setTextViewText(R.id.xp_text, text)
        setTextViewText(R.id.xp_notes, notes)
        setViewVisibility(R.id.xp_notes, if (notes.isBlank()) View.GONE else View.VISIBLE)
        setTextViewText(R.id.xp_footer, footer)
    }

    private fun canPostNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    /** Tapping the notification opens the app on this reminder (selected and scrolled into view). */
    private fun openEventIntent(context: Context, eventId: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(ACTION_OPEN_EVENT)
            // The data URI keeps one PendingIntent per reminder, so extras are never shared.
            .setData(Uri.Builder().scheme("aicalendar").authority("open").appendPath(eventId).build())
            .putExtra(EXTRA_OPEN_EVENT_ID, eventId)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context,
            1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun actionIntent(context: Context, eventId: String, action: String): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java)
            .setAction(action)
            .putExtra(AlarmScheduler.EXTRA_EVENT_ID, eventId)
        return PendingIntent.getBroadcast(
            context,
            "$eventId:$action".hashCode().absoluteValue,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun notificationId(eventId: String): Int = eventId.hashCode().absoluteValue

    private fun formatOffset(minutes: Int): String = when {
        minutes % 1440 == 0 -> "${minutes / 1440} day${if (minutes == 1440) "" else "s"}"
        minutes % 60 == 0 -> "${minutes / 60} hour${if (minutes == 60) "" else "s"}"
        else -> "$minutes minutes"
    }
}
