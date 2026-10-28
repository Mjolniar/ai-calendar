package com.example.aicalendar

import android.content.Context
import androidx.core.content.edit

/** Remembers when the phone last pulled the cloud list successfully, for the connection status display. */
object SyncStatusStore {
    private const val FILE = "sync_status"
    private const val KEY_LAST_SUCCESS = "last_success_at"

    fun recordSuccess(context: Context, atMillis: Long = System.currentTimeMillis()) {
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit { putLong(KEY_LAST_SUCCESS, atMillis) }
    }

    /** Epoch millis of the last successful sync, or 0 when the phone has never synced. */
    fun lastSuccess(context: Context): Long =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).getLong(KEY_LAST_SUCCESS, 0L)
}
