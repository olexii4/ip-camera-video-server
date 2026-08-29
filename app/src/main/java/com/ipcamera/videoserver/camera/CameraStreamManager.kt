package com.ipcamera.videoserver.camera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
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
    private val extraSurfaces = ConcurrentHashMap<CameraSource, Surface>()

    /** Returns true while this source's camera is open and has at least one subscriber. */
    // True while camera is open for streaming OR while the recorder surface is attached
    fun isStreaming(source: CameraSource): Boolean =
        activeStreams.containsKey(source) || extraSurfaces.containsKey(source)

    /** Waits until all cameras for OTHER sources are closed, then returns the stream.
     *  Prevents Camera2 "max cameras in use" errors when switching between cameras. */
    suspend fun getStreamExclusive(source: CameraSource): SharedFlow<ByteArray> {
        var waited = 0
        while (waited < 3_000) {
            if (activeStreams.keys.none { it != source }) break
            delay(150)
            waited += 150
        }
        return activeStreams.getOrPut(source) { buildStream(source) }
    }

    fun setRecordingSurface(source: CameraSource, surface: Surface?) {
        if (surface != null) extraSurfaces[source] = surface
        else extraSurfaces.remove(source)
        activeStreams.remove(source)
    }

    private fun buildStream(source: CameraSource): SharedFlow<ByteArray> =
        callbackFlow<ByteArray> {
            val cameraId = findCameraId(source) ?: run { close(); return@callbackFlow }
            // Small resolution + single buffer = minimum end-to-end latency for live preview
            val imageReader = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 1)
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

            val surfaces = buildList {
                add(imageReader.surface)
                extraSurfaces[source]?.let { add(it) }
            }

            var captureSession: CameraCaptureSession? = null
            var cameraDevice: CameraDevice? = null

            val deviceCallback = object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    @Suppress("DEPRECATION")
                    camera.createCaptureSession(
                        surfaces,
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                captureSession = session
                                val template = if (extraSurfaces.containsKey(source))
                                    CameraDevice.TEMPLATE_RECORD
                                else
                                    CameraDevice.TEMPLATE_PREVIEW
                                val request = camera
                                    .createCaptureRequest(template)
                                    .apply {
                                        surfaces.forEach { addTarget(it) }
                                        set(CaptureRequest.JPEG_QUALITY, 70.toByte())
                                    }
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
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 200),
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
