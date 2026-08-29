package com.ipcamera.videoserver.ftp

import kotlinx.coroutines.*
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FtpServer @Inject constructor() {

    private var serverJob: Job? = null
    private var serverSocket: ServerSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun start(port: Int, archiveDir: File, username: String, password: String) {
        serverJob = scope.launch {
            runCatching {
                ServerSocket(port).also { serverSocket = it }.use { ss ->
                    while (isActive) {
                        val client = runCatching { ss.accept() }.getOrNull() ?: break
                        launch { handleClient(client, archiveDir, username, password) }
                    }
                }
            }
        }
    }

    fun stop() {
        serverJob?.cancel()
        serverSocket?.close()
        serverJob = null
        serverSocket = null
    }

    private fun handleClient(
        socket: Socket,
        archiveDir: File,
        username: String,
        password: String,
    ) {
        socket.use {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = PrintWriter(socket.getOutputStream(), true)
            var authenticated = false
            var pendingUser = ""
            var dataSocket: Socket? = null
            val dataHost = socket.inetAddress.hostAddress ?: "127.0.0.1"

            writer.println("220 IP Camera FTP Server")

            while (!socket.isClosed) {
                val line = reader.readLine() ?: break
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
                        val passiveServer = ServerSocket(0)
                        val p = passiveServer.localPort
                        val host = dataHost.replace('.', ',')
                        writer.println("227 Entering Passive Mode ($host,${p / 256},${p % 256})")
                        scope.launch {
                            dataSocket = runCatching { passiveServer.accept() }.getOrNull()
                            passiveServer.close()
                        }
                    }
                    "LIST" -> {
                        if (!authenticated) { writer.println("530 Not logged in"); continue }
                        writer.println("150 Directory listing")
                        Thread.sleep(300)
                        dataSocket?.use { ds ->
                            val out = PrintWriter(ds.getOutputStream(), true)
                            val sdf = SimpleDateFormat("MMM dd HH:mm", Locale.US)
                            archiveDir.listFiles()
                                ?.sortedByDescending { it.lastModified() }
                                ?.forEach { f ->
                                    out.println("-rw-r--r-- 1 ftp ftp ${f.length()} ${sdf.format(Date(f.lastModified()))} ${f.name}")
                                }
                        }
                        dataSocket = null
                        writer.println("226 Transfer complete")
                    }
                    "RETR" -> {
                        if (!authenticated) { writer.println("530 Not logged in"); continue }
                        val file = File(archiveDir, File(arg).name)
                        if (!file.exists() || !file.canonicalPath.startsWith(archiveDir.canonicalPath)) {
                            writer.println("550 File not found"); continue
                        }
                        writer.println("150 Sending ${file.name}")
                        Thread.sleep(300)
                        dataSocket?.use { ds ->
                            file.inputStream().use { fis -> fis.copyTo(ds.getOutputStream()) }
                        }
                        dataSocket = null
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
