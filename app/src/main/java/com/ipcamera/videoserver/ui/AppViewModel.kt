package com.ipcamera.videoserver.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ipcamera.videoserver.archive.ArchiveManager
import com.ipcamera.videoserver.auth.SessionRegistry
import com.ipcamera.videoserver.service.CameraServerService
import com.ipcamera.videoserver.settings.AppSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class AppViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    val settings: AppSettings,
    private val sessionRegistry: SessionRegistry,
    private val archiveManager: ArchiveManager,
) : ViewModel() {

    val isRunning: StateFlow<Boolean> = CameraServerService.serverState
    val localIp: StateFlow<String> = CameraServerService.localIp

    val activeSessions get() = sessionRegistry.activeSessions()

    private val _archiveFiles = MutableStateFlow<List<File>>(emptyList())
    val archiveFiles: StateFlow<List<File>> = _archiveFiles

    fun refreshArchive() {
        _archiveFiles.value = archiveManager.listFiles()
    }

    fun startServer() {
        ContextCompat.startForegroundService(
            context,
            Intent(context, CameraServerService::class.java),
        )
    }

    fun stopServer() {
        context.stopService(Intent(context, CameraServerService::class.java))
    }

    fun deleteArchiveFile(file: File) {
        viewModelScope.launch {
            file.delete()
            refreshArchive()
        }
    }
}
