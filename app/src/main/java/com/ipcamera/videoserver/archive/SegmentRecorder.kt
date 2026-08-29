package com.ipcamera.videoserver.archive

import android.content.Context
import android.media.MediaRecorder
import android.view.Surface
import com.ipcamera.videoserver.camera.CameraSource
import java.io.File

class SegmentRecorder(
    private val context: Context,
    val source: CameraSource,
    private val outputFile: File,
) {
    private var recorder: MediaRecorder? = null

    fun prepare(): Surface {
        val r = MediaRecorder(context)
        r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        r.setVideoEncodingBitRate(2_000_000)
        r.setVideoFrameRate(30)
        r.setVideoSize(1280, 720)
        r.setOutputFile(outputFile.absolutePath)
        r.prepare()
        recorder = r
        return r.surface
    }

    fun start() { recorder?.start() }

    fun stop() {
        try { recorder?.stop() } catch (_: Exception) {}
        recorder?.release()
        recorder = null
    }
}
