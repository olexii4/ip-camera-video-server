package com.ipcamera.videoserver.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val SAMPLE_RATE = 16_000
private const val BIT_RATE    = 32_000
private const val TIMEOUT_US  = 10_000L

@Singleton
class AudioStreamManager @Inject constructor() {

    /**
     * Captures the microphone, encodes to AAC-LC and yields ADTS-framed packets
     * to [onFrame] until the calling coroutine is cancelled.
     */
    suspend fun stream(onFrame: suspend (ByteArray) -> Unit) = withContext(Dispatchers.IO) {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(4096)

        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf * 2,
        )

        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, minBuf)
        }
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        audioRecord.startRecording()

        val pcmBuf = ByteArray(minBuf)
        val bufInfo = MediaCodec.BufferInfo()

        try {
            while (currentCoroutineContext().isActive) {
                // Feed PCM into encoder
                val inIdx = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inIdx >= 0) {
                    val inBuf = codec.getInputBuffer(inIdx)!!
                    inBuf.clear()
                    val read = audioRecord.read(pcmBuf, 0, pcmBuf.size)
                    if (read > 0) {
                        inBuf.put(pcmBuf, 0, read)
                        codec.queueInputBuffer(inIdx, 0, read, System.nanoTime() / 1000, 0)
                    } else {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, 0)
                    }
                }
                // Pull encoded AAC frames
                val outIdx = codec.dequeueOutputBuffer(bufInfo, TIMEOUT_US)
                if (outIdx >= 0) {
                    val outBuf = codec.getOutputBuffer(outIdx)!!
                    val aacData = ByteArray(bufInfo.size)
                    outBuf.get(aacData)
                    codec.releaseOutputBuffer(outIdx, false)

                    if (bufInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && aacData.isNotEmpty()) {
                        onFrame(wrapAdts(aacData))
                    }
                }
            }
        } finally {
            audioRecord.stop()
            audioRecord.release()
            codec.stop()
            codec.release()
        }
    }

    private fun wrapAdts(aac: ByteArray): ByteArray {
        val freqIdx = 8 // 16 000 Hz → index 8 in MPEG-4 sampling frequency table
        val total = aac.size + 7
        val h = ByteArray(7)
        h[0] = 0xFF.toByte()
        h[1] = 0xF9.toByte()                                      // MPEG-4, Layer=0, no CRC
        h[2] = ((1 shl 6) or (freqIdx shl 2) or (1 shr 2)).toByte() // LC profile, freq, mono
        h[3] = ((1 and 3) shl 6 or (total shr 11)).toByte()
        h[4] = (total shr 3 and 0xFF).toByte()
        h[5] = ((total and 7) shl 5 or 0x1F).toByte()
        h[6] = 0xFC.toByte()
        return h + aac
    }
}
