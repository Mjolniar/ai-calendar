package com.example.aicalendar

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock

/**
 * What Kitchen keeps on the phone, apart from the reminders' stores: the last good snapshot
 * (`kitchen_cache`, shown when offline) and the queue of edits waiting for the cloud (`kitchen_outbox`).
 */
class KitchenStore(context: Context) {
    private val cache = context.applicationContext.getSharedPreferences(CACHE_FILE, Context.MODE_PRIVATE)
    private val queue = context.applicationContext.getSharedPreferences(OUTBOX_FILE, Context.MODE_PRIVATE)

    val outbox = KitchenOutbox(
        object : KitchenOutboxStorage {
            override fun read(): String? = queue.getString(KEY_EDITS, null)

            override fun write(text: String) {
                // Written at once (commit): a queued edit must survive the app being closed right after.
                queue.edit(commit = true) { putString(KEY_EDITS, text) }
            }
        },
    )

    /** The cached snapshot and when it was fetched, or null when there is none (or it is unreadable). */
    fun cached(): Pair<KitchenSnapshot, Instant>? {
        val text = cache.getString(KEY_SNAPSHOT, null) ?: return null
        return runCatching {
            KitchenParser.parseSnapshot(JSONObject(text)) to Instant.ofEpochMilli(cache.getLong(KEY_FETCHED_AT, 0L))
        }.getOrNull()
    }

    fun saveCache(snapshot: KitchenSnapshot, fetchedAt: Instant) {
        cache.edit {
            putString(KEY_SNAPSHOT, KitchenCacheJson.write(snapshot).toString())
            putLong(KEY_FETCHED_AT, fetchedAt.toEpochMilli())
        }
    }

    /** Edits the cloud refused while no one was looking (background sends), for the next balloon. */
    fun recordRejected(labels: List<String>) {
        if (labels.isEmpty()) return
        val all = takeRejectedInternal(clear = false) + labels
        queue.edit { putString(KEY_REJECTED, JSONArray(all.takeLast(10)).toString()) }
    }

    fun takeRejected(): List<String> = takeRejectedInternal(clear = true)

    private fun takeRejectedInternal(clear: Boolean): List<String> {
        val text = queue.getString(KEY_REJECTED, null) ?: return emptyList()
        if (clear) queue.edit { remove(KEY_REJECTED) }
        return runCatching {
            val array = JSONArray(text)
            (0 until array.length()).map { array.optString(it) }.filter(String::isNotBlank)
        }.getOrDefault(emptyList())
    }

    companion object {
        const val CACHE_FILE = "kitchen_cache"
        const val OUTBOX_FILE = "kitchen_outbox"
        private const val KEY_SNAPSHOT = "snapshot"
        private const val KEY_FETCHED_AT = "fetched_at"
        private const val KEY_EDITS = "edits"
        private const val KEY_REJECTED = "rejected"
    }
}

/**
 * Kitchen work that is not tied to the screen: the `kitchen_sync` push, and sending the outbox from
 * the reminders worker. Never schedules work of its own and never touches the reminders.
 */
object KitchenSync {
    private val sending = ReentrantLock()
    private val main by lazy { Handler(Looper.getMainLooper()) }

    @Volatile
    private var live: (() -> Unit)? = null

    /** Set while the Kitchen view is showing and the app is in the foreground. */
    fun setLiveListener(listener: () -> Unit) {
        live = listener
    }

    /** Clears [listener] if it is still the one set (a newer screen may have replaced it). */
    fun clearLiveListener(listener: () -> Unit) {
        if (live === listener) live = null
    }

    /**
     * A `kitchen_sync` signal (FCM thread). With Kitchen on screen it asks the view to fetch again
     * (the view coalesces bursts); otherwise it does nothing at all.
     */
    fun signal() {
        if (live == null) return
        main.post { live?.invoke() }
    }

    /** Sends the outbox, unless another sender is already at it (then null). Blocking. */
    fun flush(outbox: KitchenOutbox, client: KitchenClient): KitchenFlushReport? {
        if (!sending.tryLock()) return null
        return try {
            KitchenFlusher.flush(outbox, client)
        } finally {
            sending.unlock()
        }
    }

    /**
     * From BridgeSyncWorker, after the reminders: when edits are queued, fetch, send them and fetch
     * again, caching each good snapshot. Swallows every failure, so the reminders' run is not affected.
     */
    fun sendFromWorker(context: Context) {
        try {
            val store = KitchenStore(context)
            if (store.outbox.isEmpty()) return
            val client = BridgeApi.kitchenClient(context)
            val first = client.load() as? KitchenResult.Ok ?: return
            store.saveCache(first.value, Instant.now())
            val report = flush(store.outbox, client) ?: return
            store.recordRejected(report.rejected)
            if (report.changedAnything) {
                (client.load() as? KitchenResult.Ok)?.let { store.saveCache(it.value, Instant.now()) }
            }
        } catch (_: Throwable) {
            // Kitchen failures never affect reminder sync; the edits stay queued for the next try.
        }
    }
}
