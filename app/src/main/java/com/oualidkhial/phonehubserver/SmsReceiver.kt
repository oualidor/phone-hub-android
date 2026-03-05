package com.oualidkhial.phonehubserver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            for (message in messages) {
                val sender = message.displayOriginatingAddress ?: "Unknown"
                val body = message.displayMessageBody ?: ""
                Log.d("SmsReceiver", "SMS from $sender: $body")
                
                SmsRepository.addMessage(sender, body)
            }
        }
    }
}