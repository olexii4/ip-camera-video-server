package com.ipcamera.videoserver.network

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.ipcamera.videoserver.settings.AppSettings
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.net.URL
import java.util.concurrent.TimeUnit

@HiltWorker
class IpMonitor @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val settings: AppSettings,
    private val smsNotifier: SmsNotifier,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val currentIp = fetchPublicIp() ?: return Result.retry()
        val lastIp = settings.lastKnownPublicIp.first()

        if (currentIp != lastIp) {
            settings.setLastKnownPublicIp(currentIp)
            val targetNumber = settings.smsTargetNumber.first()
            val port = settings.serverPort.first()
            val simSlot = settings.smsSimSlot.first()
            if (targetNumber.isNotBlank()) {
                smsNotifier.send(
                    targetNumber,
                    "[CameraServer] IP changed. Connect at: http://$currentIp:$port",
                    simSlot,
                )
            }
        }
        return Result.success()
    }

    private fun fetchPublicIp(): String? =
        runCatching {
            val json = URL("https://api64.ipify.org?format=json").readText(Charsets.UTF_8)
            JSONObject(json).getString("ip")
        }.getOrNull()

    companion object {
        private const val WORK_NAME = "ip_monitor"

        fun schedule(context: Context, intervalMinutes: Long) {
            val interval = intervalMinutes.coerceAtLeast(15) // WorkManager minimum is 15 min
            val request = PeriodicWorkRequestBuilder<IpMonitor>(interval, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        fun cancel(context: Context) = WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}
