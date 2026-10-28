package com.example.aicalendar

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.Executors
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

    /** Writes the copy now: apply() on the main thread, commit() in a worker (EventRepository.persist). */
    fun saveCache(snapshot: KitchenSnapshot, fetchedAt: Instant) {
        cache.edit()
            .putString(KEY_SNAPSHOT, KitchenCacheJson.write(snapshot).toString())
            .putLong(KEY_FETCHED_AT, fetchedAt.toEpochMilli())
            .persist()
    }

    /** Writes the copy off the main thread, one write after another, so the newest lands last. */
    fun saveCacheLater(snapshot: KitchenSnapshot, fetchedAt: Instant) {
        disk.execute { runCatching { saveCache(snapshot, fetchedAt) } }
    }

    /** Edits the cloud refused while no one was looking (background sends), for the next balloon. */
    fun recordRejected(labels: List<String>) {
        if (labels.isEmpty()) return
        synchronized(KitchenOutbox.LOCK) {
            val all = readRejected() + labels
            queue.edit(commit = true) { putString(KEY_REJECTED, JSONArray(all.takeLast(10)).toString()) }
        }
    }

    fun takeRejected(): List<String> = synchronized(KitchenOutbox.LOCK) {
        val taken = readRejected()
        if (queue.contains(KEY_REJECTED)) queue.edit(commit = true) { remove(KEY_REJECTED) }
        taken
    }

    private fun readRejected(): List<String> {
        val text = queue.getString(KEY_REJECTED, null) ?: return emptyList()
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

        /** One thread for the view's cache writes. */
        private val disk = Executors.newSingleThreadExecutor { task -> Thread(task, "kitchen-cache").apply { isDaemon = true } }
    }
}

/** What the background kitchen job did, for WorkManager. */
enum class KitchenWorkerRun {
    /** Nothing was queued. */
    NOTHING,

    /** The queue was sent (what the cloud refused is kept for a balloon). */
    SENT,

    /** The cloud could not be reached, or another sender was at it: edits wait, try again later. */
    RETRY,
}

/**
 * Kitchen work that is not tied to the screen: the `kitchen_sync` push, the background job that
 * sends the outbox (BridgeSyncWorker's kitchen mode, enqueued when the queue gets its first edit and
 * after the reminders' runs), and a watch on the network while Kitchen is on screen. It never
 * touches the reminders, their queue or the alarms.
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
     * The background kitchen job: when edits are queued, fetch, send them and fetch again, caching
     * what that leaves (the sent edits' answers when the last fetch fails). Never throws.
     */
    fun sendFromWorker(context: Context): KitchenWorkerRun = try {
        val store = KitchenStore(context)
        if (store.outbox.isEmpty()) {
            KitchenWorkerRun.NOTHING
        } else {
            val round = KitchenRound.run(store.outbox, BridgeApi.kitchenClient(context), ::flush)
            round.latest?.let { store.saveCache(it, Instant.now()) }
            round.report?.let { store.recordRejected(it.rejected) }
            // Left over: the cloud was not reached, sending stopped, or the view is sending them.
            if (round.queued.isEmpty()) KitchenWorkerRun.SENT else KitchenWorkerRun.RETRY
        }
    } catch (_: Throwable) {
        // Kitchen failures never affect anything else; the edits stay queued for the next try.
        KitchenWorkerRun.RETRY
    }

    /**
     * While Kitchen is on screen: [onBack] runs on the main thread whenever a network becomes the
     * default one (also once right away when there is one). Returns what stops the watch.
     */
    fun watchNetwork(context: Context, onBack: () -> Unit): () -> Unit {
        val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return {}
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                main.post(onBack)
            }
        }
        return runCatching {
            manager.registerDefaultNetworkCallback(callback)
            val stop: () -> Unit = { runCatching { manager.unregisterNetworkCallback(callback) } }
            stop
        }.getOrElse { {} }
    }
}
