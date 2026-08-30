package com.ipcamera.videoserver.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

const val AUDIO_SAMPLE_RATE = 16_000

@Singleton
class AudioStreamManager @Inject constructor() {

    /**
     * Captures the microphone and yields raw 16-bit PCM chunks to [onChunk].
     * Small chunks (20ms each) keep end-to-end latency well under 100ms when
     * the browser schedules them with Web Audio API.
     */
    suspend fun stream(onChunk: suspend (ByteArray) -> Unit) = withContext(Dispatchers.IO) {
        // 20ms worth of samples at 16kHz mono 16-bit = 640 bytes
        val chunkSamples = AUDIO_SAMPLE_RATE * 20 / 1000
        val chunkBytes = chunkSamples * 2 // 16-bit = 2 bytes/sample
        val minBuf = AudioRecord.getMinBufferSize(
            AUDIO_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(chunkBytes * 4)

        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            AUDIO_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf,
        )
        audioRecord.startRecording()

        val buf = ByteArray(chunkBytes)
        try {
            while (currentCoroutineContext().isActive) {
                var offset = 0
                while (offset < chunkBytes) {
                    val read = audioRecord.read(buf, offset, chunkBytes - offset)
                    if (read > 0) offset += read else break
                }
                if (offset == chunkBytes) onChunk(buf.copyOf())
            }
        } finally {
            audioRecord.stop()
            audioRecord.release()
        }
    }
}
