package com.oualidkhial.phonehubserver

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
import java.io.File
import java.nio.file.Paths

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
                
                // Host key provider
                val hostKeyFile = File(context.filesDir, "hostkey.ser")
                Log.d("SftpServer", "Using host key file: ${hostKeyFile.absolutePath}")
                server.keyPairProvider = SimpleGeneratorHostKeyProvider(hostKeyFile.toPath())

                // SFTP Subsystem
                server.subsystemFactories = listOf(SftpSubsystemFactory.Builder().build())

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
                Log.i("SftpServer", "SFTP Server SUCCESSFULLY started on 0.0.0.0:$port")
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
            Log.i("SftpServer", "SFTP Server stopped")
        } catch (e: Exception) {
            Log.e("SftpServer", "Error stopping SFTP server", e)
        }
    }
}
