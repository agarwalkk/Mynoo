package com.krishanagarwal.mynoo.service

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Base64
import android.util.Log
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TtsService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val geminiApi: GeminiApi,
    private val client: OkHttpClient,
) {
    private var mediaPlayer: MediaPlayer? = null

    suspend fun speak(text: String, lang: String = "en", provider: String? = null, model: String? = null) = withContext(Dispatchers.IO) {
        stop()
        val clean = cleanForSpeech(text)
        if (clean.isBlank()) return@withContext

        try {
            val isMp3 = provider == "elevenlabs"
            val audioBytes = when (provider) {
                "elevenlabs" -> callElevenLabsTts(clean, lang, model ?: "eleven_v3")
                "sarvam"     -> callSarvamTts(clean, lang, model ?: "bulbul:v3")
                else         -> callGeminiTts(clean, lang, model ?: "gemini-3.1-flash-tts-preview")
            }
            playAudioBytes(audioBytes, isMp3)
        } catch (e: Exception) {
            Log.e("TtsService", "TTS synthesis failed for provider: $provider", e)
        }
    }

    fun stop() {
        mediaPlayer?.let {
            try {
                if (it.isPlaying) {
                    it.stop()
                }
                it.release()
            } catch (_: Exception) {}
        }
        mediaPlayer = null
    }

    private fun playAudioBytes(bytes: ByteArray, isMp3: Boolean) {
        try {
            val tempFile = File.createTempFile("tts_", if (isMp3) ".mp3" else ".wav", context.cacheDir)
            tempFile.writeBytes(bytes)

            val mp = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(tempFile.absolutePath)
                setOnPreparedListener { it.start() }
                setOnCompletionListener {
                    it.release()
                    tempFile.delete()
                    if (mediaPlayer == it) mediaPlayer = null
                }
                setOnErrorListener { mpError, _, _ ->
                    tempFile.delete()
                    if (mediaPlayer == mpError) mediaPlayer = null
                    true
                }
            }
            mediaPlayer = mp
            mp.prepareAsync()
        } catch (e: Exception) {
            Log.e("TtsService", "Error playing audio bytes", e)
        }
    }

    private fun voiceId(subject: String) = when (subject.lowercase()) {
        "english", "en" -> "EXAVITQu4vr4xnSDxMaL"  // Bella — warm female
        "punjabi", "pa" -> "vT0wMbLG5dssaBsksrb6"  // custom Punjabi voice
        else            -> "9FTUWXd0yHJL1ZiZ71RK"  // Matilda — multilingual (Hindi)
    }

    private suspend fun callElevenLabsTts(text: String, lang: String, modelId: String = "eleven_v3"): ByteArray =
        withContext(Dispatchers.IO) {
            val body = JSONObject().apply {
                put("text", text)
                put("model_id", modelId)
                put("voice_settings", JSONObject()
                    .put("stability", 0.5)
                    .put("similarity_boost", 0.75))
            }.toString()
            val req = Request.Builder()
                .url("https://api.elevenlabs.io/v1/text-to-speech/${voiceId(lang)}")
                .addHeader("xi-api-key", BuildConfig.ELEVENLABS_API_KEY)
                .addHeader("Content-Type", "application/json")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) throw Exception("ElevenLabs TTS error ${resp.code}: ${resp.message}")
            resp.body!!.bytes()
        }

    private suspend fun callSarvamTts(text: String, lang: String, modelId: String = "bulbul:v3"): ByteArray =
        withContext(Dispatchers.IO) {
            val targetLang = when (lang.lowercase()) {
                "hindi", "hi" -> "hi-IN"
                "punjabi", "pa" -> "pa-IN"
                else -> "hi-IN"
            }
            // Note: bulbul:v3 does NOT support pitch or loudness — omit them
            val body = JSONObject().apply {
                put("inputs", JSONArray().put(text))
                put("target_language_code", targetLang)
                put("speaker", "kavya")   // valid for hi-IN and pa-IN on bulbul:v3
                put("pace", 0.9)
                put("speech_sample_rate", 22050)
                put("enable_preprocessing", true)
                put("model", modelId)
            }.toString()
            val req = Request.Builder()
                .url("https://api.sarvam.ai/text-to-speech")
                .addHeader("api-subscription-key", BuildConfig.SARVAM_API_KEY)
                .addHeader("Content-Type", "application/json")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) throw Exception("Sarvam TTS error ${resp.code}: ${resp.message}")
            val respBody = resp.body?.string() ?: ""
            val json = JSONObject(respBody)
            val audios = json.optJSONArray("audios")
            if (audios == null || audios.length() == 0) throw Exception("Sarvam TTS empty response")
            Base64.decode(audios.getString(0), Base64.DEFAULT)
        }

    private suspend fun callGeminiTts(text: String, lang: String, modelId: String = "gemini-3.1-flash-tts-preview"): ByteArray =
        withContext(Dispatchers.IO) {
            val response = geminiApi.generateSpeech(
                model   = modelId,
                apiKey  = BuildConfig.GEMINI_API_KEY,
                request = GeminiRequest(
                    contents = listOf(GeminiContent(parts = listOf(GeminiPart(text = text)))),
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
                ?: throw Exception("Gemini TTS empty response")

            val pcmBytes  = Base64.decode(inlineData.data, Base64.DEFAULT)
            val sampleRate = parseSampleRate(inlineData.mimeType)
            pcmToWav(pcmBytes, sampleRate)
        }

    private fun pcmToWav(pcmBytes: ByteArray, sampleRate: Int): ByteArray {
        val header = ByteArray(44)
        val totalDataLen = pcmBytes.size + 36
        val byteRate = sampleRate * 2

        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()

        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = ((totalDataLen shr 8) and 0xff).toByte()
        header[6] = ((totalDataLen shr 16) and 0xff).toByte()
        header[7] = ((totalDataLen shr 24) and 0xff).toByte()

        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()

        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()

        header[16] = 16
        header[17] = 0
        header[18] = 0
        header[19] = 0

        header[20] = 1
        header[21] = 0

        header[22] = 1
        header[23] = 0

        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()

        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()

        header[32] = 2
        header[33] = 0

        header[34] = 16
        header[35] = 0

        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()

        header[40] = (pcmBytes.size and 0xff).toByte()
        header[41] = ((pcmBytes.size shr 8) and 0xff).toByte()
        header[42] = ((pcmBytes.size shr 16) and 0xff).toByte()
        header[43] = ((pcmBytes.size shr 24) and 0xff).toByte()

        return header + pcmBytes
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
