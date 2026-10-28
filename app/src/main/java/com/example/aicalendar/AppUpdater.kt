package com.example.aicalendar

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.provider.Settings
import androidx.core.content.edit
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.net.toUri
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** The phone build the bridge offers (`GET /v1/app/latest`). */
data class AppRelease(
    val versionCode: Long,
    val versionName: String,
    val sha256: String,
    val size: Long,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("versionCode", versionCode)
        .put("versionName", versionName)
        .put("sha256", sha256)
        .put("size", size)

    companion object {
        fun fromJson(json: JSONObject): AppRelease? = runCatching {
            AppRelease(
                versionCode = json.getLong("versionCode"),
                versionName = json.getString("versionName"),
                sha256 = json.getString("sha256").lowercase().also { require(Regex("^[0-9a-f]{64}$").matches(it)) },
                size = json.getLong("size").also { require(it in 1..MAX_APK_BYTES) },
            )
        }.getOrNull()

        /** No phone build is anywhere near this; a bigger answer is refused before any download. */
        const val MAX_APK_BYTES = 200L * 1024 * 1024
    }
}

/**
 * Updates over the cloud. The bridge publishes a build; the phone downloads it, checks its SHA-256
 * against the published one, and hands it to Android's installer. Android asks the user to confirm
 * (an app from outside a store cannot update itself silently) and only accepts a build signed with
 * the same key as the installed app.
 */
object AppUpdater {
    private const val PREFS = "app_update"
    private const val KEY_LAST_CHECK = "last_check"
    private const val KEY_LAST_ERROR = "last_error"
    private const val KEY_PENDING = "pending_install"

    /** How long an install waits for the "Allow from this source" permission before it is forgotten. */
    const val PENDING_INSTALL_MS = 30 * 60 * 1000L
    /** Automatic checks at most this often; Help > Check for Updates always checks. */
    const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L

    fun installedVersionCode(context: Context): Long =
        PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))

    fun isNewer(context: Context, release: AppRelease): Boolean = release.versionCode > installedVersionCode(context)

    /** Whether the user allowed AI Calendar to install apps ("Install unknown apps"). */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** The settings screen where the user allows it, for this app only. */
    fun allowInstallsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())

    fun dueForCheck(context: Context, now: Long = System.currentTimeMillis()): Boolean =
        now - prefs(context).getLong(KEY_LAST_CHECK, 0L) >= CHECK_INTERVAL_MS

    fun recordCheck(context: Context, now: Long = System.currentTimeMillis()) {
        prefs(context).edit { putLong(KEY_LAST_CHECK, now) }
    }

    /** The last install failure Android reported, once; null when there is none. */
    fun takeLastError(context: Context): String? {
        val prefs = prefs(context)
        val error = prefs.getString(KEY_LAST_ERROR, null) ?: return null
        prefs.edit { remove(KEY_LAST_ERROR) }
        return error
    }

    internal fun recordError(context: Context, message: String) {
        prefs(context).edit { putString(KEY_LAST_ERROR, message) }
    }

    /**
     * An install that stopped to ask for "Allow from this source". Kept on the phone, so coming back
     * from Android's settings continues it even if the app was closed meanwhile.
     */
    fun setPendingInstall(context: Context, release: AppRelease, now: Long = System.currentTimeMillis()) {
        prefs(context).edit { putString(KEY_PENDING, pendingJson(release, now)) }
    }

    /** The install waiting for the permission, or null (none, or older than [PENDING_INSTALL_MS]). */
    fun pendingInstall(context: Context, now: Long = System.currentTimeMillis()): AppRelease? =
        pendingFrom(prefs(context).getString(KEY_PENDING, null), now)

    fun clearPendingInstall(context: Context) {
        prefs(context).edit { remove(KEY_PENDING) }
    }

    fun pendingJson(release: AppRelease, now: Long): String = release.toJson().put("askedAt", now).toString()

    fun pendingFrom(text: String?, now: Long): AppRelease? {
        val json = text?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        val asked = json.optLong("askedAt", 0L)
        if (now - asked !in 0..PENDING_INSTALL_MS) return null
        return AppRelease.fromJson(json)
    }

    /** Checks a downloaded file against the release before it goes anywhere near the installer. */
    fun verify(file: File, release: AppRelease): Boolean {
        if (!file.isFile || file.length() != release.size) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } == release.sha256
    }

    /** Hands a verified build to Android's installer. The result arrives at [InstallResultReceiver]. */
    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Skips the confirmation only where Android allows it (this app installed itself last time).
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("app.apk", 0, apk.length()).use { output ->
                apk.inputStream().use { it.copyTo(output) }
                session.fsync(output)
            }
            val status = PendingIntent.getBroadcast(
                context,
                sessionId,
                Intent(context, InstallResultReceiver::class.java).setPackage(context.packageName),
                // Mutable: the installer adds the status and the confirmation intent to it.
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(status.intentSender)
        }
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** Android's answer to an update: ask the user to confirm, or remember why it failed. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }) ?: return
                runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> AppUpdater.recordError(
                context,
                intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Android did not install the update.",
            )
        }
    }
}
