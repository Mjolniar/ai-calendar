package com.example.aicalendar

import android.content.Context
import org.json.JSONObject

enum class PendingOp {
    /**
     * Upload a reminder made on this phone (`POST /v1/events`). The entry carries no copy: the
     * reminder is read from the local store when it is sent, so edits made before then go with it
     * and a reminder deleted before then is simply not sent.
     */
    CREATE,
    MARK_DONE,
    /** POST /v1/events/:id/undone: reopen without sending the rest of the reminder. */
    MARK_UNDONE,
    UPDATE,
    DELETE,
    /** POST /v1/series with [PendingChange.payload]. */
    SERIES_CREATE,
    /** PATCH /v1/series/[PendingChange.target] with [PendingChange.payload]. */
    SERIES_UPDATE,
    /** DELETE /v1/series/[PendingChange.target]; [PendingChange.payload] holds the `from` date, if any. */
    SERIES_DELETE;

    val isSeries: Boolean get() = this == SERIES_CREATE || this == SERIES_UPDATE || this == SERIES_DELETE
}

/**
 * One queued phone-side mutation of a remote (cloud) event. At most one entry exists per event: newer
 * changes are coalesced into it. [seq] increases with every coalesce so the sender only removes an
 * entry after acknowledging exactly the version it delivered.
 *
 * A series change is keyed by its own idempotency key ([seriesKey]), so each one is delivered once,
 * in order, and never merged with another.
 *
 * An edit also keeps [base], the phone's copy from before the first queued edit: when the cloud
 * refuses the edit because the reminder changed elsewhere (409), it shows which fields the person
 * changed (ReminderMerge). Entries queued before 0.20.0 have none.
 */
data class PendingChange(
    val eventId: String,
    val op: PendingOp,
    val event: PlannerEvent? = null,
    val seq: Long,
    val attempts: Int = 0,
    val target: String = "",
    val payload: String = "",
    val base: PlannerEvent? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", eventId)
        .put("op", op.name)
        .put("seq", seq)
        .put("attempts", attempts)
        .apply {
            event?.let { put("event", it.toJson()) }
            base?.let { put("base", it.toJson()) }
            if (target.isNotEmpty()) put("target", target)
            if (payload.isNotEmpty()) put("payload", payload)
        }

    companion object {
        fun fromJson(json: JSONObject): PendingChange? = runCatching {
            val op = PendingOp.valueOf(json.getString("op"))
            val event = json.optJSONObject("event")?.let(PlannerEvent::fromJson)
            require(op != PendingOp.UPDATE || event != null) { "update without event" }
            PendingChange(
                eventId = json.getString("id"),
                op = op,
                event = event,
                seq = json.optLong("seq"),
                attempts = json.optInt("attempts"),
                target = json.optString("target"),
                payload = json.optString("payload"),
                base = json.optJSONObject("base")?.let { runCatching { PlannerEvent.fromJson(it) }.getOrNull() },
            )
        }.getOrNull()

        /** The outbox key of a series change. */
        fun seriesKey(idempotencyKey: String): String = "series:$idempotencyKey"
    }
}

object PendingChanges {
    /** Merge a new change into the existing queued change for the same event. */
    fun coalesce(existing: PendingChange?, incoming: PendingChange): PendingChange = when {
        existing == null -> incoming
        incoming.op == PendingOp.DELETE -> incoming
        existing.op == PendingOp.DELETE -> existing.copy(seq = incoming.seq)
        // The base stays the copy from before the first queued change; a queued Done or not
        // done was a change of the person's too, so the base is taken from before it.
        incoming.op == PendingOp.UPDATE -> incoming.copy(
            base = existing.base ?: incoming.base?.let { base ->
                when (existing.op) {
                    PendingOp.MARK_DONE -> base.copy(done = false)
                    PendingOp.MARK_UNDONE -> base.copy(done = true)
                    else -> base
                }
            },
        )
        // Done or not done on top of a queued full update: keep the update, with that state.
        existing.op == PendingOp.UPDATE -> existing.copy(
            event = existing.event?.copy(done = incoming.op == PendingOp.MARK_DONE),
            seq = incoming.seq,
        )
        else -> incoming
    }

    /**
     * Overlay queued phone changes on a server snapshot so a sync that raced ahead of delivery does
     * not undo them (e.g. an offline Done reverting to not-done). Server-only fields are kept.
     */
    fun overlay(remote: List<PlannerEvent>, pending: Map<String, PendingChange>): List<PlannerEvent> {
        if (pending.isEmpty()) return remote
        // A series deleted offline: its occurrences from that date stay gone until the delete is delivered.
        val seriesDeletes = pending.values.filter { it.op == PendingOp.SERIES_DELETE }
        return remote.mapNotNull { event ->
            if (seriesDeletes.any { it.removes(event) }) return@mapNotNull null
            val change = pending[event.id] ?: return@mapNotNull event
            when (change.op) {
                PendingOp.DELETE -> null
                PendingOp.MARK_DONE -> event.copy(done = true)
                PendingOp.MARK_UNDONE -> event.copy(done = false)
                PendingOp.UPDATE -> change.event?.let { local ->
                    event.copy(
                        title = local.title,
                        startAtMillis = local.startAtMillis,
                        location = local.location,
                        notes = local.notes,
                        critical = local.critical,
                        done = local.done,
                        domain = local.domain,
                        category = local.category,
                        reminderOffsetsMinutes = local.reminderOffsetsMinutes,
                    )
                } ?: event
                // Series changes are keyed by their own idempotency key, never by an event id.
                PendingOp.CREATE, PendingOp.SERIES_CREATE, PendingOp.SERIES_UPDATE, PendingOp.SERIES_DELETE -> event
            }
        }
    }
}

/**
 * An edit the cloud refused because the reminder changed elsewhere since this phone's copy (409,
 * which answers with the cloud's current copy). Field by field: what the person changed on the
 * phone goes on top of the cloud's copy, unless the cloud changed that same field to something
 * else meanwhile. Then nothing is merged and the conflicting fields are named.
 */
object ReminderMerge {
    data class Result(
        /** The cloud's copy with the phone's changes on top; null when a field conflicts. */
        val merged: PlannerEvent?,
        /** The fields changed on both sides, in words ("title", "time"). */
        val conflicts: List<String>,
    )

    fun merge(base: PlannerEvent, local: PlannerEvent, remote: PlannerEvent): Result {
        val conflicts = mutableListOf<String>()
        fun <T> pick(label: String, field: (PlannerEvent) -> T): T {
            val was = field(base)
            val mine = field(local)
            val theirs = field(remote)
            return when {
                // Not changed on the phone: the cloud's value.
                mine == was -> theirs
                // Changed only on the phone, or to the same value on both sides.
                theirs == was || theirs == mine -> mine
                else -> {
                    conflicts += label
                    theirs
                }
            }
        }
        val merged = remote.copy(
            title = pick("title") { it.title.trim() },
            startAtMillis = pick("time") { it.startAtMillis },
            location = pick("place") { it.location.trim() },
            notes = pick("notes") { it.notes.trim() },
            critical = pick("critical") { it.critical },
            done = pick("done") { it.done },
            domain = pick("calendar") { it.domain },
            category = pick("category") { it.category },
            reminderOffsetsMinutes = pick("alerts") { it.reminderOffsetsMinutes },
            // Kept on the phone only.
            archived = local.archived,
            remote = true,
        )
        return Result(merged.takeIf { conflicts.isEmpty() }, conflicts)
    }

    /** The issue shown when a queued edit could not be applied, naming the reminder. */
    fun issue(title: String, conflicts: List<String>): String {
        val which = if (conflicts.isEmpty()) "" else " (${conflicts.joinToString(", ")})"
        return "Couldn't save your change to \"$title\": it was also changed on another device$which while this phone was offline. " +
            "The cloud version is kept; make your change again."
    }
}

/**
 * Whether a queued series delete covers [event]: an unfinished occurrence of that series on or after
 * the `from` date, or, without one, any unfinished occurrence still ahead.
 */
internal fun PendingChange.removes(event: PlannerEvent, nowMillis: Long = System.currentTimeMillis()): Boolean {
    if (op != PendingOp.SERIES_DELETE || event.seriesId != target || event.done) return false
    return if (payload.isNotBlank()) event.occurrenceDate >= payload else event.startAtMillis >= nowMillis
}

/** Process-wide lock shared by every local store so read-modify-write sequences never interleave. */
internal object LocalStoreLock

/**
 * Durable outbox of phone changes to remote events, stored in its own preferences file. All access is
 * serialized with [LocalStoreLock] so it composes atomically with [EventRepository] writes.
 */
class PendingChangeStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun all(): Map<String, PendingChange> = synchronized(LocalStoreLock) { read() }

    fun get(eventId: String): PendingChange? = all()[eventId]

    fun isEmpty(): Boolean = all().isEmpty()

    /** [base] is the phone's copy from before an edit (see [PendingChange.base]). */
    fun enqueue(eventId: String, op: PendingOp, event: PlannerEvent? = null, base: PlannerEvent? = null): PendingChange = synchronized(LocalStoreLock) {
        val entries = read().toMutableMap()
        val nextSeq = prefs.getLong(KEY_SEQ, 0L) + 1
        val merged = PendingChanges.coalesce(entries[eventId], PendingChange(eventId, op, event, nextSeq, base = base?.takeIf { op == PendingOp.UPDATE }))
        entries[eventId] = merged
        write(entries, nextSeq)
        merged
    }

    /** Queues a series change under its idempotency key. A retry of the same key replaces it. */
    fun enqueueSeries(idempotencyKey: String, op: PendingOp, target: String, payload: String): PendingChange = synchronized(LocalStoreLock) {
        require(op.isSeries) { "not a series change" }
        val entries = read().toMutableMap()
        val nextSeq = prefs.getLong(KEY_SEQ, 0L) + 1
        val key = PendingChange.seriesKey(idempotencyKey)
        val change = PendingChange(key, op, null, nextSeq, target = target, payload = payload)
        entries[key] = change
        write(entries, nextSeq)
        change
    }

    /** Remove only if nothing newer was queued while the delivered version was in flight. */
    fun acknowledge(change: PendingChange): Boolean {
        synchronized(LocalStoreLock) {
            val entries = read().toMutableMap()
            val current = entries[change.eventId] ?: return true
            if (current.seq != change.seq) return false
            entries.remove(change.eventId)
            write(entries)
            return true
        }
    }

    fun recordAttempt(change: PendingChange) {
        synchronized(LocalStoreLock) {
            val entries = read().toMutableMap()
            val current = entries[change.eventId] ?: return
            entries[change.eventId] = current.copy(attempts = current.attempts + 1)
            write(entries)
        }
    }

    fun remove(eventId: String) {
        synchronized(LocalStoreLock) {
            val entries = read().toMutableMap()
            if (entries.remove(eventId) != null) write(entries)
        }
    }

    /** Last delivery problem that needs the user's attention (validation rejection, auth failure). */
    fun lastIssue(): String? = prefs.getString(KEY_ISSUE, null)

    fun recordIssue(message: String) {
        prefs.edit().putString(KEY_ISSUE, message).putLong(KEY_ISSUE_AT, System.currentTimeMillis()).apply()
    }

    fun clearIssue() {
        if (prefs.contains(KEY_ISSUE)) prefs.edit().remove(KEY_ISSUE).remove(KEY_ISSUE_AT).apply()
    }

    private fun read(): Map<String, PendingChange> {
        val raw = prefs.getString(KEY_ENTRIES, null) ?: return emptyMap()
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
        return buildMap {
            json.keys().forEach { key ->
                json.optJSONObject(key)?.let(PendingChange::fromJson)?.let { put(key, it) }
            }
        }
    }

    private fun write(entries: Map<String, PendingChange>, seq: Long? = null) {
        val json = JSONObject()
        entries.forEach { (id, change) -> json.put(id, change.toJson()) }
        // commit(): the outbox is the only record of an offline change, so persist before returning.
        prefs.edit().putString(KEY_ENTRIES, json.toString()).apply { seq?.let { putLong(KEY_SEQ, it) } }.commit()
    }

    companion object {
        const val FILE = "pending_changes"
        const val KEY_ISSUE = "last_issue"
        private const val KEY_ENTRIES = "entries_json"
        private const val KEY_SEQ = "seq"
        private const val KEY_ISSUE_AT = "last_issue_at"
    }
}
