package com.oualidkhial.phonehubserver

import android.content.Context
import android.content.Intent
import android.util.Log
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
import android.content.SharedPreferences
import io.ktor.server.application.install
import io.ktor.server.request.receiveText

private const val PREFS_NAME = "PhoneHubPrefs"
private const val KEY_IS_AUTHORIZED = "isAuthorized"
private const val KEY_CONNECTED_CLIENT_IP = "connectedClientIp"
private const val KEY_REST_TOKEN = "restToken"
private const val KEY_WS_TOKEN = "wsToken"
private const val KEY_CONNECTED_DEVICE_NAME = "connectedDeviceName"

data class PairingRequest(val ip: String, val deviceName: String)


object SmsServer {
    private var server: ApplicationEngine? = null
    private const val TAG = "SmsServer"

    val isAuthorized = AtomicBoolean(false)
    val authorizedState = kotlinx.coroutines.flow.MutableStateFlow(false)
    var connectedClientIp: String? = null
    var connectedDeviceName: String? = null
    val clientIpState = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val deviceNameState = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val isClientConnectedState = kotlinx.coroutines.flow.MutableStateFlow(false)
    val pairingRequests = kotlinx.coroutines.flow.MutableStateFlow<PairingRequest?>(null)
    
    var restToken: String? = null
    var wsToken: String? = null

    private var pendingRestToken: String? = null
    private var pendingWsToken: String? = null

    fun getPendingRestToken() = pendingRestToken
    fun getPendingWsToken() = pendingWsToken

    fun getRestToken(context: Context): String? {
        return restToken ?: getPrefs(context).getString(KEY_REST_TOKEN, null)
    }

    // WebSocket connections
    private val webSocketSessions = java.util.Collections.synchronizedList(mutableListOf<DefaultWebSocketServerSession>())


    // Call state (can be IDLE, RINGING, OFFHOOK)
    var callStatus = "IDLE"
        set(value) {
            field = value
            broadcastCallStatus()
        }
    var callerNumber = ""

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
        val payload = "{\"type\":\"CALL_STATUS\",\"status\":\"$callStatus\",\"number\":\"$callerNumber\"}"
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
        val payload = "{\"type\":\"NOTIFICATION\",\"id\":\"${notif.id}\",\"packageName\":\"${notif.packageName}\",\"title\":\"$safeTitle\",\"text\":\"$safeText\"}"
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
                    }
                }
                server?.start(wait = false)
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
    }

    fun getLocalIpAddress(): String {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is java.net.Inet4Address) {
                        return address.hostAddress ?: "0.0.0.0"
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting IP", e)
        }
        return "0.0.0.0"
    }
}

