package com.oualidkhial.phonehubserver
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import io.ktor.websocket.Frame
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import android.os.Build
import android.provider.Settings
import java.util.concurrent.atomic.AtomicBoolean
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import android.content.SharedPreferences
import io.ktor.server.application.install
import io.ktor.server.request.receiveText
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.AudioAttributes
import android.util.Log
import android.os.BatteryManager
import kotlinx.coroutines.Job
import org.json.JSONObject
import java.util.Locale

private const val PREFS_NAME = "PhoneHubPrefs"
private const val KEY_IS_AUTHORIZED = "isAuthorized"
private const val KEY_CONNECTED_CLIENT_IP = "connectedClientIp"
private const val KEY_REST_TOKEN = "restToken"
private const val KEY_WS_TOKEN = "wsToken"
private const val KEY_CONNECTED_DEVICE_NAME = "deviceName"
private const val KEY_SERVICE_ENABLED = "serviceEnabled"
private const val KEY_SFTP_SERVER_ENABLED = "sftpServerEnabled"

data class PairingRequest(val ip: String, val deviceName: String)


object SmsServer {
    private var server: ApplicationEngine? = null
    private const val TAG = "SmsServer"

    fun getLocalIpAddress(context: Context): String {
        try {
            val wifiManager = context.getApplicationContext().getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            val wifiInfo = wifiManager.connectionInfo
            val ipAddress = wifiInfo.ipAddress
            return String.format(
                Locale.US,
                "%d.%d.%d.%d",
                ipAddress and 0xff,
                ipAddress shr 8 and 0xff,
                ipAddress shr 16 and 0xff,
                ipAddress shr 24 and 0xff
            )
        } catch (e: Exception) {
            return "127.0.0.1"
        }
    }

    val isAuthorized = AtomicBoolean(false)
    val authorizedState = kotlinx.coroutines.flow.MutableStateFlow(false)
    var connectedClientIp: String? = null
    var connectedDeviceName: String? = null
    val clientIpState = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val deviceNameState = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val isClientConnectedState = kotlinx.coroutines.flow.MutableStateFlow(false)
    val pairingRequests = kotlinx.coroutines.flow.MutableStateFlow<PairingRequest?>(null)
    private var activeRingtone: Ringtone? = null
    
    val serviceEnabledState = kotlinx.coroutines.flow.MutableStateFlow(true)
    val sftpServerEnabledState = kotlinx.coroutines.flow.MutableStateFlow(true)

    val isServiceRunningState = kotlinx.coroutines.flow.MutableStateFlow(false)
    val isSmsServerActualRunningState = kotlinx.coroutines.flow.MutableStateFlow(false)
    val isSftpServerActualRunningState = kotlinx.coroutines.flow.MutableStateFlow(false)
    
    var restToken: String? = null
    var wsToken: String? = null

    private var pendingRestToken: String? = null
    private var pendingWsToken: String? = null

    private var statusMonitorJob: Job? = null
    private var lastBroadcastedStatus: String? = null

    fun getServiceEnabled(context: Context) = getPrefs(context).getBoolean(KEY_SERVICE_ENABLED, true)
    fun getSftpServerEnabled(context: Context) = getPrefs(context).getBoolean(KEY_SFTP_SERVER_ENABLED, true)

    fun setServiceEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SERVICE_ENABLED, enabled).apply()
        serviceEnabledState.value = enabled
    }

    fun setSftpServerEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SFTP_SERVER_ENABLED, enabled).apply()
        sftpServerEnabledState.value = enabled
    }

    fun getPendingRestToken() = pendingRestToken
    fun getPendingWsToken() = pendingWsToken

    fun getRestToken(context: Context): String? {
        return restToken ?: getPrefs(context).getString(KEY_REST_TOKEN, null)
    }

    // WebSocket connections (control channel)
    private val webSocketSessions = java.util.Collections.synchronizedList(mutableListOf<DefaultWebSocketServerSession>())


    // Call state (can be IDLE, RINGING, OFFHOOK)
    var callStatus = "IDLE"
        set(value) {
            field = value
            broadcastCallStatus()
        }
    var callerNumber = ""
        set(value) {
            val changed = field != value
            field = value
            if (changed) {
                broadcastCallStatus()
            }
        }


    // Notifications state
    private val activeNotifications = java.util.concurrent.ConcurrentHashMap<String, PhoneNotification>()

    fun addNotification(notification: PhoneNotification) {
        activeNotifications[notification.id] = notification
        broadcastNotification(notification)
    }

    fun removeNotification(id: String) {
        activeNotifications.remove(id)
    }

    fun clearAllNotifications() {
        activeNotifications.clear()
        broadcastClearAll()
    }

    private fun broadcastCallStatus() {
        Log.d(TAG, "Broadcasting callStatus=$callStatus, len=${webSocketSessions.size}")
        val payload = JSONObject().apply {
            put("type", "CALL_STATUS")
            put("status", callStatus)
            put("number", callerNumber)
        }.toString()
        webSocketSessions.toList().forEach { session ->
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    session.send(Frame.Text(payload))
                } catch (e: Exception) {
                    Log.d(TAG, "Failed to broadcast to a session: ${e.message}")
                }
            }
        }
    }

    private fun broadcastNotification(notif: PhoneNotification) {
        val safeTitle = notif.title.replace("\"", "\\\"").replace("\n", "\\n")
        val safeText = notif.text.replace("\"", "\\\"").replace("\n", "\\n")
        val payload = "{\"type\":\"NOTIFICATION\",\"id\":\"${notif.id}\",\"packageName\":\"${notif.packageName}\",\"title\":\"$safeTitle\",\"text\":\"$safeText\",\"category\":\"${notif.category ?: ""}\"}"

        webSocketSessions.toList().forEach { session ->
            CoroutineScope(Dispatchers.IO).launch {
                try { session.send(Frame.Text(payload)) } catch (e: Exception) { }
            }
        }
    }

    private fun broadcastClearAll() {
        val payload = "{\"type\":\"CLEAR_ALL\"}"
        webSocketSessions.toList().forEach { session ->
            CoroutineScope(Dispatchers.IO).launch {
                try { session.send(Frame.Text(payload)) } catch (e: Exception) { }
            }
        }
    }

    private fun startStatusMonitor(context: Context) {
        if (statusMonitorJob?.isActive == true) return

        statusMonitorJob = CoroutineScope(Dispatchers.IO).launch {
            Log.d(TAG, "Started DeviceStatusMonitor")
            while (isActive) {
                if (webSocketSessions.isEmpty() || !isAuthorized.get()) {
                    delay(3000)
                    continue
                }

                try {
                    val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                    val batteryLevel = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

                    val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
                    val bluetoothStatus = if (adapter?.isEnabled == true) "on" else "off"

                    val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
                    
                    var dataStatus = "off"
                    var operator = "Unknown"
                    var networkType = "Unknown"
                    
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                        dataStatus = if (telephonyManager.isDataEnabled) "on" else "off"
                        operator = telephonyManager.networkOperatorName ?: "Unknown"
                        
                        val typeInt = try { telephonyManager.dataNetworkType } catch (e: Exception) { 0 }
                        networkType = when (typeInt) {
                            android.telephony.TelephonyManager.NETWORK_TYPE_GPRS, android.telephony.TelephonyManager.NETWORK_TYPE_EDGE,
                            android.telephony.TelephonyManager.NETWORK_TYPE_CDMA, android.telephony.TelephonyManager.NETWORK_TYPE_1xRTT,
                            android.telephony.TelephonyManager.NETWORK_TYPE_IDEN -> "2G"

                            android.telephony.TelephonyManager.NETWORK_TYPE_UMTS, android.telephony.TelephonyManager.NETWORK_TYPE_EVDO_0,
                            android.telephony.TelephonyManager.NETWORK_TYPE_EVDO_A, android.telephony.TelephonyManager.NETWORK_TYPE_HSDPA,
                            android.telephony.TelephonyManager.NETWORK_TYPE_HSUPA, android.telephony.TelephonyManager.NETWORK_TYPE_HSPA,
                            android.telephony.TelephonyManager.NETWORK_TYPE_EVDO_B, android.telephony.TelephonyManager.NETWORK_TYPE_EHRPD,
                            android.telephony.TelephonyManager.NETWORK_TYPE_HSPAP -> "3G"

                            android.telephony.TelephonyManager.NETWORK_TYPE_LTE -> "4G"
                            android.telephony.TelephonyManager.NETWORK_TYPE_NR -> "5G"
                            else -> "Unknown"
                        }
                    }

                    val payloadJson = JSONObject().apply {
                        put("type", "DEVICE_STATUS")
                        put("battery", batteryLevel)
                        put("bluetooth", bluetoothStatus)
                        put("data_status", dataStatus)
                        put("operator", operator)
                        put("network_type", networkType)
                    }
                    val payloadStr = payloadJson.toString()

                    if (payloadStr != lastBroadcastedStatus) {
                        lastBroadcastedStatus = payloadStr
                        webSocketSessions.toList().forEach { session ->
                            launch {
                                try {
                                    session.send(Frame.Text(payloadStr))
                                } catch (e: Exception) {
                                    // Ignore
                                }
                            }
                        }
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "Error in status monitor", e)
                }

                delay(3000) // Poll every 3 seconds
            }
        }
    }

    private fun stopStatusMonitor() {
        statusMonitorJob?.cancel()
        statusMonitorJob = null
        lastBroadcastedStatus = null
        Log.d(TAG, "Stopped DeviceStatusMonitor")
    }

    private fun getDeviceFriendlyName(context: Context): String {
        return try {
            val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
                Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
            } else {
                null
            }
            name ?: Build.MODEL ?: "Android Device"
        } catch (e: Exception) {
            Build.MODEL ?: "Android Device"
        }
    }

    private fun getPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun saveAuthState(context: Context, authorized: Boolean, clientIp: String? = null, rToken: String? = null, wToken: String? = null) {
        getPrefs(context).edit().apply {
            putBoolean(KEY_IS_AUTHORIZED, authorized)
            putString(KEY_CONNECTED_CLIENT_IP, clientIp)
            putString(KEY_CONNECTED_DEVICE_NAME, connectedDeviceName)
            putString(KEY_REST_TOKEN, rToken)
            putString(KEY_WS_TOKEN, wToken)
            apply()
        }
        Log.d(TAG, "Auth state saved: authorized=$authorized, clientIp=$clientIp, tokens set=${rToken != null}")
    }


    fun setAuthorized(context: Context, authorized: Boolean, clientIp: String? = null, devName: String? = null, rToken: String? = null, wToken: String? = null) {
        isAuthorized.set(authorized)
        authorizedState.value = authorized
        connectedClientIp = if (authorized) clientIp else null
        connectedDeviceName = if (authorized) devName else null
        clientIpState.value = connectedClientIp
        deviceNameState.value = connectedDeviceName
        if (authorized) {
            restToken = rToken
            wsToken = wToken
        } else {
            restToken = null
            wsToken = null
        }
        saveAuthState(context, authorized, connectedClientIp, restToken, wsToken)
    }

    fun unpair(context: Context) {
        setAuthorized(context, false)
        Log.i(TAG, "Device unpaired manually from app. Notifying and closing active sessions.")
        
        // Notify and close all active WebSocket sessions
        val sessions = webSocketSessions.toList()
        webSocketSessions.clear()
        isClientConnectedState.value = false
        
        CoroutineScope(Dispatchers.IO).launch {
            sessions.forEach { session ->
                try {
                    session.send(Frame.Text("{\"type\":\"UNPAIR\"}"))
                    session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Unpaired from mobile app"))
                } catch (e: Exception) {
                    Log.e(TAG, "Error notifying/closing session on unpair", e)
                }
            }
        }
    }


    private fun loadAuthState(context: Context): Map<String, Any?> {
        val prefs = getPrefs(context)
        return mapOf(
            "authorized" to prefs.getBoolean(KEY_IS_AUTHORIZED, false),
            "clientIp" to prefs.getString(KEY_CONNECTED_CLIENT_IP, null),
            "deviceName" to prefs.getString(KEY_CONNECTED_DEVICE_NAME, null),
            "restToken" to prefs.getString(KEY_REST_TOKEN, null),
            "wsToken" to prefs.getString(KEY_WS_TOKEN, null)
        )
    }


    fun start(context: Context) {
        if (server != null) {
            Log.d(TAG, "Server is already running.")
            return
        }

        // Initialize display states from stored prefs
        serviceEnabledState.value = getServiceEnabled(context)
        sftpServerEnabledState.value = getSftpServerEnabled(context)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Restore authorization from previous session
                val authData = loadAuthState(context)
                val restoredAuth = authData["authorized"] as Boolean
                val restoredClientIp = authData["clientIp"] as? String
                restToken = authData["restToken"] as? String
                wsToken = authData["wsToken"] as? String
                
                isAuthorized.set(restoredAuth)
                authorizedState.value = restoredAuth
                connectedClientIp = restoredClientIp
                connectedDeviceName = authData["deviceName"] as? String
                deviceNameState.value = connectedDeviceName
                clientIpState.value = restoredClientIp
                Log.d(TAG, "Restored auth state: authorized=$restoredAuth, clientIp=$restoredClientIp, tokensRestored=${restToken != null}")

                Log.d(TAG, "Starting Ktor server on 0.0.0.0:8080...")

                server = embeddedServer(CIO, host = "0.0.0.0", port = 8080) {
                    install(WebSockets) {
                        pingPeriodMillis = 15000
                        timeoutMillis = 15000
                        maxFrameSize = Long.MAX_VALUE
                        masking = false
                    }
                    
                    routing {
                        get("/") {
                            val auth = isAuthorized.get()
                            Log.d(TAG, "GET / - authorized: $auth")
                            val name = getDeviceFriendlyName(context)
                            call.respondText(
                                "{\"status\":\"running\",\"authorized\":$auth,\"deviceName\":\"$name\",\"connectedClientIp\":\"$connectedClientIp\",\"callStatus\":\"$callStatus\",\"callerNumber\":\"$callerNumber\"}",
                                ContentType.Application.Json
                            )
                        }

                        get("/ping") {
                            call.respondText("pong")
                        }
                        post("/pair") {
                            val clientIp = call.request.local.remoteAddress
                            val deviceName = call.request.queryParameters["deviceName"] ?: "Unknown PC"
                            Log.d(TAG, "Pairing request from $clientIp ($deviceName)")

                            // Revoke previous authorization on new pairing attempt
                            isAuthorized.set(false)
                            
                            // Generate new tokens
                            val newRestToken = java.util.UUID.randomUUID().toString()
                            val newWsToken = java.util.UUID.randomUUID().toString()

                            pairingRequests.emit(PairingRequest(clientIp, deviceName))
                            
                            // We return the tokens in the response. They will be saved on PC
                            // but only become valid once 'setAuthorized' is called on the phone.
                            call.respondText(
                                "{\"status\":\"pending\",\"restToken\":\"$newRestToken\",\"wsToken\":\"$newWsToken\"}",
                                ContentType.Application.Json
                            )
                            
                             // Temporarily store them so they can be saved when user clicks "Allow"
                            pendingRestToken = newRestToken
                            pendingWsToken = newWsToken
                            connectedClientIp = clientIp
                            connectedDeviceName = deviceName
                        }
                        post("/unpair") {
                            Log.d(TAG, "Unpair request received")
                            setAuthorized(context, false)
                            call.respondText(
                                "{\"status\":\"unpaired\"}",
                                ContentType.Application.Json
                            )
                        }

                        get("/notifications") {
                            val token = call.request.queryParameters["token"]
                            if (!isAuthorized.get() || token != restToken) {
                                call.respondText("{\"error\":\"Unauthorized\"}", ContentType.Application.Json, HttpStatusCode.Unauthorized)
                                return@get
                            }
                            
                            val notifsList = activeNotifications.values.toList()
                            val jsonArray = notifsList.joinToString(prefix = "[", postfix = "]", separator = ",") { notif ->
                                val safeTitle = notif.title.replace("\"", "\\\"").replace("\n", "\\n")
                                val safeText = notif.text.replace("\"", "\\\"").replace("\n", "\\n")
                                "{\"id\":\"${notif.id}\",\"packageName\":\"${notif.packageName}\",\"title\":\"${safeTitle}\",\"text\":\"${safeText}\",\"timestamp\":${notif.timestamp}}"
                            }
                            
                            call.respondText(jsonArray, ContentType.Application.Json)
                        }

                        post("/notifications/clear") {
                            val token = call.request.queryParameters["token"]
                            if (!isAuthorized.get() || token != restToken) {
                                call.respondText("{\"error\":\"Unauthorized\"}", ContentType.Application.Json, HttpStatusCode.Unauthorized)
                                return@post
                            }
                            // In a full implementation, we would broadcast to NotificationService to cancel it.
                            // For now, we manually remove it from our queue.
                            val requestText = call.receiveText()
                            Log.d(TAG, "Clear notification request: $requestText")
                            val id = requestText.substringAfter("\"id\":\"").substringBefore("\"").trim()
                            if (id.isNotEmpty()) {
                                removeNotification(id)
                            } else {
                                clearAllNotifications()
                            }
                            call.respondText("{\"status\":\"cleared\"}", ContentType.Application.Json)
                        }

                        post("/answer_call") {
                            Log.d(TAG, "Answer call request")
                            val token = call.request.queryParameters["token"]
                            if (!isAuthorized.get() || token != restToken) {
                                call.respondText("{\"error\":\"Unauthorized\"}", ContentType.Application.Json, HttpStatusCode.Unauthorized)
                                return@post
                            }
                            try {
                                val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                        telecomManager.acceptRingingCall()
                                    }
                                    call.respondText("{\"status\":\"answered\"}", ContentType.Application.Json)
                                } else {
                                    call.respondText("{\"error\":\"Permission denied\"}", ContentType.Application.Json, HttpStatusCode.Forbidden)
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Answer error", e)
                                call.respondText("{\"error\":\"${e.message}\"}", ContentType.Application.Json, HttpStatusCode.InternalServerError)
                            }
                        }
                        post("/decline_call") {
                            Log.d(TAG, "Decline call request")
                            val token = call.request.queryParameters["token"]
                            if (!isAuthorized.get() || token != restToken) {
                                call.respondText("{\"error\":\"Unauthorized\"}", ContentType.Application.Json, HttpStatusCode.Unauthorized)
                                return@post
                            }
                            try {
                                val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                        telecomManager.endCall()
                                    }
                                    call.respondText("{\"status\":\"declined\"}", ContentType.Application.Json)
                                } else {
                                    call.respondText("{\"error\":\"Permission denied\"}", ContentType.Application.Json, HttpStatusCode.Forbidden)
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Decline error", e)
                                call.respondText("{\"error\":\"${e.message}\"}", ContentType.Application.Json, HttpStatusCode.InternalServerError)
                            }
                        }

                        webSocket("/ws") {
                            val clientIp = call.request.local.remoteAddress
                            Log.d(TAG, "WebSocket connection attempt from $clientIp")
                            
                            val token = call.request.queryParameters["token"]
                            if (!isAuthorized.get() || token != wsToken) {
                                Log.d(TAG, "WebSocket rejected: Unauthorized or invalid token")
                                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Unauthorized"))
                                return@webSocket
                            }

                            Log.d(TAG, "WebSocket connected from $clientIp")
                            webSocketSessions.add(this)
                            isClientConnectedState.value = true
                            
                            // Immediately sync current state to the new client
                            try {
                                // 1. Sync Call Status
                                send(Frame.Text("{\"type\":\"CALL_STATUS\",\"status\":\"$callStatus\",\"number\":\"$callerNumber\"}"))
                                
                                // 2. Sync Active Notifications
                                val notifs = activeNotifications.values.toList()
                                notifs.forEach { notif ->
                                    val safeTitle = notif.title.replace("\"", "\\\"").replace("\n", "\\n")
                                    val safeText = notif.text.replace("\"", "\\\"").replace("\n", "\\n")
                                    send(Frame.Text("{\"type\":\"NOTIFICATION\",\"id\":\"${notif.id}\",\"packageName\":\"${notif.packageName}\",\"title\":\"$safeTitle\",\"text\":\"$safeText\"}"))
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error syncing initial state to WS client", e)
                            }
                            
                            startStatusMonitor(context)

                            var lastPong = System.currentTimeMillis()
                            val pingJob = launch {
                                while (isActive) {
                                    delay(3000)
                                    if (System.currentTimeMillis() - lastPong > 10000) {
                                        Log.w(TAG, "WebSocket ping timeout. Closing connection from $clientIp")
                                        close(CloseReason(CloseReason.Codes.GOING_AWAY, "Ping timeout"))
                                        break
                                    }
                                    try {
                                        send(Frame.Text("{\"type\":\"PING\"}"))
                                    } catch (e: Exception) {
                                        break
                                    }
                                }
                            }

                            try {
                                for (frame in incoming) {
                                    if (frame is Frame.Text) {
                                        val text = frame.readText()
                                        if (text == "{\"type\":\"PONG\"}") {
                                            lastPong = System.currentTimeMillis()
                                        } else {
                                            Log.d(TAG, "WS Received: $text")
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Log.d(TAG, "WebSocket error: ${e.localizedMessage}")
                            } finally {
                                pingJob.cancel()
                                Log.d(TAG, "WebSocket disconnected from $clientIp")
                                webSocketSessions.remove(this)
                                isClientConnectedState.value = webSocketSessions.isNotEmpty()
                                if (webSocketSessions.isEmpty()) {
                                    stopStatusMonitor()
                                }
                            }
                        }

                        get("/device_name") {
                            val name = getDeviceFriendlyName(context)
                            call.respondText(
                                "{\"deviceName\":\"$name\"}",
                                ContentType.Application.Json
                            )
                        }

                        get("/sms") {
                            val token = call.request.queryParameters["token"]
                            if (!isAuthorized.get() || token != restToken) {
                                call.respondText(
                                    "{\"error\":\"Unauthorized\"}",
                                    ContentType.Application.Json,
                                    HttpStatusCode.Unauthorized
                                )
                                return@get
                            }

                            try {
                                Log.d(TAG, "GET /sms - Refreshing SMS list...")
                                SmsRepository.loadAllSms(context)
                                val messages = SmsRepository.messages.value

                                // Manually construct simple JSON array for messages to avoid serialization issues
                                val jsonBuilder = StringBuilder("[")
                                messages.forEachIndexed { index, msg ->
                                    jsonBuilder.append("{")
                                    jsonBuilder.append("\"sender\":\"${msg.sender.replace("\"", "\\\"")}\",")
                                    jsonBuilder.append("\"body\":\"${msg.body.replace("\"", "\\\"").replace("\n", "\\n")}\",")
                                    jsonBuilder.append("\"timestamp\":${msg.timestamp}")
                                    jsonBuilder.append("}")
                                    if (index < messages.size - 1) jsonBuilder.append(",")
                                }
                                jsonBuilder.append("]")

                                call.respondText(jsonBuilder.toString(), ContentType.Application.Json)
                            } catch (e: Exception) {
                                Log.e(TAG, "Error fetching SMS", e)
                                call.respondText("{\"error\":\"${e.message}\"}", ContentType.Application.Json, HttpStatusCode.InternalServerError)
                            }
                        }

                        get("/contacts") {
                            val token = call.request.queryParameters["token"]
                            if (!isAuthorized.get() || token != restToken) {
                                call.respondText("{\"error\":\"Unauthorized\"}", ContentType.Application.Json, HttpStatusCode.Unauthorized)
                                return@get
                            }

                            try {
                                val contacts = ContactHelper.getAllContacts(context)
                                val jsonBuilder = StringBuilder("[")
                                contacts.forEachIndexed { index, contact ->
                                    jsonBuilder.append("{")
                                    jsonBuilder.append("\"name\":\"${contact.name.replace("\"", "\\\"")}\",")
                                    jsonBuilder.append("\"number\":\"${contact.number.replace("\"", "\\\"")}\"")
                                    jsonBuilder.append("}")
                                    if (index < contacts.size - 1) jsonBuilder.append(",")
                                }
                                jsonBuilder.append("]")
                                call.respondText(jsonBuilder.toString(), ContentType.Application.Json)
                            } catch (e: Exception) {
                                Log.e(TAG, "Error fetching contacts", e)
                                call.respondText("{\"error\":\"${e.message}\"}", ContentType.Application.Json, HttpStatusCode.InternalServerError)
                            }
                        }

                        post("/call") {
                            val token = call.request.queryParameters["token"]
                            val number = call.request.queryParameters["number"]
                            if (!isAuthorized.get() || token != restToken) {
                                call.respondText("{\"error\":\"Unauthorized\"}", ContentType.Application.Json, HttpStatusCode.Unauthorized)
                                return@post
                            }

                            if (number.isNullOrEmpty()) {
                                call.respondText("{\"error\":\"Number required\"}", ContentType.Application.Json, HttpStatusCode.BadRequest)
                                return@post
                            }

                            try {
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                                    val contactName = ContactHelper.getContactName(context, number)
                                    callerNumber = contactName ?: number

                                    val intent = Intent(Intent.ACTION_CALL).apply {
                                        data = Uri.parse("tel:$number")
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(intent)
                                    call.respondText("{\"status\":\"calling\"}", ContentType.Application.Json)
                                } else {
                                    call.respondText("{\"error\":\"Permission denied\"}", ContentType.Application.Json, HttpStatusCode.Forbidden)
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Call error", e)
                                call.respondText("{\"error\":\"${e.message}\"}", ContentType.Application.Json, HttpStatusCode.InternalServerError)
                            }
                        }
                        post("/ring") {
                            val token = call.request.queryParameters["token"]
                            val action = call.request.queryParameters["action"] ?: "start"
                            
                            if (!isAuthorized.get() || token != restToken) {
                                call.respondText("{\"error\":\"Unauthorized\"}", ContentType.Application.Json, HttpStatusCode.Unauthorized)
                                return@post
                            }

                            try {
                                if (action == "start") {
                                    activeRingtone?.stop()
                                    val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) 
                                        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                                    
                                    activeRingtone = RingtoneManager.getRingtone(context, uri)
                                    activeRingtone?.audioAttributes = AudioAttributes.Builder()
                                        .setUsage(AudioAttributes.USAGE_ALARM)
                                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                        .build()
                                    
                                    activeRingtone?.play()

                                    // Launch Full-Screen Intent Activity
                                    val intent = Intent(context, FindMyPhoneActivity::class.java).apply {
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                                    }
                                    val pendingIntent = PendingIntent.getActivity(
                                        context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                                    )

                                    val channelId = "phone_hub_ringing"
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                                        val channel = NotificationChannel(
                                            channelId,
                                            "Find My Phone",
                                            NotificationManager.IMPORTANCE_HIGH
                                        ).apply {
                                            description = "Used to show a full-screen overlay when finding your phone"
                                        }
                                        notificationManager.createNotificationChannel(channel)
                                    }

                                    val notification = NotificationCompat.Builder(context, channelId)
                                        .setSmallIcon(R.mipmap.ic_launcher)
                                        .setContentTitle("Find My Phone")
                                        .setContentText("Your phone is ringing")
                                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                                        .setCategory(NotificationCompat.CATEGORY_ALARM)
                                        .setFullScreenIntent(pendingIntent, true)
                                        .setOngoing(true)
                                        .setAutoCancel(false)
                                        .build()

                                    val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                                    notificationManager.notify(778899, notification)

                                    call.respondText("{\"status\":\"ringing\"}", ContentType.Application.Json)
                                } else {
                                    stopRinging(context)
                                    call.respondText("{\"status\":\"stopped\"}", ContentType.Application.Json)
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Ringtone error", e)
                                call.respondText("{\"error\":\"${e.message}\"}", ContentType.Application.Json, HttpStatusCode.InternalServerError)
                            }
                        }
                    }
                }
                server?.start(wait = false)
                isSmsServerActualRunningState.value = true
                Log.d(TAG, "Server engine started successfully.")
            } catch (e: Exception) {
                Log.e(TAG, "Error starting server", e)
                server = null
            }
        }
    }

    fun stop() {
        Log.d(TAG, "Stopping server...")
        server?.stop(1000, 2000)
        server = null
        isSmsServerActualRunningState.value = false
    }

    fun stopRinging(context: Context) {
        activeRingtone?.stop()
        activeRingtone = null
        
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(778899)
        
        // Notify any connected WebSocket clients so they can reset their UI
        SmsServer.broadcastToClients("{\"type\":\"FIND_MY_PHONE\",\"status\":\"stopped\"}")
    }

    fun broadcastToClients(message: String) {
        CoroutineScope(Dispatchers.IO).launch {
            webSocketSessions.toList().forEach { session ->
                if (session.isActive) {
                    try {
                        session.send(Frame.Text(message))
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to send broadcast", e)
                    }
                }
            }
        }
    }
}
