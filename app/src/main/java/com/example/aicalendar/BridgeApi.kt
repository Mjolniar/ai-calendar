package com.example.aicalendar

import android.content.Context
import android.os.Build
import com.google.android.gms.tasks.Tasks
import com.google.firebase.installations.FirebaseInstallations
import com.google.firebase.messaging.FirebaseMessaging
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** Non-2xx reply from the bridge. [status] 400 means the server rejected the data as invalid. */
class BridgeHttpException(val status: Int, message: String) : IOException(message) {
    val isValidationError: Boolean get() = status == 400 || status == 422
}

/** 2xx reply whose body is not the expected JSON (captive portal, proxy page). Retryable. */
class BridgeResponseException(message: String) : IOException(message)

enum class OutboxOutcome { DELIVERED, RETRY, BLOCKED }

/** Result of a foreground save/delete of a cloud reminder. */
sealed interface RemoteWriteOutcome {
    /** Server accepted the change; local state updated. */
    data object Saved : RemoteWriteOutcome
    /** Offline or server unavailable: saved on the phone and queued for delivery. */
    data object Queued : RemoteWriteOutcome
    /** Server rejected the change (validation / not found). Nothing was changed locally. */
    data class Rejected(val message: String) : RemoteWriteOutcome
    /** Could not be attempted (not paired, pairing revoked, ...). Nothing was changed locally. */
    data class Failed(val message: String) : RemoteWriteOutcome
}

object BridgeApi {
    /**
     * Serializes all bridge traffic that reads or writes the local store: a sync applies its snapshot
     * atomically with respect to phone edits, and responses can no longer be applied out of order.
     */
    private val syncLock = Any()

    private data class Credentials(val serverUrl: String, val token: String)

    fun isTransient(error: Throwable): Boolean = when (error) {
        is BridgeHttpException -> error.status == 408 || error.status == 425 || error.status == 429 || error.status >= 500
        is MalformedSnapshotException -> true
        is IOException -> true
        is JSONException -> true
        else -> false
    }

    fun connect(
        context: Context,
        serverUrl: String,
        pairingCode: String,
    ): Result<Int> = runCatching {
        val settings = BridgeSettings(context)
        val normalizedUrl = serverUrl.trim().trimEnd('/')
        val bootstrap = request(
            normalizedUrl + "/v1/bootstrap",
            "POST",
            null,
            JSONObject().put("pairingCode", pairingCode),
        ).getJSONObject("firebase")
        val config = BridgeConfig(
            serverUrl = normalizedUrl,
            firebaseProjectId = bootstrap.getString("projectId"),
            firebaseAppId = bootstrap.getString("appId"),
            firebaseApiKey = bootstrap.getString("apiKey"),
            firebaseSenderId = bootstrap.getString("senderId"),
        )
        settings.saveConfig(config)
        FirebaseBootstrap.ensureInitialized(context) ?: error("Firebase configuration could not be initialized")
        Tasks.await(FirebaseMessaging.getInstance().register(), 30, TimeUnit.SECONDS)
        val installationId = Tasks.await(FirebaseInstallations.getInstance().id, 30, TimeUnit.SECONDS)
        val body = JSONObject()
            .put("pairingCode", pairingCode)
            .put("installationId", installationId)
            .put("deviceName", "${Build.MANUFACTURER} ${Build.MODEL}")
        val response = request(config.serverUrl + "/v1/devices/register", "POST", null, body)
        val deviceToken = response.getString("deviceToken")
        settings.saveDeviceToken(deviceToken)
        PendingChangeStore(context).clearIssue()
        BridgeSyncWorker.schedule(context)
        sync(context).getOrThrow()
    }

    fun updateInstallationId(context: Context, installationId: String): Result<Unit> = runCatching {
        val credentials = credentials(context)
        request(
            credentials.serverUrl + "/v1/devices/installation-id",
            "POST",
            credentials.token,
            JSONObject().put("installationId", installationId),
        )
        Unit
    }

    /**
     * Foreground edit of a cloud reminder (editor save, list Done toggle). Tries the server first so
     * a validation error can be shown in place; on a transient failure the change is kept on the phone
     * and queued. Alarms are the caller's responsibility.
     */
    fun saveRemoteEvent(context: Context, event: PlannerEvent): RemoteWriteOutcome = synchronized(syncLock) {
        val repository = EventRepository(context)
        val store = PendingChangeStore(context)
        // An older change for this event is still queued: queue behind it to keep ordering.
        if (store.get(event.id) != null) {
            repository.saveAndQueue(event, PendingOp.UPDATE)
            return@synchronized RemoteWriteOutcome.Queued
        }
        val credentials = runCatching { credentials(context) }.getOrElse {
            return@synchronized RemoteWriteOutcome.Failed(it.message ?: "Phone is not paired")
        }
        runCatching { patchEvent(credentials, event) }.fold(
            onSuccess = {
                repository.upsert(event)
                store.clearIssue()
                RemoteWriteOutcome.Saved
            },
            onFailure = { error -> writeFailure(error, onTransient = { repository.saveAndQueue(event, PendingOp.UPDATE) }) },
        )
    }

    fun deleteRemoteEvent(context: Context, event: PlannerEvent): RemoteWriteOutcome = synchronized(syncLock) {
        val repository = EventRepository(context)
        val store = PendingChangeStore(context)
        if (store.get(event.id) != null) {
            repository.deleteAndQueue(event)
            return@synchronized RemoteWriteOutcome.Queued
        }
        val credentials = runCatching { credentials(context) }.getOrElse {
            return@synchronized RemoteWriteOutcome.Failed(it.message ?: "Phone is not paired")
        }
        val result = runCatching { request(credentials.serverUrl + "/v1/events/${pathId(event.id)}", "DELETE", credentials.token, null) }
        val error = result.exceptionOrNull()
        if (error == null || (error is BridgeHttpException && error.status == 404)) {
            repository.deletePermanently(event)
            RemoteWriteOutcome.Saved
        } else {
            writeFailure(error, onTransient = { repository.deleteAndQueue(event) })
        }
    }

    /**
     * Sends a series change: create (no [seriesId]), change ([seriesId] with a body) or delete
     * ([seriesId], [deleteFrom] for "this and all future ones"). The body carries an idempotency key,
     * so a change that is queued and sent again cannot be applied twice. On success the phone syncs
     * at once, so the occurrences the server made are listed and armed.
     */
    fun sendSeriesChange(
        context: Context,
        op: PendingOp,
        seriesId: String,
        body: JSONObject?,
        deleteFrom: String? = null,
    ): RemoteWriteOutcome = synchronized(syncLock) {
        require(op.isSeries) { "not a series change" }
        val key = body?.optString("idempotencyKey")?.takeIf(String::isNotBlank) ?: java.util.UUID.randomUUID().toString()
        val payload = when (op) {
            PendingOp.SERIES_DELETE -> deleteFrom.orEmpty()
            else -> body!!.put("idempotencyKey", key).toString()
        }
        val change = PendingChange(PendingChange.seriesKey(key), op, null, 0, target = seriesId, payload = payload)
        val store = PendingChangeStore(context)
        // Older changes are still queued: keep the order.
        if (!store.isEmpty()) {
            store.enqueueSeries(key, op, seriesId, payload)
            return@synchronized RemoteWriteOutcome.Queued
        }
        val credentials = runCatching { credentials(context) }.getOrElse {
            return@synchronized RemoteWriteOutcome.Failed(it.message ?: "Phone is not paired")
        }
        val error = runCatching { send(credentials, change) }.exceptionOrNull()
        when {
            error == null -> {
                store.clearIssue()
                runCatching { sync(context).getOrThrow() }
                RemoteWriteOutcome.Saved
            }
            error is BridgeHttpException && error.status == 404 && op == PendingOp.SERIES_DELETE -> {
                runCatching { sync(context).getOrThrow() }
                RemoteWriteOutcome.Saved
            }
            error is BridgeHttpException && error.status == 404 ->
                RemoteWriteOutcome.Rejected("This repeating reminder no longer exists in the cloud. Sync to refresh.")
            else -> writeFailure(error, onTransient = {
                store.enqueueSeries(key, op, seriesId, payload)
                if (op == PendingOp.SERIES_DELETE) {
                    EventRepository(context).removeSeriesOccurrences(seriesId, deleteFrom)
                        .forEach { AlarmScheduler.cancelEvent(context, it.id) }
                }
            })
        }
    }

    private fun writeFailure(error: Throwable, onTransient: () -> Unit): RemoteWriteOutcome = when {
        isTransient(error) -> {
            onTransient()
            RemoteWriteOutcome.Queued
        }
        error is BridgeHttpException && error.status == 404 ->
            RemoteWriteOutcome.Rejected("This reminder no longer exists in the cloud. Sync to refresh.")
        error is BridgeHttpException && (error.status == 401 || error.status == 403) ->
            RemoteWriteOutcome.Failed("Pairing was rejected by the bridge. Reconnect the phone.")
        error is BridgeHttpException ->
            RemoteWriteOutcome.Rejected(error.message ?: "The server rejected this change (HTTP ${error.status}).")
        else -> RemoteWriteOutcome.Failed(error.message ?: "Save failed")
    }

    /**
     * Deliver queued phone changes. Entries are removed only after the server acknowledged exactly
     * the version that was sent; a rejection (400) is dropped and reported instead of retried forever.
     */
    fun flushOutbox(context: Context): OutboxOutcome = synchronized(syncLock) {
        val store = PendingChangeStore(context)
        val pending = store.all().values.sortedBy { it.seq }
        if (pending.isEmpty()) return@synchronized OutboxOutcome.DELIVERED
        val credentials = runCatching { credentials(context) }.getOrNull()
            ?: return@synchronized OutboxOutcome.BLOCKED
        var retry = false
        for (change in pending) {
            val error = runCatching { send(credentials, change) }.exceptionOrNull()
            when {
                error == null -> store.acknowledge(change)
                // Already gone on the server: nothing left to deliver.
                error is BridgeHttpException && error.status == 404 -> store.acknowledge(change)
                error is BridgeHttpException && (error.status == 401 || error.status == 403) -> {
                    store.recordIssue("The bridge rejected this phone's pairing. Reconnect to deliver pending changes.")
                    return@synchronized OutboxOutcome.BLOCKED
                }
                isTransient(error) -> {
                    store.recordAttempt(change)
                    retry = true
                }
                else -> {
                    store.acknowledge(change)
                    val title = change.event?.title
                        ?: runCatching { JSONObject(change.payload).optString("title") }.getOrNull()?.takeIf(String::isNotBlank)
                        ?: SeriesStore(context).load()[change.target]?.title?.takeIf { change.op.isSeries }
                        ?: EventRepository(context).find(change.eventId)?.title
                        ?: "a reminder"
                    store.recordIssue("Couldn't save \"$title\": ${error.message ?: "rejected by the server"}. The cloud version was restored.")
                }
            }
        }
        if (retry) OutboxOutcome.RETRY else OutboxOutcome.DELIVERED
    }

    private fun send(credentials: Credentials, change: PendingChange) {
        val base = credentials.serverUrl + "/v1/events/${pathId(change.eventId)}"
        val series = credentials.serverUrl + "/v1/series"
        when (change.op) {
            PendingOp.MARK_DONE -> request("$base/done", "POST", credentials.token, JSONObject())
            PendingOp.UPDATE -> patchEvent(credentials, change.event ?: return)
            PendingOp.DELETE -> request(base, "DELETE", credentials.token, null)
            PendingOp.SERIES_CREATE -> request(series, "POST", credentials.token, JSONObject(change.payload))
            PendingOp.SERIES_UPDATE -> request("$series/${pathId(change.target)}", "PATCH", credentials.token, JSONObject(change.payload))
            PendingOp.SERIES_DELETE -> {
                val from = change.payload.takeIf { it.matches(Regex("""\d{4}-\d{2}-\d{2}""")) }
                request("$series/${pathId(change.target)}" + (from?.let { "?from=$it" } ?: ""), "DELETE", credentials.token, null)
            }
        }
    }

    private fun patchEvent(credentials: Credentials, event: PlannerEvent) {
        val offsets = JSONArray().also { array ->
            event.reminderOffsetsMinutes.forEach(array::put)
        }
        val body = JSONObject()
            .put("title", event.title.trim())
            .put("startAt", Instant.ofEpochMilli(event.startAtMillis).toString())
            .put("timeZone", ZoneId.systemDefault().id)
            .put("location", event.location)
            .put("notes", event.notes)
            .put("critical", event.critical)
            .put("done", event.done)
            .put("domain", event.resolvedDomain().name.lowercase())
            .put("category", event.resolvedCategory().name.lowercase())
            .put("reminderOffsetsMinutes", offsets)
        request(credentials.serverUrl + "/v1/events/${pathId(event.id)}", "PATCH", credentials.token, body)
    }

    /** Pull the authoritative list. Pending phone changes are delivered first and overlaid if still queued. */
    fun sync(context: Context): Result<Int> = runCatching {
        synchronized(syncLock) {
            val credentials = credentials(context)
            flushOutbox(context)
            val response = request(credentials.serverUrl + "/v1/sync", "GET", credentials.token, null)
            val snapshot = RemoteEventParser.parseSnapshot(response)
            val merge = EventRepository(context).replaceRemote(snapshot)
            snapshot.series?.let(SeriesStore(context)::replace)
            AlarmScheduler.reconcile(context, merge)
            SyncStatusStore.recordSuccess(context)
            if (!PendingChangeStore(context).isEmpty()) BridgeSyncWorker.enqueueOutbox(context)
            merge.events.count { it.remote }
        }
    }

    fun syncCanvas(context: Context): Result<Int> = runCatching {
        val credentials = credentials(context)
        val response = request(credentials.serverUrl + "/v1/canvas/sync", "POST", credentials.token, JSONObject())
        sync(context).getOrThrow()
        response.optInt("active", 0)
    }

    private fun credentials(context: Context): Credentials {
        val settings = BridgeSettings(context)
        val config = settings.config() ?: error("Bridge is not configured")
        val token = settings.deviceToken() ?: error("Phone is not paired")
        return Credentials(config.serverUrl, token)
    }

    private fun pathId(id: String): String = URLEncoder.encode(id, "UTF-8").replace("+", "%20")

    private fun request(url: String, method: String, token: String?, body: JSONObject?): JSONObject {
        // HTTPS only: the bearer device token must never travel in cleartext.
        require(url.startsWith("https://")) { "Use an HTTPS bridge URL" }
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.useCaches = false
            // A redirect would resend the Authorization header to another host.
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val message = runCatching { JSONObject(text).optString("error") }.getOrNull()
                    ?.takeIf { it.isNotBlank() && it != "null" }
                throw BridgeHttpException(status, message ?: "Bridge returned HTTP $status")
            }
            if (text.isBlank()) return JSONObject()
            return try {
                JSONObject(text)
            } catch (_: JSONException) {
                throw BridgeResponseException("Bridge returned an unreadable response")
            }
        } finally {
            connection.disconnect()
        }
    }
}
