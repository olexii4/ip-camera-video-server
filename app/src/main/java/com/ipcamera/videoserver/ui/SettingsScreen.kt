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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val scope = rememberCoroutineScope()

    // Read current persisted values once as initial state
    val serverPort by vm.settings.serverPort.collectAsState(initial = 8080)
    val authEnabled by vm.settings.authEnabled.collectAsState(initial = true)
    val adminUsername by vm.settings.adminUsername.collectAsState(initial = "admin")
    val smsNumber by vm.settings.smsTargetNumber.collectAsState(initial = "")
    val archiveMaxFiles by vm.settings.archiveMaxFiles.collectAsState(initial = 1440)
    val archiveMaxSizeGb by vm.settings.archiveMaxSizeGb.collectAsState(initial = 30)
    val archiveEnabledMain by vm.settings.archiveEnabledMain.collectAsState(initial = false)
    val archiveEnabledFront by vm.settings.archiveEnabledFront.collectAsState(initial = false)
    val ftpEnabled by vm.settings.ftpEnabled.collectAsState(initial = false)
    val ftpPort by vm.settings.ftpPort.collectAsState(initial = 2121)
    val ftpsEnabled by vm.settings.ftpsEnabled.collectAsState(initial = false)
    val startOnBoot by vm.settings.serverStartedOnBoot.collectAsState(initial = false)

    // Local draft state for fields that need explicit Save
    var draftPort by remember(serverPort) { mutableStateOf(serverPort.toString()) }
    var draftUsername by remember(adminUsername) { mutableStateOf(adminUsername) }
    var draftPassword by remember { mutableStateOf("") }
    var draftSms by remember(smsNumber) { mutableStateOf(smsNumber) }
    var draftMaxFiles by remember(archiveMaxFiles) { mutableStateOf(archiveMaxFiles.toString()) }
    var draftMaxSizeGb by remember(archiveMaxSizeGb) { mutableStateOf(archiveMaxSizeGb.toString()) }
    var draftFtpPort by remember(ftpPort) { mutableStateOf(ftpPort.toString()) }

    var savedSnack by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)

        SectionHeader("Web Server")
        DraftTextField("Port", draftPort, KeyboardType.Number) { draftPort = it }
        LabeledSwitch("Start on boot", startOnBoot) {
            scope.launch { vm.settings.setServerStartedOnBoot(it) }
        }

        SectionHeader("Access control")
        LabeledSwitch("Require login", authEnabled) {
            scope.launch { vm.settings.setAuthEnabled(it) }
        }
        if (!authEnabled) {
            Text(
                "⚠ Anyone on the network can view cameras without a password.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (authEnabled) {
            DraftTextField("Username", draftUsername, KeyboardType.Text) { draftUsername = it }
            DraftTextField(
                "Password (leave blank to keep current)",
                draftPassword,
                KeyboardType.Password,
                visualTransformation = true,
            ) { draftPassword = it }
        }

        SectionHeader("SMS notification")
        DraftTextField("Target phone number", draftSms, KeyboardType.Phone) { draftSms = it }

        SectionHeader("Archive")
        LabeledSwitch("Record main camera", archiveEnabledMain) {
            scope.launch { vm.settings.setArchiveEnabledMain(it) }
        }
        LabeledSwitch("Record front camera", archiveEnabledFront) {
            scope.launch { vm.settings.setArchiveEnabledFront(it) }
        }
        DraftTextField("Max files", draftMaxFiles, KeyboardType.Number) { draftMaxFiles = it }
        DraftTextField("Max storage (GB)", draftMaxSizeGb, KeyboardType.Number) { draftMaxSizeGb = it }

        SectionHeader("FTP Server")
        LabeledSwitch("FTP enabled", ftpEnabled) {
            scope.launch { vm.settings.setFtpEnabled(it) }
        }
        if (ftpEnabled) {
            LabeledSwitch("Use FTPS (encrypted)", ftpsEnabled) {
                scope.launch {
                    vm.settings.setFtpsEnabled(it)
                    // Auto-switch default port unless user already overrode it
                    val currentPort = draftFtpPort.toIntOrNull()
                    if (it && currentPort == 2121) draftFtpPort = "2122"
                    else if (!it && currentPort == 2122) draftFtpPort = "2121"
                }
            }
            DraftTextField("Port", draftFtpPort, KeyboardType.Number) { draftFtpPort = it }
            if (!ftpsEnabled) {
                Text(
                    "⚠ Plain FTP transmits data unencrypted. Use on trusted networks only.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (ftpsEnabled) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "TLS certificate SHA-256 fingerprint",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    vm.tlsFingerprint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "Enter this in your FTP client to verify the server identity.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = {
                scope.launch {
                    draftPort.toIntOrNull()?.let { vm.settings.setServerPort(it) }
                    vm.settings.setSmsTargetNumber(draftSms)
                    draftMaxFiles.toIntOrNull()?.let { vm.settings.setArchiveMaxFiles(it) }
                    draftMaxSizeGb.toIntOrNull()?.let { vm.settings.setArchiveMaxSizeGb(it) }
                    draftFtpPort.toIntOrNull()?.let { vm.settings.setFtpPort(it) }
                    if (draftUsername.isNotBlank()) vm.settings.setAdminUsername(draftUsername)
                    if (draftPassword.isNotEmpty()) {
                        val hash = vm.hashPassword(draftPassword)
                        vm.settings.setAdminPasswordHash(hash)
                        vm.settings.setAdminPasswordPlain(draftPassword)
                        draftPassword = ""
                    }
                    savedSnack = true
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Save Settings")
        }

        if (savedSnack) {
            LaunchedEffect(savedSnack) {
                kotlinx.coroutines.delay(2000)
                savedSnack = false
            }
            Text(
                "✓ Saved. Restart the server to apply changes.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
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
private fun DraftTextField(
    label: String,
    value: String,
    keyboardType: KeyboardType,
    visualTransformation: Boolean = false,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        visualTransformation = if (visualTransformation) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
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
