package com.ipcamera.videoserver.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun ArchiveScreen(vm: AppViewModel) {
    LaunchedEffect(Unit) { vm.refreshArchive() }
    val files by vm.archiveFiles.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Archive", style = MaterialTheme.typography.headlineMedium)
        Text(
            "${files.size} recording(s) · ${files.sumOf { it.length() } / 1_048_576} MB total",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))

        if (files.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No recordings yet. Enable archive in Settings.")
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(files, key = { it.absolutePath }) { file ->
                    ArchiveFileRow(file) { vm.deleteArchiveFile(file) }
                }
            }
        }
    }
}

@Composable
private fun ArchiveFileRow(file: File, onDelete: () -> Unit) {
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(file.name, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "${sdf.format(Date(file.lastModified()))} · ${file.length() / 1_048_576} MB",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete ${file.name}")
            }
        }
    }
}
