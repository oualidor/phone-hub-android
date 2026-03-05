package com.oualidkhial.phonehubserver

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log

object ContactHelper {
    private const val TAG = "ContactHelper"

    fun getContactName(context: Context, phoneNumber: String?): String? {
        if (phoneNumber.isNullOrEmpty()) return null

        try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(phoneNumber)
            )
            val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)
            
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        return cursor.getString(nameIndex)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error looking up contact name for $phoneNumber", e)
        }

        return null
    }
}
