package com.ipcamera.videoserver.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.net.wifi.WifiManager
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.ipcamera.videoserver.R
import com.ipcamera.videoserver.archive.ArchiveManager
import com.ipcamera.videoserver.auth.AuthManager
import com.ipcamera.videoserver.ftp.FtpServer
import com.ipcamera.videoserver.network.IpMonitor
import com.ipcamera.videoserver.server.WebServer
import com.ipcamera.videoserver.settings.AppSettings
import com.ipcamera.videoserver.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
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
            if (hash.isEmpty()) {
                hash = BCrypt.hashpw("admin", BCrypt.gensalt(10))
                settings.setAdminPasswordHash(hash)
            }
            val username = settings.adminUsername.first()
            authManager.setHashedCredentials(username, hash)

            val port = settings.serverPort.first()
            webServer.start(port)

            val ftpEnabled = settings.ftpEnabled.first()
            if (ftpEnabled) {
                val ftpPort = settings.ftpPort.first()
                ftpServer.start(ftpPort, archiveManager.archiveDir, username, "admin")
            }

            val pollInterval = settings.ipPollIntervalMinutes.first()
            IpMonitor.schedule(this@CameraServerService, pollInterval.toLong())

            _localIp.value = resolveLocalIp()
            _serverState.value = true
        }
    }

    override fun onDestroy() {
        webServer.stop()
        ftpServer.stop()
        IpMonitor.cancel(this)
        _serverState.value = false
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun resolveLocalIp(): String {
        val wifi = getSystemService(WIFI_SERVICE) as WifiManager
        return Formatter.formatIpAddress(wifi.connectionInfo.ipAddress)
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
