package com.oualidkhial.phonehubserver

import android.content.Context
import android.provider.Telephony
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Serializable
data class SmsMessage(
    val sender: String,
    val body: String,
    val timestamp: Long
)

object SmsRepository {
    private const val TAG = "SmsRepository"
    private val _messages = MutableStateFlow<List<SmsMessage>>(emptyList())
    val messages: StateFlow<List<SmsMessage>> = _messages.asStateFlow()

    fun addMessage(sender: String, body: String, timestamp: Long = System.currentTimeMillis()) {
        val newMessage = SmsMessage(sender, body, timestamp)
        _messages.value = (listOf(newMessage) + _messages.value).take(100)
        Log.d(TAG, "Added new message. Total: ${_messages.value.size}")
    }

    fun loadAllSms(context: Context) {
        Log.d(TAG, "Loading all SMS messages...")
        val smsList = mutableListOf<SmsMessage>()
        try {
            val cursor = context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
                null,
                null,
                Telephony.Sms.DATE + " DESC"
            )

            cursor?.use {
                val addressIndex = it.getColumnIndex(Telephony.Sms.ADDRESS)
                val bodyIndex = it.getColumnIndex(Telephony.Sms.BODY)
                val dateIndex = it.getColumnIndex(Telephony.Sms.DATE)

                while (it.moveToNext()) {
                    val address = it.getString(addressIndex) ?: "Unknown"
                    val body = it.getString(bodyIndex) ?: ""
                    val date = it.getLong(dateIndex)
                    smsList.add(SmsMessage(address, body, date))
                }
            }
            Log.d(TAG, "Successfully loaded ${smsList.size} messages")
            _messages.value = smsList
        } catch (e: Exception) {
            Log.e(TAG, "Error loading SMS messages", e)
        }
    }
}
