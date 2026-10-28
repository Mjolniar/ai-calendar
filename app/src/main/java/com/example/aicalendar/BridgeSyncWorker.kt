package com.example.aicalendar

import android.content.Context
import android.os.Build
import android.os.Looper
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * All background bridge traffic runs here (never in unmanaged threads):
 * - periodic recovery sync (every 6 hours; the class name is kept so the existing schedule survives),
 * - FCM-triggered sync,
 * - outbox delivery of phone changes (Done / edits / deletes made offline),
 * - FCM installation-id updates,
 * - the kitchen's queued edits, as a job of their own (MODE_KITCHEN, unique name
 *   [UNIQUE_KITCHEN]): enqueued when the kitchen queue gets its first edit, and after a reminders
 *   run that finds edits waiting. It runs beside the reminder jobs, never inside them, so it cannot
 *   hold up a later reminder sync; it touches no reminders and no alarms.
 */
class BridgeSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val context = applicationContext
        if (!BridgeSettings(context).isConnected()) return@withContext Result.success()
        if (inputData.getString(KEY_MODE) == MODE_KITCHEN) {
            return@withContext when (KitchenSync.sendFromWorker(context)) {
                KitchenWorkerRun.RETRY -> if (runAttemptCount >= MAX_ONE_TIME_ATTEMPTS) Result.failure() else Result.retry()
                else -> Result.success()
            }
        }
        val outcome: kotlin.Result<*> = when (inputData.getString(KEY_MODE)) {
            MODE_INSTALLATION -> {
                val installationId = inputData.getString(KEY_INSTALLATION_ID)
                    ?: return@withContext Result.success()
                BridgeApi.updateInstallationId(context, installationId)
            }
            MODE_OUTBOX -> when (BridgeApi.flushOutbox(context)) {
                OutboxOutcome.RETRY -> kotlin.Result.failure(java.io.IOException("Outbox delivery pending"))
                OutboxOutcome.BLOCKED -> kotlin.Result.success(Unit)
                // Pull the authoritative state (also reverts any change the server rejected).
                OutboxOutcome.DELIVERED -> BridgeApi.sync(context)
            }
            else -> BridgeApi.sync(context)
        }
        // Then the kitchen's queued edits get their own job, after the reminders and walled off from their result.
        if (outcome.isSuccess && inputData.getString(KEY_MODE) != MODE_INSTALLATION) {
            runCatching { if (!KitchenStore(context).outbox.isEmpty()) enqueueKitchen(context) }
        }
        outcome.fold(
            onSuccess = { Result.success() },
            onFailure = { error ->
                when {
                    !BridgeApi.isTransient(error) -> Result.failure()
                    // One-time jobs give up eventually; the periodic sync keeps recovering.
                    inputData.getString(KEY_MODE) != null && runAttemptCount >= MAX_ONE_TIME_ATTEMPTS -> Result.failure()
                    else -> Result.retry()
                }
            },
        )
    }

    companion object {
        private const val UNIQUE_PERIODIC = "ai_calendar_remote_sync"
        private const val UNIQUE_SYNC_NOW = "ai_calendar_sync_now"
        private const val UNIQUE_OUTBOX = "ai_calendar_outbox"
        private const val UNIQUE_INSTALLATION = "ai_calendar_installation_id"
        private const val UNIQUE_KITCHEN = "ai_calendar_kitchen"
        private const val KEY_MODE = "mode"
        private const val KEY_INSTALLATION_ID = "installation_id"
        private const val MODE_SYNC = "sync"
        private const val MODE_OUTBOX = "outbox"
        private const val MODE_INSTALLATION = "installation"
        private const val MODE_KITCHEN = "kitchen"
        private const val MAX_ONE_TIME_ATTEMPTS = 12

        private val networkConstraint = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<BridgeSyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(networkConstraint)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        /** Sync soon (FCM signal). Coalesces bursts: skips if a sync is already waiting to run. */
        fun enqueueSync(context: Context) {
            if (hasWaitingWork(context, UNIQUE_SYNC_NOW)) return
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_SYNC_NOW,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                oneTimeRequest(workDataOf(KEY_MODE to MODE_SYNC)),
            )
        }

        /** Deliver queued phone changes as soon as the network allows. */
        fun enqueueOutbox(context: Context) {
            if (hasWaitingWork(context, UNIQUE_OUTBOX)) return
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_OUTBOX,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                oneTimeRequest(workDataOf(KEY_MODE to MODE_OUTBOX)),
            )
        }

        /**
         * Send the kitchen's queued edits as soon as there is a network: a one-time job of its own,
         * regular priority (the expedited quota stays for the reminders), retried with backoff
         * while the cloud can't be reached. Skips if one is already waiting.
         */
        fun enqueueKitchen(context: Context) {
            if (hasWaitingWork(context, UNIQUE_KITCHEN)) return
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_KITCHEN,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<BridgeSyncWorker>()
                    .setInputData(workDataOf(KEY_MODE to MODE_KITCHEN))
                    .setConstraints(networkConstraint)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build(),
            )
        }

        fun enqueueInstallationUpdate(context: Context, installationId: String) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_INSTALLATION,
                ExistingWorkPolicy.REPLACE,
                oneTimeRequest(workDataOf(KEY_MODE to MODE_INSTALLATION, KEY_INSTALLATION_ID to installationId)),
            )
        }

        private fun oneTimeRequest(data: androidx.work.Data): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<BridgeSyncWorker>()
                .setInputData(data)
                .setConstraints(networkConstraint)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .apply {
                    // Expedited jobs need no foreground notification on Android 12+; on older
                    // versions they would, so run those as regular (still prompt) work.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    }
                }
                .build()

        /** True when a request under [name] is queued but not yet running (never blocks the main thread). */
        private fun hasWaitingWork(context: Context, name: String): Boolean {
            if (Looper.myLooper() == Looper.getMainLooper()) return false
            return runCatching {
                WorkManager.getInstance(context).getWorkInfosForUniqueWork(name).get(2, TimeUnit.SECONDS)
                    .any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
            }.getOrDefault(false)
        }
    }
}
