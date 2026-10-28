package com.example.aicalendar

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import androidx.core.graphics.drawable.toDrawable
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.aicalendar.xp.XpAppearance
import com.example.aicalendar.xp.XpBalloon
import com.example.aicalendar.xp.XpButton
import com.example.aicalendar.xp.XpCloseGlyph
import com.example.aicalendar.xp.XpColors
import com.example.aicalendar.xp.XpComboBox
import com.example.aicalendar.xp.XpIcons
import com.example.aicalendar.xp.XpInfoBar
import com.example.aicalendar.xp.XpMenu
import com.example.aicalendar.xp.XpMenuBar
import com.example.aicalendar.xp.XpMenuItem
import com.example.aicalendar.xp.XpMenuSeparator
import com.example.aicalendar.xp.XpMessageBox
import com.example.aicalendar.xp.XpPopupMenu
import com.example.aicalendar.xp.XpStartButton
import com.example.aicalendar.xp.XpSwatch
import com.example.aicalendar.xp.XpTabStrip
import com.example.aicalendar.xp.XpTaskbarDivider
import com.example.aicalendar.xp.XpTaskbarIconButton
import com.example.aicalendar.xp.XpTextField
import com.example.aicalendar.xp.XpTheme
import com.example.aicalendar.xp.XpVerticalScrollbar
import com.example.aicalendar.xp.XpTitleBar
import com.example.aicalendar.xp.XpTray
import com.example.aicalendar.xp.XpType
import com.example.aicalendar.xp.xpClickable
import com.example.aicalendar.xp.xpTaskbarBackground
import com.example.aicalendar.xp.xpTitleBackground
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import org.json.JSONObject
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class PlannerSection(val label: String) {
    LIFE("Life"), SCHOOL("School"), DELIVERIES("Deliveries"), ALL("All"), ARCHIVED("Archived");

    companion object {
        fun fromStored(value: String?): PlannerSection = entries.firstOrNull { it.name == value } ?: LIFE
    }
}

internal enum class BalloonKind { INFO, WARNING, ERROR }

/** A balloon tip shown above the tray: confirmations with Undo, and errors. */
internal data class BalloonMessage(
    val id: Long,
    val kind: BalloonKind,
    val title: String,
    val text: String = "",
    val actionLabel: String? = null,
    val action: (() -> Unit)? = null,
    /** Stays until it is dismissed or acted on (update prompts); the others close by themselves. */
    val sticky: Boolean = false,
)

/**
 * The balloon on screen and the ones waiting for it. An update prompt (sticky) stays until it is
 * used or closed: a passing balloon waits behind it instead of covering it, and shows after it.
 */
@Stable
internal class BalloonQueue {
    var shown by mutableStateOf<BalloonMessage?>(null)
        private set

    private val waiting = ArrayDeque<BalloonMessage>()

    fun show(message: BalloonMessage) {
        if (shown?.sticky == true && !message.sticky) {
            waiting.addLast(message)
            while (waiting.size > MAX_WAITING) waiting.removeFirst()
        } else {
            shown = message
        }
    }

    /** Closes the balloon [id] if it is the one shown; the next waiting one takes its place. */
    fun close(id: Long) {
        if (shown?.id == id) shown = waiting.removeFirstOrNull()
    }

    companion object {
        /** The newest few wait; older ones would only be noise by the time they show. */
        const val MAX_WAITING = 3
    }
}

private data class CourseOption(val key: String, val label: String, val color: Color)

private const val BALLOON_MILLIS = 7_000L
private const val ALLOW_UPDATES_TITLE = "Allow updates first"
private const val STATUS_MILLIS = 5_000L
private val dayHeaderFormatter = DateTimeFormatter.ofPattern("EEEE, MMMM d")
private val importTypes = arrayOf("application/json", "text/calendar", "text/*", "*/*")
private val assessmentDot = Color(0xFFE68B2C)

/** View choices kept on this phone: tab, list or calendar, class filter, dark mode. */
private const val VIEW_PREFERENCES = "planner_view"
private const val PREF_DARK_MODE = "dark_mode"

/** Whether the main pane shows Kitchen instead of the reminders, and which Kitchen tab. */
private const val PREF_KITCHEN = "kitchen"
private const val PREF_KITCHEN_TAB = "kitchen_tab"

class MainActivity : ComponentActivity() {
    private val resumeTick = mutableIntStateOf(0)
    private val openEventId = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // The title bar and taskbar sit behind the system bars and are deep colors in every palette,
        // so the system icons always stay light.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        // Chosen before the first frame, so a dark app never opens with a light flash.
        XpAppearance.dark = getSharedPreferences(VIEW_PREFERENCES, MODE_PRIVATE).getBoolean(PREF_DARK_MODE, false)
        NotificationHelper.createChannels(this)
        if (savedInstanceState == null) {
            reconcileInBackground(applicationContext)
            readOpenRequest(intent)
        }
        setContent {
            XpTheme {
                // The window itself shows through while the keyboard slides and during a resize.
                val face = XpColors.face.toArgb()
                SideEffect { window.setBackgroundDrawable(face.toDrawable()) }
                PlannerApp(
                    resumeTick = resumeTick.intValue,
                    openEventId = openEventId.value,
                    onOpenEventHandled = { openEventId.value = null },
                    onMinimize = { moveTaskToBack(true) },
                    onExit = { finish() },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readOpenRequest(intent)
    }

    override fun onResume() {
        super.onResume()
        resumeTick.intValue++
    }

    /** A tapped reminder notification asks the list to show that reminder. */
    private fun readOpenRequest(intent: Intent?) {
        val id = intent?.getStringExtra(NotificationHelper.EXTRA_OPEN_EVENT_ID)
        if (!id.isNullOrBlank()) openEventId.value = id
    }
}

/**
 * Re-arms every alarm (preserving snoozes) off the main thread. Recovers alarms the system dropped
 * after a force-stop or an exact-alarm permission change, and retries any undelivered phone changes.
 */
private fun reconcileInBackground(appContext: Context) {
    thread(name = "ai-calendar-reconcile") {
        runCatching {
            AlarmScheduler.scheduleAll(appContext, EventRepository(appContext).loadEvents())
            if (!PendingChangeStore(appContext).isEmpty() && BridgeSettings(appContext).isConnected()) {
                BridgeSyncWorker.enqueueOutbox(appContext)
            }
            // Kitchen edits still queued from an earlier run: their job sends them once there is a network.
            if (!KitchenStore(appContext).outbox.isEmpty() && BridgeSettings(appContext).isConnected()) {
                BridgeSyncWorker.enqueueKitchen(appContext)
            }
        }
    }
}

/** Schedule or clear alarms (and posted notifications) to match the event's state. */
private fun syncAlarms(context: Context, event: PlannerEvent) {
    if (event.done || event.archived) AlarmScheduler.cancelEvent(context, event.id)
    else AlarmScheduler.scheduleEvent(context, event)
}

@Composable
internal fun PlannerApp(
    resumeTick: Int,
    openEventId: String?,
    onOpenEventHandled: () -> Unit,
    onMinimize: () -> Unit,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()
    val repository = remember { EventRepository(appContext) }
    val pendingStore = remember { PendingChangeStore(appContext) }
    val viewPreferences = remember { context.getSharedPreferences(VIEW_PREFERENCES, Context.MODE_PRIVATE) }
    val listState = rememberLazyListState()

    var events by remember { mutableStateOf(repository.loadEvents()) }
    var editingEvent by remember { mutableStateOf<PlannerEvent?>(null) }
    var editorDate by remember { mutableStateOf<LocalDate?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var editorError by remember { mutableStateOf<String?>(null) }
    var exactAlarmReady by remember { mutableStateOf(AlarmScheduler.canScheduleExact(context)) }
    var notificationsReady by remember { mutableStateOf(canPostNotifications(context)) }
    var bridgeConnected by remember { mutableStateOf(BridgeSettings(context).isConnected()) }
    var statusText by remember { mutableStateOf<String?>(null) }
    var syncing by remember { mutableStateOf(false) }
    var syncFailed by remember { mutableStateOf(false) }
    var lastSyncMillis by remember { mutableLongStateOf(SyncStatusStore.lastSuccess(appContext)) }
    var syncIssue by remember { mutableStateOf(pendingStore.lastIssue()) }
    var pendingCount by remember { mutableIntStateOf(pendingStore.all().size) }
    var showBridgeSetup by remember { mutableStateOf(false) }
    var connectError by remember { mutableStateOf<String?>(null) }
    var showAbout by remember { mutableStateOf(false) }
    var showOptions by remember { mutableStateOf(false) }
    var hideConnectHint by rememberSaveable { mutableStateOf(false) }
    var plannerSection by remember { mutableStateOf(PlannerSection.fromStored(viewPreferences.getString("section", null))) }
    var calendarMode by remember { mutableStateOf(viewPreferences.getBoolean("calendar_mode", false)) }
    var visibleMonth by remember { mutableStateOf(YearMonth.now()) }
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }
    var selectedCourseKey by remember { mutableStateOf(viewPreferences.getString("school_course", null)) }
    var selectedEventId by remember { mutableStateOf<String?>(null) }
    var pendingScrollId by remember { mutableStateOf<String?>(null) }
    var searchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<PlannerEvent?>(null) }
    val seriesStore = remember { SeriesStore(appContext) }
    var seriesById by remember { mutableStateOf(seriesStore.load()) }
    // An occurrence of a repeating reminder about to be edited or deleted: asks "this one or all future".
    var scopeQuestion by remember { mutableStateOf<Pair<PlannerEvent, Boolean>?>(null) }
    var editorRepeatMode by remember { mutableStateOf(RepeatEditMode.NONE) }
    var editorRepeat by remember { mutableStateOf<RepeatChoice?>(null) }
    var updating by remember { mutableStateOf(false) }
    val balloons = remember { BalloonQueue() }
    val balloon = balloons.shown
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var kitchenOpen by remember { mutableStateOf(viewPreferences.getBoolean(PREF_KITCHEN, false)) }
    var kitchenTab by remember { mutableStateOf(KitchenTab.fromStored(viewPreferences.getString(PREF_KITCHEN_TAB, null))) }

    // Derived lists are recomputed only when their inputs change, not on every recomposition.
    val today = remember(nowMillis) { LocalDate.now() }
    val eventBuckets = remember(events, nowMillis) { bucketEvents(events, nowMillis) }
    val currentEvents = eventBuckets.current
    val archivedEvents = eventBuckets.archived
    val archivedView = plannerSection == PlannerSection.ARCHIVED
    // Find lists matches from every date, so it takes over from the month view while it is open.
    val viewToggleEnabled = !archivedView && !searchActive
    val showCalendar = calendarMode && viewToggleEnabled
    val schoolCourses = remember(currentEvents) { buildCourseOptions(currentEvents) }
    val eventsByDomain = remember(currentEvents) { currentEvents.groupBy { it.resolvedDomain() } }
    val lifeEvents = eventsByDomain[EventDomain.LIFE].orEmpty()
    val schoolEvents = eventsByDomain[EventDomain.SCHOOL].orEmpty()
    val deliveryEvents = eventsByDomain[EventDomain.DELIVERY].orEmpty()
    val sectionEvents = when (plannerSection) {
        PlannerSection.ARCHIVED -> archivedEvents
        PlannerSection.ALL -> currentEvents
        PlannerSection.SCHOOL -> schoolEvents
        PlannerSection.DELIVERIES -> deliveryEvents
        PlannerSection.LIFE -> lifeEvents
    }
    val searching = searchActive && searchQuery.isNotBlank()
    val visibleEvents = remember(sectionEvents, plannerSection, selectedCourseKey, searching, searchQuery) {
        var list = sectionEvents
        if (plannerSection == PlannerSection.SCHOOL && selectedCourseKey != null) {
            list = list.filter { it.courseKey() == selectedCourseKey }
        }
        if (searching) list = list.filter { it.matchesQuery(searchQuery) }
        list
    }
    val eventsByDate = remember(visibleEvents, showCalendar) {
        if (showCalendar) visibleEvents.groupBy(::eventDate) else emptyMap()
    }
    val dayEvents = eventsByDate[selectedDate].orEmpty()
    val entries: List<PlannerListEntry> = remember(visibleEvents, dayEvents, showCalendar, today, archivedView) {
        if (showCalendar) dayEvents.map { PlannerListEntry.Item(it) } else buildListEntries(visibleEvents, today, archived = archivedView)
    }

    fun showBalloon(
        kind: BalloonKind,
        title: String,
        text: String = "",
        actionLabel: String? = null,
        sticky: Boolean = false,
        action: (() -> Unit)? = null,
    ) {
        balloons.show(BalloonMessage(System.nanoTime(), kind, title, text, actionLabel, action, sticky))
    }

    // Kitchen talks to the bridge's kitchen routes only. It shares the status line and the balloon
    // with the reminders, and nothing else: no store, no outbox, no alarms.
    val kitchen = remember {
        KitchenController(
            scope = scope,
            store = KitchenStore(appContext),
            connected = { BridgeSettings(appContext).isConnected() },
            client = { BridgeApi.kitchenClient(appContext) },
            status = { statusText = it },
            balloon = { kind, title, text, actionLabel, action -> showBalloon(kind, title, text, actionLabel, action = action) },
            // The queue's first edit: a background job sends it once there is a network, app open or not.
            scheduleSend = { BridgeSyncWorker.enqueueKitchen(appContext) },
        )
    }

    /** Switches the main pane between the reminders and Kitchen; the choice is kept on this phone. */
    fun showKitchen(open: Boolean) {
        if (kitchenOpen == open) return
        kitchenOpen = open
        viewPreferences.edit { putBoolean(PREF_KITCHEN, open) }
        if (open) {
            searchActive = false
            searchQuery = ""
            selectedEventId = null
        }
    }

    fun selectKitchenTab(tab: KitchenTab) {
        kitchenTab = tab
        viewPreferences.edit { putString(PREF_KITCHEN_TAB, tab.name) }
    }

    fun refresh(updated: List<PlannerEvent> = repository.loadEvents()) {
        events = updated.sortedWith(compareBy<PlannerEvent> { it.startAtMillis }.thenBy { it.id })
        exactAlarmReady = AlarmScheduler.canScheduleExact(context)
        notificationsReady = canPostNotifications(context)
        bridgeConnected = BridgeSettings(context).isConnected()
        syncIssue = pendingStore.lastIssue()
        pendingCount = pendingStore.all().size
        lastSyncMillis = SyncStatusStore.lastSuccess(appContext)
        seriesById = seriesStore.load()
    }

    fun selectSection(section: PlannerSection) {
        if (plannerSection == section) return
        plannerSection = section
        selectedEventId = null
        viewPreferences.edit { putString("section", section.name) }
    }

    fun changeCalendarMode(enabled: Boolean) {
        calendarMode = enabled
        selectedEventId = null
        viewPreferences.edit { putBoolean("calendar_mode", enabled) }
        if (enabled) visibleMonth = YearMonth.from(selectedDate)
    }

    fun changeDarkMode(enabled: Boolean) {
        XpAppearance.dark = enabled
        viewPreferences.edit { putBoolean(PREF_DARK_MODE, enabled) }
    }

    fun selectCourse(key: String?) {
        selectedCourseKey = key
        viewPreferences.edit {
            if (key == null) remove("school_course") else putString("school_course", key)
        }
    }

    /** Switch to wherever [event] is listed, select it and scroll it into view. */
    fun revealEvent(event: PlannerEvent) {
        showKitchen(false)
        val target = when {
            event.isFiledAt(System.currentTimeMillis()) -> PlannerSection.ARCHIVED
            plannerSection == PlannerSection.ALL -> PlannerSection.ALL
            else -> when (event.resolvedDomain()) {
                EventDomain.SCHOOL -> PlannerSection.SCHOOL
                EventDomain.DELIVERY -> PlannerSection.DELIVERIES
                else -> PlannerSection.LIFE
            }
        }
        selectSection(target)
        if (target == PlannerSection.SCHOOL && selectedCourseKey != null && event.courseKey() != selectedCourseKey) {
            selectCourse(null)
        }
        if (searchActive && !event.matchesQuery(searchQuery)) searchQuery = ""
        if (calendarMode && target != PlannerSection.ARCHIVED) {
            selectedDate = eventDate(event)
            visibleMonth = YearMonth.from(selectedDate)
        }
        selectedEventId = event.id
        pendingScrollId = event.id
    }

    fun applyEvent(event: PlannerEvent) {
        val updated = repository.upsert(event)
        syncAlarms(appContext, event)
        refresh(updated)
    }

    fun persistEvent(event: PlannerEvent, closeEditor: Boolean = false, reveal: Boolean = false) {
        if (!event.remote) {
            applyEvent(event)
            // A reminder made on this phone goes to the cloud calendar too, for the other devices.
            // The outbox uploads it as it is when it is sent (or later, when the cloud is reachable).
            if (bridgeConnected) BridgeSyncWorker.enqueueOutbox(appContext)
            if (closeEditor) showEditor = false
            if (reveal) revealEvent(event)
            return
        }
        statusText = "Saving..."
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                BridgeApi.saveRemoteEvent(appContext, event).also { outcome ->
                    if (outcome == RemoteWriteOutcome.Saved || outcome == RemoteWriteOutcome.Queued) {
                        syncAlarms(appContext, event)
                    }
                    if (outcome == RemoteWriteOutcome.Queued) BridgeSyncWorker.enqueueOutbox(appContext)
                }
            }
            refresh()
            when (outcome) {
                RemoteWriteOutcome.Saved -> {
                    statusText = "Saved to cloud"
                    if (closeEditor) showEditor = false
                    if (reveal) revealEvent(event)
                }
                RemoteWriteOutcome.Queued -> {
                    statusText = "Saved on phone"
                    if (closeEditor) showEditor = false
                    if (reveal) revealEvent(event)
                    showBalloon(BalloonKind.INFO, "Saved on this phone", "It will sync when the cloud is reachable.")
                }
                is RemoteWriteOutcome.Rejected -> {
                    statusText = "Not saved"
                    if (closeEditor && showEditor) {
                        editorError = outcome.message
                    } else {
                        showBalloon(BalloonKind.ERROR, "Couldn't save", outcome.message)
                    }
                }
                is RemoteWriteOutcome.Failed -> {
                    statusText = "Not saved"
                    if (closeEditor && showEditor) {
                        editorError = outcome.message
                    } else {
                        showBalloon(BalloonKind.ERROR, "Couldn't save", outcome.message)
                    }
                }
            }
        }
    }

    fun setManualArchive(event: PlannerEvent, archived: Boolean) {
        val updated = repository.setArchived(event.id, archived)
        val changed = updated.firstOrNull { it.id == event.id } ?: return
        syncAlarms(appContext, changed)
        refresh(updated)
    }

    /**
     * Done or not done. A cloud reminder sends only that (never the rest of this phone's copy, which
     * may be older than a change made on another device).
     */
    fun setDone(event: PlannerEvent, done: Boolean) {
        if (!event.remote) {
            applyEvent(event.copy(done = done))
            return
        }
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                BridgeApi.setRemoteDone(appContext, event, done).also { outcome ->
                    if (outcome == RemoteWriteOutcome.Queued) BridgeSyncWorker.enqueueOutbox(appContext)
                    EventRepository(appContext).find(event.id)?.let { syncAlarms(appContext, it) }
                }
            }
            refresh()
            when (outcome) {
                RemoteWriteOutcome.Saved -> statusText = if (done) "Marked done" else "Marked not done"
                RemoteWriteOutcome.Queued -> statusText = "Saved on phone"
                is RemoteWriteOutcome.Rejected -> showBalloon(BalloonKind.ERROR, "Couldn't save", outcome.message)
                is RemoteWriteOutcome.Failed -> showBalloon(BalloonKind.ERROR, "Couldn't save", outcome.message)
            }
        }
    }

    fun restoreEvent(event: PlannerEvent) {
        setManualArchive(event, false)
        if (event.done) setDone(event, false)
        statusText = "Restored"
    }

    fun toggleDone(event: PlannerEvent) {
        val done = !event.done
        setDone(event, done)
        if (done) {
            if (!archivedView && selectedEventId == event.id) selectedEventId = null
            showBalloon(BalloonKind.INFO, "Marked done", event.title, "Undo") { setDone(event, false) }
        }
    }

    fun archiveEvent(event: PlannerEvent) {
        setManualArchive(event, true)
        if (selectedEventId == event.id) selectedEventId = null
        showBalloon(BalloonKind.INFO, "Archived", event.title, "Undo") { setManualArchive(event, false) }
    }

    fun performDelete(shown: PlannerEvent) {
        // The dialog may have been open while this phone's reminder was uploaded: act on what is
        // stored now, so an uploaded reminder is deleted in the cloud too and does not come back.
        val event = repository.find(shown.id) ?: shown
        if (selectedEventId == event.id) selectedEventId = null
        if (!event.remote) {
            AlarmScheduler.cancelEvent(appContext, event.id)
            refresh(repository.deletePermanently(event))
            statusText = "Deleted"
            return
        }
        statusText = "Deleting..."
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                BridgeApi.deleteRemoteEvent(appContext, event).also { outcome ->
                    if (outcome == RemoteWriteOutcome.Saved || outcome == RemoteWriteOutcome.Queued) {
                        AlarmScheduler.cancelEvent(appContext, event.id)
                    }
                    if (outcome == RemoteWriteOutcome.Queued) BridgeSyncWorker.enqueueOutbox(appContext)
                }
            }
            refresh()
            when (outcome) {
                RemoteWriteOutcome.Saved -> statusText = "Deleted"
                RemoteWriteOutcome.Queued -> {
                    statusText = "Deleted on phone"
                    showBalloon(BalloonKind.INFO, "Deleted on this phone", "The cloud copy is removed at the next sync.")
                }
                is RemoteWriteOutcome.Rejected -> {
                    statusText = "Not deleted"
                    showBalloon(BalloonKind.ERROR, "Couldn't delete", outcome.message)
                }
                is RemoteWriteOutcome.Failed -> {
                    statusText = "Not deleted"
                    showBalloon(BalloonKind.ERROR, "Couldn't delete", outcome.message)
                }
            }
        }
    }

    /**
     * Sends a change to a repeating reminder. The bridge makes the occurrences; a successful change
     * syncs at once, so they are listed and armed before this returns.
     */
    fun sendSeries(op: PendingOp, seriesId: String, body: JSONObject?, from: String? = null, closeEditor: Boolean = false) {
        statusText = if (op == PendingOp.SERIES_DELETE) "Deleting..." else "Saving..."
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                BridgeApi.sendSeriesChange(appContext, op, seriesId, body, from).also { outcome ->
                    if (outcome == RemoteWriteOutcome.Queued) BridgeSyncWorker.enqueueOutbox(appContext)
                }
            }
            refresh()
            val failure = when (outcome) {
                RemoteWriteOutcome.Saved -> {
                    statusText = if (op == PendingOp.SERIES_DELETE) "Deleted" else "Saved to cloud"
                    if (closeEditor) showEditor = false
                    null
                }
                RemoteWriteOutcome.Queued -> {
                    statusText = "Saved on phone"
                    if (closeEditor) showEditor = false
                    showBalloon(
                        BalloonKind.INFO,
                        if (op == PendingOp.SERIES_DELETE) "Deleted on this phone" else "Waiting for the cloud",
                        if (op == PendingOp.SERIES_CREATE) "The repeating reminder is listed once the cloud is reachable." else "It will sync when the cloud is reachable.",
                    )
                    null
                }
                is RemoteWriteOutcome.Rejected -> outcome.message
                is RemoteWriteOutcome.Failed -> outcome.message
            }
            if (failure != null) {
                statusText = if (op == PendingOp.SERIES_DELETE) "Not deleted" else "Not saved"
                if (closeEditor && showEditor) editorError = failure
                else showBalloon(BalloonKind.ERROR, if (op == PendingOp.SERIES_DELETE) "Couldn't delete" else "Couldn't save", failure)
            }
        }
    }

    fun saveFromEditor(event: PlannerEvent, choice: RepeatChoice?) {
        val zone = ZoneId.systemDefault()
        val key = UUID.randomUUID().toString()
        val occurrence = editingEvent
        when {
            editorRepeatMode == RepeatEditMode.SERIES && occurrence != null && choice != null ->
                sendSeries(PendingOp.SERIES_UPDATE, occurrence.seriesId, SeriesRequests.update(event, occurrence, choice, zone, key), closeEditor = true)
            choice != null ->
                sendSeries(PendingOp.SERIES_CREATE, "", SeriesRequests.create(event, choice, zone, key), closeEditor = true)
            else -> persistEvent(event, closeEditor = true, reveal = true)
        }
    }

    fun openEditor(event: PlannerEvent?, mode: RepeatEditMode = if (event == null) RepeatEditMode.NEW else RepeatEditMode.NONE) {
        editorRepeatMode = mode
        editorRepeat = event?.takeIf { mode == RepeatEditMode.SERIES }
            ?.let { seriesById[it.seriesId] }
            ?.let { RepeatChoice.from(it.rule) }
        editingEvent = event
        // A new reminder started from the calendar view begins on the day being looked at.
        editorDate = if (event == null && showCalendar && !selectedDate.isBefore(today)) selectedDate else null
        editorError = null
        showEditor = true
    }

    /** Edit: an occurrence of a repeating reminder first asks whether the change is for this one or all future ones. */
    fun requestEdit(event: PlannerEvent) {
        if (event.isOccurrence && event.remote) scopeQuestion = event to false else openEditor(event)
    }

    fun requestDelete(event: PlannerEvent) {
        if (event.isOccurrence && event.remote) scopeQuestion = event to true else pendingDelete = event
    }

    /** Downloads the published build, verifies it and hands it to Android, which asks to confirm. */
    fun installUpdate(release: AppRelease) {
        if (!AppUpdater.canInstall(context)) {
            // Coming back with the permission granted continues this install (see the resume effect).
            AppUpdater.setPendingInstall(appContext, release)
            showBalloon(
                BalloonKind.INFO,
                ALLOW_UPDATES_TITLE,
                "Turn on \"Allow from this source\" for AI Planner, then come back here.",
                "Settings",
                sticky = true,
            ) { runCatching { context.startActivity(AppUpdater.allowInstallsIntent(context)) } }
            return
        }
        AppUpdater.clearPendingInstall(appContext)
        if (updating) return
        updating = true
        statusText = "Downloading update..."
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { AppUpdater.install(appContext, BridgeApi.downloadRelease(appContext, release)) }
            }
            updating = false
            result.onSuccess { statusText = "Installing update..." }
                .onFailure { error ->
                    statusText = "Update failed"
                    showBalloon(BalloonKind.ERROR, "Couldn't update", error.message ?: "The update could not be downloaded.")
                }
        }
    }

    /** Asks the bridge for a newer build. [manual] (Help menu) also says when there is none. */
    fun checkForUpdate(manual: Boolean) {
        if (!bridgeConnected) {
            if (manual) showBalloon(BalloonKind.INFO, "Not connected", "Connect to the cloud to get updates.")
            return
        }
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { BridgeApi.latestRelease(appContext) } }
            AppUpdater.recordCheck(appContext)
            result.onSuccess { release ->
                when {
                    release != null && AppUpdater.isNewer(appContext, release) -> showBalloon(
                        BalloonKind.INFO,
                        "Update available",
                        "AI Planner ${release.versionName} is ready to install.",
                        "Install",
                        sticky = true,
                    ) { installUpdate(release) }
                    manual -> showBalloon(BalloonKind.INFO, "No update", "You have the latest version, ${appVersionName(context)}.")
                }
            }.onFailure { error ->
                if (manual) showBalloon(BalloonKind.ERROR, "Couldn't check for updates", error.message ?: "The cloud could not be reached.")
            }
        }
    }

    fun runSync(label: String, quiet: Boolean, action: (Context) -> Result<Int>, success: (Int) -> String) {
        if (syncing) return
        statusText = label
        syncing = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { action(appContext) }
            syncing = false
            refresh()
            result.onSuccess { count ->
                syncFailed = false
                statusText = success(count)
            }.onFailure { failure ->
                syncFailed = true
                statusText = "Sync failed"
                if (!quiet) showBalloon(BalloonKind.ERROR, "Sync failed", failure.message ?: "The cloud could not be reached.")
            }
        }
    }

    fun syncNow() = runSync("Syncing...", quiet = false, BridgeApi::sync) { "Synced" }

    fun syncCanvas() = runSync("Syncing Canvas...", quiet = false, BridgeApi::syncCanvas) { count -> "Canvas: $count active" }

    // The clock ticks on the minute so the tray time and the Today/overdue buckets stay exact.
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L - System.currentTimeMillis() % 60_000L + 40L)
            nowMillis = System.currentTimeMillis()
        }
    }

    // Returning to the app: pick up permission changes and anything written while in the background.
    LaunchedEffect(resumeTick) {
        if (resumeTick > 1) {
            nowMillis = System.currentTimeMillis()
            refresh()
        }
        // Back from "Allow from this source" with the permission granted: carry on with that install,
        // if it is still newer than the app (it may have been installed meanwhile) and the phone is
        // still paired. Otherwise it is forgotten.
        val waiting = AppUpdater.pendingInstall(appContext)
        if (waiting != null) {
            val step = AppUpdater.resumeStep(
                newer = AppUpdater.isNewer(appContext, waiting),
                connected = bridgeConnected,
                canInstall = AppUpdater.canInstall(context),
            )
            if (step != AppUpdater.ResumeStep.WAIT) {
                balloons.shown?.takeIf { it.title == ALLOW_UPDATES_TITLE }?.let { balloons.close(it.id) }
                if (step == AppUpdater.ResumeStep.FORGET) AppUpdater.clearPendingInstall(appContext)
            }
            if (step == AppUpdater.ResumeStep.INSTALL) {
                installUpdate(waiting)
                return@LaunchedEffect
            }
        }
        // An update Android refused is reported once; otherwise look for a newer build now and then.
        val updateError = AppUpdater.takeLastError(appContext)
        if (updateError != null) {
            showBalloon(BalloonKind.ERROR, "Update not installed", updateError)
        } else if (bridgeConnected && AppUpdater.dueForCheck(appContext)) {
            checkForUpdate(manual = false)
        }
    }

    // Observe the stores so FCM syncs, notification actions and outbox delivery update the open list.
    val storeListener = remember {
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == EventRepository.KEY_EVENTS || key.startsWith("entries") || key == PendingChangeStore.KEY_ISSUE) {
                refresh()
            }
        }
    }
    DisposableEffect(Unit) {
        repository.preferences.registerOnSharedPreferenceChangeListener(storeListener)
        val pendingPrefs = appContext.getSharedPreferences(PendingChangeStore.FILE, Context.MODE_PRIVATE)
        pendingPrefs.registerOnSharedPreferenceChangeListener(storeListener)
        onDispose {
            repository.preferences.unregisterOnSharedPreferenceChangeListener(storeListener)
            pendingPrefs.unregisterOnSharedPreferenceChangeListener(storeListener)
        }
    }

    LaunchedEffect(bridgeConnected) {
        if (bridgeConnected) runSync("Syncing...", quiet = true, BridgeApi::sync) { "Synced" }
    }

    // The copy of the kitchen kept on the phone and its queue of edits, read off the main thread.
    LaunchedEffect(kitchen) { kitchen.restore() }

    // Kitchen is fetched when it is shown, when the app comes back, after pairing and when the date
    // changes while it is on screen (Tonight and the week move on). Each good fetch also sends the
    // queued kitchen edits.
    LaunchedEffect(kitchenOpen, resumeTick, bridgeConnected, today) {
        if (kitchenOpen) kitchen.refresh()
    }

    // And on a kitchen_sync push, but only while Kitchen is on screen with the app in the foreground.
    val lifecycleOwner = LocalLifecycleOwner.current
    var foreground by remember { mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { source, _ ->
            foreground = source.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // While Kitchen is on screen it also watches the network: when one comes back after a failed
    // try, it fetches (and sends the queue) at once. The watch exists only while Kitchen is live.
    DisposableEffect(kitchenOpen, foreground) {
        val listener: () -> Unit = { kitchen.refresh() }
        val live = kitchenOpen && foreground
        if (live) KitchenSync.setLiveListener(listener)
        val stopWatching = if (live) KitchenSync.watchNetwork(appContext) { kitchen.retryIfOffline() } else null
        onDispose {
            KitchenSync.clearLiveListener(listener)
            stopWatching?.invoke()
        }
    }

    // Offline with Kitchen on screen: try again after 30 s, 60 s, 120 s, 240 s, then every 5 minutes.
    val kitchenUnreachable = kitchen.state.offline || kitchen.state.loadError != null
    LaunchedEffect(kitchenOpen, foreground, kitchenUnreachable) {
        if (!kitchenOpen || !foreground || !kitchenUnreachable) return@LaunchedEffect
        var attempt = 0
        while (true) {
            delay(KitchenRetry.delayMillis(attempt++))
            kitchen.retryIfOffline()
        }
    }

    LaunchedEffect(schoolCourses, selectedCourseKey) {
        if (selectedCourseKey != null && schoolCourses.none { it.key == selectedCourseKey }) selectCourse(null)
    }

    // Progress and confirmations in the status area fade back to the item count.
    LaunchedEffect(statusText, syncing) {
        if (statusText != null && !syncing) {
            delay(STATUS_MILLIS)
            statusText = null
        }
    }

    LaunchedEffect(balloon?.id) {
        val shown = balloon ?: return@LaunchedEffect
        // An update prompt waits for the user: it timed out before the Install tap landed.
        if (shown.sticky) return@LaunchedEffect
        delay(BALLOON_MILLIS)
        balloons.close(shown.id)
    }

    // A tapped notification opens its reminder.
    LaunchedEffect(openEventId) {
        val id = openEventId ?: return@LaunchedEffect
        events.firstOrNull { it.id == id }?.let(::revealEvent)
        onOpenEventHandled()
    }

    LaunchedEffect(plannerSection, showCalendar) {
        if (pendingScrollId == null) listState.scrollToItem(0)
    }

    LaunchedEffect(pendingScrollId, entries) {
        val id = pendingScrollId ?: return@LaunchedEffect
        val index = entries.indexOfFirst { it is PlannerListEntry.Item && it.event.id == id }
        if (index >= 0) {
            // In calendar view two items (month grid, day caption) come first; otherwise step back one
            // row so the group caption above the reminder stays visible.
            val target = if (showCalendar) index + 2 else (index - 1).coerceAtLeast(0)
            listState.animateScrollToItem(target)
        }
        pendingScrollId = null
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        notificationsReady = canPostNotifications(context)
    }

    fun requestNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            openNotificationSettings(context)
            return
        }
        // After a second refusal Android stops showing the prompt; go to the settings page instead.
        val activity = context as? Activity
        val askedBefore = viewPreferences.getBoolean("asked_notifications", false)
        val canAskAgain = activity != null &&
            ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS)
        if (askedBefore && !canAskAgain) {
            openNotificationSettings(context)
        } else {
            viewPreferences.edit { putBoolean("asked_notifications", true) }
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun requestExactAlarms() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).setData("package:${context.packageName}".toUri()),
                )
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val (result, updated) = withContext(Dispatchers.IO) {
                    val parsed = importEvents(appContext, uri)
                    val stored = if (parsed.events.isNotEmpty()) {
                        repository.upsertAll(parsed.events).also { AlarmScheduler.scheduleAll(appContext, it) }
                    } else {
                        null
                    }
                    parsed to stored
                }
                updated?.let(::refresh)
                if (updated != null && bridgeConnected) BridgeSyncWorker.enqueueOutbox(appContext)
                val count = result.events.size
                showBalloon(
                    kind = if (count > 0 && result.warnings.isEmpty()) BalloonKind.INFO else BalloonKind.WARNING,
                    title = if (count == 0) "Nothing imported" else "Imported $count reminder${if (count == 1) "" else "s"}",
                    text = result.warnings.joinToString("; "),
                )
            }
        }
    }

    BackHandler(enabled = selectedEventId != null || searchActive) {
        if (selectedEventId != null) {
            selectedEventId = null
        } else {
            searchActive = false
            searchQuery = ""
        }
    }

    BackHandler(enabled = kitchenOpen) { showKitchen(false) }

    val menus = listOf(
        XpMenu("File") { dismiss ->
            XpMenuItem("New Reminder...", { dismiss(); openEditor(null) }, icon = XpIcons.New)
            XpMenuItem("Import from File...", { dismiss(); importLauncher.launch(importTypes) }, icon = XpIcons.Import)
            XpMenuSeparator()
            XpMenuItem("Exit", { dismiss(); onExit() })
        },
        XpMenu("View") { dismiss ->
            XpMenuItem("Reminders", { dismiss(); showKitchen(false) }, checked = !kitchenOpen, radio = true)
            XpMenuItem("Kitchen", { dismiss(); showKitchen(true) }, checked = kitchenOpen, radio = true)
            XpMenuSeparator()
            XpMenuItem("List", { dismiss(); changeCalendarMode(false) }, enabled = viewToggleEnabled && !kitchenOpen, checked = !showCalendar, radio = true)
            XpMenuItem("Calendar", { dismiss(); changeCalendarMode(true) }, enabled = viewToggleEnabled && !kitchenOpen, checked = showCalendar, radio = true)
            XpMenuSeparator()
            if (kitchenOpen) {
                XpMenuItem("Refresh Kitchen", { dismiss(); kitchen.refresh(manual = true) }, icon = XpIcons.Sync, enabled = bridgeConnected)
            } else {
                XpMenuItem("Find...", { dismiss(); searchActive = true }, icon = XpIcons.Find)
            }
        },
        XpMenu("Tools") { dismiss ->
            XpMenuItem("Sync Now", { dismiss(); syncNow() }, icon = XpIcons.Sync, enabled = bridgeConnected && !syncing)
            XpMenuItem("Sync Canvas", { dismiss(); syncCanvas() }, icon = XpIcons.School, enabled = bridgeConnected && !syncing)
            XpMenuSeparator()
            XpMenuItem("Cloud Connection...", { dismiss(); connectError = null; showBridgeSetup = true }, icon = XpIcons.Cloud)
            XpMenuItem("Notification Settings...", { dismiss(); openNotificationSettings(context) }, icon = XpIcons.Bell)
            XpMenuSeparator()
            XpMenuItem("Options...", { dismiss(); showOptions = true })
        },
        XpMenu("Help") { dismiss ->
            XpMenuItem("Check for Updates...", { dismiss(); checkForUpdate(manual = true) }, icon = XpIcons.Sync, enabled = !updating)
            XpMenuSeparator()
            XpMenuItem("About AI Planner", { dismiss(); showAbout = true }, icon = XpIcons.Info)
        },
    )

    val shownCount = if (showCalendar) dayEvents.size else visibleEvents.size
    val kitchenSnapshot = kitchen.state.snapshot
    val toBuy = kitchenSnapshot?.openItems?.size
    val statusLine = statusText ?: if (kitchenOpen) {
        when {
            toBuy == null -> if (kitchen.state.loading) "Loading..." else "Kitchen"
            toBuy == 0 -> "Nothing to buy"
            else -> "$toBuy to buy"
        }
    } else buildString {
        append(shownCount)
        append(
            when {
                searching -> " found"
                plannerSection == PlannerSection.DELIVERIES -> if (shownCount == 1) " delivery" else " deliveries"
                shownCount == 1 -> " reminder"
                else -> " reminders"
            },
        )
        if (pendingCount > 0) append(", ").append(pendingCount).append(" unsent")
    }
    val trayIcon = when {
        syncing -> XpIcons.Sync
        !bridgeConnected -> XpIcons.CloudOff
        syncIssue != null || syncFailed || pendingCount > 0 -> XpIcons.CloudWarn
        else -> XpIcons.CloudOk
    }
    val trayLabel = when {
        syncing -> "Syncing"
        !bridgeConnected -> "Cloud: not connected"
        syncIssue != null || syncFailed || pendingCount > 0 -> "Cloud: needs attention"
        else -> "Cloud: connected"
    }

    Box(Modifier.fillMaxSize().background(XpColors.face)) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
            Column(Modifier.fillMaxWidth().xpTitleBackground()) {
                Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
                XpTitleBar(
                    if (kitchenOpen) "Kitchen - AI Planner" else "AI Planner",
                    icon = if (kitchenOpen) XpIcons.Kitchen else XpIcons.App,
                    onMinimize = onMinimize,
                    showMaximize = true,
                    onClose = onExit,
                )
            }
            // Kitchen sits on the menu bar's row, labeled, so it is found without opening a menu:
            // a sixth tab does not fit beside the reminder tabs at phone width. The same button
            // leads back to the reminders.
            XpMenuBar(
                menus,
                trailing = {
                    XpButton(
                        if (kitchenOpen) "Reminders" else "Kitchen",
                        onClick = { showKitchen(!kitchenOpen) },
                        modifier = Modifier.padding(start = 6.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
                        icon = if (kitchenOpen) XpIcons.App else XpIcons.Kitchen,
                        minWidth = 0.dp,
                        minHeight = 34.dp,
                    )
                },
            )
            if (kitchenOpen) {
                XpTabStrip(
                    tabs = KitchenTab.entries.map { tab ->
                        if (tab == KitchenTab.LIST && toBuy != null) "${tab.label} ($toBuy)" else tab.label
                    },
                    selectedIndex = kitchenTab.ordinal,
                    onSelect = { selectKitchenTab(KitchenTab.entries[it]) },
                )
            } else {
                XpTabStrip(
                    tabs = listOf(
                        "Life (${lifeEvents.size})",
                        "School (${schoolEvents.size})",
                        "Deliveries (${deliveryEvents.size})",
                        "All (${currentEvents.size})",
                        "Archived",
                    ),
                    selectedIndex = plannerSection.ordinal,
                    onSelect = { selectSection(PlannerSection.entries[it]) },
                )
            }
            if (!notificationsReady) {
                XpInfoBar("Notifications are off, so reminders cannot alert you. Tap to allow them.", XpIcons.Warning, onClick = ::requestNotifications)
            }
            if (!exactAlarmReady) {
                XpInfoBar("Exact alarms are not allowed; critical reminders may arrive late. Tap to fix.", XpIcons.Warning, onClick = ::requestExactAlarms)
            }
            if (!bridgeConnected && !hideConnectHint) {
                XpInfoBar(
                    "Not connected to the cloud. Tap to pair this phone.",
                    XpIcons.CloudOff,
                    onClick = {
                        connectError = null
                        showBridgeSetup = true
                    },
                    onDismiss = { hideConnectHint = true },
                )
            }
            syncIssue?.let { issue ->
                XpInfoBar(
                    issue,
                    XpIcons.Warning,
                    onDismiss = {
                        pendingStore.clearIssue()
                        syncIssue = null
                    },
                )
            }
            if (!kitchenOpen && plannerSection == PlannerSection.SCHOOL && schoolCourses.isNotEmpty()) {
                ClassFilterRow(schoolCourses, selectedCourseKey, ::selectCourse)
            }
            if (searchActive && !kitchenOpen) {
                FindRow(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    onClose = {
                        searchActive = false
                        searchQuery = ""
                    },
                )
            }

            Box(Modifier.weight(1f).fillMaxWidth().background(XpColors.window)) {
                if (kitchenOpen) {
                    KitchenPane(
                        controller = kitchen,
                        tab = kitchenTab,
                        connected = bridgeConnected,
                        today = today,
                        nowMillis = nowMillis,
                        onConnect = {
                            connectError = null
                            showBridgeSetup = true
                        },
                    )
                } else ReminderList(
                    listState = listState,
                    entries = entries,
                    nowMillis = nowMillis,
                    today = today,
                    archivedView = archivedView,
                    selectedEventId = selectedEventId,
                    calendar = if (showCalendar) {
                        CalendarPane(
                            month = visibleMonth,
                            selectedDate = selectedDate,
                            eventsByDate = eventsByDate,
                            onSelectDate = {
                                selectedDate = it
                                selectedEventId = null
                            },
                            onMonthChange = { month ->
                                visibleMonth = month
                                selectedDate = if (month == YearMonth.from(today)) today else month.atDay(1)
                                selectedEventId = null
                            },
                        )
                    } else {
                        null
                    },
                    seriesById = seriesById,
                    emptyMessage = {
                        when {
                            searching -> EmptyListMessage(
                                "No reminders match \"${searchQuery.trim()}\".",
                                listOf("Clear the search" to { searchQuery = "" }),
                            )
                            archivedView -> EmptyListMessage("No archived reminders.")
                            showCalendar -> EmptyListMessage(
                                "Nothing on this day.",
                                listOf("Add a reminder" to { openEditor(null) }),
                            )
                            plannerSection == PlannerSection.DELIVERIES -> EmptyListMessage(
                                "No deliveries on the way.",
                                listOf("Add a delivery" to { openEditor(null) }),
                            )
                            else -> EmptyListMessage(
                                "No upcoming reminders.",
                                listOf(
                                    "Create a new reminder" to { openEditor(null) },
                                    "Import from a file" to { importLauncher.launch(importTypes) },
                                ),
                            )
                        }
                    },
                    onSelect = { event -> selectedEventId = if (selectedEventId == event.id) null else event.id },
                    onToggleDone = ::toggleDone,
                    onEdit = ::requestEdit,
                    onDelete = ::requestDelete,
                    onArchive = ::archiveEvent,
                    onRestore = ::restoreEvent,
                )
                balloon?.let { message ->
                    XpBalloon(
                        title = message.title,
                        text = message.text,
                        icon = when (message.kind) {
                            BalloonKind.INFO -> XpIcons.Info
                            BalloonKind.WARNING -> XpIcons.Warning
                            BalloonKind.ERROR -> XpIcons.Error
                        },
                        onDismiss = { balloons.close(message.id) },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 6.dp).width(300.dp),
                        actionLabel = message.actionLabel,
                        onAction = message.action?.let { action ->
                            {
                                balloons.close(message.id)
                                action()
                            }
                        },
                        tailFromEnd = 78.dp,
                    )
                }
            }

            PlannerTaskbar(
                statusLine = statusLine,
                clock = formatTime(nowMillis),
                searchActive = searchActive,
                showCalendar = showCalendar,
                viewToggleEnabled = viewToggleEnabled,
                syncing = syncing,
                trayIcon = trayIcon,
                trayLabel = trayLabel,
                kitchen = kitchenOpen,
                kitchenRefreshEnabled = bridgeConnected && !kitchen.state.loading,
                onNew = { openEditor(null) },
                onToggleFind = {
                    if (searchActive) searchQuery = ""
                    searchActive = !searchActive
                },
                onToggleView = { changeCalendarMode(!calendarMode) },
                onRefreshKitchen = { kitchen.refresh(manual = true) },
                onOpenConnection = {
                    connectError = null
                    showBridgeSetup = true
                },
            )
        }

        if (kitchenOpen) {
            KitchenDinnerDialog(kitchen)
            KitchenOnHandActions(kitchen)
        }

        if (showEditor) {
            EventEditorDialog(
                initial = editingEvent,
                newDate = editorDate,
                newDomain = when {
                    kitchenOpen -> EventDomain.LIFE
                    plannerSection == PlannerSection.SCHOOL -> EventDomain.SCHOOL
                    plannerSection == PlannerSection.DELIVERIES -> EventDomain.DELIVERY
                    else -> EventDomain.LIFE
                },
                saveError = editorError,
                onDismiss = {
                    showEditor = false
                    editorError = null
                },
                onSave = { event, choice ->
                    editorError = null
                    saveFromEditor(event, choice)
                },
                repeatMode = editorRepeatMode,
                initialRepeat = editorRepeat,
                canRepeat = bridgeConnected,
            )
        }

        scopeQuestion?.let { (event, deleting) ->
            SeriesScopeDialog(
                title = if (deleting) "Delete Repeating Reminder" else "Edit Repeating Reminder",
                eventTitle = event.title,
                action = if (deleting) "Delete" else "Edit",
                onDismiss = { scopeQuestion = null },
                onChoose = { choice ->
                    scopeQuestion = null
                    when {
                        // The choice was the confirmation: its default button says Delete.
                        choice == SeriesScope.THIS_ONE && deleting -> performDelete(event)
                        choice == SeriesScope.THIS_ONE -> openEditor(event)
                        seriesById[event.seriesId] == null && !deleting -> showBalloon(
                            BalloonKind.ERROR,
                            "Not synced yet",
                            "The pattern of this repeating reminder has not reached the phone. Sync, then try again.",
                        )
                        deleting -> {
                            if (selectedEventId == event.id) selectedEventId = null
                            sendSeries(PendingOp.SERIES_DELETE, event.seriesId, null, from = event.occurrenceDate.ifBlank { null })
                        }
                        else -> openEditor(event, RepeatEditMode.SERIES)
                    }
                },
            )
        }

        pendingDelete?.let { event ->
            XpMessageBox(
                title = "Delete Reminder",
                message = if (event.remote) {
                    "Delete \"${event.title}\" from this phone and the cloud? It will not come back on the next sync. This cannot be undone."
                } else {
                    "Delete \"${event.title}\" from this phone? This cannot be undone."
                },
                icon = XpIcons.Warning,
                onDismiss = { pendingDelete = null },
                buttons = {
                    XpButton(
                        "Delete",
                        onClick = {
                            pendingDelete = null
                            performDelete(event)
                        },
                    )
                    XpButton("Cancel", onClick = { pendingDelete = null }, default = true)
                },
            )
        }

        if (showBridgeSetup) {
            ConnectionDialog(
                initial = remember { BridgeSettings(context).config() },
                connected = bridgeConnected,
                busy = syncing,
                lastSyncMillis = lastSyncMillis,
                pendingCount = pendingCount,
                issue = syncIssue,
                error = connectError,
                onDismissIssue = {
                    pendingStore.clearIssue()
                    syncIssue = null
                },
                onSyncNow = ::syncNow,
                onSyncCanvas = ::syncCanvas,
                onDismiss = { showBridgeSetup = false },
                onConnect = { serverUrl, pairingCode ->
                    connectError = null
                    statusText = "Connecting..."
                    syncing = true
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { BridgeApi.connect(appContext, serverUrl, pairingCode) }
                        syncing = false
                        result.onSuccess {
                            refresh()
                            bridgeConnected = true
                            syncFailed = false
                            statusText = "Connected"
                            showBridgeSetup = false
                        }.onFailure {
                            connectError = it.message ?: "Connection failed"
                            statusText = "Connection failed"
                        }
                    }
                },
            )
        }

        if (showOptions) {
            OptionsDialog(
                darkMode = XpColors.dark,
                onDarkModeChange = ::changeDarkMode,
                onDismiss = { showOptions = false },
            )
        }

        if (showAbout) {
            AboutDialog(
                versionName = remember { appVersionName(context) },
                upcomingCount = currentEvents.size,
                archivedCount = archivedEvents.size,
                notificationsReady = notificationsReady,
                exactAlarmReady = exactAlarmReady,
                connected = bridgeConnected,
                lastSyncMillis = lastSyncMillis,
                onDismiss = { showAbout = false },
            )
        }
    }
}

/** Everything the month view needs; null while the plain list is shown. */
private class CalendarPane(
    val month: YearMonth,
    val selectedDate: LocalDate,
    val eventsByDate: Map<LocalDate, List<PlannerEvent>>,
    val onSelectDate: (LocalDate) -> Unit,
    val onMonthChange: (YearMonth) -> Unit,
)

/** The scrolling area: optional month calendar, then the grouped reminder rows, with the scroll bar. */
@Composable
private fun ReminderList(
    listState: LazyListState,
    entries: List<PlannerListEntry>,
    nowMillis: Long,
    today: LocalDate,
    archivedView: Boolean,
    selectedEventId: String?,
    calendar: CalendarPane?,
    seriesById: Map<String, ReminderSeries>,
    emptyMessage: @Composable () -> Unit,
    onSelect: (PlannerEvent) -> Unit,
    onToggleDone: (PlannerEvent) -> Unit,
    onEdit: (PlannerEvent) -> Unit,
    onDelete: (PlannerEvent) -> Unit,
    onArchive: (PlannerEvent) -> Unit,
    onRestore: (PlannerEvent) -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 20.dp),
        ) {
            if (calendar != null) {
                item(key = "month", contentType = "month") {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(XpColors.face)
                            .drawBehind {
                                val line = 1.dp.toPx()
                                drawRect(XpColors.shadow, topLeft = Offset(0f, size.height - line), size = Size(size.width, line))
                            }
                            .padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 10.dp),
                    ) {
                        XpMonthGrid(
                            month = calendar.month,
                            selectedDate = calendar.selectedDate,
                            today = today,
                            onSelectDate = calendar.onSelectDate,
                            onMonthChange = calendar.onMonthChange,
                            marks = { date -> calendar.eventsByDate[date]?.let(::dayMarksFor) },
                        )
                    }
                }
                item(key = "day", contentType = "header") {
                    GroupHeaderRow(calendar.selectedDate.format(dayHeaderFormatter), entries.size)
                }
            }
            if (entries.isEmpty()) {
                item(key = "empty", contentType = "empty") { emptyMessage() }
            }
            items(
                entries,
                key = { it.key },
                contentType = { if (it is PlannerListEntry.Header) "header" else "row" },
            ) { entry ->
                when (entry) {
                    is PlannerListEntry.Header -> GroupHeaderRow(entry.group.label, entry.count)
                    is PlannerListEntry.Item -> {
                        val event = entry.event
                        val row: @Composable () -> Unit = {
                            EventRow(
                                event = event,
                                nowMillis = nowMillis,
                                today = today,
                                selected = selectedEventId == event.id,
                                showDate = calendar == null,
                                archivedView = archivedView,
                                onSelect = { onSelect(event) },
                                onToggleDone = { onToggleDone(event) },
                                onEdit = { onEdit(event) },
                                onDelete = { onDelete(event) },
                                onArchive = if (archivedView) null else ({ onArchive(event) }),
                                onRestore = if (archivedView && (event.archived || event.done)) ({ onRestore(event) }) else null,
                                repeatText = seriesById[event.seriesId]?.let { RepeatText.describe(it.rule) },
                            )
                        }
                        if (archivedView) row() else SwipeToArchive(onArchive = { onArchive(event) }, content = row)
                    }
                }
            }
        }
        XpVerticalScrollbar(listState, Modifier.align(Alignment.CenterEnd))
    }
}

/** Dots under a calendar day: quizzes/exams orange, critical reminders red, the rest by class or category. */
private fun dayMarksFor(dayEvents: List<PlannerEvent>): DayMarks = DayMarks(
    dots = dayEvents.take(3).map { event ->
        when {
            event.assessmentKind() != null -> assessmentDot
            event.critical -> XpColors.today
            else -> event.accentColor()
        }
    },
    assessment = dayEvents.any { it.assessmentKind() != null },
)

/**
 * Bottom bar in the taskbar style: green New button, quick-launch icons, a status line, and a tray
 * with the cloud state and the clock. It also covers the system navigation bar and the keyboard.
 */
@Composable
private fun PlannerTaskbar(
    statusLine: String,
    clock: String,
    searchActive: Boolean,
    showCalendar: Boolean,
    viewToggleEnabled: Boolean,
    syncing: Boolean,
    trayIcon: ImageVector,
    trayLabel: String,
    kitchen: Boolean,
    kitchenRefreshEnabled: Boolean,
    onNew: () -> Unit,
    onToggleFind: () -> Unit,
    onToggleView: () -> Unit,
    onRefreshKitchen: () -> Unit,
    onOpenConnection: () -> Unit,
) {
    // The spin lives here so each animation frame redraws only the taskbar.
    val trayRotation = if (syncing) {
        val spin = rememberInfiniteTransition(label = "sync")
        val angle by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "angle")
        angle
    } else {
        0f
    }
    Column(Modifier.fillMaxWidth().xpTaskbarBackground()) {
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            XpStartButton("New", onClick = onNew, icon = XpIcons.Sparkle)
            Spacer(Modifier.width(4.dp))
            if (kitchen) {
                // Find and the calendar belong to the reminders; Kitchen has Refresh instead.
                XpTaskbarIconButton(XpIcons.Sync, label = "Refresh kitchen", onClick = onRefreshKitchen, enabled = kitchenRefreshEnabled)
            } else {
                XpTaskbarIconButton(XpIcons.Find, label = "Find", onClick = onToggleFind, active = searchActive)
                XpTaskbarIconButton(
                    if (showCalendar) XpIcons.ViewList else XpIcons.ViewCalendar,
                    label = if (showCalendar) "List view" else "Calendar view",
                    onClick = onToggleView,
                    enabled = viewToggleEnabled,
                )
            }
            XpTaskbarDivider()
            Text(
                statusLine,
                modifier = Modifier.weight(1f).padding(horizontal = 5.dp),
                style = XpType.Small,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            XpTray {
                Box(
                    Modifier
                        .size(width = 38.dp, height = 44.dp)
                        .semantics { contentDescription = trayLabel }
                        .xpClickable(role = Role.Button, onClick = onOpenConnection),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(trayIcon, contentDescription = null, modifier = Modifier.size(24.dp).rotate(trayRotation))
                }
                Text(clock, style = XpType.Small, color = Color.White, maxLines = 1)
            }
        }
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars.union(WindowInsets.ime)))
    }
}

@Composable
private fun ClassFilterRow(courses: List<CourseOption>, selectedKey: String?, onSelect: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selected = courses.firstOrNull { it.key == selectedKey }
    val allColor = Color(EventCategory.SCHOOL.colorArgb)
    Row(
        Modifier
            .fillMaxWidth()
            .background(XpColors.face)
            .drawBehind {
                val line = 1.dp.toPx()
                drawRect(XpColors.shadow, topLeft = Offset(0f, size.height - line), size = Size(size.width, line))
            }
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Class:", style = XpType.Small)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            XpComboBox(
                selected?.label ?: "All classes",
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
                leading = { XpSwatch(selected?.color ?: allColor) },
            )
            XpPopupMenu(expanded = expanded, onDismiss = { expanded = false }) {
                XpMenuItem(
                    "All classes",
                    onClick = {
                        onSelect(null)
                        expanded = false
                    },
                    leading = { XpSwatch(allColor) },
                )
                courses.forEach { course ->
                    XpMenuItem(
                        course.label,
                        onClick = {
                            onSelect(course.key)
                            expanded = false
                        },
                        leading = { XpSwatch(course.color) },
                    )
                }
            }
        }
    }
}

@Composable
private fun FindRow(query: String, onQueryChange: (String) -> Unit, onClose: () -> Unit) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    Row(
        Modifier
            .fillMaxWidth()
            .background(XpColors.face)
            .drawBehind {
                val line = 1.dp.toPx()
                drawRect(XpColors.shadow, topLeft = Offset(0f, size.height - line), size = Size(size.width, line))
            }
            .padding(start = 10.dp, top = 5.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Find:", style = XpType.Small)
        Spacer(Modifier.width(8.dp))
        XpTextField(
            query,
            onQueryChange,
            modifier = Modifier.weight(1f),
            placeholder = "Title, place, notes or class",
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            focusRequester = focusRequester,
        )
        XpCloseGlyph("Close find", onClose)
    }
}

/** One filter entry per Canvas class, labeled with the short course code where that is unambiguous. */
private fun buildCourseOptions(events: List<PlannerEvent>): List<CourseOption> {
    val raw = events
        .filter { it.isCanvas() && it.resolvedDomain() == EventDomain.SCHOOL }
        .mapNotNull { event ->
            event.courseKey().takeIf(String::isNotBlank)?.let { key -> key to event.courseDisplayName() }
        }
        .distinctBy { it.first }
    val short = raw.map { compactCourseLabel(it.second) }
    val ambiguous = short.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    return raw.mapIndexed { index, (key, label) ->
        CourseOption(key, if (short[index] in ambiguous) label else short[index], courseColorForKey(key))
    }.sortedBy { it.label.lowercase() }
}

private const val MAX_IMPORT_BYTES = 5 * 1024 * 1024

/** Runs on a background thread. Size-capped so a wrong file choice cannot exhaust memory. */
private fun importEvents(context: Context, uri: Uri): ImportResult = runCatching {
    val text = context.contentResolver.openInputStream(uri)?.use { stream ->
        val bytes = stream.readNBytesCompat(MAX_IMPORT_BYTES + 1)
        require(bytes.size <= MAX_IMPORT_BYTES) { "File is larger than 5 MB" }
        String(bytes, Charsets.UTF_8)
    } ?: error("File could not be opened")
    EventImportParser.parseDetailed(text)
}.getOrElse { ImportResult(emptyList(), listOf(it.message ?: "File could not be read")) }

private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    while (out.size() < limit) {
        val read = read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (read < 0) break
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}

/** True when the app may post notifications (permission on Android 13+, and not switched off in settings). */
private fun canPostNotifications(context: Context): Boolean =
    NotificationManagerCompat.from(context).areNotificationsEnabled()

private fun openNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

private fun appVersionName(context: Context): String =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
