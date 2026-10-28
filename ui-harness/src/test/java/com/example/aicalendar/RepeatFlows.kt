package com.example.aicalendar

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onFirst
import com.example.aicalendar.xp.LocalXpInlineDialogs
import com.example.aicalendar.xp.XpTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.TimeZone

/** A weekly series on [firstDate]'s weekday at 8:00 AM, alerting at 5:00 PM the day before. */
internal fun trashSeries(firstDate: LocalDate) = ReminderSeries(
    id = "trash",
    title = "Trash pickup",
    category = EventCategory.HOUSING,
    reminderOffsetsMinutes = listOf(900),
    timeZone = "America/Denver",
    rule = RepeatRule(RepeatFrequency.WEEKLY, 1, listOf(firstDate.dayOfWeek.value), firstDate, LocalTime.of(8, 0)),
)

/** HARNESS ONLY: the repeat box, the "this one or all future" choice and what reaches the bridge. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w443dp-h986dp-420dpi")
class RepeatFlows {
    @get:Rule
    val rule = createComposeRule()

    private lateinit var context: Context
    private val first = LocalDate.now().plusDays(1)

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/Denver"))
        context = RuntimeEnvironment.getApplication()
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        BridgeApi.seriesCalls.clear()
    }

    private fun at(date: LocalDate, hour: Int) = date.atTime(hour, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun occurrence(id: String, date: LocalDate) = PlannerEvent(
        id = id, title = "Trash pickup", startAtMillis = at(date, 8), category = EventCategory.HOUSING, remote = true, source = "chatgpt",
        reminderOffsetsMinutes = listOf(900), seriesId = "trash", occurrenceDate = date.toString(),
    )

    private fun launch(connected: Boolean = true) {
        EventRepository(context).saveEvents(listOf(occurrence("r1", first), occurrence("r2", first.plusDays(7))))
        SeriesStore(context).replace(listOf(trashSeries(first)))
        if (connected) {
            BridgeSettings(context).saveConfig(BridgeConfig("https://example.invalid/api", "p", "a", "k", "s"))
            BridgeSettings(context).saveDeviceToken("token")
        }
        rule.setContent {
            XpTheme {
                CompositionLocalProvider(LocalXpInlineDialogs provides true) {
                    PlannerApp(resumeTick = 1, openEventId = null, onOpenEventHandled = {}, onMinimize = {}, onExit = {})
                }
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun detailsShowTheRuleInWords() {
        launch()
        rule.onAllNodesWithText("Trash pickup").onFirst().performClick()
        val weekday = RepeatText.weekdayName(first.dayOfWeek.value)
        rule.onNodeWithText("Every week on $weekday at 8:00 AM").assertIsDisplayed()
    }

    @Test
    fun deletingThisOneDeletesOnlyThatOccurrence() {
        launch()
        rule.onAllNodesWithText("Trash pickup").onFirst().performClick()
        rule.onNodeWithText("Delete").performClick()
        rule.onNodeWithText("This one").assertIsDisplayed()
        // The choice is the confirmation: no second dialog.
        rule.onAllNodesWithText("Delete").onLast().performClick()
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithText("Delete Reminder").fetchSemanticsNodes().isEmpty())
        assertEquals(listOf("r2"), EventRepository(context).loadEvents().map { it.id })
        assertTrue(BridgeApi.seriesCalls.isEmpty())
    }

    @Test
    fun deletingAllFutureSendsTheSeriesDeleteFromThatDate() {
        launch()
        rule.onAllNodesWithText("Trash pickup").onFirst().performClick()
        rule.onNodeWithText("Delete").performClick()
        rule.onNodeWithText("This and all future ones").performClick()
        rule.onAllNodesWithText("Delete").onLast().performClick()
        rule.waitForIdle()
        val call = BridgeApi.seriesCalls.single()
        assertEquals(PendingOp.SERIES_DELETE, call.op)
        assertEquals("trash", call.seriesId)
        assertEquals(first.toString(), call.deleteFrom)
        assertTrue(EventRepository(context).loadEvents().isEmpty())
    }

    @Test
    fun editingThisOneIsAnOrdinaryEdit() {
        launch()
        rule.onAllNodesWithText("Trash pickup").onFirst().performClick()
        rule.onNodeWithText("Edit").performClick()
        rule.onAllNodesWithText("Edit").onLast().performClick()
        rule.waitForIdle()
        // "This one": no Repeat box, and saving goes through the ordinary reminder save.
        assertTrue(rule.onAllNodesWithText("Does not repeat").fetchSemanticsNodes().isEmpty())
        rule.onNodeWithText("Edit Reminder").assertIsDisplayed()
        rule.onNodeWithText("Save").performClick()
        rule.waitForIdle()
        assertTrue(BridgeApi.seriesCalls.isEmpty())
    }

    @Test
    fun editingAllFutureSendsTheSeriesChangeFromThatDate() {
        launch()
        rule.onAllNodesWithText("Trash pickup").onFirst().performClick()
        rule.onNodeWithText("Edit").performClick()
        rule.onNodeWithText("This and all future ones").performClick()
        rule.onAllNodesWithText("Edit").onLast().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Weekly").assertIsDisplayed()
        rule.onNodeWithText("Save").performClick()
        rule.waitForIdle()
        val call = BridgeApi.seriesCalls.single()
        assertEquals(PendingOp.SERIES_UPDATE, call.op)
        assertEquals("trash", call.seriesId)
        assertEquals(first.toString(), call.body!!.getString("from"))
        val ruleJson = call.body!!.getJSONObject("rule")
        assertEquals("weekly", ruleJson.getString("freq"))
        assertEquals("08:00", ruleJson.getString("time"))
        // The day was not moved, so the series keeps its first date.
        assertTrue(!ruleJson.has("startDate"))
    }

    @Test
    fun aNewWeeklyReminderIsCreatedInTheCloud() {
        launch()
        rule.onNodeWithText("New").performClick()
        rule.onAllNodes(hasSetTextAction()).onFirst().performTextInput("Water plants")
        rule.onNodeWithText("Does not repeat").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Weekly").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Save").performClick()
        rule.waitForIdle()
        val call = BridgeApi.seriesCalls.single()
        assertEquals(PendingOp.SERIES_CREATE, call.op)
        assertEquals("Water plants", call.body!!.getString("title"))
        val ruleJson = call.body!!.getJSONObject("rule")
        assertEquals("weekly", ruleJson.getString("freq"))
        assertEquals(first.toString(), ruleJson.getString("startDate"))
        assertEquals("[${first.dayOfWeek.value}]", ruleJson.getJSONArray("weekdays").toString())
        assertTrue(call.body!!.getString("idempotencyKey").isNotBlank())
    }

    @Test
    fun aRepeatNeedsTheCloudConnection() {
        launch(connected = false)
        rule.onNodeWithText("New").performClick()
        rule.onAllNodes(hasSetTextAction()).onFirst().performTextInput("Water plants")
        rule.onNodeWithText("Does not repeat").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Daily").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Save").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("A repeating reminder is kept in the cloud. Connect this phone to the cloud first.").assertIsDisplayed()
        assertTrue(BridgeApi.seriesCalls.isEmpty())
    }

    @Test
    fun aDeliveryCannotRepeat() {
        launch()
        rule.onNodeWithText("New").performClick()
        rule.onAllNodesWithText("Deliveries").onLast().performClick()
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithText("Does not repeat").fetchSemanticsNodes().isEmpty())
        assertNull(BridgeApi.seriesCalls.firstOrNull())
    }
}
