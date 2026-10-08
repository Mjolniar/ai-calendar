package com.example.aicalendar

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasSetTextAction
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import com.example.aicalendar.xp.LocalXpInlineDialogs
import com.example.aicalendar.xp.XpAppearance
import com.example.aicalendar.xp.XpTheme
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone

/** HARNESS ONLY: the light renders, in build/shots. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w443dp-h986dp-420dpi")
class XpShots : XpShotsBase(dark = false)

/** HARNESS ONLY: the same screens in dark mode, in build/shots-dark. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w443dp-h986dp-420dpi")
class XpShotsDark : XpShotsBase(dark = true)

/** HARNESS ONLY: renders the app headlessly so the XP layout can be reviewed without a device. */
@OptIn(ExperimentalRoborazziApi::class, ExperimentalLayoutApi::class)
abstract class XpShotsBase(private val dark: Boolean) {
    @get:Rule
    val rule = createComposeRule()

    private val dir = if (dark) "build/shots-dark" else "build/shots"

    private val options = RoborazziOptions(recordOptions = RoborazziOptions.RecordOptions(resizeScale = 0.5))
    private lateinit var context: Context

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/Denver"))
        context = RuntimeEnvironment.getApplication()
        // The phone grants exact alarms at install (USE_EXACT_ALARM); mirror that here.
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        XpAppearance.dark = dark
    }

    @After
    fun tearDown() {
        XpAppearance.dark = false
    }

    private fun at(days: Long, hour: Int, minute: Int = 0): Long =
        LocalDate.now().plusDays(days).atTime(hour, minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun sample(): List<PlannerEvent> {
        val chem = "CHM110L 4001 CHM110L 4002 CHM110L 4201"
        val hlth = "NUTR150 4002 NUTR150 4006 NUTR150 4007"
        fun canvas(id: String, title: String, start: Long, code: String, key: String, type: String = "assignment", critical: Boolean = false) =
            PlannerEvent(
                id = id, title = title, startAtMillis = start, source = "canvas", sourceUrl = "https://canvas.example.edu/x",
                courseCode = code, courseName = "", courseColorKey = key, courseId = key, schoolItemType = type,
                domain = EventDomain.SCHOOL, category = EventCategory.SCHOOL, remote = true, critical = critical,
                details = "Complete the worksheet and upload a PDF. Show all work for full credit.",
                pointsPossible = 25.0, submissionTypes = listOf("online_upload"), allowedAttempts = 2,
                reminderOffsetsMinutes = if (type == "quiz") listOf(4320, 1440) else listOf(1440),
            )
        return listOf(
            PlannerEvent(id = "l0", title = "Pick up prescription", startAtMillis = at(0, 23, 30), location = "Pharmacy on State St", remote = true, source = "chatgpt"),
            PlannerEvent(
                id = "l1", title = "Plumber visit", startAtMillis = at(1, 9), location = "1200 W Example St, Springfield",
                notes = "Kitchen sink leak. They will text 30 minutes before arriving.", critical = true, category = EventCategory.HOUSING,
                remote = true, source = "chatgpt", reminderOffsetsMinutes = listOf(1440, 0),
            ),
            PlannerEvent(id = "l2", title = "Sam's birthday", startAtMillis = at(2, 9), category = EventCategory.PERSONAL, remote = true, source = "chatgpt"),
            PlannerEvent(id = "l3", title = "Rent due", startAtMillis = at(4, 9), category = EventCategory.FINANCIAL, source = "manual"),
            PlannerEvent(
                id = "l4", title = "Clinic appointment", startAtMillis = at(6, 13), location = "Medical Center, Building B, Room 114",
                critical = true, category = EventCategory.MEDICAL, remote = true, source = "chatgpt", reminderOffsetsMinutes = listOf(1440, 60, 0),
            ),
            PlannerEvent(id = "l5", title = "Electrician: in-person estimate for the garage sub-panel and the outdoor outlets", startAtMillis = at(9, 10, 30), critical = true, category = EventCategory.HOUSING, remote = true, source = "chatgpt"),
            PlannerEvent(id = "l6", title = "Flight to Seattle", startAtMillis = at(24, 6, 15), location = "Airport", category = EventCategory.TRAVEL, critical = true, remote = true, source = "chatgpt"),
            PlannerEvent(id = "l7", title = "Car registration renewal", startAtMillis = at(45, 9), remote = true, source = "chatgpt"),
            canvas("s1", "Exp 3 Quiz - Nuclear Decay Half Life", at(1, 23, 59), chem, "chem", type = "quiz"),
            canvas("s2", "Exp 3 - Nuclear Decay - Half-life", at(1, 23, 59), chem, "chem"),
            canvas("s3", "Relationship Ethics Case Study", at(2, 23, 59), "ETH110 001", "eth110"),
            canvas("s4", "Chapter 7 Vitamins Quiz", at(3, 23, 59), hlth, "hlth", type = "quiz"),
            canvas("s5", "Midterm Exam", at(8, 14), "MATH143 002", "math", critical = true),
            canvas("s6", "Discussion: Sleep and Stress", at(12, 23, 59), hlth, "hlth", type = "discussion"),
            // The first one's time has already passed for most of the day; a delivery stays listed anyway.
            PlannerEvent(
                id = "d1", title = "Desk lamp", startAtMillis = at(0, 0, 5), location = "UPS", notes = "Order 114-2291. Leave at the side door.",
                domain = EventDomain.DELIVERY, remote = true, source = "chatgpt", reminderOffsetsMinutes = emptyList(),
            ),
            PlannerEvent(
                id = "d2", title = "Textbook: Chemistry in Context", startAtMillis = at(2, 9), location = "USPS",
                domain = EventDomain.DELIVERY, remote = true, source = "chatgpt", reminderOffsetsMinutes = emptyList(),
            ),
            PlannerEvent(
                id = "d3", title = "Winter jacket", startAtMillis = at(5, 9), location = "FedEx",
                domain = EventDomain.DELIVERY, category = EventCategory.DELIVERY, source = "manual", reminderOffsetsMinutes = emptyList(),
            ),
            PlannerEvent(id = "a1", title = "Oil change", startAtMillis = at(-3, 10), category = EventCategory.GENERAL, done = true, remote = true, source = "chatgpt"),
            PlannerEvent(id = "a2", title = "Library books due", startAtMillis = at(-9, 17), source = "manual"),
            PlannerEvent(id = "a3", title = "CHM 110 Exam 3", startAtMillis = at(70, 23, 59), domain = EventDomain.SCHOOL, done = true, remote = true, source = "chatgpt"),
            PlannerEvent(id = "a4", title = "Dentist cleaning", startAtMillis = at(-40, 8), category = EventCategory.MEDICAL, archived = true, remote = true, source = "chatgpt"),
        )
    }

    private fun launch(connected: Boolean = true, events: List<PlannerEvent> = sample(), section: String? = null, issue: String? = null) {
        EventRepository(context).saveEvents(events)
        if (connected) {
            BridgeSettings(context).saveConfig(BridgeConfig("https://example.invalid/api", "p", "a", "k", "s"))
            BridgeSettings(context).saveDeviceToken("token")
        }
        if (issue != null) PendingChangeStore(context).recordIssue(issue)
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

    private fun shot(name: String) {
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("$dir/$name.png", options)
    }

    private fun screenShot(name: String) {
        rule.waitForIdle()
        captureScreenRoboImage("$dir/$name.png", options)
    }

    @Test
    fun lifeList() {
        launch()
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("$dir/00-life-full.png")
        shot("01-life")
        rule.onNodeWithText("Plumber visit").performClick()
        shot("02-life-selected")
    }

    @Test
    fun schoolList() {
        launch(section = "SCHOOL")
        shot("03-school")
        rule.onNodeWithText("Exp 3 Quiz - Nuclear Decay Half Life").performClick()
        shot("04-school-selected")
    }

    @Test
    fun calendarView() {
        launch(section = "ALL")
        rule.onNodeWithContentDescription("Calendar view").performClick()
        shot("05-calendar")
    }

    @Test
    fun archived() {
        launch(section = "ARCHIVED")
        shot("06-archived")
    }

    @Test
    fun deliveries() {
        launch(section = "DELIVERIES")
        shot("26-deliveries")
        rule.onNodeWithText("Desk lamp").performClick()
        shot("27-deliveries-selected")
    }

    @Test
    fun deliveryEditor() {
        launch(section = "DELIVERIES")
        rule.onNodeWithText("New").performClick()
        shot("28-editor-delivery")
    }

    @Test
    fun deliveriesEmpty() {
        launch(events = sample().filterNot { it.isDelivery() }, section = "DELIVERIES")
        shot("29-deliveries-empty")
    }

    @Test
    fun tabsWithThreeDigitCounts() {
        // The counts on the real phone: every tab must stay on screen at this width without scrolling.
        val life = (1..33).map { PlannerEvent(id = "L$it", title = "Life $it", startAtMillis = at(it.toLong(), 9), domain = EventDomain.LIFE) }
        val school = (1..125).map { PlannerEvent(id = "S$it", title = "School $it", startAtMillis = at(it.toLong(), 10), domain = EventDomain.SCHOOL) }
        val parcels = (1..12).map {
            PlannerEvent(id = "D$it", title = "Parcel $it", startAtMillis = at(it.toLong(), 11), domain = EventDomain.DELIVERY, reminderOffsetsMinutes = emptyList())
        }
        launch(events = life + school + parcels)
        listOf("Life (33)", "School (125)", "Deliveries (12)", "All (170)", "Archived").forEach { rule.onNodeWithText(it).assertIsDisplayed() }
        shot("31-tabs-real-counts")
        rule.onNodeWithText("Archived").performClick()
        rule.waitForIdle()
        listOf("Life (33)", "School (125)", "Deliveries (12)", "All (170)", "Archived").forEach { rule.onNodeWithText(it).assertIsDisplayed() }
    }

    @Test
    fun birthdayToday() {
        // A birthday whose 12:10 AM time has passed stays under Today until the day is over.
        val birthday = PlannerEvent(
            id = "bd", title = "Alex's birthday", startAtMillis = at(0, 0, 10), category = EventCategory.PERSONAL, remote = true, source = "chatgpt",
        )
        launch(events = sample() + birthday)
        rule.onNodeWithText("Alex's birthday").assertIsDisplayed()
        shot("32-birthday-today")
    }

    /** Two occurrences of a weekly series, the second one edited on its own, and the series itself. */
    private fun withTrashSeries(): List<PlannerEvent> {
        val friday = LocalDate.now().plusDays(1)
        SeriesStore(context).replace(listOf(trashSeries(friday)))
        return sample() + listOf(
            PlannerEvent(
                id = "r1", title = "Trash pickup", startAtMillis = at(1, 8), category = EventCategory.HOUSING, remote = true, source = "chatgpt",
                reminderOffsetsMinutes = listOf(900), seriesId = "trash", occurrenceDate = friday.toString(),
            ),
            PlannerEvent(
                id = "r2", title = "Trash pickup", startAtMillis = at(8, 7, 30), category = EventCategory.HOUSING, remote = true, source = "chatgpt",
                reminderOffsetsMinutes = listOf(900), seriesId = "trash", occurrenceDate = friday.plusDays(7).toString(), detached = true,
            ),
        )
    }

    @Test
    fun repeatingReminder() {
        launch(events = withTrashSeries())
        shot("33-repeat-rows")
        rule.onAllNodesWithText("Trash pickup")[0].performClick()
        rule.onNodeWithText("Repeats", substring = true).assertIsDisplayed()
        shot("34-repeat-details")
        rule.onNodeWithText("Edit").performClick()
        shot("35-repeat-edit-scope")
        rule.onNodeWithText("This and all future ones").performClick()
        rule.onAllNodesWithText("Edit").onLast().performClick()
        rule.onNodeWithText("Changes apply to this and all future occurrences.").assertIsDisplayed()
        shot("36-repeat-editor-series")
    }

    @Test
    fun repeatingDelete() {
        launch(events = withTrashSeries())
        rule.onAllNodesWithText("Trash pickup")[0].performClick()
        rule.onNodeWithText("Delete").performClick()
        shot("37-repeat-delete-scope")
    }

    @Test
    fun repeatEditorWeekly() {
        launch()
        rule.onNodeWithText("New").performClick()
        rule.onNodeWithText("Does not repeat").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Weekly").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Ends").performScrollTo()
        shot("38-editor-repeat-weekly")
    }

    @Test
    fun allList() {
        launch(section = "ALL")
        shot("30-all")
    }

    @Test
    fun find() {
        launch(section = "ALL")
        rule.onNodeWithContentDescription("Find").performClick()
        rule.waitForIdle()
        rule.onNode(hasSetTextAction()).performTextInput("quiz")
        shot("07-find")
    }

    @Test
    fun editor() {
        launch()
        rule.onNodeWithText("New").performClick()
        shot("08-editor-new")
    }

    @Test
    fun editorExisting() {
        launch()
        rule.onNodeWithText("Clinic appointment").performClick()
        rule.onNodeWithText("Edit").performClick()
        shot("09-editor-edit")
    }

    @Test
    fun pickers() {
        launch()
        rule.onNodeWithText("New").performClick()
        rule.waitForIdle()
        rule.onAllNodesWithText("9:00 AM").onLast().performClick()
        shot("10-time-picker")
    }

    @Test
    fun datePicker() {
        launch()
        rule.onNodeWithText("New").performClick()
        rule.waitForIdle()
        rule.onAllNodesWithText(", 20", substring = true)[0].performClick()
        shot("11-date-picker")
    }

    @Test
    fun connection() {
        launch(issue = "Couldn't save \"Rent due\": title is required. The cloud version was restored.")
        rule.onNodeWithContentDescription("Cloud: needs attention").performClick()
        shot("12-connection")
    }

    @Test
    fun notConnected() {
        launch(connected = false)
        shot("13-not-connected")
        rule.onNodeWithContentDescription("Cloud: not connected").performClick()
        shot("14-connection-new")
    }

    @Test
    fun deleteAndBalloon() {
        launch()
        rule.onNodeWithText("Rent due").performClick()
        rule.onNodeWithText("Delete").performClick()
        shot("15-delete")
        rule.onNodeWithText("Cancel").performClick()
        rule.onNodeWithText("Archive").performClick()
        shot("16-balloon")
        rule.onNodeWithText("Undo").performClick()
        rule.onNodeWithContentDescription("Mark done", useUnmergedTree = true).let { }
        shot("16b-after-undo")
    }

    @Test
    fun emptyState() {
        launch(events = emptyList())
        shot("17-empty")
    }

    @Test
    fun menu() {
        launch()
        rule.onNodeWithText("Tools").performClick()
        screenShot("18-menu-tools")
    }

    @Test
    fun menuView() {
        launch()
        rule.onNodeWithText("View").performClick()
        screenShot("18b-menu-view")
    }

    @Test
    fun menuFile() {
        launch()
        rule.onNodeWithText("File").performClick()
        screenShot("18c-menu-file")
    }

    @Test
    fun iconSheet() {
        val icons = with(com.example.aicalendar.xp.XpIcons) {
            listOf(App, New, ViewCalendar, ViewList, Find, Sync, Cloud, CloudOk, CloudOff, CloudWarn, Import, School, Bell,
                Check, Delete, Edit, Archive, Restore, OpenLink, Clock, Warning, Error, Sparkle, Info, Repeat)
        }
        rule.setContent {
            XpTheme {
                val colors = com.example.aicalendar.xp.XpColors
                androidx.compose.foundation.layout.Column {
                    listOf(colors.face, colors.menu, colors.window, colors.tooltip).forEach { paper ->
                        androidx.compose.foundation.layout.FlowRow(
                            androidx.compose.ui.Modifier
                                .fillMaxWidth()
                                .background(paper)
                                .padding(8.dp),
                        ) {
                            icons.forEach {
                                androidx.compose.foundation.Image(it, null, androidx.compose.ui.Modifier.padding(5.dp).size(40.dp))
                            }
                        }
                    }
                }
            }
        }
        rule.onRoot().captureRoboImage("$dir/25-icons.png")
    }

    @Test
    fun options() {
        launch()
        rule.onNodeWithText("Tools").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Options...").performClick()
        shot("24-options")
    }

    @Test
    fun about() {
        launch()
        rule.onNodeWithText("Help").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("About AI Calendar").performClick()
        shot("19-about")
    }
    @Test
    fun longArchive() {
        val many = (1..60).map { n ->
            PlannerEvent(id = "m$n", title = "Past reminder $n", startAtMillis = at(-n.toLong() * 3, 9 + n % 8), done = n % 3 == 0, remote = true, source = "chatgpt")
        }
        launch(events = many + sample(), section = "ARCHIVED")
        shot("20-archive-scrollbar")
    }

    @Test
    fun largeText() {
        EventRepository(context).saveEvents(sample())
        rule.setContent {
            val base = androidx.compose.ui.platform.LocalDensity.current
            CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(base.density, fontScale = 1.3f),
                LocalXpInlineDialogs provides true,
            ) {
                XpTheme { PlannerApp(resumeTick = 1, openEventId = null, onOpenEventHandled = {}, onMinimize = {}, onExit = {}) }
            }
        }
        shot("21-large-text")
        // Five tabs do not fit at this text size: the strip scrolls, and the chosen tab is brought into view.
        rule.onNodeWithText("Archived").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Archived").assertIsDisplayed()
        shot("21b-large-text-last-tab")
        rule.onNodeWithText("Life (8)").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Life (8)").assertIsDisplayed()
        rule.onNodeWithText("New").performClick()
        shot("22-large-text-editor")
    }

    @Test
    fun notificationBalloons() {
        rule.setContent {
            androidx.compose.ui.viewinterop.AndroidView(
                factory = { ctx ->
                    android.widget.LinearLayout(ctx).apply {
                        orientation = android.widget.LinearLayout.VERTICAL
                        setBackgroundColor(0xFF1F2023.toInt())
                        val pad = (16 * ctx.resources.displayMetrics.density).toInt()
                        setPadding(pad, pad, pad, pad)
                        fun add(views: android.widget.RemoteViews) {
                            val child = views.apply(ctx, this)
                            addView(child, android.widget.LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = pad })
                        }
                        add(android.widget.RemoteViews(ctx.packageName, R.layout.notification_balloon).apply {
                            setImageViewResource(R.id.xp_icon, R.drawable.xp_ic_info)
                            setTextViewText(R.id.xp_title, "Tomorrow: Sam's birthday")
                            setTextViewText(R.id.xp_text, "Sat, Oct 3 at 9:00 AM")
                        })
                        add(android.widget.RemoteViews(ctx.packageName, R.layout.notification_balloon).apply {
                            setImageViewResource(R.id.xp_icon, R.drawable.xp_ic_warning)
                            setTextViewText(R.id.xp_title, "Now: Electrician: in-person estimate for the garage sub-panel")
                            setTextViewText(R.id.xp_text, "Sat, Oct 10 at 10:30 AM - 1200 W Example St, Springfield")
                        })
                        add(android.widget.RemoteViews(ctx.packageName, R.layout.notification_balloon_big).apply {
                            setImageViewResource(R.id.xp_icon, R.drawable.xp_ic_warning)
                            setTextViewText(R.id.xp_title, "Now: Electrician: in-person estimate for the garage sub-panel and the outdoor outlets")
                            setTextViewText(R.id.xp_text, "Sat, Oct 10 at 10:30 AM - 1200 W Example St, Springfield")
                            setTextViewText(R.id.xp_notes, "Bring the panel photos and the list of outlets. Ask about permit timing and whether the quote includes trenching.")
                            setTextViewText(R.id.xp_footer, "Housing - Critical")
                        })
                        add(android.widget.RemoteViews(ctx.packageName, R.layout.notification_balloon_big).apply {
                            setImageViewResource(R.id.xp_icon, R.drawable.xp_ic_info)
                            setTextViewText(R.id.xp_title, "Tomorrow: Sam's birthday")
                            setTextViewText(R.id.xp_text, "Sat, Oct 3 at 9:00 AM")
                            setViewVisibility(R.id.xp_notes, android.view.View.GONE)
                            setTextViewText(R.id.xp_footer, "Personal")
                        })
                    }
                },
            )
        }
        shot("23-notification-balloons")
    }
}
