package com.oualidkhial.phonehubserver
import android.Manifest
import org.apache.sshd.server.shell.ProcessShellFactory
import android.content.Context
import android.os.Environment
import android.util.Log
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.common.util.io.PathUtils
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import android.os.BatteryManager
import java.io.File
import java.nio.file.Paths
import org.apache.sshd.server.command.Command
import java.io.InputStream
import java.io.OutputStream
import org.apache.sshd.server.Environment as SshEnvironment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.session.ServerSession
import org.apache.sshd.server.shell.ShellFactory
import org.apache.sshd.server.command.CommandFactory
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.shell.InteractiveProcessShellFactory
import android.telephony.TelephonyManager
import androidx.annotation.RequiresPermission

class SftpServer(private val context: Context) {
    private var sshd: SshServer? = null
    private val port = 2222

    fun start() {
        if (sshd != null && sshd!!.isOpen) return


        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d("SftpServer", "Setting up home folder resolver...")
                PathUtils.setUserHomeFolderResolver { context.filesDir.toPath() }
                
                Log.d("SftpServer", "Starting server setup...")
                val server = SshServer.setUpDefaultServer()
                server.host = "0.0.0.0"
                server.port = this@SftpServer.port



//                server.shellFactory = ShellFactory { channel ->
//                    // Try just "sh" with the interactive flag
//                    ProcessShellFactory("sh").createShell(channel)
//                }
//                // Support non-interactive commands
//                server.commandFactory = CommandFactory { channel, command ->
//                    Log.d("SftpServer", "Executing remote command: $command")
//                    ProcessShellFactory("/system/bin/sh", "-c", command).createShell(channel)
//                }

                // Password Authenticator
                server.passwordAuthenticator = object : PasswordAuthenticator {
                    override fun authenticate(username: String?, password: String?, session: org.apache.sshd.server.session.ServerSession?): Boolean {
                        val savedToken = SmsServer.getRestToken(context)
                        val matches = (password != null && password == savedToken)
                        Log.d("SftpServer", "Auth attempt: user=$username")
                        Log.d("SftpServer", "Token debug: providedLen=${password?.length}, savedLen=${savedToken?.length}, matches=$matches")
                        if (password != null && savedToken != null && !matches) {
                            Log.d("SftpServer", "Mismatch debug: providedPrefix=${password.take(4)}, savedPrefix=${savedToken.take(4)}")
                        }
                        return (username == "phonehub" || username == "root") && matches
                    }
                }

                val hostKeyFile = File(context.filesDir, "hostkey.ser")
                Log.d("SftpServer", "Using host key file: ${hostKeyFile.absolutePath}")
                server.keyPairProvider = SimpleGeneratorHostKeyProvider(hostKeyFile.toPath())

                // SFTP Subsystem
                server.subsystemFactories = listOf(SftpSubsystemFactory.Builder().build())

                // Remote Shell Support
                server.setShellFactory(InteractiveProcessShellFactory.INSTANCE)

                // Support non-interactive commands
                server.commandFactory = CommandFactory { _, command ->
                    Log.d("SftpServer", "Requested command: $command")
                    val cmd = command.trim().lowercase()
                    when {
                        cmd == "battery" -> BatteryCommand(context)
                        cmd == "bluetooth" -> BluetoothCommand(context)
                        cmd.startsWith("mobile_data") -> {
                            val arg = cmd.substringAfter("mobile_data").trim()
                            MobileDataCommand(context, arg)
                        }
                        else -> {
                            // General shell command support
                            ProcessShellFactory("/system/bin/sh", "-c", command).createShell(null)
                        }
                    }
                }

                


                // Root directory - Try to be more permissive with path
                try {
                    val rootPath = Environment.getExternalStorageDirectory().toPath()
                    server.fileSystemFactory = VirtualFileSystemFactory(rootPath)
                    Log.d("SftpServer", "Root path set to: $rootPath")
                } catch (e: Exception) {
                    Log.e("SftpServer", "Failed to set root path, using default", e)
                }
                
                server.start()
                sshd = server
                SmsServer.isSftpServerActualRunningState.value = true
                Log.i("SftpServer", "SFTP Server SUCCESSFULLY started on 0.0.0.0:$port")
                val shellPath = "/system/bin/sh"
                val shellFile = File(shellPath)
                Log.d("SftpServer", "Shell exists: ${shellFile.exists()}, Can execute: ${shellFile.canExecute()}")

                if (!shellFile.exists()) {
                    Log.e("SftpServer", "CRITICAL: /system/bin/sh not found. Device might be non-standard.")
                }
            } catch (e: Throwable) {
                Log.e("SftpServer", "CRITICAL ERROR during SFTP server startup", e)
                e.printStackTrace()
            }
        }
    }

    fun stop() {
        try {
            sshd?.stop(true)
            sshd = null
            SmsServer.isSftpServerActualRunningState.value = false
            Log.i("SftpServer", "SFTP Server stopped")
        } catch (e: Exception) {
            Log.e("SftpServer", "Error stopping SFTP server", e)
        }
    }
}

class BatteryCommand(private val context: Context) : Command {
    private var out: OutputStream? = null
    private var callback: ExitCallback? = null

    override fun setInputStream(`in`: InputStream?) {}
    override fun setOutputStream(out: OutputStream?) { this.out = out }
    override fun setErrorStream(err: OutputStream?) {}
    override fun setExitCallback(callback: ExitCallback?) { this.callback = callback }

    override fun start(channel: ChannelSession?, env: SshEnvironment?) {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val response = "Battery Level: $level%\n"
        
        try {
            out?.write(response.toByteArray())
            out?.flush()
            callback?.onExit(0)
        } catch (e: Exception) {
            callback?.onExit(1, e.message)
        }
    }

    override fun destroy(channel: ChannelSession?) {}
}

class MobileDataCommand(private val context: Context, private val arg: String) : Command {
    private var out: OutputStream? = null
    private var callback: ExitCallback? = null

    override fun setInputStream(`in`: InputStream?) {}
    override fun setOutputStream(out: OutputStream?) {
        this.out = out
    }

    override fun setErrorStream(err: OutputStream?) {}
    override fun setExitCallback(callback: ExitCallback?) {
        this.callback = callback
    }

    @RequiresPermission(Manifest.permission.READ_PHONE_STATE)
    override fun start(channel: ChannelSession?, env: SshEnvironment?) {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

        val result = try {
            val enabled = tm.isDataEnabled
            val operator = tm.networkOperatorName ?: "Unknown"
            val networkType = tm.dataNetworkType
            val typeStr = getNetworkTypeString(networkType)
            "Mobile Data: ${if (enabled) "on" else "off"} ($operator - $typeStr)\n"
        } catch (e: Exception) {
            "Error: ${e.message}\n"
        }

        try {
            out?.write(result.toByteArray())
            out?.flush()
            callback?.onExit(0)
        } catch (e: Exception) {
            callback?.onExit(1, e.message)
        }
    }

    private fun getNetworkTypeString(type: Int): String {
        return when (type) {
            TelephonyManager.NETWORK_TYPE_GPRS, TelephonyManager.NETWORK_TYPE_EDGE,
            TelephonyManager.NETWORK_TYPE_CDMA, TelephonyManager.NETWORK_TYPE_1xRTT,
            TelephonyManager.NETWORK_TYPE_IDEN -> "2G"

            TelephonyManager.NETWORK_TYPE_UMTS, TelephonyManager.NETWORK_TYPE_EVDO_0,
            TelephonyManager.NETWORK_TYPE_EVDO_A, TelephonyManager.NETWORK_TYPE_HSDPA,
            TelephonyManager.NETWORK_TYPE_HSUPA, TelephonyManager.NETWORK_TYPE_HSPA,
            TelephonyManager.NETWORK_TYPE_EVDO_B, TelephonyManager.NETWORK_TYPE_EHRPD,
            TelephonyManager.NETWORK_TYPE_HSPAP -> "3G"

            TelephonyManager.NETWORK_TYPE_LTE -> "4G"
            TelephonyManager.NETWORK_TYPE_NR -> "5G"
            else -> "Unknown"
        }
    }

    override fun destroy(channel: ChannelSession?) {}

}

class BluetoothCommand(private val context: Context) : Command {
    private var out: OutputStream? = null
    private var callback: ExitCallback? = null

    override fun setInputStream(`in`: InputStream?) {}
    override fun setOutputStream(out: OutputStream?) { this.out = out }
    override fun setErrorStream(err: OutputStream?) {}
    override fun setExitCallback(callback: ExitCallback?) { this.callback = callback }

    override fun start(channel: ChannelSession?, env: SshEnvironment?) {
        val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
        val result = try {
            if (adapter == null) {
                "Bluetooth: missing\n"
            } else {
                if (adapter.isEnabled) "Bluetooth: on\n" else "Bluetooth: off\n"
            }
        } catch (e: Exception) {
            "Error: ${e.message}\n"
        }

        try {
            out?.write(result.toByteArray())
            out?.flush()
            callback?.onExit(0)
        } catch (e: Exception) {
            callback?.onExit(1, e.message)
        }
    }

    override fun destroy(channel: ChannelSession?) {}
}
