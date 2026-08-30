package com.ipcamera.videoserver.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.ipcamera.videoserver.R
import com.ipcamera.videoserver.archive.ArchiveManager
import com.ipcamera.videoserver.archive.AudioSegmentRecorder
import com.ipcamera.videoserver.archive.SegmentRecorder
import com.ipcamera.videoserver.auth.AuthManager
import com.ipcamera.videoserver.camera.CameraSource
import com.ipcamera.videoserver.camera.CameraStreamManager
import com.ipcamera.videoserver.ftp.FtpServer
import com.ipcamera.videoserver.network.IpMonitor
import com.ipcamera.videoserver.server.WebServer
import com.ipcamera.videoserver.settings.AppSettings
import com.ipcamera.videoserver.tls.TlsCertManager
import com.ipcamera.videoserver.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.mindrot.jbcrypt.BCrypt
import java.util.UUID
import javax.inject.Inject

@AndroidEntryPoint
class CameraServerService : LifecycleService() {

    @Inject lateinit var settings: AppSettings
    @Inject lateinit var authManager: AuthManager
    @Inject lateinit var sessionRegistry: com.ipcamera.videoserver.auth.SessionRegistry
    @Inject lateinit var webServer: WebServer
    @Inject lateinit var ftpServer: FtpServer
    @Inject lateinit var archiveManager: ArchiveManager
    @Inject lateinit var cameraStreamManager: CameraStreamManager
    @Inject lateinit var tlsCertManager: TlsCertManager

    private val archiveJobs = mutableMapOf<CameraSource, Job>()

    companion object {
        private val _serverState = MutableStateFlow(false)
        val serverState: StateFlow<Boolean> = _serverState

        private val _localIp = MutableStateFlow("")
        val localIp: StateFlow<String> = _localIp

        private const val NOTIFICATION_ID = 1
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())

        lifecycleScope.launch {
            val jwtSecret = settings.jwtSecret.first().ifEmpty {
                UUID.randomUUID().toString().also { settings.setJwtSecret(it) }
            }
            authManager.configure(jwtSecret)
            authManager.authRequired = settings.authEnabled.first()

            var hash = settings.adminPasswordHash.first()
            val plainPassword = if (hash.isEmpty()) {
                val initial = settings.adminPasswordPlain.first().ifEmpty { "admin" }
                hash = BCrypt.hashpw(initial, BCrypt.gensalt(10))
                settings.setAdminPasswordHash(hash)
                settings.setAdminPasswordPlain(initial)
                initial
            } else {
                settings.adminPasswordPlain.first().ifEmpty { "admin" }
            }
            val username = settings.adminUsername.first()
            authManager.setHashedCredentials(username, hash)

            val port = settings.serverPort.first()
            webServer.start(port)

            if (settings.ftpEnabled.first()) {
                val port = settings.ftpPort.first()
                if (settings.ftpsEnabled.first()) {
                    val sslFactory = runCatching { tlsCertManager.serverSocketFactory() }.getOrNull()
                    if (sslFactory != null) {
                        ftpServer.startSecure(port, archiveManager.archiveDir, username, plainPassword, sslFactory)
                    }
                } else {
                    ftpServer.start(port, archiveManager.archiveDir, username, plainPassword)
                }
            }

            IpMonitor.schedule(this@CameraServerService, settings.ipPollIntervalMinutes.first().toLong())

            _localIp.value = resolveLocalIp()
            _serverState.value = true

            val audioEnabled = settings.archiveAudioEnabled.first()
            // Migrate: if new unified setting is off but legacy per-camera setting was on, enable it
            val archiveEnabled = settings.archiveEnabled.first().let { enabled ->
                if (!enabled && (settings.archiveEnabledMain.first() || settings.archiveEnabledFront.first())) {
                    settings.setArchiveEnabled(true)
                    true
                } else enabled
            }
            if (archiveEnabled) startActiveCameraArchive(audioEnabled)
            if (settings.archiveAudioOnlyEnabled.first()) startAudioOnlyArchive()
        }
    }

    private fun startActiveCameraArchive(audioEnabled: Boolean) {
        archiveJobs[CameraSource.MAIN]?.cancel()
        archiveJobs[CameraSource.MAIN] = lifecycleScope.launch {
            while (true) {
                // Find whichever camera is currently being streamed
                val source = listOf(CameraSource.MAIN, CameraSource.FRONT)
                    .firstOrNull { cameraStreamManager.isStreaming(it) }

                if (source == null) {
                    delay(3_000L)
                    continue
                }

                var recorder: SegmentRecorder? = null
                var currentFile: java.io.File? = null
                runCatching {
                    val outputFile = archiveManager.segmentFileName(source)
                    currentFile = outputFile
                    // Record from the JPEG SharedFlow directly — no Camera2 surface integration needed
                    recorder = SegmentRecorder(source, outputFile, audioEnabled)
                    archiveManager.markSaving(outputFile.name)
                    recorder!!.startFrom(cameraStreamManager.getStreamExclusive(source))
                    var elapsed = 0L
                    while (elapsed < 15 * 60 * 1000L && cameraStreamManager.isStreaming(source)) {
                        delay(1_000L)
                        elapsed += 1_000L
                        if (archiveManager.consumeFinalize()) break
                    }
                    recorder!!.stop()
                    archiveManager.markDone(outputFile.name)
                    archiveManager.enforceRotation()
                }.onFailure {
                    runCatching { recorder?.stop() }
                    currentFile?.let { archiveManager.markDone(it.name) }
                }
                delay(2_000L)
            }
        }
    }

    private fun startAudioOnlyArchive() {
        archiveJobs[CameraSource.USB]?.cancel() // reuse USB slot for audio-only job
        archiveJobs[CameraSource.USB] = lifecycleScope.launch {
            while (true) {
                var recorder: AudioSegmentRecorder? = null
                var currentFile: java.io.File? = null
                runCatching {
                    val outputFile = archiveManager.audioSegmentFileName()
                    currentFile = outputFile
                    recorder = AudioSegmentRecorder(this@CameraServerService, outputFile)
                    archiveManager.markSaving(outputFile.name)
                    recorder!!.start()
                    delay(15 * 60 * 1000L)
                    recorder!!.stop()
                    archiveManager.markDone(outputFile.name)
                    archiveManager.enforceRotation()
                }.onFailure {
                    runCatching { recorder?.stop() }
                    currentFile?.let { archiveManager.markDone(it.name) }
                }
                delay(2_000L)
            }
        }
    }

    override fun onDestroy() {
        archiveJobs.values.forEach { it.cancel() }
        archiveJobs.clear()
        sessionRegistry.clearAll()
        webServer.stop()
        ftpServer.stop()
        cameraStreamManager.stopAll()
        IpMonitor.cancel(this)
        _serverState.value = false
        super.onDestroy()
    }

    private fun resolveLocalIp(): String {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return ""
        val props: LinkProperties = cm.getLinkProperties(network) ?: return ""
        return props.linkAddresses
            .map { it.address }
            .firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains(':') == false }
            ?.hostAddress ?: ""
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, getString(R.string.notification_channel_id))
            .setContentTitle(getString(R.string.notification_title))
            .setContentText("Tap to open")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }
}
