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
import com.ipcamera.videoserver.archive.SegmentRecorder
import com.ipcamera.videoserver.auth.AuthManager
import com.ipcamera.videoserver.camera.CameraSource
import com.ipcamera.videoserver.camera.CameraStreamManager
import com.ipcamera.videoserver.ftp.FtpServer
import com.ipcamera.videoserver.network.IpMonitor
import com.ipcamera.videoserver.server.WebServer
import com.ipcamera.videoserver.settings.AppSettings
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
    @Inject lateinit var webServer: WebServer
    @Inject lateinit var ftpServer: FtpServer
    @Inject lateinit var archiveManager: ArchiveManager
    @Inject lateinit var cameraStreamManager: CameraStreamManager

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
                val ftpPort = settings.ftpPort.first()
                ftpServer.start(ftpPort, archiveManager.archiveDir, username, plainPassword)
            }

            IpMonitor.schedule(this@CameraServerService, settings.ipPollIntervalMinutes.first().toLong())

            _localIp.value = resolveLocalIp()
            _serverState.value = true

            startArchiveIfEnabled(CameraSource.MAIN, settings.archiveEnabledMain.first())
            startArchiveIfEnabled(CameraSource.FRONT, settings.archiveEnabledFront.first())
        }
    }

    private fun startArchiveIfEnabled(source: CameraSource, enabled: Boolean) {
        if (!enabled) return
        archiveJobs[source]?.cancel()
        archiveJobs[source] = lifecycleScope.launch {
            while (true) {
                runCatching {
                    val outputFile = archiveManager.segmentFileName(source)
                    val recorder = SegmentRecorder(this@CameraServerService, source, outputFile)
                    val surface = recorder.prepare()
                    recorder.start()
                    delay(30 * 60 * 1000L)
                    recorder.stop()
                    archiveManager.enforceRotation()
                }
                delay(5_000L)
            }
        }
    }

    override fun onDestroy() {
        archiveJobs.values.forEach { it.cancel() }
        archiveJobs.clear()
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
