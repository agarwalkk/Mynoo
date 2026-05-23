package com.krishanagarwal.mynoo.data.repository

import com.krishanagarwal.mynoo.BuildConfig
import kotlinx.coroutines.Dispatchers
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
)

@Singleton
class VocabRepository @Inject constructor(
    private val client: OkHttpClient,
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
        val speakText:   String,
        val meaning:     String,
        val translation: String,
        val audioPath:   String,
        val vector:      List<Double>,
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
                speakText   = fsStr(f, "speakText"),
                meaning     = fsStr(f, "meaning"),
                translation = fsStr(f, "translation"),
                audioPath   = fsStr(f, "audioPath"),
                vector      = vec,
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
            // Reject tautological entries (meaning same letters as word)
            val norm = word.lowercase().filter { it.isLetter() }
            if (norm.length >= 3 && meaning.lowercase().filter { it.isLetter() } == norm) return null
            return VocabEntry(
                word        = fsStr(f, "word").ifBlank { word },
                speakText   = fsStr(f, "speakText"),
                meaning     = meaning,
                translation = translation,
                audioPath   = fsStr(f, "audioPath").ifBlank { "vocab/$word.mp3" },
            )
        }

        // Multi-sense: pick best match by cosine similarity to current sentence
        val senses = readSenses(word)
        if (senses.isEmpty()) return null
        val qVec = embed(sentence)
        val best = senses.maxByOrNull { cosine(it.vector, qVec) }!!
        return VocabEntry(word, best.speakText, best.meaning, best.translation, best.audioPath)
    }

    // ── Gemini LLM generation ────────────────────────────────────────────────

    private fun buildPrompt(word: String, sentence: String, subject: String): Pair<String, String> {
        val (system, userPrefix) = when (subject) {
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
            else -> Pair(  // hindi
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

    private suspend fun callGemini(
        word: String, sentence: String, subject: String,
    ): Triple<String, String, String> = withContext(Dispatchers.IO) {
        val (system, user) = buildPrompt(word, sentence, subject)
        val body = JSONObject().apply {
            put("system_instruction", JSONObject().put("parts",
                JSONArray().put(JSONObject().put("text", system))))
            put("contents", JSONArray().put(JSONObject()
                .put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", user)))))
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.2)
                put("maxOutputTokens", 512)
                put("responseMimeType", "application/json")
            })
        }.toString()
        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=${BuildConfig.GEMINI_API_KEY}")
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        val raw = JSONObject(client.newCall(req).execute().body!!.string())
        val text = raw
            .getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts").getJSONObject(0)
            .getString("text").trim()
        val p = JSONObject(text)
        Triple(p.optString("speakText", ""), p.optString("meaning", ""), p.optString("translation", ""))
    }

    // ── ElevenLabs TTS ───────────────────────────────────────────────────────

    private fun voiceId(subject: String) = when (subject) {
        "english" -> "EXAVITQu4vr4xnSDxMaL"  // Bella — warm female, free tier
        "punjabi" -> "vT0wMbLG5dssaBsksrb6"  // custom Punjabi voice
        else      -> "9FTUWXd0yHJL1ZiZ71RK"  // Matilda — multilingual (Hindi)
    }

    private suspend fun callElevenLabs(speakText: String, subject: String): ByteArray =
        withContext(Dispatchers.IO) {
            val body = JSONObject().apply {
                put("text", speakText)
                put("model_id", "eleven_v3")
                put("voice_settings", JSONObject()
                    .put("stability", 0.5)
                    .put("similarity_boost", 0.75))
            }.toString()
            val req = Request.Builder()
                .url("https://api.elevenlabs.io/v1/text-to-speech/${voiceId(subject)}")
                .addHeader("xi-api-key", BuildConfig.ELEVENLABS_API_KEY)
                .addHeader("Content-Type", "application/json")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) throw Exception("ElevenLabs TTS error ${resp.code}: ${resp.message}")
            resp.body!!.bytes()
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
            }).toString()
            val req = Request.Builder()
                .url(firestoreDocUrl(entry.word))
                .method("PATCH", body.toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute()
        }

    // ── Public: generate ─────────────────────────────────────────────────────

    suspend fun generate(word: String, sentence: String, subject: String): VocabEntry {
        val (speakText, meaning, translation) = callGemini(word, sentence, subject)
        val audio     = callElevenLabs(speakText, subject)
        val audioPath = uploadStorage("vocab/$word.mp3", audio, "audio/mpeg")
        val entry     = VocabEntry(word, speakText, meaning, translation, audioPath)
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
