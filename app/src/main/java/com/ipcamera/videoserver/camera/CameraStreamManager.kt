package com.ipcamera.videoserver.camera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CameraStreamManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val activeStreams = ConcurrentHashMap<CameraSource, SharedFlow<ByteArray>>()
    private val handlerThread = HandlerThread("CameraStream").also { it.start() }
    private val handler = Handler(handlerThread.looper)
    private val scope = CoroutineScope(SupervisorJob())

    fun getStream(source: CameraSource): SharedFlow<ByteArray> =
        activeStreams.getOrPut(source) { buildStream(source) }

    private fun buildStream(source: CameraSource): SharedFlow<ByteArray> =
        callbackFlow<ByteArray> {
            val cameraId = findCameraId(source) ?: run { close(); return@callbackFlow }
            val imageReader = ImageReader.newInstance(1280, 720, ImageFormat.JPEG, 2)
            imageReader.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    trySend(bytes)
                } finally {
                    image.close()
                }
            }, handler)

            var captureSession: CameraCaptureSession? = null
            var cameraDevice: CameraDevice? = null

            val deviceCallback = object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    @Suppress("DEPRECATION")
                    camera.createCaptureSession(
                        listOf(imageReader.surface),
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                captureSession = session
                                val request = camera
                                    .createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                                    .apply { addTarget(imageReader.surface) }
                                    .build()
                                session.setRepeatingRequest(request, null, handler)
                            }
                            override fun onConfigureFailed(session: CameraCaptureSession) { close() }
                        },
                        handler,
                    )
                }
                override fun onDisconnected(camera: CameraDevice) { camera.close(); close() }
                override fun onError(camera: CameraDevice, error: Int) { camera.close(); close() }
            }

            try {
                cameraManager.openCamera(cameraId, deviceCallback, handler)
            } catch (e: SecurityException) {
                close(e)
            }

            awaitClose {
                captureSession?.close()
                cameraDevice?.close()
                imageReader.close()
                activeStreams.remove(source)
            }
        }.shareIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
            replay = 1,
        )

    private fun findCameraId(source: CameraSource): String? {
        val facing = when (source) {
            CameraSource.FRONT -> CameraCharacteristics.LENS_FACING_FRONT
            CameraSource.MAIN -> CameraCharacteristics.LENS_FACING_BACK
            CameraSource.USB -> return null
        }
        return cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == facing
        }
    }

    fun availableSources(): List<CameraSource> =
        CameraSource.entries.filter { it != CameraSource.USB && findCameraId(it) != null }

    fun stopAll() {
        handlerThread.quitSafely()
    }
}
