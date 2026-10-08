// HARNESS ONLY: stand-ins for the app's network, worker, parser and keystore classes, so the UI layer
// compiles and runs on the JVM without Firebase, WorkManager or a server. The real implementations
// are the files of the same names in app/src/main/java/com/example/aicalendar, which build.gradle.kts
// leaves out of this build (see `stubbedOut` there).
package com.example.aicalendar

import android.content.Context
import org.json.JSONObject

class BridgeHttpException(val status: Int, message: String) : java.io.IOException(message)

sealed interface RemoteWriteOutcome {
    data object Saved : RemoteWriteOutcome
    data object Queued : RemoteWriteOutcome
    data class Rejected(val message: String) : RemoteWriteOutcome
    data class Failed(val message: String) : RemoteWriteOutcome
}

object BridgeApi {
    fun connect(context: Context, serverUrl: String, pairingCode: String): Result<Int> = Result.success(0)
    fun saveRemoteEvent(context: Context, event: PlannerEvent): RemoteWriteOutcome {
        EventRepository(context).upsert(event)
        return RemoteWriteOutcome.Saved
    }
    fun deleteRemoteEvent(context: Context, event: PlannerEvent): RemoteWriteOutcome {
        EventRepository(context).deletePermanently(event)
        return RemoteWriteOutcome.Saved
    }
    fun sync(context: Context): Result<Int> = runCatching {
        SyncStatusStore.recordSuccess(context)
        EventRepository(context).loadEvents().count { it.remote }
    }
    fun syncCanvas(context: Context): Result<Int> = sync(context)

    /** What the UI asked the bridge to do with a series, for the flows to check. */
    data class SeriesCall(val op: PendingOp, val seriesId: String, val body: JSONObject?, val deleteFrom: String?)
    val seriesCalls = mutableListOf<SeriesCall>()

    fun sendSeriesChange(context: Context, op: PendingOp, seriesId: String, body: JSONObject?, deleteFrom: String? = null): RemoteWriteOutcome {
        seriesCalls += SeriesCall(op, seriesId, body, deleteFrom)
        if (op == PendingOp.SERIES_DELETE) EventRepository(context).removeSeriesOccurrences(seriesId, deleteFrom)
        return RemoteWriteOutcome.Saved
    }
}

class BridgeSyncWorker {
    companion object {
        fun schedule(context: Context) = Unit
        fun enqueueSync(context: Context) = Unit
        fun enqueueOutbox(context: Context) = Unit
        fun enqueueInstallationUpdate(context: Context, installationId: String) = Unit
    }
}

enum class PendingOp {
    MARK_DONE, UPDATE, DELETE, SERIES_CREATE, SERIES_UPDATE, SERIES_DELETE;

    val isSeries: Boolean get() = this == SERIES_CREATE || this == SERIES_UPDATE || this == SERIES_DELETE
}

data class PendingChange(
    val eventId: String,
    val op: PendingOp,
    val event: PlannerEvent? = null,
    val seq: Long,
    val attempts: Int = 0,
    val target: String = "",
    val payload: String = "",
)

object PendingChanges {
    fun overlay(remote: List<PlannerEvent>, pending: Map<String, PendingChange>): List<PlannerEvent> = remote
}

internal object LocalStoreLock

class PendingChangeStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    fun all(): Map<String, PendingChange> = buildMap {
        repeat(prefs.getInt("stub_pending_count", 0)) { index -> put("p$index", PendingChange("p$index", PendingOp.MARK_DONE, null, index.toLong())) }
    }
    fun get(eventId: String): PendingChange? = all()[eventId]
    fun isEmpty(): Boolean = all().isEmpty()
    fun enqueue(eventId: String, op: PendingOp, event: PlannerEvent? = null): PendingChange = PendingChange(eventId, op, event, 1)
    fun lastIssue(): String? = prefs.getString(KEY_ISSUE, null)
    fun recordIssue(message: String) { prefs.edit().putString(KEY_ISSUE, message).apply() }
    fun clearIssue() { prefs.edit().remove(KEY_ISSUE).apply() }

    companion object {
        const val FILE = "pending_changes"
        const val KEY_ISSUE = "last_issue"
    }
}

data class RemoteSnapshot(val events: List<PlannerEvent>, val unparsedIds: Set<String> = emptySet())

data class ImportResult(val events: List<PlannerEvent>, val warnings: List<String>)

object EventImportParser {
    fun parseDetailed(text: String): ImportResult = runCatching {
        val array = JSONObject(text).getJSONArray("events")
        ImportResult(List(array.length()) { PlannerEvent.fromJson(array.getJSONObject(it)) }, emptyList())
    }.getOrElse { ImportResult(emptyList(), listOf(it.message ?: "unreadable")) }
}

class SecureTokenStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("stub_secure", Context.MODE_PRIVATE)
    fun read(key: String): String? = prefs.getString(key, null)
    fun write(key: String, value: String): Boolean = prefs.edit().putString(key, value).commit()
    fun remove(key: String) { prefs.edit().remove(key).apply() }
    fun contains(key: String): Boolean = prefs.contains(key)
}

object FirebaseBootstrap {
    fun ensureInitialized(context: Context): Any? = null
}
