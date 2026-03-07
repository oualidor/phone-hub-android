package com.oualidkhial.phonehubserver

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import com.oualidkhial.phonehubserver.ui.theme.PhoneHUBServerTheme


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            PhoneHUBServerTheme {
                val localContext = androidx.compose.ui.platform.LocalContext.current
                var permissionStates by remember { mutableStateOf(getPermissionStates(localContext)) }

                // Refresh permissions when returning to the app
                val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
                androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
                    val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                        if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                            permissionStates = getPermissionStates(localContext)
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose {
                        lifecycleOwner.lifecycle.removeObserver(observer)
                    }
                }

                val permissionsLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) { permissions ->
                    if (permissions[Manifest.permission.READ_SMS] == true) {
                        sendBroadcast(Intent("LOAD_SMS_ACTION"))
                    }
                    if (permissions[Manifest.permission.POST_NOTIFICATIONS] == true || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                        startSmsServerService()
                    }
                }

                val pairingRequest by SmsServer.pairingRequests.collectAsState()

                if (pairingRequest != null) {
                    val info = pairingRequest!!
                    AlertDialog(
                        onDismissRequest = { SmsServer.pairingRequests.value = null },
                        title = { Text("Pairing Request") },
                        text = { Text("Allow connection from ${info.deviceName} (${info.ip})?") },
                        confirmButton = {
                            TextButton(onClick = {
                                SmsServer.setAuthorized(
                                    this@MainActivity, 
                                    true, 
                                    info.ip,
                                    info.deviceName,
                                    SmsServer.getPendingRestToken(),
                                    SmsServer.getPendingWsToken()
                                )
                                SmsServer.pairingRequests.value = null
                            }) {
                                Text("Allow")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                SmsServer.pairingRequests.value = null
                            }) {
                                Text("Deny")
                            }
                        }
                    )
                }

                LaunchedEffect(Unit) {
                    val permissionsToRequest = mutableListOf(
                        Manifest.permission.RECEIVE_SMS,
                        Manifest.permission.READ_SMS,
                        Manifest.permission.READ_PHONE_STATE,
                        Manifest.permission.READ_CALL_LOG,
                        Manifest.permission.ANSWER_PHONE_CALLS,
                        Manifest.permission.READ_CONTACTS,
                        Manifest.permission.CAMERA
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    permissionsLauncher.launch(permissionsToRequest.toTypedArray())
                }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    SmsListScreen(
                        modifier = Modifier.padding(innerPadding),
                        permissionStates = permissionStates
                    )
                }
            }
        }
    }

    private fun startSmsServerService() {
        val serviceIntent = Intent(this, SmsServerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }
}

@Composable
fun SmsListScreen(
    modifier: Modifier = Modifier,
    permissionStates: PermissionStates
) {
    val messages by SmsRepository.messages.collectAsState()
    val ipAddress = SmsServer.getLocalIpAddress()
    val isAuthorized by SmsServer.authorizedState.collectAsState()
    val connectedClientIp by SmsServer.clientIpState.collectAsState()
    val connectedDeviceName by SmsServer.deviceNameState.collectAsState()
    val isClientConnected by SmsServer.isClientConnectedState.collectAsState()
    val localContext = androidx.compose.ui.platform.LocalContext.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // --- HEADER SECTION ---
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Phone HUB Server",
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "IP Address: $ipAddress:8080",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Connection Status Area
                if (isAuthorized) {
                    if (isClientConnected) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(16.dp)
                            ) {
                                // Online Indicator Dot
                                androidx.compose.foundation.layout.Box(
                                    modifier = Modifier
                                        .padding(end = 12.dp)
                                        .width(10.dp)
                                        .height(10.dp)
                                        .background(Color(0xFF4CAF50), shape = androidx.compose.foundation.shape.CircleShape)
                                )
                                Column {
                                    Text(
                                        text = "Connected securely to PC",
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                        color = Color(0xFF2E7D32)
                                    )
                                    Text(
                                        text = connectedDeviceName ?: connectedClientIp ?: "Unknown PC",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFF388E3C)
                                    )
                                }
                            }
                        }
                    } else {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(16.dp)
                            ) {
                                androidx.compose.foundation.layout.Box(
                                    modifier = Modifier
                                        .padding(end = 12.dp)
                                        .width(10.dp)
                                        .height(10.dp)
                                        .background(Color.Gray, shape = androidx.compose.foundation.shape.CircleShape)
                                )
                                Column {
                                    Text(
                                        text = "Paired but disconnected",
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                    Text(
                                        text = connectedDeviceName ?: connectedClientIp ?: "Unknown PC",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                                    )
                                }
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedButton(
                        onClick = { SmsServer.unpair(localContext) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = "Unpair Device",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                } else {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Column {
                                Text(
                                    text = "Not Connected",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "1. Install the Phone HUB extension on your GNOME desktop.\n" +
                                           "2. Open the extension and select 'Pair New Device'.\n" +
                                           "3. Tap the button below and scan the QR code on your PC.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.9f)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Button(
                                    onClick = {
                                        localContext.startActivity(Intent(localContext, QrScannerActivity::class.java))
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Scan QR to Pair")
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // --- PERMISSIONS SECTION ---
        
        // 1. Notification Access (Special)
        if (!permissionStates.notificationsEnabled) {
            PermissionCard(
                title = "Notification Access Required",
                description = "To see phone notifications on your PC, you must enable Notification Access for this app.",
                buttonText = "Enable Notification Access",
                onClick = {
                    localContext.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
                }
            )
        }

        // 2. SMS Permissions
        if (!permissionStates.smsEnabled) {
            PermissionCard(
                title = "SMS Sync Required",
                description = "To see and respond to SMS on your PC, please grant SMS permissions.",
                buttonText = "Grant SMS Permissions",
                onClick = {
                    localContext.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", localContext.packageName, null)
                    })
                }
            )
        }

        // 3. Call Permissions
        if (!permissionStates.callsEnabled) {
            PermissionCard(
                title = "Call Sync Required",
                description = "To see incoming calls and answer them from your PC, please grant Phone and Call Log permissions.",
                buttonText = "Grant Call Permissions",
                onClick = {
                    localContext.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", localContext.packageName, null)
                    })
                }
            )
        }

        // 4. Contact Permissions
        if (!permissionStates.contactsEnabled) {
            PermissionCard(
                title = "Contact Names Required",
                description = "To see contact names instead of just phone numbers, please grant Contacts permission.",
                buttonText = "Grant Contacts Permission",
                onClick = {
                    localContext.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", localContext.packageName, null)
                    })
                }
            )
        }



        // 6. Camera Permission (for QR)
        if (!permissionStates.cameraEnabled) {
            PermissionCard(
                title = "Scanner Access Required",
                description = "To scan the pairing QR code, please grant Camera permission.",
                buttonText = "Grant Camera Permission",
                onClick = {
                    localContext.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", localContext.packageName, null)
                    })
                }
            )
        }

        // 7. Storage Permission (for SFTP)
        if (!permissionStates.storageEnabled) {
            PermissionCard(
                title = "File Access Required",
                description = "To mount your phone's files on your PC, please enable file access.",
                buttonText = "Enable File Access",
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        try {
                            val intent = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                data = Uri.parse("package:${localContext.packageName}")
                            }
                            localContext.startActivity(intent)
                        } catch (e: Exception) {
                            val intent = Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                            localContext.startActivity(intent)
                        }
                    } else {
                        localContext.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", localContext.packageName, null)
                        })
                    }
                }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

data class PermissionStates(
    val notificationsEnabled: Boolean,
    val smsEnabled: Boolean,
    val callsEnabled: Boolean,
    val contactsEnabled: Boolean,
    val cameraEnabled: Boolean,
    val storageEnabled: Boolean
)

fun getPermissionStates(context: android.content.Context): PermissionStates {
    fun check(perm: String) = ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
    
    return PermissionStates(
        notificationsEnabled = android.provider.Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")?.contains(context.packageName) == true,
        smsEnabled = check(Manifest.permission.READ_SMS) && check(Manifest.permission.RECEIVE_SMS),
        callsEnabled = check(Manifest.permission.READ_PHONE_STATE) && check(Manifest.permission.READ_CALL_LOG) && check(Manifest.permission.ANSWER_PHONE_CALLS),
        contactsEnabled = check(Manifest.permission.READ_CONTACTS),
        cameraEnabled = check(Manifest.permission.CAMERA),
        storageEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            check(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    )
}

@Composable
fun PermissionCard(
    title: String,
    description: String,
    buttonText: String,
    onClick: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onClick,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(buttonText)
            }
        }
    }
}

@Composable
fun SmsItem(message: SmsMessage) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = message.sender,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                        .format(java.util.Date(message.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = message.body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                maxLines = 3,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
    }
}

