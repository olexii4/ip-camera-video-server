package com.ipcamera.videoserver.ftp

import kotlinx.coroutines.*
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ServerSocketFactory
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLServerSocketFactory
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

@Singleton
class FtpServer @Inject constructor() {

    private var serverJob: Job? = null
    private var serverSocket: ServerSocket? = null
    private var secureJob: Job? = null
    private var secureSocket: ServerSocket? = null
    private var scope = newScope()

    private fun newScope() = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun start(port: Int, archiveDir: File, username: String, password: String) =
        launch(port, archiveDir, username, password, null, isSecure = false)

    fun startSecure(
        port: Int,
        archiveDir: File,
        username: String,
        password: String,
        sslSocketFactory: SSLServerSocketFactory,
    ) = launch(port, archiveDir, username, password, sslSocketFactory, isSecure = true)

    private fun launch(
        port: Int,
        archiveDir: File,
        username: String,
        password: String,
        sslSocketFactory: SSLServerSocketFactory?,
        isSecure: Boolean,
    ) {
        val factory: ServerSocketFactory = sslSocketFactory ?: ServerSocketFactory.getDefault()
        val job = scope.launch {
            runCatching {
                (factory.createServerSocket(port) as ServerSocket).also { ss ->
                    (ss as? SSLServerSocket)?.useClientMode = false
                    if (isSecure) secureSocket = ss else serverSocket = ss
                    ss.use {
                        while (isActive) {
                            val client = runCatching { ss.accept() }.getOrNull() ?: break
                            launch { handleClient(client, archiveDir, username, password, sslSocketFactory) }
                        }
                    }
                }
            }
        }
        if (isSecure) secureJob = job else serverJob = job
    }

    fun stop() {
        serverJob?.cancel(); serverSocket?.close()
        secureJob?.cancel(); secureSocket?.close()
        scope.cancel()
        scope = newScope()
        serverJob = null; serverSocket = null
        secureJob = null; secureSocket = null
    }

    private suspend fun handleClient(
        socket: Socket,
        archiveDir: File,
        username: String,
        password: String,
        sslSocketFactory: SSLServerSocketFactory? = null,
    ) {
        socket.use {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = PrintWriter(socket.getOutputStream(), true)
            var authenticated = false
            var pendingUser = ""
            var dataDeferred: CompletableDeferred<Socket?>? = null
            val dataHost = socket.inetAddress.hostAddress ?: "127.0.0.1"

            writer.println("220 IP Camera FTP Server")

            while (!socket.isClosed) {
                val line = withContext(Dispatchers.IO) { reader.readLine() } ?: break
                val cmd = line.substringBefore(' ').uppercase()
                val arg = if (line.contains(' ')) line.substringAfter(' ').trim() else ""

                when (cmd) {
                    "USER" -> { pendingUser = arg; writer.println("331 Password required") }
                    "PASS" -> {
                        if (pendingUser == username && arg == password) {
                            authenticated = true
                            writer.println("230 Logged in")
                        } else {
                            writer.println("530 Authentication failed")
                        }
                    }
                    "QUIT" -> { writer.println("221 Bye"); break }
                    "SYST" -> writer.println("215 UNIX Type: L8")
                    "FEAT" -> writer.println("211-Features:\r\n PASV\r\n211 End")
                    "PWD"  -> writer.println("257 \"/\" is current directory")
                    "CWD"  -> writer.println("250 Directory changed")
                    "TYPE" -> writer.println("200 Type set")
                    "PASV" -> {
                        if (!authenticated) { writer.println("530 Not logged in"); continue }
                        val passiveServerSocket: ServerSocket = if (sslSocketFactory != null) {
                            (sslSocketFactory.createServerSocket(0) as SSLServerSocket)
                                .also { it.useClientMode = false }
                        } else {
                            ServerSocket(0)
                        }
                        val p = passiveServerSocket.localPort
                        val host = dataHost.replace('.', ',')
                        writer.println("227 Entering Passive Mode ($host,${p / 256},${p % 256})")
                        val deferred = CompletableDeferred<Socket?>()
                        dataDeferred = deferred
                        scope.launch {
                            deferred.complete(runCatching { passiveServerSocket.accept() }.getOrNull())
                            passiveServerSocket.close()
                        }
                    }
                    "LIST" -> {
                        if (!authenticated) { writer.println("530 Not logged in"); continue }
                        writer.println("150 Directory listing")
                        val ds = dataDeferred?.await()
                        dataDeferred = null
                        ds?.use { conn ->
                            val out = PrintWriter(conn.getOutputStream(), true)
                            val sdf = SimpleDateFormat("MMM dd HH:mm", Locale.US)
                            archiveDir.listFiles()
                                ?.sortedByDescending { it.lastModified() }
                                ?.forEach { f ->
                                    out.println("-rw-r--r-- 1 ftp ftp ${f.length()} ${sdf.format(Date(f.lastModified()))} ${f.name}")
                                }
                        }
                        writer.println("226 Transfer complete")
                    }
                    "RETR" -> {
                        if (!authenticated) { writer.println("530 Not logged in"); continue }
                        val file = File(archiveDir, File(arg).name)
                        if (!file.exists() || !file.canonicalPath.startsWith(archiveDir.canonicalPath)) {
                            writer.println("550 File not found"); continue
                        }
                        writer.println("150 Sending ${file.name}")
                        val ds = dataDeferred?.await()
                        dataDeferred = null
                        ds?.use { conn ->
                            withContext(Dispatchers.IO) {
                                file.inputStream().use { fis -> fis.copyTo(conn.getOutputStream()) }
                            }
                        }
                        writer.println("226 Transfer complete")
                    }
                    "SIZE" -> {
                        val file = File(archiveDir, File(arg).name)
                        if (file.exists()) writer.println("213 ${file.length()}")
                        else writer.println("550 File not found")
                    }
                    "NOOP" -> writer.println("200 OK")
                    else -> writer.println("502 Command not implemented: $cmd")
                }
            }
        }
    }
}
