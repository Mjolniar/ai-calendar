package com.example.aicalendar

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * FCM callbacks only hand work to WorkManager; the callback's execution window is short and
 * unmanaged threads started here could be killed with their failures discarded.
 */
class CalendarMessagingService : FirebaseMessagingService() {
    override fun onRegistered(installationId: String) {
        BridgeSyncWorker.enqueueInstallationUpdate(applicationContext, installationId)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        when (message.data["type"]) {
            "calendar_sync" -> BridgeSyncWorker.enqueueSync(applicationContext)
            // Only refetches when Kitchen is on screen in the foreground; no job, no notification.
            "kitchen_sync" -> KitchenSync.signal()
        }
    }

    override fun onDeletedMessages() {
        BridgeSyncWorker.enqueueSync(applicationContext)
    }
}
