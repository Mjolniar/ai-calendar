package com.example.aicalendar

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions

object FirebaseBootstrap {
    fun ensureInitialized(context: Context): FirebaseApp? {
        FirebaseApp.getApps(context).firstOrNull()?.let { return it }
        val config = BridgeSettings(context).config() ?: return null
        val options = FirebaseOptions.Builder()
            .setProjectId(config.firebaseProjectId)
            .setApplicationId(config.firebaseAppId)
            .setApiKey(config.firebaseApiKey)
            .setGcmSenderId(config.firebaseSenderId)
            .build()
        return FirebaseApp.initializeApp(context, options)
    }
}
