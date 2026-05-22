package com.krishanagarwal.mynoo.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

data class RecordingResult(
    val wavFile: File,
    val durationMs: Long,
    val peakAmplitude: Short,
)

@Singleton
class AudioRecorderService @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        private const val SAMPLE_RATE    = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT   = AudioFormat.ENCODING_PCM_16BIT
        private const val MAX_DURATION_MS = 30_000L
    }

    @Volatile private var stopRequested = false
    @Volatile private var sendRequested = false

    fun requestStop() { stopRequested = true }
    fun requestSend() { sendRequested = true }

    suspend fun record(): RecordingResult = withContext(Dispatchers.IO) {
        check(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) { "RECORD_AUDIO permission not granted" }

        stopRequested = false
        sendRequested = false

        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val recorder   = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize * 4,
        )

        val pcmOut  = ByteArrayOutputStream()
        val buffer  = ShortArray(bufferSize)
        var peak    = 0.toShort()
        val startMs = System.currentTimeMillis()

        recorder.startRecording()
        try {
            while (true) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read > 0) {
                    val byteArray = ByteArray(read * 2)
                    for (i in 0 until read) {
                        byteArray[i * 2]     = (buffer[i].toInt() and 0xFF).toByte()
                        byteArray[i * 2 + 1] = (buffer[i].toInt() shr 8 and 0xFF).toByte()
                        if (Math.abs(buffer[i].toInt()) > Math.abs(peak.toInt())) peak = buffer[i]
                    }
                    pcmOut.write(byteArray)
                }
                val elapsed = System.currentTimeMillis() - startMs
                if (stopRequested || sendRequested || elapsed >= MAX_DURATION_MS) break
                delay(10)
            }
        } finally {
            recorder.stop()
            recorder.release()
        }

        val duration = System.currentTimeMillis() - startMs
        val pcmBytes = pcmOut.toByteArray()
        val wavFile  = File(context.cacheDir, "recording_${System.currentTimeMillis()}.wav")
        writeWav(wavFile, pcmBytes, SAMPLE_RATE, 1, 16)
        RecordingResult(wavFile, duration, peak)
    }

    private fun writeWav(file: File, pcm: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int) {
        val byteRate   = sampleRate * channels * bitsPerSample / 8
        val blockAlign = (channels * bitsPerSample / 8).toShort()
        FileOutputStream(file).use { fos ->
            DataOutputStream(fos).use { dos ->
                val dataLen = pcm.size
                val totalLen = dataLen + 36
                // RIFF header
                dos.writeBytes("RIFF")
                dos.writeInt(Integer.reverseBytes(totalLen))
                dos.writeBytes("WAVE")
                // fmt chunk
                dos.writeBytes("fmt ")
                dos.writeInt(Integer.reverseBytes(16))
                dos.writeShort(java.lang.Short.reverseBytes(1.toShort()).toInt())   // PCM
                dos.writeShort(java.lang.Short.reverseBytes(channels.toShort()).toInt())
                dos.writeInt(Integer.reverseBytes(sampleRate))
                dos.writeInt(Integer.reverseBytes(byteRate))
                dos.writeShort(java.lang.Short.reverseBytes(blockAlign).toInt())
                dos.writeShort(java.lang.Short.reverseBytes(bitsPerSample.toShort()).toInt())
                // data chunk
                dos.writeBytes("data")
                dos.writeInt(Integer.reverseBytes(dataLen))
                dos.write(pcm)
            }
        }
    }
}
