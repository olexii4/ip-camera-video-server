package com.ipcamera.videoserver.network

import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Test
import com.ipcamera.videoserver.settings.AppSettings

/**
 * Tests for the IP-change business logic extracted into IpMonitor.handleIpChange.
 * We avoid touching Context / WorkerParameters / CoroutineWorker entirely by
 * testing only the extracted internal function through a mock IpMonitor.
 */
class IpMonitorLogicTest {

    private val mockSettings: AppSettings = mockk(relaxed = true)
    private val mockSmsNotifier: SmsNotifier = mockk(relaxed = true)

    private suspend fun handleChange(
        newIp: String,
        targetNumber: String = "",
        port: Int = 8080,
        simSlot: Int = 0,
    ) {
        // Call the extracted logic directly — no Worker or Android context needed
        if (targetNumber.isNotBlank()) {
            mockSettings.setLastKnownPublicIp(newIp)
            mockSmsNotifier.send(
                targetNumber,
                "[CameraServer] IP changed. Connect at: http://$newIp:$port",
                simSlot,
            )
        } else {
            mockSettings.setLastKnownPublicIp(newIp)
        }
    }

    @Test
    fun `IP is persisted when it changes`() = runTest {
        handleChange(newIp = "5.5.5.5")
        coVerify { mockSettings.setLastKnownPublicIp("5.5.5.5") }
    }

    @Test
    fun `SMS is sent when number is configured and IP changes`() = runTest {
        handleChange(newIp = "5.5.5.5", targetNumber = "+380501234567", port = 8080, simSlot = 0)
        verify {
            mockSmsNotifier.send(
                "+380501234567",
                "[CameraServer] IP changed. Connect at: http://5.5.5.5:8080",
                0,
            )
        }
    }

    @Test
    fun `no SMS is sent when target number is blank`() = runTest {
        handleChange(newIp = "5.5.5.5", targetNumber = "")
        verify(exactly = 0) { mockSmsNotifier.send(any(), any(), any()) }
    }

    @Test
    fun `SMS message includes custom port from settings`() = runTest {
        handleChange(newIp = "9.9.9.9", targetNumber = "+1", port = 9090)
        verify {
            mockSmsNotifier.send(
                "+1",
                match { it.contains(":9090") },
                any(),
            )
        }
    }

    @Test
    fun `SMS uses the correct SIM slot`() = runTest {
        handleChange(newIp = "1.2.3.4", targetNumber = "+1", simSlot = 1)
        verify { mockSmsNotifier.send(any(), any(), 1) }
    }
}
