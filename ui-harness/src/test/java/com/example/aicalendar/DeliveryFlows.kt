package com.example.aicalendar

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.example.aicalendar.xp.LocalXpInlineDialogs
import com.example.aicalendar.xp.XpTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone

/** HARNESS ONLY: the Deliveries tab, and the promise that a delivery never alerts. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w443dp-h986dp-420dpi")
class DeliveryFlows {
    @get:Rule
    val rule = createComposeRule()

    private lateinit var context: Context
    private val repo get() = EventRepository(context)
    private val alarms get() = shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).scheduledAlarms
    private val posted get() = shadowOf(context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).allNotifications

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/Denver"))
        context = RuntimeEnvironment.getApplication()
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        NotificationHelper.createChannels(context)
    }

    private fun at(days: Long, hour: Int, minute: Int = 0): Long =
        LocalDate.now().plusDays(days).atTime(hour, minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private val rent get() = PlannerEvent(id = "rent", title = "Rent due", startAtMillis = at(4, 9), category = EventCategory.FINANCIAL)
    private val lamp get() = PlannerEvent(
        id = "lamp", title = "Desk lamp", startAtMillis = at(3, 9), location = "UPS",
        domain = EventDomain.DELIVERY, reminderOffsetsMinutes = emptyList(),
    )

    private fun launch(events: List<PlannerEvent>, section: String? = null) {
        repo.saveEvents(events)
        if (section != null) context.getSharedPreferences("planner_view", Context.MODE_PRIVATE).edit().putString("section", section).commit()
        rule.setContent {
            XpTheme {
                CompositionLocalProvider(LocalXpInlineDialogs provides true) {
                    PlannerApp(resumeTick = 1, openEventId = null, onOpenEventHandled = {}, onMinimize = {}, onExit = {})
                }
            }
        }
        rule.waitForIdle()
    }

    private fun fire(eventId: String, kind: String = "offset_1440") {
        ReminderReceiver().onReceive(
            context,
            Intent(context, ReminderReceiver::class.java)
                .setAction("com.example.aicalendar.REMINDER_$kind")
                .putExtra(AlarmScheduler.EXTRA_EVENT_ID, eventId)
                .putExtra(AlarmScheduler.EXTRA_REMINDER_KIND, kind),
        )
    }

    @Test
    fun theTabListsDeliveriesAndCountsThemApartFromLife() {
        launch(listOf(rent, lamp))
        rule.onNodeWithText("Life (1)").assertIsDisplayed()
        rule.onNodeWithText("School (0)").assertIsDisplayed()
        rule.onNodeWithText("Deliveries (1)").assertIsDisplayed()
        rule.onNodeWithText("All (2)").assertIsDisplayed()
        rule.onNodeWithText("Archived").assertIsDisplayed()
        rule.onAllNodesWithText("Desk lamp").assertCountEquals(0)

        rule.onNodeWithText("Deliveries (1)").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Desk lamp").assertIsDisplayed()
        rule.onAllNodesWithText("Rent due").assertCountEquals(0)
        rule.onNodeWithText("1 delivery").assertIsDisplayed()
        assertEquals("DELIVERIES", context.getSharedPreferences("planner_view", Context.MODE_PRIVATE).getString("section", null))

        rule.onNodeWithText("All (2)").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Desk lamp").assertIsDisplayed()
        rule.onNodeWithText("Rent due").assertIsDisplayed()
    }

    @Test
    fun aDeliveryStartedOnTheTabIsSavedWithoutAlertsOrAlarms() {
        launch(listOf(rent), section = "DELIVERIES")
        rule.onNodeWithText("No deliveries on the way.").assertIsDisplayed()
        rule.onNodeWithText("New").performClick()
        rule.onNodeWithText("New Delivery").assertIsDisplayed()
        rule.onNodeWithText("Deliveries never alert. This one stays listed through the day it is expected.").assertIsDisplayed()
        rule.onAllNodesWithText("1 day before").assertCountEquals(0)
        rule.onAllNodesWithText("Critical: exact alarm, heads-up alert").assertCountEquals(0)
        rule.onAllNodes(hasSetTextAction()).onFirst().performTextInput("Desk lamp")
        rule.onNodeWithText("Save").performClick()
        rule.waitForIdle()

        val saved = repo.loadEvents().single { it.title == "Desk lamp" }
        assertEquals(EventDomain.DELIVERY, saved.domain)
        assertEquals(emptyList<Int>(), saved.reminderOffsetsMinutes)
        assertTrue(saved.isDelivery())
        assertEquals(0, alarms.size)
        rule.onNodeWithText("Deliveries (1)").assertIsDisplayed()
        // Selected after saving: the details say why there are no alerts.
        rule.onNodeWithText("None. Deliveries never alert.").assertIsDisplayed()
        rule.onNodeWithText("Delivery - This phone").assertIsDisplayed()
    }

    @Test
    fun turningAReminderIntoADeliveryCancelsItsAlarms() {
        repo.saveEvents(listOf(rent))
        AlarmScheduler.scheduleEvent(context, rent)
        assertEquals(1, alarms.size)

        launch(listOf(rent))
        rule.onNodeWithText("Rent due").performClick()
        rule.onNodeWithText("Edit").performClick()
        // Once in the details behind the editor, once as a lead time in the editor.
        rule.onAllNodesWithText("1 day before").assertCountEquals(2)
        rule.onNodeWithText("Deliveries").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Edit Delivery").assertIsDisplayed()
        rule.onAllNodesWithText("1 day before").assertCountEquals(1)
        rule.onNodeWithText("Save").performClick()
        rule.waitForIdle()

        val saved = repo.find("rent")!!
        assertEquals(EventDomain.DELIVERY, saved.domain)
        assertEquals(EventCategory.FINANCIAL, saved.category) // keeps the color it was given
        assertEquals(emptyList<Int>(), saved.reminderOffsetsMinutes)
        assertEquals(0, alarms.size)
        // The list followed it to its new tab.
        rule.onNodeWithText("Life (0)").assertIsDisplayed()
        rule.onNodeWithText("Deliveries (1)").assertIsDisplayed()
        rule.onNodeWithText("Rent due").assertIsDisplayed()
    }

    @Test
    fun aDeliveryMovedToLifeGetsTheUsualAlertBack() {
        launch(listOf(lamp.copy(category = EventCategory.DELIVERY)), section = "DELIVERIES")
        rule.onNodeWithText("Desk lamp").performClick()
        rule.onNodeWithText("Edit").performClick()
        rule.onNodeWithText("Life").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Edit Reminder").assertIsDisplayed()
        rule.onNodeWithText("1 day before").assertIsDisplayed()
        rule.onNodeWithText("Save").performClick()
        rule.waitForIdle()

        val saved = repo.find("lamp")!!
        assertEquals(EventDomain.LIFE, saved.domain)
        assertEquals(EventCategory.AUTO, saved.category)
        assertEquals(listOf(1440), saved.reminderOffsetsMinutes)
        assertFalse(saved.isDelivery())
        assertEquals(1, alarms.size)
        rule.onNodeWithText("Life (1)").assertIsDisplayed()
        rule.onNodeWithText("Deliveries (0)").assertIsDisplayed()
    }

    @Test
    fun choosingTheDeliveryCategoryFilesItUnderDeliveries() {
        launch(listOf(rent))
        rule.onNodeWithText("Rent due").performClick()
        rule.onNodeWithText("Edit").performClick()
        rule.onAllNodesWithText("Financial").onLast().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Delivery").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Edit Delivery").assertIsDisplayed()
        rule.onNodeWithText("Save").performClick()
        rule.waitForIdle()

        val saved = repo.find("rent")!!
        assertEquals(EventDomain.DELIVERY, saved.domain)
        assertEquals(EventCategory.DELIVERY, saved.category)
        assertEquals(emptyList<Int>(), saved.reminderOffsetsMinutes)
    }

    @Test
    fun aDeliveryStaysListedAfterItsTimeOnTheDayItIsExpected() {
        val earlier = lamp.copy(startAtMillis = at(0, 0, 1))
        val yesterday = lamp.copy(id = "late", title = "Late parcel", startAtMillis = at(-1, 9))
        val meeting = PlannerEvent(id = "meet", title = "Morning call", startAtMillis = at(0, 0, 1))
        launch(listOf(earlier, yesterday, meeting), section = "DELIVERIES")

        rule.onNodeWithText("Desk lamp").assertIsDisplayed()
        rule.onNodeWithText("Deliveries (1)").assertIsDisplayed()
        // An ordinary reminder is filed at its time, and so is a delivery once its day is over.
        rule.onNodeWithText("Life (0)").assertIsDisplayed()
        rule.onNodeWithText("Archived").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Late parcel").assertIsDisplayed()
        rule.onNodeWithText("Morning call").assertIsDisplayed()
        rule.onAllNodesWithText("Desk lamp").assertCountEquals(0)
    }

    @Test
    fun aBirthdayStaysListedUntilTheDayAfter() {
        val birthday = PlannerEvent(id = "bd", title = "Priya's birthday", startAtMillis = at(0, 0, 1), category = EventCategory.PERSONAL)
        val lastOne = PlannerEvent(id = "old", title = "Dad's birthday", startAtMillis = at(-1, 9))
        val call = PlannerEvent(id = "call", title = "Morning call", startAtMillis = at(0, 0, 1))
        launch(listOf(birthday, lastOne, call))

        // Its time (12:01 AM) has passed, but it is still today's.
        rule.onNodeWithText("Life (1)").assertIsDisplayed()
        rule.onNodeWithText("Priya's birthday").assertIsDisplayed()
        rule.onAllNodesWithText("ago", substring = true).assertCountEquals(0)

        // Yesterday's birthday and an ordinary reminder whose time has passed are filed.
        rule.onNodeWithText("Archived").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Dad's birthday").assertIsDisplayed()
        rule.onNodeWithText("Morning call").assertIsDisplayed()
        rule.onAllNodesWithText("Priya's birthday").assertCountEquals(0)
    }

    @Test
    fun anAlarmThatStillFiresForADeliveryPostsNothing() {
        repo.saveEvents(listOf(rent, lamp, lamp.copy(id = "tagged", domain = EventDomain.AUTO, category = EventCategory.DELIVERY)))

        fire("lamp")
        fire("tagged", kind = "offset_0")
        fire("lamp", kind = AlarmScheduler.KIND_SNOOZE)
        assertEquals(0, posted.size)

        // The same broadcast for an ordinary reminder does alert, so the check above means something.
        fire("rent")
        assertEquals(1, posted.size)
    }

    @Test
    fun syncNeverArmsADeliveryAndDisarmsOneThatBecameADelivery() {
        val remoteRent = rent.copy(remote = true, source = "chatgpt")
        val first = repo.replaceRemote(RemoteSnapshot(listOf(remoteRent, lamp.copy(remote = true, source = "chatgpt"))))
        AlarmScheduler.reconcile(context, first)
        assertEquals(1, alarms.size) // the rent reminder only

        // The server turns the rent reminder into a delivery. Even if it still carried lead times, nothing stays armed.
        val moved = remoteRent.copy(domain = EventDomain.DELIVERY, reminderOffsetsMinutes = listOf(1440, 60))
        val second = repo.replaceRemote(RemoteSnapshot(listOf(moved, lamp.copy(remote = true, source = "chatgpt"))))
        AlarmScheduler.reconcile(context, second)
        assertEquals(0, alarms.size)

        // A full reschedule (boot, app update) arms nothing for deliveries either.
        AlarmScheduler.scheduleAll(context, repo.loadEvents())
        assertEquals(0, alarms.size)

        // Moved back to Life with its lead time, it alerts again.
        val back = moved.copy(domain = EventDomain.LIFE, reminderOffsetsMinutes = listOf(1440))
        AlarmScheduler.reconcile(context, repo.replaceRemote(RemoteSnapshot(listOf(back))))
        assertEquals(1, alarms.size)
    }
}
