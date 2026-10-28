package com.example.aicalendar

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.example.aicalendar.xp.LocalXpInlineDialogs
import com.example.aicalendar.xp.XpAppearance
import com.example.aicalendar.xp.XpTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.TimeZone

/** HARNESS ONLY: drives the real screens to check the behavior behind the new UI. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w443dp-h986dp-420dpi")
class XpFlows {
    @get:Rule
    val rule = createComposeRule()

    private lateinit var context: Context
    private val repo get() = EventRepository(context)

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/Denver"))
        context = RuntimeEnvironment.getApplication()
        // The phone grants exact alarms at install (USE_EXACT_ALARM); mirror that here.
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    private fun at(days: Long, hour: Int, minute: Int = 0): Long =
        LocalDate.now().plusDays(days).atTime(hour, minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun seed() = listOf(
        PlannerEvent(id = "rent", title = "Rent due", startAtMillis = at(4, 9), category = EventCategory.FINANCIAL),
        PlannerEvent(id = "clinic", title = "Clinic appointment", startAtMillis = at(6, 13), critical = true, category = EventCategory.MEDICAL, reminderOffsetsMinutes = listOf(1440, 0)),
        PlannerEvent(
            id = "quiz", title = "Chapter 4 Practice Quiz", startAtMillis = at(3, 23, 59), source = "canvas", courseCode = "NUTR150 4002 NUTR150 4006",
            courseColorKey = "hlth", schoolItemType = "quiz", domain = EventDomain.SCHOOL, category = EventCategory.SCHOOL,
        ),
        PlannerEvent(id = "old", title = "Library books due", startAtMillis = at(-9, 17)),
    )

    private var openId by mutableStateOf<String?>(null)

    private fun launch(section: String? = null) {
        repo.saveEvents(seed())
        if (section != null) context.getSharedPreferences("planner_view", Context.MODE_PRIVATE).edit().putString("section", section).commit()
        rule.setContent {
            XpTheme {
                CompositionLocalProvider(LocalXpInlineDialogs provides true) {
                    PlannerApp(resumeTick = 1, openEventId = openId, onOpenEventHandled = { openId = null }, onMinimize = {}, onExit = {})
                }
            }
        }
        rule.waitForIdle()
    }

    @After
    fun tearDown() {
        XpAppearance.dark = false
    }

    @Test
    fun darkModeToggleAppliesAtOnceAndIsRemembered() {
        launch()
        val prefs = context.getSharedPreferences("planner_view", Context.MODE_PRIVATE)
        rule.onNodeWithText("Tools").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Options...").performClick()
        rule.onNodeWithText("Dark mode").performClick()
        rule.waitForIdle()
        assertTrue(XpAppearance.dark)
        assertTrue(prefs.getBoolean("dark_mode", false))
        rule.onNodeWithText("Dark mode").performClick()
        rule.waitForIdle()
        assertFalse(XpAppearance.dark)
        assertFalse(prefs.getBoolean("dark_mode", true))
        rule.onNodeWithText("Close").performClick()
        rule.onNodeWithText("Rent due").assertIsDisplayed()
    }

    @Test
    fun newReminderIsSavedSelectedAndShown() {
        launch()
        rule.onNodeWithText("New").performClick()
        rule.onAllNodes(hasSetTextAction()).onFirst().performTextInput("Buy stamps")
        rule.onNodeWithText("Save").performClick()
        rule.waitForIdle()
        val saved = repo.loadEvents().single { it.title == "Buy stamps" }
        assertEquals(listOf(1440), saved.reminderOffsetsMinutes)
        assertEquals(EventDomain.LIFE, saved.domain)
        assertFalse(saved.remote)
        assertEquals(LocalTime.of(9, 0), Instant.ofEpochMilli(saved.startAtMillis).atZone(ZoneId.systemDefault()).toLocalTime())
        // The new row is selected, so its details (and action buttons) are open.
        rule.onNodeWithText("General - This phone").assertIsDisplayed()
        rule.onNodeWithText("Archive").assertIsDisplayed()
    }

    @Test
    fun blankTitleIsRejectedInTheEditor() {
        launch()
        rule.onNodeWithText("New").performClick()
        rule.onNodeWithText("Save").performClick()
        rule.onNodeWithText("Title is required.").assertIsDisplayed()
        assertEquals(4, repo.loadEvents().size)
    }

    @Test
    fun newReminderFromSchoolTabDefaultsToSchool() {
        launch(section = "SCHOOL")
        rule.onNodeWithText("New").performClick()
        rule.onAllNodes(hasSetTextAction()).onFirst().performTextInput("Study group")
        rule.onNodeWithText("Save").performClick()
        rule.waitForIdle()
        assertEquals(EventDomain.SCHOOL, repo.loadEvents().single { it.title == "Study group" }.domain)
    }

    @Test
    fun doneCheckboxArchivesAndUndoRestores() {
        launch()
        rule.onAllNodesWithContentDescription("Mark done").onFirst().performClick()
        rule.waitForIdle()
        assertTrue(repo.find("rent")!!.done)
        rule.onNodeWithText("Marked done").assertIsDisplayed()
        rule.onAllNodesWithText("Rent due").assertCountEquals(1) // only inside the balloon now
        rule.onNodeWithText("Undo").performClick()
        rule.waitForIdle()
        assertFalse(repo.find("rent")!!.done)
        rule.onAllNodesWithText("Marked done").assertCountEquals(0)
    }

    @Test
    fun archiveButtonAndUndo() {
        launch()
        rule.onNodeWithText("Rent due").performClick()
        rule.onNodeWithText("Archive").performClick()
        rule.waitForIdle()
        assertTrue(repo.find("rent")!!.archived)
        rule.onNodeWithText("Undo").performClick()
        rule.waitForIdle()
        assertFalse(repo.find("rent")!!.archived)
    }

    @Test
    fun deleteAsksFirst() {
        launch()
        rule.onNodeWithText("Rent due").performClick()
        rule.onNodeWithText("Delete").performClick()
        rule.onNodeWithText("Cancel").performClick()
        assertNotNull(repo.find("rent"))
        rule.onNodeWithText("Delete").performClick()
        rule.onAllNodesWithText("Delete").onLast().performClick()
        rule.waitForIdle()
        assertNull(repo.find("rent"))
    }

    @Test
    fun editTimeWithPresetAndCriticalAddsEventTimeAlert() {
        launch()
        rule.onNodeWithText("Rent due").performClick()
        rule.onNodeWithText("Edit").performClick()
        rule.onAllNodesWithText("9:00 AM").onLast().performClick()
        rule.onNodeWithText("5:00 PM").performClick()
        rule.onNodeWithText("OK").performClick()
        rule.onNodeWithText("Critical: exact alarm, heads-up alert").performClick()
        rule.onNodeWithText("Save").performClick()
        rule.waitForIdle()
        val saved = repo.find("rent")!!
        assertEquals(LocalTime.of(17, 0), Instant.ofEpochMilli(saved.startAtMillis).atZone(ZoneId.systemDefault()).toLocalTime())
        assertTrue(saved.critical)
        assertEquals(listOf(1440, 0), saved.reminderOffsetsMinutes)
    }

    @Test
    fun untouchedEditKeepsTheExactStartInstant() {
        val withSeconds = at(4, 9) + 59_000L
        repo.saveEvents(listOf(PlannerEvent(id = "s", title = "Deadline", startAtMillis = withSeconds)))
        rule.setContent {
            XpTheme {
                CompositionLocalProvider(LocalXpInlineDialogs provides true) {
                    PlannerApp(resumeTick = 1, openEventId = null, onOpenEventHandled = {}, onMinimize = {}, onExit = {})
                }
            }
        }
        rule.onNodeWithText("Deadline").performClick()
        rule.onNodeWithText("Edit").performClick()
        rule.onAllNodes(hasSetTextAction()).onFirst().performTextReplacement("Deadline moved")
        rule.onNodeWithText("Save").performClick()
        rule.waitForIdle()
        val saved = repo.find("s")!!
        assertEquals("Deadline moved", saved.title)
        assertEquals(withSeconds, saved.startAtMillis)
    }

    @Test
    fun reminderTimesCanBeChosen() {
        launch()
        rule.onNodeWithText("Rent due").performClick()
        rule.onNodeWithText("Edit").performClick()
        rule.onNodeWithText("1 hour before").performClick()
        rule.onAllNodesWithText("1 day before").onLast().performClick()
        rule.onNodeWithText("Save").performClick()
        rule.waitForIdle()
        assertEquals(listOf(60), repo.find("rent")!!.reminderOffsetsMinutes)
    }

    @Test
    fun findFiltersTheList() {
        launch(section = "ALL")
        rule.onNodeWithContentDescription("Find").performClick()
        rule.waitForIdle()
        rule.onNode(hasSetTextAction()).performTextInput("practice")
        rule.waitForIdle()
        rule.onNodeWithText("Chapter 4 Practice Quiz").assertIsDisplayed()
        rule.onAllNodesWithText("Rent due").assertCountEquals(0)
        rule.onNodeWithText("1 found").assertIsDisplayed()
    }

    @Test
    fun notificationTapOpensTheReminder() {
        launch()
        openId = "quiz"
        rule.waitForIdle()
        // Switched to the School tab with the quiz selected (details open).
        rule.onNodeWithText("School - Canvas").assertIsDisplayed()
        assertNull(openId)
    }

    @Test
    fun notificationTapOnPastReminderOpensArchived() {
        launch()
        openId = "old"
        rule.waitForIdle()
        rule.onNodeWithText("General - This phone").assertIsDisplayed()
        rule.onAllNodesWithText("Rent due").assertCountEquals(0)
    }

    @Test
    fun calendarDaySelectionListsThatDay() {
        launch(section = "ALL")
        rule.onNodeWithContentDescription("Calendar view").performClick()
        rule.waitForIdle()
        val target = LocalDate.now().plusDays(4)
        if (target.month == LocalDate.now().month) {
            rule.onNodeWithContentDescription(target.format(java.time.format.DateTimeFormatter.ofPattern("EEEE, MMMM d"))).performClick()
            rule.waitForIdle()
            rule.onNodeWithText("Rent due").assertIsDisplayed()
        }
    }
}
