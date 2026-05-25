package com.krishanagarwal.mynoo.data.repository

import android.util.Base64
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.krishanagarwal.mynoo.BuildConfig
import com.krishanagarwal.mynoo.data.api.*
import com.krishanagarwal.mynoo.data.store.MynooPrefsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

data class VocabEntry(
    val word:        String,
    val speakText:   String,
    val meaning:     String,
    val translation: String,
    val audioPath:   String,
    val timingsPath: String?  = null,
    val llmInputJson: String? = null,
    val llmOutputJson: String? = null,
    val ttsModelUsed: String? = null,
    val ttsTokenUsage: Int? = null,
)

data class TtsResult(
    val audio:   ByteArray,
    val model:   String,
    val tokens:  Int,
    val timings: List<WordTiming>? = null,
)

@Singleton
class VocabRepository @Inject constructor(
    private val client:              OkHttpClient,
    private val prefsStore:          MynooPrefsStore,
    private val db:                  FirebaseFirestore,
    private val placementRepo:       PlacementRepository,
    private val geminiApi:           GeminiApi,
    private val openAiApi:           OpenAiApi,
    private val xaiApi:              XaiApi,
    private val sarvamChatApi:       SarvamChatApi,
    private val globalSettingsRepo:  GlobalSettingsRepository,
) {
    private val bucket  = "aaravtutor-1e880.firebasestorage.app"
    private val project = "aaravtutor-1e880"

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun storageUrl(path: String) =
        "https://firebasestorage.googleapis.com/v0/b/$bucket/o/${enc(path)}?alt=media"

    private fun firestoreDocUrl(word: String) =
        "https://firestore.googleapis.com/v1/projects/$project/databases/(default)/documents/vocab/${enc(word)}"

    // ── Firestore REST helpers ───────────────────────────────────────────────

    private suspend fun getJson(url: String): JSONObject? = withContext(Dispatchers.IO) {
        val resp = client.newCall(Request.Builder().url(url).build()).execute()
        if (!resp.isSuccessful) return@withContext null
        runCatching { JSONObject(resp.body!!.string()) }.getOrNull()
    }

    private fun fsStr(f: JSONObject, k: String) =
        f.optJSONObject(k)?.optString("stringValue") ?: ""

    private fun fsBool(f: JSONObject, k: String) =
        f.optJSONObject(k)?.optBoolean("booleanValue") ?: false

    private fun fsInt(f: JSONObject, k: String): Int? {
        val s = f.optJSONObject(k)?.optString("integerValue") ?: return null
        return s.toIntOrNull()
    }

    // ── OpenAI embedding (multi-sense cosine matching) ───────────────────────

    private suspend fun embed(text: String): List<Double> = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("model", "text-embedding-3-small")
            .put("input", text)
            .toString()
        val req = Request.Builder()
            .url("https://api.openai.com/v1/embeddings")
            .addHeader("Authorization", "Bearer ${BuildConfig.OPENAI_API_KEY}")
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        val json = JSONObject(client.newCall(req).execute().body!!.string())
        val arr  = json.getJSONArray("data").getJSONObject(0).getJSONArray("embedding")
        (0 until arr.length()).map { arr.getDouble(it) }
    }

    private fun cosine(a: List<Double>, b: List<Double>): Double {
        var dot = 0.0; var mA = 0.0; var mB = 0.0
        a.indices.forEach { i -> dot += a[i] * b[i]; mA += a[i] * a[i]; mB += b[i] * b[i] }
        val m = sqrt(mA) * sqrt(mB)
        return if (m == 0.0) 0.0 else dot / m
    }

    // ── Senses subcollection ─────────────────────────────────────────────────

    private data class VocabSense(
        val speakText:    String,
        val meaning:      String,
        val translation:  String,
        val audioPath:    String,
        val vector:       List<Double>,
        val timingsPath:  String?  = null,
        val llmInputJson: String? = null,
        val llmOutputJson: String? = null,
        val ttsModelUsed: String? = null,
        val ttsTokenUsage: Int? = null,
    )

    private suspend fun readSenses(word: String): List<VocabSense> = withContext(Dispatchers.IO) {
        val url  = "https://firestore.googleapis.com/v1/projects/$project/databases/(default)/documents/vocab/${enc(word)}/senses"
        val json = getJson(url) ?: return@withContext emptyList()
        val docs = json.optJSONArray("documents") ?: return@withContext emptyList()
        (0 until docs.length()).mapNotNull { i ->
            val f  = docs.getJSONObject(i).optJSONObject("fields") ?: return@mapNotNull null
            val va = f.optJSONObject("vector")?.optJSONObject("arrayValue")?.optJSONArray("values")
            val vec = if (va != null) (0 until va.length()).map { j ->
                val v = va.getJSONObject(j)
                v.optDouble("doubleValue").takeIf { !it.isNaN() } ?: v.optDouble("integerValue", 0.0)
            } else emptyList()
            VocabSense(
                speakText    = fsStr(f, "speakText"),
                meaning      = fsStr(f, "meaning"),
                translation  = fsStr(f, "translation"),
                audioPath    = fsStr(f, "audioPath"),
                vector       = vec,
                timingsPath  = fsStr(f, "timingsPath").takeIf { it.isNotBlank() },
                llmInputJson = fsStr(f, "llmInputJson").takeIf { it.isNotBlank() },
                llmOutputJson = fsStr(f, "llmOutputJson").takeIf { it.isNotBlank() },
                ttsModelUsed = fsStr(f, "ttsModelUsed").takeIf { it.isNotBlank() },
                ttsTokenUsage = fsInt(f, "ttsTokenUsage"),
            )
        }
    }

    // ── Public: lookup ───────────────────────────────────────────────────────

    suspend fun lookup(word: String, sentence: String): VocabEntry? {
        val root    = getJson(firestoreDocUrl(word)) ?: return null
        val f       = root.optJSONObject("fields")  ?: return null
        val isMulti = fsBool(f, "multiSense")

        if (!isMulti) {
            val meaning     = fsStr(f, "meaning").ifBlank { fsStr(f, "meaning_hi") }
            val translation = fsStr(f, "translation").ifBlank { fsStr(f, "english_equiv") }
            if (meaning.isBlank() && translation.isBlank()) return null
            val norm = word.lowercase().filter { it.isLetter() }
            if (norm.length >= 3 && meaning.lowercase().filter { it.isLetter() } == norm) return null
            return VocabEntry(
                word         = fsStr(f, "word").ifBlank { word },
                speakText    = fsStr(f, "speakText"),
                meaning      = meaning,
                translation  = translation,
                audioPath    = fsStr(f, "audioPath").ifBlank { "vocab/$word.mp3" },
                timingsPath  = fsStr(f, "timingsPath").takeIf { it.isNotBlank() },
                llmInputJson = fsStr(f, "llmInputJson").takeIf { it.isNotBlank() },
                llmOutputJson = fsStr(f, "llmOutputJson").takeIf { it.isNotBlank() },
                ttsModelUsed = fsStr(f, "ttsModelUsed").takeIf { it.isNotBlank() },
                ttsTokenUsage = fsInt(f, "ttsTokenUsage"),
            )
        }

        val senses = readSenses(word)
        if (senses.isEmpty()) return null
        val qVec = embed(sentence)
        val best = senses.maxByOrNull { cosine(it.vector, qVec) }!!
        return VocabEntry(
            word          = word,
            speakText     = best.speakText,
            meaning       = best.meaning,
            translation   = best.translation,
            audioPath     = best.audioPath,
            timingsPath   = best.timingsPath,
            llmInputJson  = best.llmInputJson,
            llmOutputJson = best.llmOutputJson,
            ttsModelUsed  = best.ttsModelUsed,
            ttsTokenUsage = best.ttsTokenUsage,
        )
    }

    // ── Prompt building with Gist fallbacks ───────────────────────────────────

    private fun buildPrompt(word: String, sentence: String, subject: String): Pair<String, String> {
        val (system, userPrefix) = when (subject.lowercase()) {
            "punjabi" -> Pair(
                "You are a vocabulary teacher for Class 6-8 students studying Punjabi. " +
                "The student understands simple Hindi and English.\n" +
                "speakText format: \"<word>. <word> da matlab hai — <simple Hindi meaning>. English mein ise kehte hain — <English equivalent>.\"\n" +
                "meaning: short Hindi meaning in Devanagari (≤12 words)\n" +
                "translation: 1-3 English words\n" +
                "CRITICAL: speakText MUST start with the word itself. Output ONLY valid JSON.",
                "Punjabi word"
            )
            "english" -> Pair(
                "You are a vocabulary teacher for Class 6-8 students studying English. " +
                "The student also understands Hindi.\n" +
                "speakText format: \"<word>. <word> means — <definition or synonym>. Hindi mein ise kehte hain — <Hindi equivalent>.\"\n" +
                "meaning: simple English definition (2-12 words, NEVER the word itself)\n" +
                "translation: 1-4 Hindi words in Devanagari\n" +
                "CRITICAL: NEVER define a word using the word itself. Output ONLY valid JSON.",
                "English word"
            )
            else -> Pair(
                "You are a Hindi vocabulary teacher for Class 6-8 students.\n" +
                "speakText format: \"<word>. <word> ka matlab hai — <simple Hindi meaning>. English mein ise kehte hain — <English equivalent>.\"\n" +
                "meaning: short Hindi meaning in Devanagari (≤12 words)\n" +
                "translation: 1-3 English words\n" +
                "CRITICAL: speakText MUST start with the word itself. Output ONLY valid JSON.",
                "Hindi word"
            )
        }
        return system to "$userPrefix: \"$word\"\nIn sentence: \"$sentence\""
    }

    private fun buildVocabSchema(): com.google.gson.JsonElement {
        return com.google.gson.JsonObject().apply {
            addProperty("type", "object")
            add("properties", com.google.gson.JsonObject().apply {
                add("speakText", com.google.gson.JsonObject().apply { addProperty("type", "string") })
                add("meaning", com.google.gson.JsonObject().apply { addProperty("type", "string") })
                add("translation", com.google.gson.JsonObject().apply { addProperty("type", "string") })
            })
            add("required", com.google.gson.JsonArray().apply {
                add("speakText")
                add("meaning")
                add("translation")
            })
            addProperty("additionalProperties", false)
        }
    }

    private fun buildGeminiVocabSchema(): com.google.gson.JsonElement {
        return com.google.gson.JsonObject().apply {
            addProperty("type", "object")
            add("properties", com.google.gson.JsonObject().apply {
                add("speakText", com.google.gson.JsonObject().apply { addProperty("type", "string") })
                add("meaning", com.google.gson.JsonObject().apply { addProperty("type", "string") })
                add("translation", com.google.gson.JsonObject().apply { addProperty("type", "string") })
            })
            add("required", com.google.gson.JsonArray().apply {
                add("speakText")
                add("meaning")
                add("translation")
            })
        }
    }

    private fun extractTextFromLlmResponse(res: LlmResponseResponse): String {
        for (item in res.output ?: emptyList()) {
            for (c in item.content ?: emptyList()) {
                if (c.type == "output_text" || c.type == "text") {
                    return c.text ?: ""
                }
            }
        }
        throw Exception("Responses API returned no text output block")
    }

    private fun cleanResponseText(raw: String): String {
        var cleaned = raw.replace(Regex("<think>[\\s\\S]*?</think>"), "")
        cleaned = cleaned.replace(Regex("```(?:json)?\\s*([\\s\\S]*?)\\s*```"), "$1")
        return cleaned.trim()
    }

    // ── ElevenLabs TTS ───────────────────────────────────────────────────────

    private fun voiceId(subject: String) = when (subject.lowercase()) {
        "english", "en" -> "EXAVITQu4vr4xnSDxMaL"  // Bella
        "punjabi", "pa" -> "vT0wMbLG5dssaBsksrb6"
        else            -> "9FTUWXd0yHJL1ZiZ71RK"  // Matilda
    }

    private suspend fun callElevenLabs(speakText: String, subject: String, modelId: String = "eleven_v3"): TtsResult =
        withContext(Dispatchers.IO) {
            val body = JSONObject().apply {
                put("text", speakText)
                put("model_id", modelId)
                put("voice_settings", JSONObject()
                    .put("stability", 0.5)
                    .put("similarity_boost", 0.75))
            }.toString()
            val req = Request.Builder()
                .url("https://api.elevenlabs.io/v1/text-to-speech/${voiceId(subject)}/stream/with-timestamps")
                .addHeader("xi-api-key", BuildConfig.ELEVENLABS_API_KEY)
                .addHeader("Content-Type", "application/json")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) throw Exception("ElevenLabs TTS error ${resp.code}: ${resp.message}")

            val audioParts = mutableListOf<ByteArray>()
            val chars      = mutableListOf<String>()
            val startMs    = mutableListOf<Int>()
            val durMs      = mutableListOf<Int>()

            resp.body!!.charStream().buffered().forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                runCatching {
                    val obj = JSONObject(line)
                    val audioB64 = obj.optString("audio_base64", "")
                    if (audioB64.isNotEmpty()) audioParts += Base64.decode(audioB64, Base64.DEFAULT)
                    val alignment = obj.optJSONObject("normalizedAlignment") ?: return@runCatching
                    val charArr  = alignment.optJSONArray("chars")              ?: return@runCatching
                    val startArr = alignment.optJSONArray("charStartTimesMs")   ?: return@runCatching
                    val durArr   = alignment.optJSONArray("charDurationsMs")    ?: return@runCatching
                    for (i in 0 until charArr.length()) {
                        chars   += charArr.getString(i)
                        startMs += startArr.getInt(i)
                        durMs   += durArr.getInt(i)
                    }
                }
            }

            val audio   = audioParts.fold(ByteArray(0)) { acc, arr -> acc + arr }
            val timings = charsToWordTimings(chars, startMs, durMs).takeIf { it.isNotEmpty() }
            TtsResult(audio, modelId, speakText.length, timings)
        }

    /** Groups ElevenLabs character-level alignment arrays into word-level WordTimings. */
    private fun charsToWordTimings(
        chars:   List<String>,
        startMs: List<Int>,
        durMs:   List<Int>,
    ): List<WordTiming> {
        val timings = mutableListOf<WordTiming>()
        val buf     = StringBuilder()
        var wStart  = 0
        var wEnd    = 0
        for (i in chars.indices) {
            val c = chars[i]
            if (c == " " || c == "\n") {
                if (buf.isNotEmpty()) {
                    timings += WordTiming(word = buf.toString(), start = wStart / 1000.0, end = wEnd / 1000.0)
                    buf.clear()
                }
            } else {
                if (buf.isEmpty()) wStart = startMs[i]
                buf.append(c)
                wEnd = startMs[i] + durMs[i]
            }
        }
        if (buf.isNotEmpty()) timings += WordTiming(word = buf.toString(), start = wStart / 1000.0, end = wEnd / 1000.0)
        return timings
    }

    private suspend fun callSarvamTts(text: String, subject: String, modelId: String = "bulbul:v3"): TtsResult =
        withContext(Dispatchers.IO) {
            val targetLang = when (subject.lowercase()) {
                "hindi"   -> "hi-IN"
                "punjabi" -> "pa-IN"
                else      -> "hi-IN"
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
            val audio = Base64.decode(audios.getString(0), Base64.DEFAULT)
            TtsResult(audio, modelId, text.length)
        }

    private suspend fun callGeminiTts(text: String, subject: String, modelId: String = "gemini-3.1-flash-tts-preview"): TtsResult =
        withContext(Dispatchers.IO) {
            val request = GeminiRequest(
                contents = listOf(GeminiContent(parts = listOf(GeminiPart(text = text)))),
                generationConfig = GeminiGenConfig(
                    responseModalities = listOf("AUDIO"),
                    speechConfig = GeminiSpeechConfig(
                        voiceConfig = GeminiVoiceConfig(
                            prebuiltVoiceConfig = GeminiPrebuiltVoice(voiceName = "Aoede"),
                        )
                    ),
                ),
            )
            val response = geminiApi.generateSpeech(
                model   = modelId,
                apiKey  = BuildConfig.GEMINI_API_KEY,
                request = request,
            )

            if (response.error != null) {
                throw Exception("Gemini TTS API error ${response.error.code}: ${response.error.message}")
            }

            val inlineData = response.candidates
                ?.firstOrNull()?.content?.parts?.firstOrNull()?.inlineData
                ?: throw Exception("Gemini TTS empty response (Candidates/InlineData missing). Response: $response")

            val pcmBytes  = Base64.decode(inlineData.data, Base64.DEFAULT)
            val sampleRate = 24000
            val audio = pcmToWav(pcmBytes, sampleRate)
            val tokens = response.usageMetadata?.totalTokenCount ?: text.length
            TtsResult(audio, modelId, tokens)
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

    /** Serialise word timings to a compact JSON byte array for Storage upload. */
    private fun wordTimingsToJson(timings: List<WordTiming>): ByteArray {
        val arr = JSONArray()
        for (t in timings) arr.put(JSONObject().put("word", t.word).put("start", t.start).put("end", t.end))
        return arr.toString().toByteArray(Charsets.UTF_8)
    }

    /** Download and parse a word-timings JSON file previously stored in Firebase Storage. */
    suspend fun loadTimings(timingsPath: String): List<WordTiming>? = withContext(Dispatchers.IO) {
        runCatching {
            val resp = client.newCall(Request.Builder().url(storageUrl(timingsPath)).build()).execute()
            if (!resp.isSuccessful) return@withContext null
            val json = JSONArray(resp.body!!.string())
            (0 until json.length()).map { i ->
                val o = json.getJSONObject(i)
                WordTiming(word = o.getString("word"), start = o.getDouble("start"), end = o.getDouble("end"))
            }
        }.getOrNull()
    }

    // ── Firebase Storage upload ──────────────────────────────────────────────

    private suspend fun uploadStorage(path: String, data: ByteArray, mime: String): String =
        withContext(Dispatchers.IO) {
            val url = "https://firebasestorage.googleapis.com/v0/b/$bucket/o?name=${enc(path)}"
            val req = Request.Builder()
                .url(url)
                .post(data.toRequestBody(mime.toMediaType()))
                .build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) throw Exception("Storage upload error ${resp.code}")
            path
        }

    // ── Firestore write ──────────────────────────────────────────────────────

    private suspend fun writeFirestore(entry: VocabEntry, subject: String) =
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("fields", JSONObject().apply {
                put("word",        JSONObject().put("stringValue", entry.word))
                put("speakText",   JSONObject().put("stringValue", entry.speakText))
                put("meaning",     JSONObject().put("stringValue", entry.meaning))
                put("translation", JSONObject().put("stringValue", entry.translation))
                put("audioPath",   JSONObject().put("stringValue", entry.audioPath))
                put("subject",     JSONObject().put("stringValue", subject))
                put("multiSense",  JSONObject().put("booleanValue", false))
                put("generatedAt", JSONObject().put("timestampValue", Instant.now().toString()))
                entry.llmInputJson?.let { put("llmInputJson", JSONObject().put("stringValue", it)) }
                entry.llmOutputJson?.let { put("llmOutputJson", JSONObject().put("stringValue", it)) }
                entry.ttsModelUsed?.let  { put("ttsModelUsed",  JSONObject().put("stringValue",  it)) }
                entry.ttsTokenUsage?.let { put("ttsTokenUsage", JSONObject().put("integerValue", it.toString())) }
                entry.timingsPath?.let   { put("timingsPath",   JSONObject().put("stringValue",  it)) }
            }).toString()
            val req = Request.Builder()
                .url(firestoreDocUrl(entry.word))
                .method("PATCH", body.toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute()
        }

    // ── Public: generate ─────────────────────────────────────────────────────

    suspend fun generate(
        word: String,
        sentence: String,
        subject: String,
        onProgress: (status: String) -> Unit = {}
    ): VocabEntry {
        val childName = prefsStore.lastChildName.first()
        var model       = "gemini-3.5-flash"
        var temp        = 0.2
        var ttsProvider = "elevenlabs"
        var ttsModel: String? = null

        try {
            val langConfig = globalSettingsRepo.load().resolve("read", "en")
            model       = langConfig.llm.model
            temp        = langConfig.llm.temperature
            ttsProvider = langConfig.tts.provider
            ttsModel    = langConfig.tts.model
        } catch (e: Exception) {
            Log.w("VocabRepo", "Error reading global settings, using defaults", e)
        }

        // Fetch prompt from Gist
        val gistFilename = when (subject.lowercase()) {
            "english" -> "aarav_vocab_system_prompt_english.json"
            "punjabi" -> "aarav_vocab_system_prompt_punjabi.json"
            else -> "aarav_vocab_system_prompt_hindi.json"
        }

        val gistContent = try {
            placementRepo.getGistFile(gistFilename)
        } catch (e: Exception) {
            Log.w("VocabRepo", "Error fetching prompt from Gist, using local fallback", e)
            null
        }

        val (system, user) = if (gistContent != null) {
            val jsonObj = JSONObject(gistContent)
            val systemArray = jsonObj.optJSONArray("system")
            val sysPrompt = if (systemArray != null) {
                (0 until systemArray.length()).map { systemArray.getString(it) }.joinToString("\n")
            } else {
                jsonObj.optString("system", "")
            }
            val userPrefix = jsonObj.optString("userPrefix", "")
                .replace("{{word}}", word)
                .replace("{{sentence}}", sentence)
            sysPrompt to userPrefix
        } else {
            buildPrompt(word, sentence, subject)
        }

        onProgress("Generating meaning using $model...")

        var llmInputJson = ""
        var llmOutputJson = ""

        // Call multi-engine LLM
        val rawResponse = when {
            model.startsWith("grok-") -> {
                val schemaJson = buildVocabSchema()
                val request = LlmResponseRequest(
                    model = model,
                    input = user,
                    instructions = system,
                    temperature = null, // Grok does not support temperature parameter
                    maxOutputTokens = 512,
                    text = LlmTextFormat(LlmJsonSchemaFormat(name = "vocab_meaning", schema = schemaJson))
                )
                llmInputJson = com.google.gson.Gson().toJson(request)
                val res = xaiApi.createResponse("Bearer ${BuildConfig.XAI_API_KEY}", request)
                llmOutputJson = com.google.gson.Gson().toJson(res)
                extractTextFromLlmResponse(res)
            }
            model.startsWith("gpt-") -> {
                val schemaJson = buildVocabSchema()
                val request = LlmResponseRequest(
                    model = model,
                    input = user,
                    instructions = system,
                    temperature = temp,
                    maxOutputTokens = 512,
                    text = LlmTextFormat(LlmJsonSchemaFormat(name = "vocab_meaning", schema = schemaJson))
                )
                llmInputJson = com.google.gson.Gson().toJson(request)
                val res = openAiApi.createResponse("Bearer ${BuildConfig.OPENAI_API_KEY}", request)
                llmOutputJson = com.google.gson.Gson().toJson(res)
                extractTextFromLlmResponse(res)
            }
            model.startsWith("sarvam-") -> {
                val request = SarvamChatRequest(
                    model = model,
                    messages = listOf(
                        SarvamChatMessage("system", system),
                        SarvamChatMessage("user", user)
                    ),
                    temperature = temp,
                    maxTokens = 512,
                    responseFormat = SarvamResponseFormat()
                )
                llmInputJson = com.google.gson.Gson().toJson(request)
                val res = sarvamChatApi.chatCompletions(BuildConfig.SARVAM_API_KEY, request)
                llmOutputJson = com.google.gson.Gson().toJson(res)
                res.choices?.firstOrNull()?.message?.content ?: "{}"
            }
            else -> {
                val request = GeminiRequest(
                    systemInstruction = GeminiContent(parts = listOf(GeminiPart(text = system))),
                    contents = listOf(GeminiContent(role = "user", parts = listOf(GeminiPart(text = user)))),
                    generationConfig = GeminiGenConfig(
                        temperature = temp,
                        responseMimeType = "application/json",
                        responseSchema = buildGeminiVocabSchema()
                    )
                )
                llmInputJson = com.google.gson.Gson().toJson(request)
                val res = geminiApi.generateContent(model, BuildConfig.GEMINI_API_KEY, request)
                llmOutputJson = com.google.gson.Gson().toJson(res)
                if (res.error != null) {
                    throw Exception("Gemini content generation error ${res.error.code}: ${res.error.message}")
                }
                res.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                    ?: throw Exception("Gemini content generation returned no candidates")
            }
        }

        val clean = cleanResponseText(rawResponse)
        val p = try {
            val trimmed = clean.trim()
            if (trimmed.startsWith("[")) {
                val arr = JSONArray(trimmed)
                var found: JSONObject? = null
                val targetWordNorm = word.trim().lowercase()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val w = obj.optString("word", "").trim().lowercase()
                    if (w == targetWordNorm) {
                        found = obj
                        break
                    }
                }
                if (found == null) {
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val w = obj.optString("word", "").trim().lowercase()
                        if (w.contains(targetWordNorm) || targetWordNorm.contains(w)) {
                            found = obj
                            break
                        }
                    }
                }
                found ?: if (arr.length() > 0) arr.getJSONObject(0) else JSONObject()
            } else {
                JSONObject(clean)
            }
        } catch (e: Exception) {
            Log.e("VocabRepo", "Error parsing response JSON: $clean", e)
            try {
                JSONObject(clean)
            } catch (e2: Exception) {
                JSONObject()
            }
        }
        val speakText = p.optString("speakText", "")
        val meaning = p.optString("meaning", "")
        val translation = p.optString("translation", "")

        val resolvedTtsModel = ttsModel ?: when (ttsProvider) {
            "elevenlabs" -> "eleven_v3"
            "sarvam"     -> "bulbul:v3"
            else         -> "gemini-3.1-flash-tts-preview"
        }
        onProgress("Generating audio using $resolvedTtsModel…")

        // Generate TTS based on settings
        val ttsResult = when (ttsProvider) {
            "elevenlabs" -> callElevenLabs(speakText, subject, resolvedTtsModel)
            "sarvam"     -> callSarvamTts(speakText, subject, resolvedTtsModel)
            else         -> callGeminiTts(speakText, subject, resolvedTtsModel)
        }

        val audioPath = uploadStorage("vocab/$word.mp3", ttsResult.audio, "audio/mpeg")
        val timingsPath = ttsResult.timings
            ?.let { timings -> wordTimingsToJson(timings) }
            ?.let { json   -> uploadStorage("vocab/${word}_timings.json", json, "application/json") }
        val entry = VocabEntry(
            word          = word,
            speakText     = speakText,
            meaning       = meaning,
            translation   = translation,
            audioPath     = audioPath,
            timingsPath   = timingsPath,
            llmInputJson  = llmInputJson,
            llmOutputJson = llmOutputJson,
            ttsModelUsed  = ttsResult.model,
            ttsTokenUsage = ttsResult.tokens,
        )
        writeFirestore(entry, subject)
        return entry
    }

    // ── Public: delete (for refresh/retry flow) ──────────────────────────────

    suspend fun delete(word: String) = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(Request.Builder().url(firestoreDocUrl(word)).delete().build()).execute()
        }
        for (ext in listOf("mp3", "wav")) {
            runCatching {
                val url = "https://firebasestorage.googleapis.com/v0/b/$bucket/o/${enc("vocab/$word.$ext")}"
                client.newCall(Request.Builder().url(url).delete().build()).execute()
            }
        }
    }

    // ── Public: audio playback URL ───────────────────────────────────────────

    fun audioUrl(audioPath: String) = storageUrl(audioPath)
}
