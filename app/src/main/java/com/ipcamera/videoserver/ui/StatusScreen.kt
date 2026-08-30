package com.ipcamera.videoserver.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun StatusScreen(vm: AppViewModel) {
    val isRunning by vm.isRunning.collectAsState()
    val localIp by vm.localIp.collectAsState()
    val port by vm.settings.serverPort.collectAsState(initial = 8080)
    val context = LocalContext.current
    val sessions = vm.activeSessions
    val sessionLabel = sessions.firstOrNull()
        ?.let { "${it.username}@${it.remoteAddress}" }
        ?: "—"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("IP Camera Server", style = MaterialTheme.typography.headlineMedium)

        StatusRow("Status", if (isRunning) "Running" else "Stopped")
        StatusRow("Local IP", localIp.ifEmpty { "—" })
        StatusRow("Port", port.toString())
        StatusRow("Active session", sessionLabel)

        if (isRunning && localIp.isNotEmpty()) {
            val url = "http://$localIp:$port"
            OutlinedButton(
                onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("Server URL", url))
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Copy URL: $url")
            }
        }

        Spacer(Modifier.weight(1f))

        Button(
            onClick = { if (isRunning) vm.stopServer() else vm.startServer() },
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isRunning) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (isRunning) "Stop Server" else "Start Server")
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
    HorizontalDivider()
}
