package com.ipcamera.videoserver.archive

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.view.Surface
import com.ipcamera.videoserver.camera.CameraSource
import java.io.File

class SegmentRecorder(
    private val context: Context,
    val source: CameraSource,
    private val outputFile: File,
) {
    private var recorder: MediaRecorder? = null
    private var started = false

    fun prepare(): Surface {
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        r.setVideoEncodingBitRate(2_000_000)
        r.setVideoFrameRate(25)
        r.setVideoSize(1280, 720)
        r.setOrientationHint(90) // portrait phones — rotate so macOS/iOS plays correctly
        r.setOutputFile(outputFile.absolutePath)
        r.prepare()
        recorder = r
        return r.surface
    }

    fun start() {
        recorder?.start()
        started = true
    }

    fun stop() {
        val r = recorder ?: return
        recorder = null
        if (started) {
            // Only call stop() if recording actually started; otherwise file would be corrupt
            try { r.stop() } catch (_: Exception) { outputFile.delete() }
        }
        try { r.release() } catch (_: Exception) {}
    }
}
