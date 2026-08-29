package com.ipcamera.videoserver.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val scope = rememberCoroutineScope()
    val serverPort by vm.settings.serverPort.collectAsState(initial = 8080)
    val smsNumber by vm.settings.smsTargetNumber.collectAsState(initial = "")
    val archiveMaxFiles by vm.settings.archiveMaxFiles.collectAsState(initial = 1440)
    val archiveMaxSizeGb by vm.settings.archiveMaxSizeGb.collectAsState(initial = 30)
    val archiveEnabledMain by vm.settings.archiveEnabledMain.collectAsState(initial = false)
    val archiveEnabledFront by vm.settings.archiveEnabledFront.collectAsState(initial = false)
    val ftpEnabled by vm.settings.ftpEnabled.collectAsState(initial = false)
    val ftpPort by vm.settings.ftpPort.collectAsState(initial = 2121)
    val startOnBoot by vm.settings.serverStartedOnBoot.collectAsState(initial = false)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)

        SectionHeader("Web Server")
        SettingTextField("Port", serverPort.toString(), KeyboardType.Number) { v ->
            v.toIntOrNull()?.let { scope.launch { vm.settings.setServerPort(it) } }
        }
        LabeledSwitch("Start on boot", startOnBoot) {
            scope.launch { vm.settings.setServerStartedOnBoot(it) }
        }

        SectionHeader("SMS Notification")
        SettingTextField("Target phone number", smsNumber, KeyboardType.Phone) { v ->
            scope.launch { vm.settings.setSmsTargetNumber(v) }
        }

        SectionHeader("Archive")
        LabeledSwitch("Record main camera", archiveEnabledMain) {
            scope.launch { vm.settings.setArchiveEnabledMain(it) }
        }
        LabeledSwitch("Record front camera", archiveEnabledFront) {
            scope.launch { vm.settings.setArchiveEnabledFront(it) }
        }
        SettingTextField("Max files", archiveMaxFiles.toString(), KeyboardType.Number) { v ->
            v.toIntOrNull()?.let { scope.launch { vm.settings.setArchiveMaxFiles(it) } }
        }
        SettingTextField("Max storage (GB)", archiveMaxSizeGb.toString(), KeyboardType.Number) { v ->
            v.toIntOrNull()?.let { scope.launch { vm.settings.setArchiveMaxSizeGb(it) } }
        }

        SectionHeader("FTP Server")
        LabeledSwitch("FTP enabled", ftpEnabled) {
            scope.launch { vm.settings.setFtpEnabled(it) }
        }
        SettingTextField("FTP port", ftpPort.toString(), KeyboardType.Number) { v ->
            v.toIntOrNull()?.let { scope.launch { vm.settings.setFtpPort(it) } }
        }
        if (ftpEnabled) {
            Text(
                "Warning: FTP transfers data unencrypted. Use on trusted networks only.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    HorizontalDivider()
}

@Composable
private fun SettingTextField(
    label: String,
    value: String,
    keyboardType: KeyboardType,
    onSave: (String) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; onSave(it) },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
}

@Composable
private fun LabeledSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
