package com.krishanagarwal.mynoo.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Base64
import com.krishanagarwal.mynoo.BuildConfig
import com.krishanagarwal.mynoo.data.api.GeminiApi
import com.krishanagarwal.mynoo.data.api.GeminiContent
import com.krishanagarwal.mynoo.data.api.GeminiGenConfig
import com.krishanagarwal.mynoo.data.api.GeminiPart
import com.krishanagarwal.mynoo.data.api.GeminiPrebuiltVoice
import com.krishanagarwal.mynoo.data.api.GeminiRequest
import com.krishanagarwal.mynoo.data.api.GeminiSpeechConfig
import com.krishanagarwal.mynoo.data.api.GeminiVoiceConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TtsService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val geminiApi: GeminiApi,
) {
    private var currentTrack: AudioTrack? = null

    suspend fun speak(text: String) = withContext(Dispatchers.IO) {
        stop()
        val clean = cleanForSpeech(text)
        if (clean.isBlank()) return@withContext

        val response = geminiApi.generateSpeech(
            apiKey  = BuildConfig.GEMINI_API_KEY,
            request = GeminiRequest(
                contents = listOf(GeminiContent(parts = listOf(GeminiPart(text = clean)))),
                generationConfig = GeminiGenConfig(
                    responseModalities = listOf("AUDIO"),
                    speechConfig = GeminiSpeechConfig(
                        voiceConfig = GeminiVoiceConfig(
                            prebuiltVoiceConfig = GeminiPrebuiltVoice(voiceName = "Aoede"),
                        )
                    ),
                ),
            ),
        )

        val inlineData = response.candidates
            ?.firstOrNull()?.content?.parts?.firstOrNull()?.inlineData
            ?: return@withContext

        val pcmBytes  = Base64.decode(inlineData.data, Base64.DEFAULT)
        val sampleRate = parseSampleRate(inlineData.mimeType)
        playPcm(pcmBytes, sampleRate)
    }

    fun stop() {
        currentTrack?.let {
            try { it.stop(); it.release() } catch (_: Exception) {}
        }
        currentTrack = null
    }

    private fun playPcm(pcm: ByteArray, sampleRate: Int) {
        val bufSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        currentTrack = track
        track.play()
        val chunkSize = 4096
        var offset = 0
        while (offset < pcm.size) {
            val end = minOf(offset + chunkSize, pcm.size)
            track.write(pcm, offset, end - offset)
            offset = end
            if (currentTrack == null) break  // stop() was called
        }
        if (currentTrack != null) {
            track.stop()
            track.release()
            if (currentTrack == track) currentTrack = null
        }
    }

    private fun parseSampleRate(mimeType: String): Int {
        val rateParam = mimeType.split(";")
            .map { it.trim() }
            .firstOrNull { it.startsWith("rate=") }
        return rateParam?.removePrefix("rate=")?.toIntOrNull() ?: 24000
    }

    private fun cleanForSpeech(text: String) = text
        .replace(Regex("""\*\*(.*?)\*\*"""), "$1")
        .replace(Regex("""\*(.*?)\*"""), "$1")
        .replace(Regex("""`(.*?)`"""), "$1")
        .replace(Regex("""\[.*?]\(.*?\)"""), "")
        .replace(Regex("""#{1,6} """), "")
        .replace(Regex("""\n+"""), " ")
        .trim()
}
