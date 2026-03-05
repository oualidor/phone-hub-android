package com.oualidkhial.phonehubserver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log

class CallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
            val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
            val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER) ?: ""
            
            Log.d("CallReceiver", "Phone State Broadcast: state=$state, number=$number")
            
            if (state != null) {
                // Update number/name BEFORE status, because setting callStatus triggers a broadcast
                if (state == TelephonyManager.EXTRA_STATE_RINGING) {
                    val contactName = ContactHelper.getContactName(context, number)
                    SmsServer.callerNumber = contactName ?: number
                } else if (state == TelephonyManager.EXTRA_STATE_IDLE) {
                    SmsServer.callerNumber = ""
                }
                SmsServer.callStatus = state
            }
        }
    }
}
