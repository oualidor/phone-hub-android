package com.oualidkhial.phonehubserver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

class SmsServerService : Service() {

    companion object {
        const val CHANNEL_ID = "sms_server_channel"
        const val ACTION_DISCONNECT = "ACTION_DISCONNECT"
    }

    // Broadcast receiver for loading SMS
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d("SmsServerService", "Received broadcast: ${intent?.action}")
            if (intent?.action == "LOAD_SMS_ACTION" && context != null) {
                Log.d("SmsServerService", "Loading all SMS messages...")
                SmsRepository.loadAllSms(context)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("SmsServerService", "onCreate")

        // Start Ktor server
        SmsServer.start(this)

        // Register broadcast receiver
        val filter = IntentFilter("LOAD_SMS_ACTION")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        }

        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        // Handle Disconnect Action
        if (intent?.action == ACTION_DISCONNECT) {
            Log.d("SmsServerService", "Disconnect requested")

            SmsServer.stop()

            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()

            return START_NOT_STICKY
        }

        val notification = createNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                1,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(1, notification)
        }

        return START_STICKY
    }

    private fun createNotification(): Notification {

        // Open app when notification is clicked
        val openAppIntent = Intent(this, MainActivity::class.java)
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Disconnect button action
        val disconnectIntent = Intent(this, SmsServerService::class.java).apply {
            action = ACTION_DISCONNECT
        }

        val disconnectPendingIntent = PendingIntent.getService(
            this,
            1,
            disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val connectionText =
            if (SmsServer.authorizedState.value)
                "Connected to ${SmsServer.clientIpState.value}"
            else
                "Waiting for connection • Port 8080"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Phone HUB Server Running")
            .setContentText(connectionText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(openAppPendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Disconnect",
                disconnectPendingIntent
            )
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Phone HUB Server",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("SmsServerService", "onDestroy")

        SmsServer.stop()

        try {
            unregisterReceiver(receiver)
        } catch (e: Exception) {
            Log.w("SmsServerService", "Receiver already unregistered")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}