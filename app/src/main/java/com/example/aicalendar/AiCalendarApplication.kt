package com.example.aicalendar

import android.app.Application

class AiCalendarApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        FirebaseBootstrap.ensureInitialized(this)
        if (BridgeSettings(this).isConnected()) {
            BridgeSyncWorker.schedule(this)
        }
    }
}
