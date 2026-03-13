package com.oualidkhial.phonehubserver

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

data class PhoneNotification(
    val id: String,
    val packageName: String,
    val title: String,
    val text: String,
    val timestamp: Long,
    val category: String? = null
)

class NotificationService : NotificationListenerService() {

    companion object {
        private const val TAG = "NotificationService"
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        
        // Ignore our own notifications
        if (packageName == applicationContext.packageName) return

        val extras = sbn.notification.extras
        val title = extras.getString(android.app.Notification.EXTRA_TITLE) ?: ""
        val text = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString() ?: ""

        if (title.isBlank() && text.isBlank()) return

        val notificationId = "${sbn.packageName}_${sbn.id}"
        val notification = PhoneNotification(
            id = notificationId,
            packageName = packageName,
            title = title,
            text = text,
            timestamp = sbn.postTime,
            category = sbn.notification.category
        )

        // If Android hid the caller ID from TelephonyManager, try extracting it from the call notification
        if (sbn.notification.category == android.app.Notification.CATEGORY_CALL) {
            if (SmsServer.callStatus == android.telephony.TelephonyManager.EXTRA_STATE_RINGING || SmsServer.callStatus == android.telephony.TelephonyManager.EXTRA_STATE_OFFHOOK) {
                // Usually the Title is the Contact Name and Text is the status like "Incoming call" or "Ongoing call"
                val possibleName = title.takeIf { it.isNotBlank() } ?: text
                if (possibleName.isNotBlank() && !possibleName.equals("Incoming call", ignoreCase = true)) {
                    SmsServer.callerNumber = possibleName
                }
            }
        }

        Log.d(TAG, "Notification posted: [${notification.packageName}] ${notification.title} - ${notification.text}")
        SmsServer.addNotification(notification)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        val notificationId = "${sbn.packageName}_${sbn.id}"
        Log.d(TAG, "Notification removed: $notificationId")
        SmsServer.removeNotification(notificationId)
    }
}
