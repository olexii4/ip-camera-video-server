package com.ipcamera.videoserver.archive

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

class AudioSegmentRecorder(
    private val context: Context,
    private val outputFile: File,
) {
    private var recorder: MediaRecorder? = null
    private var started = false

    fun start() {
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioEncodingBitRate(128_000)
        r.setAudioSamplingRate(44_100)
        r.setOutputFile(outputFile.absolutePath)
        r.prepare()
        r.start()
        recorder = r
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
