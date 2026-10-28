package com.example.aicalendar

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.aicalendar.xp.XpButton
import com.example.aicalendar.xp.XpCheckBox
import com.example.aicalendar.xp.XpCheckMark
import com.example.aicalendar.xp.XpCloseGlyph
import com.example.aicalendar.xp.XpColors
import com.example.aicalendar.xp.XpDialog
import com.example.aicalendar.xp.XpEtchedLine
import com.example.aicalendar.xp.XpGroupBox
import com.example.aicalendar.xp.XpIcons
import com.example.aicalendar.xp.XpInfoBar
import com.example.aicalendar.xp.XpLink
import com.example.aicalendar.xp.XpRadioButton
import com.example.aicalendar.xp.XpTextField
import com.example.aicalendar.xp.XpType
import com.example.aicalendar.xp.XpVerticalScrollbar
import com.example.aicalendar.xp.xpClickable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// The Kitchen view: tonight's dinner and the week, the shopping list, the pantry. A view of the
// bridge's kitchen routes with an offline copy and a queue of edits (KitchenStore). It never
// touches reminders or alarms.

internal enum class KitchenTab(val label: String) {
    // PANTRY keeps its stored name; the tab is On hand since 0.19.0.
    WEEK("This week"), LIST("Shopping list"), PANTRY("On hand");

    companion object {
        fun fromStored(value: String?): KitchenTab = entries.firstOrNull { it.name == value } ?: WEEK
    }
}

/** What the Kitchen view shows: the last snapshot (from the cloud or the phone's copy) and the queued edits on top. */
@Stable
internal class KitchenState {
    /** The last snapshot from the cloud (or the cache), with the edits this phone sent since. */
    var base by mutableStateOf<KitchenSnapshot?>(null)

    /** When [base] was fetched. */
    var fetchedAt by mutableStateOf<Instant?>(null)

    /** Edits waiting in the outbox; they are shown on top of [base]. */
    var pending by mutableStateOf<List<KitchenEdit>>(emptyList())

    /** The cloud could not be reached on the last try. */
    var offline by mutableStateOf(false)

    /** What is shown: [base] with the waiting edits applied. */
    val snapshot: KitchenSnapshot? by derivedStateOf { base?.let { KitchenOverlay.apply(it, pending) } }

    var loading by mutableStateOf(false)

    /** Why there is nothing to show; null once there is. */
    var loadError by mutableStateOf<String?>(null)

    /** Edits on their way to the cloud, by item id; their controls wait for the answer. */
    var busy by mutableStateOf(emptySet<String>())

    /** The dinner whose details are open. */
    var openDay by mutableStateOf<LocalDate?>(null)
    var noteError by mutableStateOf<String?>(null)

    /** The Tell Claude box: its text, and the item it is about (Something else…). */
    var noteDraft by mutableStateOf(TextFieldValue(""))
    var noteItem by mutableStateOf<PantryItem?>(null)

    /** Counts requests to put the keyboard in the Tell Claude box. */
    var noteFocus by mutableIntStateOf(0)

    /** The estimate row whose actions are open. */
    var actionsFor by mutableStateOf<PantryItem?>(null)

    /** On hand: every correction instead of the newest three, and only what is running low or out. */
    var showAllCorrections by mutableStateOf(false)
    var attentionOnly by mutableStateOf(false)
}

/**
 * Loads the kitchen and sends edits. Online, an edit goes straight to the cloud and the view shows
 * what the cloud returns; a refusal leaves the view as it was and says why. When the cloud can't be
 * reached, the edit is queued (KitchenStore's outbox), shown at once, and sent oldest first after
 * the next good fetch. While edits are queued, new ones queue behind them, so the order holds.
 * [scheduleSend] asks for the background job that sends the queue when it gets its first edit.
 */
internal class KitchenController(
    private val scope: CoroutineScope,
    private val store: KitchenStore,
    private val connected: () -> Boolean,
    private val client: () -> KitchenClient,
    private val status: (String) -> Unit,
    private val balloon: (kind: BalloonKind, title: String, text: String, actionLabel: String?, action: (() -> Unit)?) -> Unit,
    private val scheduleSend: () -> Unit = {},
) {
    val state = KitchenState()

    /** Revision of the last whole snapshot shown; edits applied since then may carry a newer one. */
    private var fetchedRevision = -1L
    private var fetchAgain = false

    /** The phone's copy and queue are read off the main thread ([restore]); until then an edit queues. */
    private var restored = false
    private var restoring = false

    /** Counts the times [KitchenState.pending] was set, so the late read at start never undoes a newer one. */
    private var pendingSets = 0

    /**
     * Reads the copy kept on the phone and the queue, off the main thread, once (the screen calls it
     * from an effect, never while composing). A fetch that lands first wins over the copy.
     */
    fun restore() {
        if (restoring) return
        restoring = true
        val asOf = pendingSets
        scope.launch {
            val (cached, queued) = withContext(Dispatchers.IO) { store.cached() to store.outbox.all() }
            if (state.base == null && cached != null) {
                state.base = cached.first
                state.fetchedAt = cached.second
                fetchedRevision = cached.first.revision
                // A fetch that failed before the copy was read: show the copy, offline.
                if (state.loadError != null) {
                    state.loadError = null
                    state.offline = true
                }
            }
            if (pendingSets == asOf) state.pending = queued
            restored = true
        }
    }

    private fun setPending(edits: List<KitchenEdit>) {
        pendingSets++
        state.pending = edits
    }

    /**
     * Fetches the kitchen; with edits queued, sends them oldest first and fetches again. At most
     * one fetch runs; a request made during it runs once after it, so a burst of `kitchen_sync`
     * signals costs two fetches at most. [manual] (Refresh) reports progress in the status line.
     */
    fun refresh(manual: Boolean = false) {
        if (state.loading) {
            fetchAgain = true
            return
        }
        if (!connected()) {
            if (state.base == null) state.loadError = KITCHEN_OFFLINE else state.offline = true
            return
        }
        state.loading = true
        if (manual) status("Refreshing kitchen...")
        scope.launch {
            val (round, refusedMeanwhile) = withContext(Dispatchers.IO) {
                KitchenRound.run(store.outbox, client(), KitchenSync::flush) to store.takeRejected()
            }
            val at = Instant.now()
            state.loading = false
            setPending(round.queued)
            val report = round.report
            when (val first = round.first) {
                is KitchenResult.Ok -> {
                    val again = round.again
                    if (again != null) {
                        accept(again, at)
                    } else {
                        accept(first.value, at)
                        // Sent, but the fresh copy did not arrive: keep what the cloud answered,
                        // so the sent edits never show (or come back after a restart) in their old state.
                        if (round.answers.isNotEmpty()) updateBase { base -> round.answers.fold(base) { shown, answer -> answer.applyTo(shown) } }
                    }
                    state.loadError = null
                    state.offline = report?.stopped == true
                    if (manual) status(if (state.offline) "Kitchen changes not sent" else "Kitchen up to date")
                }
                is KitchenResult.Failed -> {
                    if (state.base == null) state.loadError = first.message else state.offline = true
                    if (manual) status("Kitchen not updated")
                }
            }
            val refused = report?.rejected.orEmpty() + refusedMeanwhile
            if (refused.isNotEmpty()) {
                balloon(
                    BalloonKind.ERROR,
                    if (refused.size == 1) "A kitchen change was refused" else "${refused.size} kitchen changes were refused",
                    refused.joinToString("\n"),
                    null,
                    null,
                )
            }
            if (fetchAgain) {
                fetchAgain = false
                refresh()
            }
        }
    }

    /** The network came back, or the offline retry is due: try again if the last try failed. */
    fun retryIfOffline() {
        if ((state.offline || state.loadError != null) && !state.loading) refresh()
    }

    /** Takes [next] when [KitchenRevisions] says so, and keeps it on the phone. */
    private fun accept(next: KitchenSnapshot, at: Instant) {
        state.fetchedAt = at
        if (KitchenRevisions.accepts(state.base?.revision, fetchedRevision, next.revision)) {
            state.base = next
            fetchedRevision = next.revision
            store.saveCacheLater(next, at)
        }
    }

    private fun updateBase(change: (KitchenSnapshot) -> KitchenSnapshot) {
        val current = state.base ?: return
        val next = change(current)
        state.base = next
        store.saveCacheLater(next, state.fetchedAt ?: Instant.now())
    }

    /** An edit as the outbox keeps it. */
    private class Edit(val op: KitchenOp, val target: String, val fields: Map<String, String>, val label: String)

    private fun queue(edit: Edit, target: String) {
        val first = store.outbox.isEmpty()
        store.outbox.enqueue(edit.op, target, edit.fields, edit.label, Instant.now().toString())
        setPending(store.outbox.all())
        // The queue just got its first edit: the background job sends it once there is a network,
        // even if the app is closed first.
        if (first) scheduleSend()
        status("Saved on this phone")
    }

    private fun <T> change(
        key: String,
        edit: Edit,
        failTitle: String,
        direct: (KitchenClient, String) -> KitchenResult<T>,
        onQueued: () -> Unit,
        onFailed: (KitchenResult.Failed) -> Unit = { balloon(BalloonKind.ERROR, failTitle, it.message, null, null) },
        onSaved: (T) -> Unit,
    ) {
        if (key in state.busy) return
        val target = store.outbox.resolve(edit.target)
        val mustQueue = !restored || state.offline || state.pending.isNotEmpty() || !connected() || (!edit.op.isAdd && isLocalKitchenId(target))
        if (mustQueue) {
            queue(edit, target)
            onQueued()
            // Online with older edits waiting: send them all now, in order.
            if (!state.offline && connected()) refresh()
            return
        }
        state.busy = state.busy + key
        status("Saving...")
        scope.launch {
            val result = withContext(Dispatchers.IO) { direct(client(), target) }
            state.busy = state.busy - key
            when (result) {
                is KitchenResult.Ok -> onSaved(result.value)
                is KitchenResult.Failed -> when {
                    // Weak signal: keep it on the phone and send it later.
                    result.connection -> {
                        state.offline = true
                        queue(edit, target)
                        onQueued()
                    }
                    else -> {
                        status("Not saved")
                        onFailed(result)
                        if (result.notFound) refresh()
                    }
                }
            }
        }
    }

    /**
     * Adds [name] to the list. [restore] is Undo after a remove: the line comes back with that
     * status and [orderRef] (bridge 0.11.0; an older bridge puts it back as need).
     */
    fun addItem(
        name: String,
        qty: String = "",
        note: String = "",
        restore: ListStatus? = null,
        orderRef: String = "",
        onAdded: () -> Unit = {},
    ) {
        val clean = name.trim().replace(Regex("\\s+"), " ")
        if (clean.isEmpty()) return
        val listed = state.snapshot?.openItems?.any { KitchenText.sameName(it.name, clean) } == true
        val fields = mapOf("name" to clean, "qty" to qty.trim(), "note" to note.trim()) +
            (restore?.let { mapOf("restore" to "true", "status" to it.wire, "orderRef" to orderRef) } ?: emptyMap())
        change(
            key = KEY_ADD,
            edit = Edit(KitchenOp.ADD_ITEM, newLocalKitchenId(), fields, clean),
            failTitle = "Couldn't add",
            direct = { api, _ -> api.addItem(clean, qty, note, restore, orderRef) },
            onQueued = {
                onAdded()
                if (listed && restore?.isOpen != false) balloon(BalloonKind.INFO, "Already on the list", clean, null, null)
            },
        ) { answer ->
            updateBase(KitchenAnswer.ListItem(answer)::applyTo)
            onAdded()
            when {
                answer.created && restore != null -> status("Put back")
                answer.created -> status("Added ${answer.item.name}")
                else -> {
                    status("Already on the list")
                    balloon(BalloonKind.INFO, "Already on the list", answer.item.name, null, null)
                }
            }
        }
    }

    /** Ticks [item] as bought, or puts it back on the list. */
    fun setGot(item: KitchenListItem, got: Boolean) {
        val target = if (got) ListStatus.GOT else ListStatus.NEED
        change(
            key = item.id,
            edit = Edit(KitchenOp.ITEM_STATUS, item.id, mapOf("status" to target.wire), item.name),
            failTitle = "Couldn't save",
            direct = { api, id -> api.setItemStatus(id, target) },
            onQueued = {},
        ) { answer ->
            updateBase(KitchenAnswer.ListItem(answer)::applyTo)
            status(if (got) "Got ${item.name}" else "Back on the list")
            // Buying something restocks the pantry on the server (rule 3): show that too.
            refresh()
        }
    }

    /** Removes [item]; Undo puts it back as it was: on order with its order, or bought. */
    fun remove(item: KitchenListItem) {
        val undo = { addItem(item.name, item.qty, item.note, restore = item.status, orderRef = item.orderRef) }
        change(
            key = item.id,
            edit = Edit(KitchenOp.REMOVE_ITEM, item.id, emptyMap(), item.name),
            failTitle = "Couldn't remove",
            direct = { api, id -> api.removeItem(id) },
            onQueued = { balloon(BalloonKind.INFO, "Removed from the list", item.name, "Undo", undo) },
        ) { revision ->
            updateBase(KitchenAnswer.ListItemRemoved(item.id, revision)::applyTo)
            status("Removed")
            balloon(BalloonKind.INFO, "Removed from the list", item.name, "Undo", undo)
        }
    }

    /** A new correction's local id, and its clientId: the same on every send of it. */
    private fun newCorrection(): Pair<String, String> {
        val local = newLocalKitchenId()
        return local to local.removePrefix(LOCAL_ID_PREFIX)
    }

    /** We're out / We have it: applied at once in the cloud; the reply says what happened. */
    fun correct(item: PantryItem, kind: CorrectionKind) {
        val title = if (kind == CorrectionKind.OUT) "We're out: ${item.name}" else "We have it: ${item.name}"
        val (local, clientId) = newCorrection()
        change(
            key = item.id,
            edit = Edit(
                KitchenOp.CORRECTION,
                local,
                mapOf("kind" to kind.wire, KitchenOutbox.KEY_ITEM_ID to item.id, "itemName" to item.name, KitchenOutbox.KEY_CLIENT_ID to clientId),
                title,
            ),
            failTitle = "Couldn't send",
            direct = { api, _ -> api.addCorrection(kind, "", item.id, clientId) },
            onQueued = { balloon(BalloonKind.INFO, title, "Saved on this phone. It is sent when you're back online.", null, null) },
        ) { answer ->
            // The item, and the list line it added (out) or took off (have, bridge 0.11.0).
            updateBase(KitchenAnswer.Corrected(answer)::applyTo)
            status(if (kind == CorrectionKind.OUT) "Marked out" else "Marked as on hand")
            val fallback = if (kind == CorrectionKind.OUT) "Marked out." else "Marked as on hand."
            balloon(BalloonKind.INFO, title, answer.correction.reply.ifBlank { fallback }, null, null)
        }
    }

    /** A note for Claude from the Tell Claude box, maybe about [item]. [onSent] clears the box. */
    fun sendNote(text: String, item: PantryItem?, onSent: () -> Unit) {
        val clean = text.trim().replace(Regex("\\s+"), " ")
        if (clean.isEmpty()) return
        val (local, clientId) = newCorrection()
        val fields = mapOf("kind" to CorrectionKind.NOTE.wire, "text" to clean, KitchenOutbox.KEY_CLIENT_ID to clientId) +
            (item?.let { mapOf(KitchenOutbox.KEY_ITEM_ID to it.id, "itemName" to it.name) } ?: emptyMap())
        change(
            key = KEY_NOTE,
            edit = Edit(KitchenOp.CORRECTION, local, fields, "Note to Claude: \"${clean.take(40)}\""),
            failTitle = "Couldn't send",
            direct = { api, _ -> api.addCorrection(CorrectionKind.NOTE, clean, item?.id, clientId) },
            onQueued = onSent,
        ) { answer ->
            updateBase(KitchenAnswer.Corrected(answer)::applyTo)
            onSent()
            status("Sent to Claude")
        }
    }

    /** Takes back an open note; one that never left the phone is just dropped from the queue. */
    fun withdraw(correction: Correction) {
        change(
            key = correction.id,
            edit = Edit(KitchenOp.WITHDRAW_CORRECTION, correction.id, emptyMap(), "Withdraw: \"${correction.text.take(40)}\""),
            failTitle = "Couldn't withdraw",
            direct = { api, id -> api.withdrawCorrection(id) },
            onQueued = { status("Withdrawn") },
            onFailed = { failed ->
                if (failed.notFound) {
                    balloon(BalloonKind.INFO, "Already answered", "Claude has handled this note, so it can't be withdrawn now.", null, null)
                } else {
                    balloon(BalloonKind.ERROR, "Couldn't withdraw", failed.message, null, null)
                }
            },
        ) { revision ->
            updateBase(KitchenAnswer.Withdrawn(correction.id, revision)::applyTo)
            status("Withdrawn")
        }
    }

    /** Saves how a dinner went. The details stay open with the reason when the cloud refuses it. */
    fun saveNote(date: LocalDate, note: DinnerNote) {
        state.noteError = null
        change(
            key = noteKey(date),
            edit = Edit(
                KitchenOp.NOTE,
                date.toString(),
                mapOf("cooked" to note.cooked.wire, "rating" to note.rating.wire, "note" to note.note.trim()),
                "How dinner went on ${date.format(weekdayFormat)} ${date.format(monthDayFormat)}",
            ),
            failTitle = "Couldn't save",
            direct = { api, _ -> api.saveNote(date, note) },
            onFailed = { state.noteError = it.message },
            onQueued = { if (state.openDay == date) state.openDay = null },
        ) { answer ->
            updateBase(KitchenAnswer.Note(answer)::applyTo)
            status("Saved")
            if (state.openDay == date) state.openDay = null
        }
    }

    fun isBusy(key: String): Boolean = key in state.busy

    companion object {
        const val KEY_ADD = "add"
        const val KEY_NOTE = "tell-claude"
        fun noteKey(date: LocalDate) = "note:$date"
    }
}

// ------------------------------------------------------------------ the pane

@Composable
internal fun KitchenPane(
    controller: KitchenController,
    tab: KitchenTab,
    connected: Boolean,
    today: LocalDate,
    nowMillis: Long,
    onConnect: () -> Unit,
) {
    val state = controller.state
    val snapshot = state.snapshot
    val loadError = state.loadError
    Column(Modifier.fillMaxSize().background(XpColors.window)) {
        if (snapshot != null && state.offline) {
            XpInfoBar(
                KitchenOverlay.offlineText(state.fetchedAt, state.pending.size, now = Instant.ofEpochMilli(nowMillis)),
                XpIcons.CloudOff,
                onClick = { controller.refresh(manual = true) },
            )
        }
        when {
            snapshot == null && !connected -> KitchenMessage(
                KITCHEN_OFFLINE,
                icon = XpIcons.CloudOff,
                actions = listOf("Connect this phone" to onConnect),
            )
            snapshot == null && loadError != null -> KitchenMessage(
                loadError,
                icon = XpIcons.CloudWarn,
                actions = listOf("Try again" to { controller.refresh(manual = true) }),
            )
            snapshot == null -> KitchenMessage("Loading the kitchen...")
            else -> when (tab) {
                KitchenTab.WEEK -> WeekTab(snapshot, today, onOpen = { controller.state.openDay = it })
                KitchenTab.LIST -> ShoppingTab(controller, snapshot, nowMillis)
                KitchenTab.PANTRY -> OnHandTab(controller, snapshot)
            }
        }
    }
}

@Composable
private fun KitchenMessage(
    title: String,
    detail: String = "",
    icon: ImageVector = XpIcons.Kitchen,
    actions: List<Pair<String, () -> Unit>> = emptyList(),
) {
    Column(
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 40.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(icon, contentDescription = null, modifier = Modifier.size(44.dp))
        Spacer(Modifier.height(10.dp))
        Text(title, style = XpType.Body, color = XpColors.text, textAlign = TextAlign.Center)
        if (detail.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(detail, style = XpType.Small, color = XpColors.textDim, textAlign = TextAlign.Center)
        }
        actions.forEach { (label, action) -> XpLink(label, action) }
    }
}

/** Bold blue caption with a fading rule, like the reminder list's groups. */
@Composable
private fun KitchenHeader(label: String, detail: String = "") {
    Column(
        Modifier
            .fillMaxWidth()
            .background(XpColors.window)
            .padding(start = 12.dp, end = 20.dp, top = 12.dp, bottom = 5.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(label, style = XpType.BodyBold.copy(fontSize = 14.sp), color = XpColors.headerText)
            if (detail.isNotBlank()) {
                Spacer(Modifier.width(6.dp))
                Text(detail, style = XpType.Small, color = XpColors.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(3.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(Brush.horizontalGradient(listOf(XpColors.headerRule, XpColors.headerRule.copy(alpha = 0f)))),
        )
    }
}

@Composable
private fun RowDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(XpColors.divider))
}

/** `**bold**` drawn bold. */
@Composable
private fun rich(text: String): AnnotatedString = remember(text) {
    buildAnnotatedString {
        KitchenText.boldRuns(text).forEach { run ->
            if (run.bold) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(run.text) } else append(run.text)
        }
    }
}

/** A small tag in the balloon colors, like the quiz badge. */
@Composable
private fun KitchenTag(text: String) {
    Text(
        text.uppercase(),
        modifier = Modifier.background(XpColors.tagFill).border(1.dp, XpColors.tagBorder).padding(horizontal = 5.dp, vertical = 1.dp),
        style = XpType.Tiny.copy(fontWeight = FontWeight.Bold),
        color = XpColors.tagText,
        maxLines = 1,
    )
}

/** The day's own note ("Thaw 1 lb chicken tonight") in the balloon color. */
@Composable
private fun DayNote(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier
            .background(XpColors.tooltip)
            .border(1.dp, XpColors.tagBorder)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(XpIcons.Clock, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(rich(text), style = XpType.Small, color = XpColors.tooltipText)
    }
}

private val weekdayFormat = DateTimeFormatter.ofPattern("EEE")
private val monthDayFormat = DateTimeFormatter.ofPattern("M/d")
private val rangeFormat = DateTimeFormatter.ofPattern("MMM d")
private val longDayFormat = DateTimeFormatter.ofPattern("EEEE, MMM d")

private fun weekRange(plan: MealPlan): String = when {
    plan.start != null && plan.end != null -> "${plan.start.format(rangeFormat)} – ${plan.end.format(rangeFormat)}"
    else -> KitchenText.stripBold(plan.title)
}

private fun findDay(snapshot: KitchenSnapshot, date: LocalDate): Pair<MealPlan, MealDay>? =
    listOfNotNull(snapshot.week, snapshot.nextWeek).firstNotNullOfOrNull { plan ->
        plan.days.firstOrNull { it.date == date }?.let { plan to it }
    }

// ------------------------------------------------------------------ this week

@Composable
private fun WeekTab(snapshot: KitchenSnapshot, today: LocalDate, onOpen: (LocalDate) -> Unit) {
    val plan = remember(snapshot, today) { KitchenDays.currentPlan(snapshot, today) }
    if (plan == null) {
        KitchenMessage(
            "No meal plan yet.",
            "Claude publishes the week's meal plan when it does the Monday grocery order.",
        )
        return
    }
    val tonight = KitchenDays.tonight(plan, today)
    val others = KitchenDays.otherDays(plan, today)
    val following = KitchenDays.followingPlan(snapshot, plan)
    val listState = rememberLazyListState()
    val planLabel = when {
        plan.covers(today) -> "This week"
        plan.start?.isAfter(today) == true -> "Next week"
        else -> "Last week"
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 20.dp)) {
            if (tonight != null) {
                item(key = "tonight-header") { KitchenHeader("Tonight", tonight.date.format(longDayFormat)) }
                item(key = "tonight") {
                    TonightCard(tonight, plan.recipeFor(tonight), snapshot.noteFor(tonight.date)) { onOpen(tonight.date) }
                }
            }
            if (others.isNotEmpty()) {
                item(key = "week-header") { KitchenHeader(planLabel, weekRange(plan)) }
                items(others, key = { "day-${it.date}" }) { day ->
                    MealDayRow(day, snapshot.noteFor(day.date), past = day.date.isBefore(today)) { onOpen(day.date) }
                }
            }
            if (plan.tagline.isNotBlank() || plan.meta.isNotEmpty() || plan.boxes.isNotEmpty()) {
                item(key = "about-header") { KitchenHeader("About this week") }
                item(key = "about") { PlanAbout(plan) }
            }
            if (following != null) {
                item(key = "next-header") { KitchenHeader("Next week", weekRange(following)) }
                items(following.days, key = { "next-${it.date}" }) { day ->
                    MealDayRow(day, snapshot.noteFor(day.date), past = false) { onOpen(day.date) }
                }
            }
        }
        XpVerticalScrollbar(listState, Modifier.align(Alignment.CenterEnd))
    }
}

@Composable
private fun TonightCard(day: MealDay, recipe: Recipe?, note: DinnerNote, onOpen: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(XpColors.window)) {
        Row(
            Modifier
                .fillMaxWidth()
                .xpClickable(role = Role.Button, onClickLabel = "Open tonight's dinner", onClick = onOpen)
                .drawBehind { drawRect(XpColors.tabAccent, size = Size(5.dp.toPx(), size.height)) }
                .padding(start = 17.dp, end = 20.dp, top = 8.dp, bottom = 12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Image(XpIcons.Kitchen, contentDescription = null, modifier = Modifier.padding(top = 2.dp).size(36.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(rich(day.dinner.ifBlank { recipe?.title.orEmpty() }), style = XpType.BodyBold.copy(fontSize = 18.sp, lineHeight = 23.sp))
                if (day.base.isNotBlank()) Text(rich("with ${day.base}"), style = XpType.Small, color = XpColors.textDim)
                if (recipe?.sub?.isNotBlank() == true) Text(rich(recipe.sub), style = XpType.Small, color = XpColors.textDim)
                if (day.kind != DinnerKind.COOK) KitchenTag(if (day.kind == DinnerKind.LEFTOVERS) "Leftovers" else "Special")
                if (day.note.isNotBlank()) DayNote(day.note, Modifier.padding(top = 2.dp))
                if (note.summary.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(XpIcons.Check, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(note.summary, style = XpType.SmallBold)
                    }
                }
                XpButton(
                    if (recipe != null) "Recipe" else "Details",
                    onClick = onOpen,
                    icon = if (recipe != null) XpIcons.OpenLink else XpIcons.Info,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        RowDivider()
    }
}

@Composable
private fun MealDayRow(day: MealDay, note: DinnerNote, past: Boolean, onOpen: () -> Unit) {
    val ink = if (past) XpColors.textDim else XpColors.text
    Column(Modifier.fillMaxWidth().background(XpColors.window)) {
        Row(
            Modifier
                .fillMaxWidth()
                .xpClickable(role = Role.Button, onClickLabel = "Open dinner", onClick = onOpen)
                .heightIn(min = 58.dp)
                .padding(start = 12.dp, end = 20.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.width(46.dp)) {
                Text(day.date.format(weekdayFormat), style = XpType.SmallBold, color = ink, maxLines = 1)
                Text(day.date.format(monthDayFormat), style = XpType.Tiny, color = XpColors.textDim, maxLines = 1)
            }
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    rich(day.dinner.ifBlank { "Dinner" }),
                    style = XpType.RowTitle,
                    color = ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val detail = listOf(day.base.takeIf(String::isNotBlank)?.let { "with $it" }, day.note.takeIf(String::isNotBlank))
                    .filterNotNull()
                    .joinToString(" · ")
                if (detail.isNotBlank()) {
                    Text(rich(detail), style = XpType.Small, color = XpColors.textDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Column(Modifier.padding(start = 8.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                if (day.kind != DinnerKind.COOK) KitchenTag(if (day.kind == DinnerKind.LEFTOVERS) "Leftovers" else "Special")
                if (note.cooked != Cooked.NONE) Text(note.cooked.label, style = XpType.Tiny, color = XpColors.textDim, maxLines = 1)
                if (note.rating != Rating.NONE) Text(note.rating.label, style = XpType.SmallBold, color = ink, maxLines = 1)
            }
        }
        RowDivider()
    }
}

@Composable
private fun PlanAbout(plan: MealPlan) {
    Column(
        Modifier.fillMaxWidth().background(XpColors.window).padding(start = 14.dp, end = 22.dp, top = 4.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (plan.title.isNotBlank()) Text(rich(plan.title), style = XpType.SmallBold)
        if (plan.tagline.isNotBlank()) Text(rich(plan.tagline), style = XpType.Small, color = XpColors.textDim)
        plan.meta.forEach { Text(rich(it), style = XpType.Small) }
        plan.boxes.forEach { box ->
            Spacer(Modifier.height(2.dp))
            if (box.title.isNotBlank()) Text(rich(box.title), style = XpType.SmallBold, color = XpColors.groupText)
            box.items.forEach { line ->
                Row {
                    Text("•", style = XpType.Small, modifier = Modifier.width(14.dp))
                    Text(rich(line), style = XpType.Small, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

// ------------------------------------------------------------------ dinner details

/** The open dinner: its recipe and how it went. Drawn above everything, like the app's other dialogs. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun KitchenDinnerDialog(controller: KitchenController) {
    val state = controller.state
    val date = state.openDay ?: return
    val snapshot = state.snapshot ?: return
    val (plan, day) = findDay(snapshot, date) ?: return
    val recipe = plan.recipeFor(day)
    val saved = snapshot.noteFor(date)
    var cooked by remember(date) { mutableStateOf(saved.cooked) }
    var rating by remember(date) { mutableStateOf(saved.rating) }
    var note by remember(date) { mutableStateOf(saved.note) }
    val saving = controller.isBusy(KitchenController.noteKey(date))
    val close = {
        state.openDay = null
        state.noteError = null
    }
    XpDialog(
        title = "Dinner - ${date.format(weekdayFormat)} ${date.format(monthDayFormat)}",
        onDismiss = close,
        buttons = {
            XpButton(
                "Save",
                onClick = {
                    val edited = DinnerNote(cooked, rating, note.trim())
                    if (edited.sameChoices(saved)) close() else controller.saveNote(date, edited)
                },
                enabled = !saving,
                default = true,
            )
            XpButton("Cancel", onClick = close)
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(rich(day.dinner.ifBlank { recipe?.title.orEmpty() }), style = XpType.BodyBold.copy(fontSize = 18.sp, lineHeight = 23.sp))
            if (day.base.isNotBlank()) Text(rich("with ${day.base}"), style = XpType.Small, color = XpColors.textDim)
            if (recipe != null && recipe.title.isNotBlank() && recipe.title != day.dinner) {
                Text(rich(recipe.title), style = XpType.Small, color = XpColors.textDim)
            }
            if (recipe?.sub?.isNotBlank() == true) Text(rich(recipe.sub), style = XpType.Small, color = XpColors.textDim)
        }
        if (day.note.isNotBlank()) DayNote(day.note, Modifier.fillMaxWidth())
        if (recipe != null) {
            if (recipe.ingredients.isNotBlank()) {
                XpGroupBox("Ingredients") {
                    Text(rich(recipe.ingredients), style = XpType.Small)
                }
            }
            if (recipe.steps.isNotEmpty()) {
                XpGroupBox("Steps") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        recipe.steps.forEachIndexed { index, step ->
                            Row {
                                Text("${index + 1}.", style = XpType.SmallBold, modifier = Modifier.width(24.dp))
                                Text(rich(step), style = XpType.Small, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
            if (recipe.note.isNotBlank()) Text(rich(recipe.note), style = XpType.Small, color = XpColors.textDim)
        } else {
            Text(
                if (day.kind == DinnerKind.LEFTOVERS) "Leftovers night: there is no recipe." else "There is no recipe for this dinner.",
                style = XpType.Small,
                color = XpColors.textDim,
            )
        }
        XpGroupBox("How did it go?") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                listOf(Cooked.YES, Cooked.SKIPPED).forEach { choice ->
                    XpRadioButton(cooked == choice, onClick = { cooked = if (cooked == choice) Cooked.NONE else choice }, label = choice.label)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                listOf(Rating.LOVED, Rating.OK, Rating.NO).forEach { choice ->
                    XpRadioButton(rating == choice, onClick = { rating = if (rating == choice) Rating.NONE else choice }, label = choice.label)
                }
            }
            Spacer(Modifier.height(4.dp))
            XpTextField(
                note,
                { note = it.take(300) },
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Note about this dinner" },
                placeholder = "Note (optional)",
                singleLine = false,
                minLines = 2,
                maxLines = 5,
            )
            state.noteError?.let { Text(it, style = XpType.Small, color = XpColors.errorText, modifier = Modifier.padding(top = 4.dp)) }
        }
    }
}

// ------------------------------------------------------------------ shopping list

@Composable
private fun ShoppingTab(controller: KitchenController, snapshot: KitchenSnapshot, nowMillis: Long) {
    var draft by rememberSaveable { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val need = snapshot.list.filter { it.status == ListStatus.NEED }
    val ordered = snapshot.list.filter { it.status == ListStatus.ORDERED }
    val got = remember(snapshot, nowMillis) { KitchenOrder.recentGot(snapshot.list, Instant.ofEpochMilli(nowMillis)) }
    val adding = controller.isBusy(KitchenController.KEY_ADD)
    val submit = {
        if (draft.isNotBlank() && !adding) {
            controller.addItem(draft) {
                draft = ""
                keyboard?.hide()
            }
        }
    }
    val listState = rememberLazyListState()
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(XpColors.face)
                .drawBehind {
                    val line = 1.dp.toPx()
                    drawRect(XpColors.shadow, topLeft = Offset(0f, size.height - line), size = Size(size.width, line))
                }
                .padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 7.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                XpTextField(
                    draft,
                    { draft = it.take(120) },
                    modifier = Modifier.weight(1f).semantics { contentDescription = "Add to the shopping list" },
                    placeholder = "Add an item, like dish soap",
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                )
                Spacer(Modifier.width(8.dp))
                XpButton("Add", onClick = { submit() }, enabled = draft.isNotBlank() && !adding, minWidth = 70.dp)
            }
            Text(
                "Claude adds everything here to Monday's Walmart order.",
                style = XpType.Small,
                color = XpColors.textDim,
                modifier = Modifier.padding(top = 5.dp),
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth().background(XpColors.window)) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 20.dp)) {
                item(key = "need-header") { KitchenHeader("To buy", "(${need.size})") }
                if (need.isEmpty() && ordered.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            "Nothing to buy. Add what runs out above.",
                            style = XpType.Small,
                            color = XpColors.textDim,
                            modifier = Modifier.padding(start = 14.dp, end = 22.dp, top = 8.dp, bottom = 10.dp),
                        )
                    }
                }
                items(need, key = { it.id }) { item -> ShoppingRow(item, controller) }
                if (ordered.isNotEmpty()) {
                    item(key = "ordered-header") { KitchenHeader("Ordered", "(${ordered.size})") }
                    items(ordered, key = { it.id }) { item -> ShoppingRow(item, controller) }
                }
                if (got.isNotEmpty()) {
                    item(key = "got-header") { KitchenHeader("Got it", "(${got.size}) last 7 days") }
                    items(got, key = { it.id }) { item -> ShoppingRow(item, controller) }
                }
            }
            XpVerticalScrollbar(listState, Modifier.align(Alignment.CenterEnd))
        }
    }
}

@Composable
private fun ShoppingRow(item: KitchenListItem, controller: KitchenController) {
    val got = item.status == ListStatus.GOT
    val busy = controller.isBusy(item.id)
    val details = buildList {
        if (item.qty.isNotBlank()) add(item.qty)
        if (item.note.isNotBlank()) add(item.note)
        if (item.status == ListStatus.ORDERED && item.orderRef.isNotBlank()) add(item.orderRef)
        if (got) gotWhen(KitchenOrder.gotAt(item))?.let(::add)
        if (!got) {
            when (item.source) {
                "ai" -> add("Added by Claude")
                "pantry" -> add("Out at home")
            }
        }
    }.joinToString(" · ")
    Column(Modifier.fillMaxWidth().background(XpColors.window)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 54.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .padding(start = 5.dp)
                    .size(width = 46.dp, height = 50.dp)
                    .semantics { contentDescription = if (got) "Put ${item.name} back on the list" else "Tick off ${item.name}" }
                    // A check box to TalkBack too: it reads ticked or not ticked.
                    .toggleable(value = got, interactionSource = null, indication = null, enabled = !busy, role = Role.Checkbox) { controller.setGot(item, it) },
                contentAlignment = Alignment.Center,
            ) {
                XpCheckMark(checked = got, box = 22.dp, enabled = !busy)
            }
            Column(Modifier.weight(1f).padding(vertical = 7.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    item.name,
                    style = XpType.RowTitle,
                    color = if (got) XpColors.textDim else XpColors.text,
                    textDecoration = if (got) TextDecoration.LineThrough else null,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (details.isNotBlank()) {
                    Text(details, style = XpType.Small, color = XpColors.textDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            // Clear of the scroll bar strip at the right edge.
            XpCloseGlyph("Remove ${item.name}", onClick = { if (!busy) controller.remove(item) }, modifier = Modifier.padding(end = 10.dp))
        }
        RowDivider()
    }
}

private fun gotWhen(boughtAt: String): String? {
    val date = KitchenOrder.instant(boughtAt)?.atZone(ZoneId.systemDefault())?.toLocalDate() ?: return null
    val today = LocalDate.now()
    return when (date) {
        today -> "Got today"
        today.minusDays(1) -> "Got yesterday"
        else -> "Got ${date.format(weekdayFormat)} ${date.format(monthDayFormat)}"
    }
}

// ------------------------------------------------------------------ on hand

/** The estimate's words for a status. */
internal fun estimateLabel(status: PantryStatus): String = when (status) {
    PantryStatus.HAVE -> "Have"
    PantryStatus.LOW -> "Running low"
    PantryStatus.OUT -> "Probably out"
}

/** The palette's good, warning and critical colors (not the accent). */
@Composable
private fun estimateColor(status: PantryStatus): Color = when (status) {
    PantryStatus.HAVE -> XpColors.checkGreen
    PantryStatus.LOW -> XpColors.estimateWarn
    PantryStatus.OUT -> XpColors.estimateCritical
}

/** Claude's estimate in plain words: a colored dot and the word, in a frame of the same color. */
@Composable
private fun EstimateChip(status: PantryStatus) {
    val color = estimateColor(status)
    Row(
        Modifier
            .background(XpColors.window)
            .border(1.dp, color, RoundedCornerShape(3.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(9.dp).background(color, RoundedCornerShape(5.dp)))
        Spacer(Modifier.width(5.dp))
        Text(estimateLabel(status), style = XpType.Tiny.copy(fontWeight = FontWeight.Bold), color = XpColors.text, maxLines = 1)
    }
}

private val checkedFormat = DateTimeFormatter.ofPattern("EEE M/d")

@Composable
private fun OnHandTab(controller: KitchenController, snapshot: KitchenSnapshot) {
    val state = controller.state
    val attentionOnly = state.attentionOnly
    val sections = remember(snapshot.pantry, attentionOnly) { KitchenOrder.estimateSections(snapshot.pantry, attentionOnly) }
    val corrections = snapshot.corrections
    val shownCorrections = remember(corrections, state.showAllCorrections) {
        if (state.showAllCorrections) corrections else KitchenOrder.newestCorrections(corrections, COLLAPSED_CORRECTIONS)
    }
    val updated = remember(snapshot.pantry) { KitchenOrder.lastChecked(snapshot.pantry)?.atZone(ZoneId.systemDefault()) }
    val listState = rememberLazyListState()
    Column(Modifier.fillMaxSize()) {
        TellClaudeBox(controller, updated?.format(checkedFormat))
        Box(Modifier.weight(1f).fillMaxWidth().background(XpColors.window)) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 20.dp)) {
                if (corrections.isNotEmpty()) {
                    item(key = "corrections-header") { KitchenHeader("Your corrections", "(${corrections.size})") }
                    items(shownCorrections, key = { "correction-${it.id}" }) { correction -> CorrectionRow(correction, controller) }
                    if (corrections.size > COLLAPSED_CORRECTIONS) {
                        item(key = "corrections-more") {
                            XpLink(
                                if (state.showAllCorrections) "Show fewer" else "Show all (${corrections.size})",
                                onClick = { state.showAllCorrections = !state.showAllCorrections },
                                modifier = Modifier.padding(start = 14.dp),
                                style = XpType.Small,
                            )
                        }
                    }
                }
                item(key = "estimate-header") {
                    Column(Modifier.fillMaxWidth().background(XpColors.window)) {
                        KitchenHeader("Claude's estimate", if (snapshot.pantry.isEmpty()) "" else "(${snapshot.pantry.size})")
                        if (snapshot.pantry.isNotEmpty()) {
                            XpCheckBox(
                                attentionOnly,
                                { state.attentionOnly = it },
                                "Running low & out only",
                                Modifier.padding(start = 14.dp, end = 20.dp),
                            )
                        }
                    }
                }
                when {
                    snapshot.pantry.isEmpty() -> item(key = "estimate-empty") {
                        KitchenMessage("Claude fills this in after the next grocery order.")
                    }
                    sections.isEmpty() -> item(key = "estimate-none") {
                        Text(
                            "Nothing is running low or out.",
                            style = XpType.Small,
                            color = XpColors.textDim,
                            modifier = Modifier.padding(start = 14.dp, end = 22.dp, top = 8.dp, bottom = 10.dp),
                        )
                    }
                    else -> sections.forEach { (section, items) ->
                        item(key = "section-$section") { KitchenHeader(section, "(${items.size})") }
                        items(items, key = { "estimate-${it.id}" }) { item -> EstimateRow(item) { state.actionsFor = item } }
                    }
                }
            }
            XpVerticalScrollbar(listState, Modifier.align(Alignment.CenterEnd))
        }
    }
}

private const val COLLAPSED_CORRECTIONS = 3

/** "What Claude thinks you have", and the box to tell Claude what is different. */
@Composable
private fun TellClaudeBox(controller: KitchenController, updated: String?) {
    val state = controller.state
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    val sending = controller.isBusy(KitchenController.KEY_NOTE)
    LaunchedEffect(state.noteFocus) {
        if (state.noteFocus > 0) runCatching { focus.requestFocus() }
    }
    val send = {
        if (state.noteDraft.text.isNotBlank() && !sending) {
            controller.sendNote(state.noteDraft.text, state.noteItem) {
                state.noteDraft = TextFieldValue("")
                state.noteItem = null
                keyboard?.hide()
            }
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(XpColors.face)
            .drawBehind {
                val line = 1.dp.toPx()
                drawRect(XpColors.shadow, topLeft = Offset(0f, size.height - line), size = Size(size.width, line))
            }
            .padding(start = 10.dp, end = 10.dp, top = 7.dp, bottom = 7.dp),
    ) {
        Text(
            if (updated == null) "What Claude thinks you have." else "What Claude thinks you have. Updated $updated.",
            style = XpType.Small,
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            XpTextField(
                state.noteDraft,
                { typed ->
                    state.noteDraft = if (typed.text.length <= 300) typed else typed.copy(text = typed.text.take(300), selection = TextRange(300))
                    if (typed.text.isBlank()) state.noteItem = null
                },
                modifier = Modifier.weight(1f).semantics { contentDescription = "Tell Claude" },
                placeholder = "Out of eggs, have 2 cans of beans, tossed the cilantro…",
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                focusRequester = focus,
            )
            Spacer(Modifier.width(8.dp))
            XpButton("Send", onClick = { send() }, enabled = state.noteDraft.text.isNotBlank() && !sending, minWidth = 70.dp)
        }
        val about = state.noteItem
        Text(
            if (about != null) "About ${about.name}. Claude reads it and answers here." else "Tell Claude; it reads your note and answers here.",
            style = XpType.Tiny,
            color = XpColors.textDim,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

private val correctionDayFormat = DateTimeFormatter.ofPattern("EEE M/d")

@Composable
private fun CorrectionRow(correction: Correction, controller: KitchenController) {
    val handled = correction.status != CorrectionStatus.OPEN
    val title = when (correction.kind) {
        CorrectionKind.OUT -> "We're out: ${correction.itemName.ifBlank { "an item" }}"
        CorrectionKind.HAVE -> "We have it: ${correction.itemName.ifBlank { "an item" }}"
        CorrectionKind.NOTE -> "“${correction.text}”"
    }
    val answer = when {
        correction.queued -> "Not sent yet"
        !handled -> "Waiting for Claude"
        correction.kind == CorrectionKind.NOTE -> "Claude: ${correction.reply.ifBlank { if (correction.status == CorrectionStatus.DISMISSED) "Left as it was." else "Done." }}"
        else -> "Applied: ${correction.reply.ifBlank { "Done." }}"
    }
    val day = KitchenOrder.instant(correction.addedAt)?.atZone(ZoneId.systemDefault())?.format(correctionDayFormat)
    val who = listOf(correction.addedBy.takeIf(String::isNotBlank), day).filterNotNull().joinToString(" · ")
    Column(Modifier.fillMaxWidth().background(XpColors.window)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 12.dp, end = 20.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                when {
                    !handled -> XpIcons.Clock
                    correction.status == CorrectionStatus.DISMISSED -> XpIcons.Info
                    else -> XpIcons.Check
                },
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = XpType.Body, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(
                    answer,
                    style = if (handled) XpType.Small else XpType.SmallBold,
                    color = if (handled) XpColors.text else XpColors.textDim,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
                if (who.isNotBlank()) Text(who, style = XpType.Tiny, color = XpColors.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // Only an open note can be taken back; We're out and We have it apply at once in the cloud.
            if (!handled && correction.kind == CorrectionKind.NOTE) {
                Spacer(Modifier.width(8.dp))
                XpLink("Withdraw", onClick = { controller.withdraw(correction) }, style = XpType.Small)
            }
        }
        RowDivider()
    }
}

@Composable
private fun EstimateRow(item: PantryItem, onOpen: () -> Unit) {
    val label = estimateLabel(item.status)
    Column(Modifier.fillMaxWidth().background(XpColors.window)) {
        Row(
            Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "${item.name}: $label" }
                .xpClickable(role = Role.Button, onClickLabel = "Correct ${item.name}", onClick = onOpen)
                .heightIn(min = 52.dp)
                .padding(start = 14.dp, end = 20.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(item.name, style = XpType.RowTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (item.basis.isNotBlank()) {
                    Text(item.basis, style = XpType.Small, color = XpColors.textDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(8.dp))
            EstimateChip(item.status)
        }
        RowDivider()
    }
}

/** Tapping an estimate row: We're out, We have it, or Something else (a note about it). */
@Composable
internal fun KitchenOnHandActions(controller: KitchenController) {
    val state = controller.state
    val item = state.actionsFor ?: return
    val close = { state.actionsFor = null }
    XpDialog(
        title = item.name,
        onDismiss = close,
        dismissOnOutside = true,
        buttons = { XpButton("Cancel", onClick = close) },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Claude thinks:", style = XpType.Small)
            Spacer(Modifier.width(8.dp))
            EstimateChip(item.status)
        }
        if (item.basis.isNotBlank()) Text(item.basis, style = XpType.Small, color = XpColors.textDim)
        XpEtchedLine()
        XpButton(
            "We're out",
            onClick = {
                close()
                controller.correct(item, CorrectionKind.OUT)
            },
            modifier = Modifier.fillMaxWidth(),
            icon = XpIcons.Delete,
        )
        XpButton(
            "We have it",
            onClick = {
                close()
                controller.correct(item, CorrectionKind.HAVE)
            },
            modifier = Modifier.fillMaxWidth(),
            icon = XpIcons.Check,
        )
        XpButton(
            "Something else…",
            onClick = {
                close()
                // Typing goes on after the name: the cursor sits at the end.
                val prefix = "${item.name}: "
                state.noteDraft = TextFieldValue(prefix, selection = TextRange(prefix.length))
                state.noteItem = item
                state.noteFocus++
            },
            modifier = Modifier.fillMaxWidth(),
            icon = XpIcons.Edit,
        )
        Text("We're out also puts it on the shopping list.", style = XpType.Tiny, color = XpColors.textDim)
    }
}
