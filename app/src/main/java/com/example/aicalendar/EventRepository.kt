package com.example.aicalendar

import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject

/** What became of a phone reminder once its upload was accepted. */
sealed interface UploadOutcome {
    data object Adopted : UploadOutcome
    data object DeletedMeanwhile : UploadOutcome
    data class EditedMeanwhile(val latest: PlannerEvent) : UploadOutcome
}

/**
 * The cloud reminders this phone deleted and keeps hidden. One stays hidden while the cloud still
 * lists it (its delete not delivered yet) or while a change for it is queued; once the cloud no
 * longer lists it, it is gone there and the entry is dropped, so a reminder the cloud brings back
 * later (restored on purpose) shows again.
 */
object HiddenRemoteIds {
    fun prune(hidden: Set<String>, listed: Set<String>, queued: Set<String>): Set<String> =
        hidden.filterTo(HashSet()) { it in listed || it in queued }
}

/** Result of applying a server snapshot. [previous] is the local state before the merge. */
data class RemoteMerge(
    val events: List<PlannerEvent>,
    val removed: List<PlannerEvent>,
    val previous: Map<String, PlannerEvent>,
)

/**
 * Local event store (one JSON document in SharedPreferences, format unchanged from v0.9.1).
 *
 * Every read-modify-write runs under the process-wide [LocalStoreLock], so separate instances (UI,
 * sync worker, notification receiver) can no longer overwrite each other's changes.
 */
class EventRepository(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Preferences backing the event list; register a change listener here to observe updates. */
    val preferences: SharedPreferences get() = prefs

    fun loadEvents(): List<PlannerEvent> = synchronized(LocalStoreLock) { readEvents() }

    fun saveEvents(events: List<PlannerEvent>) = synchronized(LocalStoreLock) { writeEvents(events) }

    fun upsert(event: PlannerEvent): List<PlannerEvent> = synchronized(LocalStoreLock) {
        val overrides = categoryOverrides().toMutableMap()
        applyOverride(overrides, event.id, event.category)
        writeCategoryOverrides(overrides)
        val current = readEvents().filterNot { it.id == event.id }
        (current + event).sortedEvents().also(::writeEvents)
    }

    fun upsertAll(incoming: List<PlannerEvent>): List<PlannerEvent> = synchronized(LocalStoreLock) {
        val byId = readEvents().associateBy { it.id }.toMutableMap()
        val overrides = categoryOverrides().toMutableMap()
        incoming.forEach { event ->
            val importKey = "${event.title}|${event.startAtMillis}|${event.location}"
            val duplicate = byId[event.id] ?: byId.values.firstOrNull {
                "${it.title}|${it.startAtMillis}|${it.location}" == importKey
            }
            // Never let an import overwrite a cloud-managed reminder; it would diverge from the server.
            if (duplicate?.remote == true) return@forEach
            val targetId = duplicate?.id ?: event.id
            val merged = event.copy(
                id = targetId,
                done = duplicate?.done ?: event.done,
                archived = duplicate?.archived ?: event.archived,
                category = if (event.category != EventCategory.AUTO) event.category else duplicate?.category ?: EventCategory.AUTO,
                domain = if (event.domain != EventDomain.AUTO) event.domain else duplicate?.domain ?: EventDomain.AUTO,
            )
            byId[targetId] = merged
            applyOverride(overrides, targetId, merged.category)
        }
        writeCategoryOverrides(overrides)
        byId.values.toList().sortedEvents().also(::writeEvents)
    }

    fun replaceRemote(snapshot: RemoteSnapshot): RemoteMerge = synchronized(LocalStoreLock) {
        val current = readEvents()
        val archivedIds = manuallyArchivedIds()
        val categoryOverrides = categoryOverrides()
        val hidden = hiddenRemoteIds()
        val pending = PendingChangeStore(appContext).all()
        val incoming = PendingChanges.overlay(snapshot.events, pending).filterNot { it.id in hidden }
        val incomingIds = incoming.mapTo(HashSet()) { it.id }
        // Keep our copy of records the server sent in a malformed shape instead of deleting them.
        val keptUnparsed = current.filter { it.remote && it.id in snapshot.unparsedIds && it.id !in incomingIds }
        val removed = current.filter { it.remote && it.id !in incomingIds && it.id !in snapshot.unparsedIds }
        // A reminder this phone uploaded is listed by the server under the same id: the cloud copy
        // replaces the phone-only one.
        val local = current.filterNot { it.remote || it.id in incomingIds }
        val merged = incoming.map { event ->
            event.copy(
                remote = true,
                archived = event.id in archivedIds,
                category = categoryOverrides[event.id] ?: event.category,
            )
        }
        val updated = (local + keptUnparsed + merged).sortedEvents()
        writeEvents(updated)
        val stillHidden = HiddenRemoteIds.prune(hidden, snapshot.events.mapTo(HashSet()) { it.id } + snapshot.unparsedIds, pending.keys)
        if (stillHidden != hidden) prefs.edit().putStringSet(KEY_HIDDEN_REMOTE_IDS, stillHidden).persist()
        RemoteMerge(updated, removed, current.associateBy { it.id })
    }

    fun setArchived(eventId: String, archived: Boolean): List<PlannerEvent> = synchronized(LocalStoreLock) {
        val archivedIds = manuallyArchivedIds().toMutableSet().apply {
            if (archived) add(eventId) else remove(eventId)
        }
        prefs.edit().putStringSet(KEY_MANUALLY_ARCHIVED_IDS, archivedIds).persist()
        readEvents().map { event ->
            if (event.id == eventId) event.copy(archived = archived) else event
        }.sortedEvents().also(::writeEvents)
    }

    fun markDone(eventId: String, done: Boolean = true): PlannerEvent? = synchronized(LocalStoreLock) {
        val updated = readEvents().map {
            if (it.id == eventId) it.copy(done = done) else it
        }
        writeEvents(updated)
        updated.firstOrNull { it.id == eventId }
    }

    /**
     * Saves [event] locally and, for a cloud event, queues it for delivery in the same critical
     * section, so a concurrent sync overlays it instead of reverting it.
     */
    fun saveAndQueue(event: PlannerEvent, op: PendingOp = PendingOp.UPDATE): List<PlannerEvent> = synchronized(LocalStoreLock) {
        // The copy from before the edit tells, should the cloud refuse it as stale, what was changed.
        val before = readEvents().firstOrNull { it.id == event.id }
        val updated = upsert(event)
        if (event.remote) PendingChangeStore(appContext).enqueue(event.id, op, event.takeIf { op == PendingOp.UPDATE }, before)
        updated
    }

    /** Notification "Done": durable local completion plus a queued delivery for cloud events. */
    fun markDoneAndQueue(eventId: String): PlannerEvent? = synchronized(LocalStoreLock) {
        val changed = markDone(eventId, true) ?: return@synchronized null
        if (changed.remote) PendingChangeStore(appContext).enqueue(eventId, PendingOp.MARK_DONE)
        changed
    }

    fun deleteAndQueue(event: PlannerEvent): List<PlannerEvent> = synchronized(LocalStoreLock) {
        val updated = deletePermanently(event)
        if (event.remote) PendingChangeStore(appContext).enqueue(event.id, PendingOp.DELETE)
        updated
    }

    fun delete(eventId: String): List<PlannerEvent> = synchronized(LocalStoreLock) {
        prefs.edit().putStringSet(KEY_MANUALLY_ARCHIVED_IDS, manuallyArchivedIds() - eventId).persist()
        val overrides = categoryOverrides().toMutableMap()
        applyOverride(overrides, eventId, EventCategory.AUTO)
        writeCategoryOverrides(overrides)
        readEvents().filterNot { it.id == eventId }.sortedEvents().also(::writeEvents)
    }

    fun deletePermanently(event: PlannerEvent): List<PlannerEvent> = synchronized(LocalStoreLock) {
        if (event.remote) {
            prefs.edit().putStringSet(KEY_HIDDEN_REMOTE_IDS, hiddenRemoteIds() + event.id).persist()
        }
        delete(event.id)
    }

    fun find(eventId: String): PlannerEvent? = loadEvents().firstOrNull { it.id == eventId }

    /** Records the server's revision after it accepted a change, so the next edit is not seen as stale. */
    fun updateRevision(eventId: String, revision: Long) = synchronized(LocalStoreLock) {
        if (revision <= 0L) return@synchronized
        val current = readEvents()
        if (current.none { it.id == eventId && it.revision != revision }) return@synchronized
        writeEvents(current.map { if (it.id == eventId) it.copy(revision = revision) else it })
    }

    /** Reminders that exist only on this phone and are fit to upload. */
    fun localOnly(): List<PlannerEvent> = loadEvents().filter { !it.remote && it.validationError() == null }

    /**
     * After an upload of [sent]: the phone copy becomes the cloud reminder (same id, so alarms,
     * archive state and colors carry over). Says whether it was deleted on the phone while the
     * upload was in flight, or edited meanwhile, so the caller can delete it in the cloud or send
     * the edit.
     */
    fun adoptUploaded(sent: PlannerEvent): UploadOutcome = synchronized(LocalStoreLock) {
        val current = readEvents()
        val local = current.firstOrNull { it.id == sent.id } ?: return@synchronized UploadOutcome.DeletedMeanwhile
        writeEvents(current.map { if (it.id == sent.id) it.copy(remote = true) else it })
        if (local.copy(remote = false) == sent.copy(remote = false)) UploadOutcome.Adopted
        else UploadOutcome.EditedMeanwhile(local.copy(remote = true))
    }

    /**
     * Takes a series' unfinished occurrences off the phone ahead of the server: those on or after
     * [fromDate], or without one every one still ahead. Returns what was removed, to cancel alarms.
     */
    fun removeSeriesOccurrences(
        seriesId: String,
        fromDate: String?,
        nowMillis: Long = System.currentTimeMillis(),
    ): List<PlannerEvent> = synchronized(LocalStoreLock) {
        val current = readEvents()
        val removed = current.filter { event ->
            event.seriesId == seriesId && !event.done &&
                if (fromDate.isNullOrBlank()) event.startAtMillis >= nowMillis else event.occurrenceDate >= fromDate
        }
        if (removed.isNotEmpty()) writeEvents(current - removed.toSet())
        removed
    }

    companion object {
        const val FILE = "planner_events"
        const val KEY_EVENTS = "events_json"
        private const val KEY_EVENTS_CORRUPT_BACKUP = "events_json_unreadable_backup"
        private const val KEY_HIDDEN_REMOTE_IDS = "hidden_remote_ids"
        private const val KEY_MANUALLY_ARCHIVED_IDS = "manually_archived_ids"
        private const val KEY_CATEGORY_OVERRIDES = "category_overrides_json"
    }

    private fun readEvents(): List<PlannerEvent> {
        val raw = prefs.getString(KEY_EVENTS, "[]") ?: "[]"
        val array = runCatching { JSONArray(raw) }.getOrElse {
            // Keep the unreadable document so it can be recovered instead of silently overwritten.
            if (!prefs.contains(KEY_EVENTS_CORRUPT_BACKUP)) {
                prefs.edit().putString(KEY_EVENTS_CORRUPT_BACKUP, raw).commit()
            }
            JSONArray()
        }
        return buildList {
            for (index in 0 until array.length()) {
                val json = array.optJSONObject(index) ?: continue
                val event = runCatching { PlannerEvent.fromJson(json) }.getOrNull()
                if (event != null && event.title.isNotBlank() && event.startAtMillis > 0L) {
                    add(event)
                }
            }
        }.sortedEvents()
    }

    private fun writeEvents(events: List<PlannerEvent>) {
        val array = JSONArray()
        events.sortedEvents().forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_EVENTS, array.toString()).persist()
    }

    private fun hiddenRemoteIds(): Set<String> =
        prefs.getStringSet(KEY_HIDDEN_REMOTE_IDS, emptySet())?.toSet().orEmpty()

    private fun manuallyArchivedIds(): Set<String> =
        prefs.getStringSet(KEY_MANUALLY_ARCHIVED_IDS, emptySet())?.toSet().orEmpty()

    private fun categoryOverrides(): Map<String, EventCategory> {
        val json = runCatching { JSONObject(prefs.getString(KEY_CATEGORY_OVERRIDES, "{}") ?: "{}") }
            .getOrElse { JSONObject() }
        return buildMap {
            json.keys().forEach { id ->
                EventCategory.fromStored(json.optString(id)).takeIf { it != EventCategory.AUTO }?.let { put(id, it) }
            }
        }
    }

    private fun applyOverride(overrides: MutableMap<String, EventCategory>, eventId: String, category: EventCategory) {
        if (category == EventCategory.AUTO) overrides.remove(eventId) else overrides[eventId] = category
    }

    private fun writeCategoryOverrides(overrides: Map<String, EventCategory>) {
        val json = JSONObject()
        overrides.forEach { (id, value) -> json.put(id, value.name) }
        prefs.edit().putString(KEY_CATEGORY_OVERRIDES, json.toString()).persist()
    }
}

/**
 * apply() on the main thread (never block the UI); commit() elsewhere, because a worker or receiver
 * process can be killed right after returning and an unflushed apply() would lose the change.
 */
internal fun SharedPreferences.Editor.persist() {
    if (Looper.myLooper() == Looper.getMainLooper()) apply() else commit()
}

private fun List<PlannerEvent>.sortedEvents(): List<PlannerEvent> =
    sortedWith(compareBy<PlannerEvent> { it.startAtMillis }.thenBy { it.id })
