package com.example.aicalendar

import android.content.Context

data class BridgeConfig(
    val serverUrl: String,
    val firebaseProjectId: String,
    val firebaseAppId: String,
    val firebaseApiKey: String,
    val firebaseSenderId: String,
)

class BridgeSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("bridge_settings", Context.MODE_PRIVATE)
    private val secure = SecureTokenStore(context)

    fun config(): BridgeConfig? {
        val serverUrl = prefs.getString(KEY_SERVER_URL, null)?.trimEnd('/') ?: return null
        val projectId = prefs.getString(KEY_PROJECT_ID, null) ?: return null
        val appId = prefs.getString(KEY_APP_ID, null) ?: return null
        val apiKey = prefs.getString(KEY_API_KEY, null) ?: return null
        val senderId = prefs.getString(KEY_SENDER_ID, null) ?: return null
        return BridgeConfig(serverUrl, projectId, appId, apiKey, senderId)
    }

    fun saveConfig(config: BridgeConfig) {
        prefs.edit()
            .putString(KEY_SERVER_URL, config.serverUrl.trim().trimEnd('/'))
            .putString(KEY_PROJECT_ID, config.firebaseProjectId.trim())
            .putString(KEY_APP_ID, config.firebaseAppId.trim())
            .putString(KEY_API_KEY, config.firebaseApiKey.trim())
            .putString(KEY_SENDER_ID, config.firebaseSenderId.trim())
            .apply()
    }

    /**
     * The device bearer token. Stored encrypted with a Keystore key; a plaintext token left by
     * v0.9.1 or earlier is migrated on first read and only deleted after the encrypted copy has been
     * verified, so pairing survives the upgrade. If the Keystore is unusable the plaintext copy is
     * kept (the app keeps working rather than silently unpairing).
     */
    fun deviceToken(): String? {
        cachedToken?.let { return it }
        synchronized(tokenLock) {
            cachedToken?.let { return it }
            val token = secure.read(KEY_DEVICE_TOKEN) ?: migrateLegacyToken()
            cachedToken = token?.takeIf(String::isNotBlank)
            return cachedToken
        }
    }

    fun saveDeviceToken(token: String) {
        synchronized(tokenLock) {
            if (secure.write(KEY_DEVICE_TOKEN, token) && secure.read(KEY_DEVICE_TOKEN) == token) {
                prefs.edit().remove(KEY_DEVICE_TOKEN).commit()
            } else {
                secure.remove(KEY_DEVICE_TOKEN)
                prefs.edit().putString(KEY_DEVICE_TOKEN, token).commit()
            }
            cachedToken = token
        }
    }

    /** Cheap check (no decryption) suitable for the main thread. */
    fun isConnected(): Boolean =
        config() != null && (cachedToken != null || secure.contains(KEY_DEVICE_TOKEN) || !prefs.getString(KEY_DEVICE_TOKEN, null).isNullOrBlank())

    private fun migrateLegacyToken(): String? {
        val legacy = prefs.getString(KEY_DEVICE_TOKEN, null)?.takeIf(String::isNotBlank) ?: return null
        if (secure.write(KEY_DEVICE_TOKEN, legacy) && secure.read(KEY_DEVICE_TOKEN) == legacy) {
            prefs.edit().remove(KEY_DEVICE_TOKEN).commit()
        } else {
            secure.remove(KEY_DEVICE_TOKEN)
        }
        return legacy
    }

    companion object {
        @Volatile
        private var cachedToken: String? = null
        private val tokenLock = Any()

        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_PROJECT_ID = "firebase_project_id"
        private const val KEY_APP_ID = "firebase_app_id"
        private const val KEY_API_KEY = "firebase_api_key"
        private const val KEY_SENDER_ID = "firebase_sender_id"
        private const val KEY_DEVICE_TOKEN = "device_token"
    }
}
