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
    private val audioEnabled: Boolean = false,
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
        if (audioEnabled) {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
        }
        r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        r.setVideoEncodingBitRate(2_000_000)
        r.setVideoFrameRate(25)
        r.setVideoSize(1280, 720)
        if (audioEnabled) {
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(128_000)
            r.setAudioSamplingRate(44_100)
        }
        r.setOrientationHint(90)
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
            try { r.stop() } catch (_: Exception) { outputFile.delete() }
        }
        try { r.release() } catch (_: Exception) {}
    }
}
