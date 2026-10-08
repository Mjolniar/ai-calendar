package com.example.aicalendar

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.ZoneId

/** HARNESS ONLY: starts the real activity (manifest, theme, edge-to-edge) and posts a real notification. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w443dp-h986dp-420dpi")
@OptIn(ExperimentalRoborazziApi::class)
class XpActivityTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()

    private fun at(days: Long, hour: Int): Long =
        LocalDate.now().plusDays(days).atTime(hour, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test
    fun activityStartsAndOpensTheReminderFromItsNotification() {
        EventRepository(context).saveEvents(
            listOf(
                PlannerEvent(id = "a", title = "Rent due", startAtMillis = at(4, 9), category = EventCategory.FINANCIAL),
                PlannerEvent(id = "b", title = "Clinic appointment", startAtMillis = at(6, 13), critical = true, category = EventCategory.MEDICAL),
            ),
        )
        val intent = Intent(context, MainActivity::class.java).putExtra(NotificationHelper.EXTRA_OPEN_EVENT_ID, "b")
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            scenario.onActivity { }
            captureScreenRoboImage(
                "build/shots/25-activity-open-event.png",
                RoborazziOptions(recordOptions = RoborazziOptions.RecordOptions(resizeScale = 0.5)),
            )
            // A second tap on another notification reaches the running activity through onNewIntent.
            scenario.onActivity { activity ->
                activity.startActivity(
                    Intent(context, MainActivity::class.java)
                        .putExtra(NotificationHelper.EXTRA_OPEN_EVENT_ID, "a")
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                )
            }
        }
    }

    @Test
    fun endOfDayDeadlineArmsEveningAlarmsAndSaysTonight() {
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        NotificationHelper.createChannels(context)
        val zone = ZoneId.systemDefault()
        val dueDay = LocalDate.now().plusDays(5)
        val event = PlannerEvent(
            id = "q1", title = "Exp 3 Quiz", startAtMillis = dueDay.atTime(23, 59, 59).atZone(zone).toInstant().toEpochMilli(),
            critical = true, reminderOffsetsMinutes = listOf(4320, 1440, 120, 60, 0),
        )
        AlarmScheduler.scheduleEvent(context, event)

        val alarms = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val armed = shadowOf(alarms).scheduledAlarms
            .map { java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(it.triggerAtMs), zone) }
            .sorted()
        assertEquals(
            listOf(dueDay.minusDays(3).atTime(20, 0), dueDay.minusDays(1).atTime(20, 0), dueDay.atTime(20, 0)),
            armed,
        )

        NotificationHelper.showReminder(context, event, AlarmScheduler.KIND_TONIGHT)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        assertEquals("Tonight: Exp 3 Quiz", shadowOf(manager).allNotifications.single().extras.getString("android.title"))
    }

    @Test
    fun reminderNotificationUsesTheBalloonAndOpensItsReminder() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        NotificationHelper.createChannels(context)
        val event = PlannerEvent(
            id = "n1", title = "Clinic appointment", startAtMillis = at(1, 13), location = "Building B",
            notes = "Bring the referral.", critical = true, category = EventCategory.MEDICAL,
        )
        NotificationHelper.showReminder(context, event, AlarmScheduler.KIND_DAY_BEFORE)

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val posted = shadowOf(manager).allNotifications.single()
        assertEquals("critical_planner_reminders", posted.channelId)
        assertEquals("Tomorrow: Clinic appointment", posted.extras.getString("android.title"))
        assertTrue(posted.extras.getString("android.template").orEmpty().contains("DecoratedCustomViewStyle"))
        assertEquals(3, posted.actions.size)
        val open = shadowOf(posted.contentIntent).savedIntent
        assertEquals("n1", open.getStringExtra(NotificationHelper.EXTRA_OPEN_EVENT_ID))
        assertEquals(MainActivity::class.java.name, open.component?.className)
        assertNotNull(open.data)
    }
}
