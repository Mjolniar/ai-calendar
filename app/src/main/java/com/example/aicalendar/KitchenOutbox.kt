package com.example.aicalendar

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

// Kitchen offline queue (KITCHEN-SPEC.md, round 2). Edits made while the bridge can't be reached
// wait in a FIFO outbox, are shown at once on top of the last snapshot, and are sent oldest first
// when a fetch succeeds. Separate from the reminders outbox (PendingChanges.kt) in every way.

enum class KitchenOp(val wire: String) {
    ADD_ITEM("add_item"),
    ITEM_STATUS("item_status"),
    REMOVE_ITEM("remove_item"),
    PANTRY_STATUS("pantry_status"),
    PANTRY_ADD("pantry_add"),
    PANTRY_REMOVE("pantry_remove"),
    NOTE("note"),

    /** A correction to the estimate (out, have or a note for Claude); its target is its own local id. */
    CORRECTION("correction"),
    WITHDRAW_CORRECTION("withdraw_correction");

    val isAdd: Boolean get() = this == ADD_ITEM || this == PANTRY_ADD || this == CORRECTION
    val isRemove: Boolean get() = this == REMOVE_ITEM || this == PANTRY_REMOVE || this == WITHDRAW_CORRECTION

    companion object {
        fun fromWire(value: String): KitchenOp? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * One queued kitchen edit. [target] is an item id (`local-...` for an item added on this phone
 * and not sent yet) or, for a dinner note, its date. [label] names it in a balloon.
 */
data class KitchenEdit(
    val seq: Long,
    val op: KitchenOp,
    val target: String,
    val fields: Map<String, String> = emptyMap(),
    val label: String = "",
    val createdAt: String = "",
) {
    fun field(key: String): String = fields[key].orEmpty()
}

const val LOCAL_ID_PREFIX = "local-"

fun isLocalKitchenId(id: String): Boolean = id.startsWith(LOCAL_ID_PREFIX)

fun newLocalKitchenId(): String = LOCAL_ID_PREFIX + UUID.randomUUID()

/** Where the outbox is kept: one text value (SharedPreferences `kitchen_outbox` on the phone). */
interface KitchenOutboxStorage {
    fun read(): String?
    fun write(text: String)
}

class InMemoryOutboxStorage(var text: String? = null) : KitchenOutboxStorage {
    override fun read(): String? = text
    override fun write(text: String) {
        this.text = text
    }
}

/**
 * The FIFO of kitchen edits waiting for the cloud. Every change is written through [storage] at
 * once, so the queue survives the app being closed. Safe across threads: the view adds edits while
 * a sender (the view or the background worker) takes them off the front.
 */
class KitchenOutbox(private val storage: KitchenOutboxStorage) {
    private data class State(val edits: List<KitchenEdit>, val nextSeq: Long, val aliases: Map<String, String>)

    private fun load(): State {
        val text = storage.read() ?: return State(emptyList(), 1, emptyMap())
        return runCatching {
            val json = JSONObject(text)
            val edits = json.optJSONArray("edits")?.let { array ->
                (0 until array.length()).mapNotNull { index -> array.optJSONObject(index)?.let(::parseEdit) }
            }.orEmpty()
            val aliases = json.optJSONObject("aliases")?.let { map ->
                map.keys().asSequence().associateWith { map.optString(it) }.filterValues(String::isNotBlank)
            }.orEmpty()
            val next = maxOf(json.optLong("next", 1L), (edits.maxOfOrNull { it.seq } ?: 0L) + 1)
            State(edits, next, aliases)
        }.getOrElse { State(emptyList(), 1, emptyMap()) }
    }

    private fun save(state: State) {
        val aliases = JSONObject()
        // Only recent ones are needed: they cover a view that still shows an item by its local id.
        state.aliases.entries.toList().takeLast(MAX_ALIASES).forEach { (local, real) -> aliases.put(local, real) }
        storage.write(
            JSONObject()
                .put("next", state.nextSeq)
                .put("edits", JSONArray(state.edits.map(::editJson)))
                .put("aliases", aliases)
                .toString(),
        )
    }

    private inline fun <T> change(block: (State) -> Pair<State, T>): T = synchronized(LOCK) {
        val (next, result) = block(load())
        save(next)
        result
    }

    /** Names a local id by the real one it was sent as, in the target and in an `itemId` field. */
    private fun State.resolved(id: String): String = aliases[id] ?: id

    fun all(): List<KitchenEdit> = synchronized(LOCK) { load().edits }

    fun size(): Int = all().size

    fun isEmpty(): Boolean = all().isEmpty()

    fun first(): KitchenEdit? = all().firstOrNull()

    /** The real id that a local id was sent as, or [id] itself. */
    fun resolve(id: String): String = synchronized(LOCK) { load().aliases[id] ?: id }

    /**
     * Queues an edit behind the others. Removing an item that exists only on this phone drops its
     * queued add and every edit to it instead, so nothing about it reaches the cloud; that returns null.
     */
    fun enqueue(op: KitchenOp, target: String, fields: Map<String, String> = emptyMap(), label: String = "", createdAt: String = ""): KitchenEdit? =
        change { state ->
            val resolved = state.resolved(target)
            if (op.isRemove && isLocalKitchenId(resolved)) {
                return@change state.copy(edits = state.edits.filterNot { it.target == resolved }) to null
            }
            // A note about an item may name it by the local id a screen still shows.
            val named = fields[KEY_ITEM_ID]?.let { fields + (KEY_ITEM_ID to state.resolved(it)) } ?: fields
            val edit = KitchenEdit(state.nextSeq, op, resolved, named, label, createdAt)
            state.copy(edits = state.edits + edit, nextSeq = state.nextSeq + 1) to edit
        }

    /**
     * Marks [edit] sent. For an add, the edits after it that name its local id (as their target or
     * as the item a correction is about) now name [realId]. Returns false when the edit was no
     * longer queued: its item was removed on this phone while the add was on its way.
     */
    fun sent(edit: KitchenEdit, realId: String? = null): Boolean = change { state ->
        val queued = state.edits.any { it.seq == edit.seq }
        val rest = state.edits.filterNot { it.seq == edit.seq }
        if (!edit.op.isAdd || realId.isNullOrBlank() || !isLocalKitchenId(edit.target)) {
            return@change state.copy(edits = rest) to queued
        }
        val remapped = rest.map { later ->
            val target = if (later.target == edit.target) realId else later.target
            val fields = if (later.fields[KEY_ITEM_ID] == edit.target) later.fields + (KEY_ITEM_ID to realId) else later.fields
            later.copy(target = target, fields = fields)
        }
        state.copy(edits = remapped, aliases = state.aliases + (edit.target to realId)) to queued
    }

    /** Drops [edit] unsent. A dropped add takes the edits that name its local id with it. */
    fun drop(edit: KitchenEdit) {
        change { state ->
            val rest = state.edits.filterNot { it.seq == edit.seq }
            val kept = if (edit.op.isAdd && isLocalKitchenId(edit.target)) rest.filterNot { it.target == edit.target } else rest
            state.copy(edits = kept) to Unit
        }
    }

    companion object {
        /**
         * One lock for every outbox in the process: the view's and the worker's share one store.
         * KitchenStore takes it too, for the refused edits it keeps beside the queue.
         */
        internal val LOCK = Any()
        private const val MAX_ALIASES = 100

        /** The field of a correction that names the pantry item it is about. */
        const val KEY_ITEM_ID = "itemId"

        /** The field that keeps a correction's clientId, the same on every send. */
        const val KEY_CLIENT_ID = "clientId"

        private fun editJson(edit: KitchenEdit): JSONObject = JSONObject()
            .put("seq", edit.seq)
            .put("op", edit.op.wire)
            .put("target", edit.target)
            .put("fields", JSONObject(edit.fields))
            .put("label", edit.label)
            .put("at", edit.createdAt)

        private fun parseEdit(json: JSONObject): KitchenEdit? {
            val op = KitchenOp.fromWire(json.optString("op")) ?: return null
            val target = json.optString("target").takeIf(String::isNotBlank) ?: return null
            val fields = json.optJSONObject("fields")?.let { map ->
                map.keys().asSequence().associateWith { key -> map.optString(key) }
            }.orEmpty()
            return KitchenEdit(json.optLong("seq"), op, target, fields, json.optString("label"), json.optString("at"))
        }
    }
}

/** What one sending of the outbox did. */
data class KitchenFlushReport(
    /** Edits the cloud accepted. */
    val sent: Int = 0,
    /** Edits dropped because their item is gone (404). */
    val gone: Int = 0,
    /** Edits the cloud refused (400), named for a balloon. */
    val rejected: List<String> = emptyList(),
    /** Sending stopped at a network error, a 5xx or a refused pairing; the rest waits for the next try. */
    val stopped: Boolean = false,
    /** The cloud's answers to the sent edits, oldest first, to put into the shown copy. */
    val answers: List<KitchenAnswer> = emptyList(),
) {
    val changedAnything: Boolean get() = sent > 0 || gone > 0 || rejected.isNotEmpty()
}

/** Sends the outbox oldest first (KITCHEN-SPEC.md, "Each queued edit, when sent"). */
object KitchenFlusher {
    /**
     * One sent edit: the cloud's [answer], and for an add the new item's [newId] and whether the
     * cloud [created] it (false: an open item of that name was there already, perhaps another
     * device's, and it is answered instead).
     */
    private class Sent(val answer: KitchenAnswer?, val newId: String? = null, val created: Boolean = true)

    fun flush(outbox: KitchenOutbox, client: KitchenClient): KitchenFlushReport {
        var sent = 0
        var gone = 0
        val rejected = mutableListOf<String>()
        val answers = mutableListOf<KitchenAnswer>()
        val tried = mutableSetOf<Long>()
        while (true) {
            val edit = outbox.first() ?: break
            // An edit that could not be taken off the queue must not be sent twice.
            if (!tried.add(edit.seq)) break
            when (val result = send(client, edit)) {
                is KitchenResult.Ok -> {
                    val done = result.value
                    val realId = done.newId
                    val stillQueued = outbox.sent(edit, realId)
                    if (!stillQueued && edit.op.isAdd && realId != null) {
                        // Removed on this phone while its add was on the way: remove it in the cloud
                        // too, but only what this add made. An item that was there already stays.
                        if (done.created) {
                            when (edit.op) {
                                KitchenOp.ADD_ITEM -> client.removeItem(realId)
                                KitchenOp.CORRECTION -> client.withdrawCorrection(realId)
                                else -> client.removePantryItem(realId)
                            }
                        } else {
                            done.answer?.let(answers::add)
                        }
                    } else {
                        done.answer?.let(answers::add)
                    }
                    sent++
                }
                is KitchenResult.Failed -> when {
                    result.notFound -> {
                        outbox.drop(edit)
                        gone++
                    }
                    result.connection -> return KitchenFlushReport(sent, gone, rejected, stopped = true, answers = answers)
                    else -> {
                        outbox.drop(edit)
                        rejected += "${edit.label.ifBlank { "A change" }}: ${result.message}"
                    }
                }
            }
        }
        return KitchenFlushReport(sent, gone, rejected, answers = answers)
    }

    /** The clientId of a queued correction: its own field, or the uuid of its local id (queued by 0.19.0). */
    fun clientId(edit: KitchenEdit): String = edit.field(KitchenOutbox.KEY_CLIENT_ID).ifBlank { edit.target.removePrefix(LOCAL_ID_PREFIX) }

    /** One edit to its route. */
    private fun send(client: KitchenClient, edit: KitchenEdit): KitchenResult<Sent> {
        fun <T> KitchenResult<T>.sent(make: (T) -> Sent): KitchenResult<Sent> = when (this) {
            is KitchenResult.Ok -> KitchenResult.Ok(make(value))
            is KitchenResult.Failed -> this
        }
        return when (edit.op) {
            KitchenOp.ADD_ITEM -> {
                val restore = edit.field("restore").toBoolean()
                client.addItem(
                    edit.field("name"), edit.field("qty"), edit.field("note"),
                    restore = if (restore) ListStatus.fromWire(edit.field("status")) ?: ListStatus.NEED else null,
                    orderRef = edit.field("orderRef"),
                ).sent { Sent(KitchenAnswer.ListItem(it), it.item.id, it.created) }
            }
            KitchenOp.ITEM_STATUS -> {
                val status = ListStatus.fromWire(edit.field("status")) ?: return KitchenResult.Failed("Unknown status")
                client.setItemStatus(edit.target, status).sent { Sent(KitchenAnswer.ListItem(it)) }
            }
            KitchenOp.REMOVE_ITEM -> client.removeItem(edit.target).sent { Sent(KitchenAnswer.ListItemRemoved(edit.target, it)) }
            KitchenOp.PANTRY_STATUS -> {
                val status = PantryStatus.fromWire(edit.field("status")) ?: return KitchenResult.Failed("Unknown status")
                client.setPantryStatus(edit.target, status).sent { Sent(KitchenAnswer.Pantry(it)) }
            }
            KitchenOp.PANTRY_ADD -> {
                val status = PantryStatus.fromWire(edit.field("status")) ?: PantryStatus.HAVE
                client.addPantryItem(edit.field("name"), edit.field("section"), status).sent { Sent(KitchenAnswer.Pantry(it), it.item.id, it.created) }
            }
            KitchenOp.PANTRY_REMOVE -> client.removePantryItem(edit.target).sent { Sent(KitchenAnswer.PantryRemoved(edit.target, it)) }
            KitchenOp.NOTE -> {
                val date = runCatching { LocalDate.parse(edit.target) }.getOrNull() ?: return KitchenResult.Failed("Unknown date")
                val note = DinnerNote(Cooked.fromWire(edit.field("cooked")), Rating.fromWire(edit.field("rating")), edit.field("note"))
                client.saveNote(date, note).sent { Sent(KitchenAnswer.Note(it)) }
            }
            KitchenOp.CORRECTION -> {
                val kind = CorrectionKind.fromWire(edit.field("kind")) ?: return KitchenResult.Failed("Unknown kind of correction")
                val text = edit.field("text")
                val itemId = edit.field(KitchenOutbox.KEY_ITEM_ID).ifBlank { null }
                val clientId = clientId(edit)
                var result = client.addCorrection(kind, text, itemId, clientId)
                // A note about an item removed since: bridge 0.10.0 answers 404. Say it without the item.
                if (kind == CorrectionKind.NOTE && itemId != null && result is KitchenResult.Failed && result.notFound) {
                    result = client.addCorrection(kind, text, null, clientId)
                }
                // A note is never dropped quietly: if it still can't be stored, a balloon names it.
                if (kind == CorrectionKind.NOTE && result is KitchenResult.Failed && result.notFound) {
                    result = KitchenResult.Failed("The cloud calendar couldn't store this note.")
                }
                result.sent { Sent(KitchenAnswer.Corrected(it), it.correction.id) }
            }
            KitchenOp.WITHDRAW_CORRECTION -> client.withdrawCorrection(edit.target).sent { Sent(KitchenAnswer.Withdrawn(edit.target, it)) }
        }
    }
}

/**
 * One round with the cloud: fetch; with edits queued, send them oldest first and fetch again. The
 * Kitchen view and the background job both run it, and both keep what it leaves (KitchenStore).
 */
data class KitchenRound(
    val first: KitchenResult<KitchenSnapshot>,
    /** What the sending did; null when nothing was sent (nothing queued, or another sender is at it). */
    val report: KitchenFlushReport? = null,
    /** The fetch after the sends, when there was one and it succeeded. */
    val again: KitchenSnapshot? = null,
    /** The edits still queued afterwards. */
    val queued: List<KitchenEdit> = emptyList(),
) {
    /** The answers of the sent edits, to put into the copy when [again] did not arrive. */
    val answers: List<KitchenAnswer> get() = report?.answers.orEmpty()

    /**
     * What to keep: the fetch after the sends, or the first fetch with each sent edit's answer put
     * in, so a sent edit never reappears in its old state when the second fetch fails.
     */
    val latest: KitchenSnapshot? get() = again ?: (first as? KitchenResult.Ok)?.value?.let { base -> answers.fold(base) { shown, answer -> answer.applyTo(shown) } }

    companion object {
        /** [flush] is KitchenSync.flush on the phone: it returns null while another sender is at it. */
        fun run(
            outbox: KitchenOutbox,
            client: KitchenClient,
            flush: (KitchenOutbox, KitchenClient) -> KitchenFlushReport? = KitchenFlusher::flush,
        ): KitchenRound {
            val first = client.load()
            if (first !is KitchenResult.Ok || outbox.isEmpty()) return KitchenRound(first, queued = outbox.all())
            val report = flush(outbox, client) ?: return KitchenRound(first, queued = outbox.all())
            if (!report.changedAnything) return KitchenRound(first, report, queued = outbox.all())
            val again = (client.load() as? KitchenResult.Ok)?.value
            return KitchenRound(first, report, again, outbox.all())
        }
    }
}

/**
 * Which fetched snapshot the view takes. The revision only grows. A fetch with the revision
 * already fetched changed nothing and is not re-rendered; one older than an edit applied since is a
 * late answer and is dropped. A fetch at the revision of the phone's own last edit is taken: it
 * carries what the server did besides (a bought item restocks the pantry).
 */
object KitchenRevisions {
    fun accepts(shownRevision: Long?, fetchedRevision: Long, next: Long): Boolean =
        shownRevision == null || next == 0L || (next > fetchedRevision && next >= shownRevision)
}

/** How long the Kitchen view waits before trying the cloud again while it is offline. */
object KitchenRetry {
    const val FIRST_MS = 30_000L
    const val MAX_MS = 5 * 60_000L

    /** 30 s, 60 s, 120 s, 240 s, then every 5 minutes. */
    fun delayMillis(attempt: Int): Long = if (attempt >= 4) MAX_MS else minOf(MAX_MS, FIRST_MS shl attempt.coerceAtLeast(0))
}

/** Queued edits shown on top of a snapshot, the way the server will apply them. */
object KitchenOverlay {
    fun apply(base: KitchenSnapshot, edits: List<KitchenEdit>): KitchenSnapshot = edits.fold(base, ::applyOne)

    fun applyOne(snapshot: KitchenSnapshot, edit: KitchenEdit): KitchenSnapshot = when (edit.op) {
        KitchenOp.ADD_ITEM -> {
            val name = edit.field("name").trim()
            // Undo after a remove puts the line back as it was: ordered with its order, or bought.
            val status = if (edit.field("restore").toBoolean()) ListStatus.fromWire(edit.field("status")) ?: ListStatus.NEED else ListStatus.NEED
            // Rule 1: an open item with the same name is not added twice (a bought one comes back anyway).
            if (name.isEmpty() || (status.isOpen && snapshot.openItems.any { KitchenText.sameName(it.name, name) })) {
                snapshot
            } else {
                snapshot.withListItem(
                    KitchenListItem(
                        id = edit.target, name = name, qty = edit.field("qty"), note = edit.field("note"),
                        status = status, orderRef = if (status == ListStatus.NEED) "" else edit.field("orderRef"),
                        source = "device", addedAt = edit.createdAt, updatedAt = edit.createdAt,
                        doneAt = if (status == ListStatus.GOT) edit.createdAt else "",
                    ),
                    snapshot.revision,
                )
            }
        }
        KitchenOp.ITEM_STATUS -> {
            val item = snapshot.list.firstOrNull { it.id == edit.target }
            val status = ListStatus.fromWire(edit.field("status"))
            if (item == null || status == null) {
                snapshot
            } else {
                val done = if (status == ListStatus.GOT) edit.createdAt else ""
                val changed = snapshot.withListItem(item.copy(status = status, doneAt = done, updatedAt = edit.createdAt), snapshot.revision)
                // Rule 3: a bought item restocks the pantry.
                if (status != ListStatus.GOT) {
                    changed
                } else {
                    changed.copy(pantry = changed.pantry.map { if (KitchenText.sameName(it.name, item.name)) it.copy(status = PantryStatus.HAVE) else it })
                }
            }
        }
        KitchenOp.REMOVE_ITEM -> snapshot.withoutListItem(edit.target, snapshot.revision)
        KitchenOp.PANTRY_STATUS -> {
            val item = snapshot.pantry.firstOrNull { it.id == edit.target }
            val status = PantryStatus.fromWire(edit.field("status"))
            if (item == null || status == null) snapshot else snapshot.withPantryItem(item.copy(status = status), snapshot.revision)
        }
        KitchenOp.PANTRY_ADD -> {
            val name = edit.field("name").trim()
            if (name.isEmpty() || snapshot.pantry.any { KitchenText.sameName(it.name, name) }) {
                snapshot
            } else {
                snapshot.withPantryItem(
                    PantryItem(
                        id = edit.target,
                        name = name,
                        section = edit.field("section").trim().ifBlank { PANTRY_OTHER },
                        status = PantryStatus.fromWire(edit.field("status")) ?: PantryStatus.HAVE,
                        updatedAt = edit.createdAt,
                    ),
                    snapshot.revision,
                )
            }
        }
        KitchenOp.PANTRY_REMOVE -> snapshot.copy(pantry = snapshot.pantry.filterNot { it.id == edit.target })
        KitchenOp.NOTE -> snapshot.withNote(
            edit.target,
            DinnerNote(Cooked.fromWire(edit.field("cooked")), Rating.fromWire(edit.field("rating")), edit.field("note"), edit.createdAt),
            snapshot.revision,
        )
        KitchenOp.CORRECTION -> {
            val kind = CorrectionKind.fromWire(edit.field("kind"))
            if (kind == null) {
                snapshot
            } else {
                val item = snapshot.pantry.firstOrNull { it.id == edit.field("itemId") }
                // We're out / We have it change the estimate at once, as the bridge will.
                val changed = if (item == null || kind == CorrectionKind.NOTE) {
                    snapshot
                } else {
                    val out = kind == CorrectionKind.OUT
                    snapshot.withPantryItem(
                        item.copy(
                            status = if (out) PantryStatus.OUT else PantryStatus.HAVE,
                            basis = if (out) "You said it's out (not sent yet)" else "You said you have it (not sent yet)",
                        ),
                        snapshot.revision,
                    )
                }
                changed.withCorrection(
                    Correction(
                        id = edit.target, kind = kind, text = edit.field("text"), itemId = edit.field("itemId"),
                        itemName = item?.name ?: edit.field("itemName"), status = CorrectionStatus.OPEN,
                        addedBy = "This phone", addedAt = edit.createdAt,
                    ),
                    snapshot.revision,
                )
            }
        }
        KitchenOp.WITHDRAW_CORRECTION -> snapshot.withoutCorrection(edit.target, snapshot.revision)
    }

    private val sameDay = DateTimeFormatter.ofPattern("h:mm a")
    private val otherDay = DateTimeFormatter.ofPattern("MMM d, h:mm a")

    /** "Offline: showing 9:41 AM. 2 changes will be sent when you're back online." */
    fun offlineText(fetchedAt: Instant?, pending: Int, zone: ZoneId = ZoneId.systemDefault(), now: Instant = Instant.now()): String {
        val shown = fetchedAt?.atZone(zone)
        val time = when {
            shown == null -> "the last copy"
            shown.toLocalDate() == now.atZone(zone).toLocalDate() -> shown.format(sameDay)
            else -> shown.format(otherDay)
        }
        val changes = when (pending) {
            0 -> "Changes will be sent when you're back online."
            1 -> "1 change will be sent when you're back online."
            else -> "$pending changes will be sent when you're back online."
        }
        return "Offline: showing $time. $changes"
    }
}

/** The cached snapshot (`kitchen_cache`), written in the same shape `GET /v1/kitchen` answers. */
object KitchenCacheJson {
    fun write(snapshot: KitchenSnapshot): JSONObject = JSONObject()
        .put("revision", snapshot.revision)
        .put("list", JSONArray(snapshot.list.map(::item)))
        .put("pantry", JSONArray(snapshot.pantry.map(::pantry)))
        .put("week", snapshot.week?.let(::plan) ?: JSONObject.NULL)
        .put("nextWeek", snapshot.nextWeek?.let(::plan) ?: JSONObject.NULL)
        .put("notes", JSONObject().also { notes -> snapshot.notes.forEach { (date, note) -> notes.put(date, note(note)) } })
        .put("corrections", JSONArray(snapshot.corrections.map(::correction)))
        .also { json -> snapshot.today?.let { json.put("today", it.toString()) } }

    private fun item(item: KitchenListItem): JSONObject = JSONObject()
        .put("id", item.id).put("name", item.name).put("qty", item.qty).put("note", item.note)
        .put("status", item.status.wire).put("orderRef", item.orderRef).put("source", item.source).put("addedBy", item.addedBy)
        .put("addedAt", item.addedAt).put("updatedAt", item.updatedAt).put("doneAt", item.doneAt)

    private fun pantry(item: PantryItem): JSONObject = JSONObject()
        .put("id", item.id).put("name", item.name).put("section", item.section)
        .put("status", item.status.wire).put("note", item.note).put("updatedAt", item.updatedAt)
        .put("basis", item.basis).put("checkedAt", item.checkedAt)

    private fun correction(item: Correction): JSONObject = JSONObject()
        .put("id", item.id).put("kind", item.kind.wire).put("text", item.text).put("itemId", item.itemId).put("itemName", item.itemName)
        .put("status", item.status.wire).put("reply", item.reply).put("addedBy", item.addedBy).put("addedAt", item.addedAt)
        .put("resolvedAt", item.resolvedAt)

    private fun note(note: DinnerNote): JSONObject = JSONObject()
        .put("cooked", note.cooked.wire).put("rating", note.rating.wire).put("note", note.note).put("updatedAt", note.updatedAt)

    private fun plan(plan: MealPlan): JSONObject = JSONObject()
        .put(
            "week",
            JSONObject().put("title", plan.title).also { week ->
                plan.start?.let { week.put("start", it.toString()) }
                plan.end?.let { week.put("end", it.toString()) }
            },
        )
        .put("status", plan.status)
        .put("tagline", plan.tagline)
        .put("meta", JSONArray(plan.meta))
        .put(
            "days",
            JSONArray(
                plan.days.map { day ->
                    JSONObject()
                        .put("date", day.date.toString()).put("label", day.label).put("dinner", day.dinner)
                        .put("base", day.base).put("note", day.note).put("kind", day.kind.name.lowercase())
                        .also { if (day.recipeId.isNotBlank()) it.put("recipe", day.recipeId) }
                },
            ),
        )
        .put("boxes", JSONArray(plan.boxes.map { box -> JSONObject().put("title", box.title).put("items", JSONArray(box.items)) }))
        .put(
            "recipes",
            JSONArray(
                plan.recipes.map { recipe ->
                    JSONObject()
                        .put("id", recipe.id).put("title", recipe.title).put("sub", recipe.sub)
                        .put("ingredients", recipe.ingredients).put("steps", JSONArray(recipe.steps)).put("note", recipe.note)
                },
            ),
        )
}
