package com.ipcamera.videoserver.archive

import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.Build
import com.ipcamera.videoserver.camera.CameraSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import java.io.File
import java.nio.ByteBuffer

private const val MIME = "video/avc"  // H.264
private const val WIDTH = 640
private const val HEIGHT = 480
private const val FRAME_RATE = 15
private const val BIT_RATE = 800_000
private const val I_FRAME_INTERVAL = 2 // seconds
private const val TIMEOUT_US = 10_000L

/**
 * Records JPEG frames from a SharedFlow into an MP4 file using MediaCodec + MediaMuxer.
 * Does NOT require a Camera2 surface — works with the streaming JPEG flow directly.
 */
class SegmentRecorder(
    val source: CameraSource,
    private val outputFile: File,
    private val audioEnabled: Boolean = false,
) {
    private var muxer: MediaMuxer? = null
    private var codec: MediaCodec? = null
    private var videoTrack = -1
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null
    private var started = false

    fun startFrom(frames: SharedFlow<ByteArray>) {
        val format = MediaFormat.createVideoFormat(MIME, WIDTH, HEIGHT).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
        }
        val c = MediaCodec.createEncoderByType(MIME)
        c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = c.createInputSurface()
        c.start()

        val mx = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        codec = c
        muxer = mx
        started = true

        val bufInfo = MediaCodec.BufferInfo()
        var muxerStarted = false
        var presentationUs = 0L

        job = scope.launch {
            // Draw each JPEG frame onto the encoder's input surface
            frames.collect { jpeg ->
                val bmp = runCatching {
                    BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                }.getOrNull() ?: return@collect

                val canvas = inputSurface.lockHardwareCanvas() ?: inputSurface.lockCanvas(null)
                canvas.drawBitmap(bmp, null,
                    android.graphics.RectF(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat()), null)
                inputSurface.unlockCanvasAndPost(canvas)
                bmp.recycle()
                presentationUs += 1_000_000L / FRAME_RATE

                // Drain encoder output
                while (true) {
                    val idx = c.dequeueOutputBuffer(bufInfo, TIMEOUT_US)
                    if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        videoTrack = mx.addTrack(c.outputFormat)
                        mx.start()
                        muxerStarted = true
                        continue
                    }
                    if (idx >= 0) {
                        val buf: ByteBuffer = c.getOutputBuffer(idx)!!
                        if (muxerStarted && bufInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            bufInfo.presentationTimeUs = presentationUs
                            mx.writeSampleData(videoTrack, buf, bufInfo)
                        }
                        c.releaseOutputBuffer(idx, false)
                        if (bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        scope.cancel()
        runCatching { codec?.signalEndOfInputStream() }
        runCatching { codec?.stop(); codec?.release() }
        runCatching { muxer?.stop(); muxer?.release() }
        codec = null; muxer = null
        if (outputFile.length() < 1024L) outputFile.delete() // delete empty/truncated files
    }
}

