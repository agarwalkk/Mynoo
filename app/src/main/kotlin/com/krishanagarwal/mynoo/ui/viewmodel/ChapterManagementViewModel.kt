package com.krishanagarwal.mynoo.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.firestore.FirebaseFirestore
import com.krishanagarwal.mynoo.BuildConfig
import com.krishanagarwal.mynoo.data.repository.ChapterMeta
import com.krishanagarwal.mynoo.data.repository.ChapterRepository
import com.krishanagarwal.mynoo.data.repository.WordTiming
import com.krishanagarwal.mynoo.service.TtsService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import javax.inject.Inject

data class ChapterUiItem(
    val id: String,
    val title: String,
    val order: Int,
    val wordCount: Int,
    val published: Boolean,
    val audioExists: Boolean = false,
    val audioStatus: String = "none",
    val voicePct: Int = 0,
    val audioError: String? = null,
    val sentenceCount: Int = 0,
    val segmentsUploaded: Int = 0,
    val segmentsTotal: Int = 0
)

data class AudioJobState(
    val chapterId: String,
    val subjectSlug: String,
    val phase: String, // "fetching" | "generating" | "done" | "error" | "aborted" | "stopped"
    val done: Int,
    val total: Int,
    val currentText: String = "",
    val errors: List<String> = emptyList(),
    val abortError: String? = null
)

@HiltViewModel
class ChapterManagementViewModel @Inject constructor(
    private val repo: ChapterRepository,
    private val db: FirebaseFirestore,
    private val ttsService: TtsService,
    private val client: OkHttpClient
) : ViewModel() {

    private val _chaptersList = MutableStateFlow<List<ChapterUiItem>>(emptyList())
    val chaptersList: StateFlow<List<ChapterUiItem>> = _chaptersList.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _uploading = MutableStateFlow(false)
    val uploading: StateFlow<Boolean> = _uploading.asStateFlow()

    private val _uploadError = MutableStateFlow<String?>(null)
    val uploadError: StateFlow<String?> = _uploadError.asStateFlow()

    private val _audioJobState = MutableStateFlow<AudioJobState?>(null)
    val audioJobState: StateFlow<AudioJobState?> = _audioJobState.asStateFlow()

    private var activeJob: Job? = null

    fun loadChapters(classNum: String, subject: String) {
        _loading.value = true
        viewModelScope.launch {
            try {
                val list = repo.getChapters(classNum, subject)
                val slug = subject.lowercase().replace(' ', '_')
                
                val uiItems = list.map { ch ->
                    val doc = db.collection("classes").document(classNum)
                        .collection("subjects").document(slug)
                        .collection("chapters").document(ch.id).get().await()
                        
                    val status = doc.getString("audioStatus") ?: if (doc.getBoolean("audioExists") == true) "complete" else "none"
                    val segmentsUploaded = (doc.get("voiceSegmentsReady") as? Number)?.toInt() ?: 0
                    val segmentsTotal = (doc.get("voiceSegmentsTotal") as? Number)?.toInt() ?: 0
                    val sentenceCount = (doc.get("sentenceCount") as? Number)?.toInt() ?: 0
                    val pct = if (segmentsTotal > 0) {
                        Math.min(100, Math.round((segmentsUploaded.toDouble() / segmentsTotal.toDouble()) * 100).toInt())
                    } else {
                        (doc.get("voicePct") as? Number)?.toInt() ?: if (status == "complete") 100 else 0
                    }
                    val exists = doc.getBoolean("audioExists") ?: (status == "complete")
                    val err = doc.getString("audioError")
                    
                    ChapterUiItem(
                        id = ch.id,
                        title = ch.title,
                        order = ch.order,
                        wordCount = ch.wordCount,
                        published = ch.published,
                        audioExists = exists,
                        audioStatus = status,
                        voicePct = pct,
                        audioError = err,
                        sentenceCount = sentenceCount,
                        segmentsUploaded = segmentsUploaded,
                        segmentsTotal = segmentsTotal
                    )
                }
                _chaptersList.value = uiItems
            } catch (e: Exception) {
                Log.e("ChapterMgmtVM", "Error loading chapters", e)
            } finally {
                _loading.value = false
            }
        }
    }

    fun uploadChapter(classNum: String, subject: String, title: String, order: Int, jsonContent: String, onDone: () -> Unit) {
        _uploading.value = true
        _uploadError.value = null
        viewModelScope.launch {
            try {
                val parsed = JSONObject(jsonContent)
                val paras = parsed.optJSONArray("paragraphs")
                if (paras == null || paras.length() == 0) {
                    throw Exception("JSON must have a non-empty 'paragraphs' array.")
                }

                // Count stats
                var sentenceCount = 0
                var wordCount = 0
                for (i in 0 until paras.length()) {
                    val p = paras.getJSONObject(i)
                    val sents = p.optJSONArray("sentences")
                    if (sents != null) {
                        sentenceCount += sents.length()
                        for (j in 0 until sents.length()) {
                            val text = sents.getJSONObject(j).optString("text", "")
                            wordCount += text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.size
                        }
                    } else {
                        val text = p.optString("text", "")
                        wordCount += text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.size
                    }
                }

                val chapterId = title.trim()
                    .lowercase()
                    .replace(Regex("[^a-z0-9\\s-]"), "")
                    .trim()
                    .replace(Regex("\\s+"), "-")
                    .replace(Regex("-+"), "-")
                    .take(60).ifBlank { "chapter-${System.currentTimeMillis()}" }

                // 1. Upload JSON to Firebase Storage
                repo.uploadChapterJson(classNum, subject, chapterId, jsonContent)

                // 2. Write metadata to Firestore
                repo.writeChapterMeta(classNum, subject, chapterId, title, order, wordCount, sentenceCount)

                // 3. Write initial audio status
                repo.writeChapterAudioStatus(classNum, subject, chapterId, "none", null, sentenceCount, 0)

                _uploading.value = false
                loadChapters(classNum, subject)
                onDone()
            } catch (e: Exception) {
                Log.e("ChapterMgmtVM", "Error uploading chapter", e)
                _uploadError.value = e.message ?: "Failed to upload chapter"
                _uploading.value = false
            }
        }
    }

    fun generateVoiceForChapter(classNum: String, subject: String, chapterId: String, provider: String, forceRegenerate: Boolean) {
        stopVoiceJob()
        
        val slug = subject.lowercase().replace(' ', '_')
        val lang = when (slug) {
            "hindi" -> "hi"
            "punjabi" -> "pa"
            else -> "en"
        }

        activeJob = viewModelScope.launch(Dispatchers.IO) {
            _audioJobState.value = AudioJobState(chapterId, slug, "fetching", 0, 0)
            
            try {
                // 1. Fetch content JSON to parse segments
                val content = repo.getContent(classNum, subject, chapterId)
                val segments = mutableListOf<SegmentItem>()
                val noAudioTypes = setOf("heading", "attribution", "subheading", "table", "note")

                for (p in content.paragraphs) {
                    if (noAudioTypes.contains(p.type)) continue
                    if (p.type == "list" && p.items.isNotEmpty()) {
                        p.items.forEachIndexed { idx, item ->
                            val id = "${p.id}-item-$idx"
                            val text = cleanMarkdown(item)
                            if (text.isNotBlank()) {
                                segments.add(SegmentItem(id, text, "paragraph"))
                            }
                        }
                    } else if (p.sentences.isNotEmpty()) {
                        for (s in p.sentences) {
                            if (s.id.isNotBlank() && s.text.isNotBlank()) {
                                segments.add(SegmentItem(s.id, cleanMarkdown(s.text), "sentence"))
                            }
                        }
                    } else if (p.text.isNotBlank()) {
                        segments.add(SegmentItem(p.id, cleanMarkdown(p.text), "paragraph"))
                    }
                }

                if (segments.isEmpty()) {
                    _audioJobState.value = AudioJobState(chapterId, slug, "error", 0, 0, abortError = "No speakable segments found.")
                    return@launch
                }

                // 1b. Fetch existing segment audio files in Storage if resuming
                val existingIds = mutableSetOf<String>()
                if (!forceRegenerate) {
                    val audioPrefix = "classes/$classNum/$slug/$chapterId/audio/"
                    var pageToken: String? = null
                    val storageBucket = "aaravtutor-1e880.firebasestorage.app"
                    do {
                        val listUrl = "https://firebasestorage.googleapis.com/v0/b/$storageBucket/o?prefix=${java.net.URLEncoder.encode(audioPrefix, "UTF-8")}" +
                            if (pageToken != null) "&pageToken=${java.net.URLEncoder.encode(pageToken, "UTF-8")}" else ""
                        val req = Request.Builder().url(listUrl).get().build()
                        client.newCall(req).execute().use { resp ->
                            if (resp.isSuccessful) {
                                val bodyStr = resp.body?.string() ?: ""
                                val json = org.json.JSONObject(bodyStr)
                                val items = json.optJSONArray("items")
                                if (items != null) {
                                    for (j in 0 until items.length()) {
                                        val item = items.getJSONObject(j)
                                        val name = item.optString("name", "")
                                        val base = name.split('/').lastOrNull() ?: ""
                                        val segId = base.replace(Regex("\\.(mp3|wav|json)$"), "")
                                        if (segId.isNotEmpty()) {
                                            existingIds.add(segId)
                                        }
                                    }
                                }
                                val token = json.optString("nextPageToken", "")
                                pageToken = if (token.isNotEmpty()) token else null
                            } else {
                                pageToken = null
                            }
                        }
                    } while (pageToken != null)
                }

                val initialDone = segments.count { !forceRegenerate && existingIds.contains(it.id) }
                _audioJobState.value = AudioJobState(chapterId, slug, "generating", initialDone, segments.size)

                val errors = mutableListOf<String>()
                var done = initialDone

                // 2. Loop and generate audio + timings
                for (i in segments.indices) {
                    val seg = segments[i]
                    if (!forceRegenerate && existingIds.contains(seg.id)) {
                        continue
                    }
                    _audioJobState.update { it?.copy(done = done, currentText = seg.text.take(60)) }

                    try {
                        // Generate audio bytes
                        val ext = if (provider == "elevenlabs") "mp3" else "wav"
                        val mimeType = if (provider == "elevenlabs") "audio/mpeg" else "audio/wav"
                        
                        val audioBytes = when (provider) {
                            "elevenlabs" -> callElevenLabsTts(seg.text, lang)
                            "sarvam" -> callSarvamTts(seg.text, lang)
                            else -> callGoogleTts(seg.text, lang)
                        }

                        // Upload audio bytes to Firebase Storage
                        repo.uploadAudioBytes(classNum, subject, chapterId, seg.id, seg.kind, ext, mimeType, audioBytes)

                        // Call OpenAI Whisper to get word timings
                        try {
                            val timings = callWhisperForTimings(audioBytes, mimeType, ext, lang)
                            if (timings.isNotEmpty()) {
                                val timingsJson = JSONArray().apply {
                                    timings.forEach { t ->
                                        put(JSONObject().apply {
                                            put("word", t.word)
                                            put("start", t.start)
                                            put("end", t.end)
                                        })
                                    }
                                }.toString()
                                repo.uploadTimingJson(classNum, subject, chapterId, seg.id, seg.kind, timingsJson)
                            }
                        } catch (e: Exception) {
                            Log.w("ChapterMgmtVM", "Whisper timing failed for segment ${seg.id}: ${e.message}")
                        }

                        done++
                        // Update progress in Firestore periodically
                        val status = if (done < segments.size) "partial" else "complete"
                        repo.writeChapterAudioStatus(classNum, subject, chapterId, status, null, segments.size, done)

                    } catch (e: Exception) {
                        Log.e("ChapterMgmtVM", "Error generating segment ${seg.id}", e)
                        errors.add("${seg.id}: ${e.message}")
                    }
                    
                    delay(300) // slight delay to avoid rate limiting
                }

                val finalStatus = if (done < segments.size) "partial" else "complete"
                val finalError = if (errors.isNotEmpty()) errors.joinToString("\n") else null
                repo.writeChapterAudioStatus(classNum, subject, chapterId, finalStatus, finalError, segments.size, done)
                
                _audioJobState.value = AudioJobState(
                    chapterId = chapterId,
                    subjectSlug = slug,
                    phase = "done",
                    done = done,
                    total = segments.size,
                    errors = errors
                )

                // Refresh local listing
                loadChapters(classNum, subject)

            } catch (e: Exception) {
                Log.e("ChapterMgmtVM", "Audio generation loop aborted", e)
                _audioJobState.value = AudioJobState(chapterId, slug, "error", 0, 0, abortError = e.message)
            }
        }
    }

    fun stopVoiceJob() {
        activeJob?.cancel()
        activeJob = null
        _audioJobState.value?.let { current ->
            if (current.phase == "generating" || current.phase == "fetching") {
                _audioJobState.value = current.copy(phase = "stopped", abortError = "Voice generation stopped by parent.")
            }
        }
    }

    fun deleteChapter(classNum: String, subject: String, chapterId: String, onDone: () -> Unit) {
        viewModelScope.launch {
            try {
                repo.deleteChapter(classNum, subject, chapterId)
                loadChapters(classNum, subject)
                onDone()
            } catch (e: Exception) {
                Log.e("ChapterMgmtVM", "Error deleting chapter", e)
            }
        }
    }

    private fun cleanMarkdown(s: String): String {
        return s.replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")
            .replace(Regex("\\*([^*]+)\\*"), "$1")
            .trim()
    }

    private data class SegmentItem(val id: String, val text: String, val kind: String)

    // Helper TTS calls returning raw audio bytes
    private fun voiceId(lang: String) = when (lang.lowercase()) {
        "en" -> "EXAVITQu4vr4xnSDxMaL"
        "pa" -> "vT0wMbLG5dssaBsksrb6"
        else -> "9FTUWXd0yHJL1ZiZ71RK"
    }

    private fun callElevenLabsTts(text: String, lang: String): ByteArray {
        val body = JSONObject().apply {
            put("text", text)
            put("model_id", "eleven_v3")
            put("voice_settings", JSONObject().put("stability", 0.5).put("similarity_boost", 0.75))
        }.toString()
        val req = Request.Builder()
            .url("https://api.elevenlabs.io/v1/text-to-speech/${voiceId(lang)}")
            .addHeader("xi-api-key", BuildConfig.ELEVENLABS_API_KEY)
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("ElevenLabs failed (${resp.code}): ${resp.message}")
            return resp.body!!.bytes()
        }
    }

    private fun callSarvamTts(text: String, lang: String): ByteArray {
        val targetLang = if (lang == "pa") "pa-IN" else if (lang == "hi") "hi-IN" else "en-IN"
        val body = JSONObject().apply {
            put("inputs", JSONArray().put(text))
            put("target_language_code", targetLang)
            put("speaker", "kavya")
            put("pace", 0.9)
            put("speech_sample_rate", 22050)
            put("enable_preprocessing", true)
            put("model", "bulbul:v3")
        }.toString()
        val req = Request.Builder()
            .url("https://api.sarvam.ai/text-to-speech")
            .addHeader("api-subscription-key", BuildConfig.SARVAM_API_KEY)
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Sarvam failed (${resp.code}): ${resp.message}")
            val json = JSONObject(resp.body!!.string())
            val audios = json.getJSONArray("audios")
            return android.util.Base64.decode(audios.getString(0), android.util.Base64.DEFAULT)
        }
    }

    private fun callGoogleTts(text: String, lang: String): ByteArray {
        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().put("text", text)))
            }))
            put("generationConfig", JSONObject().apply {
                put("responseModalities", JSONArray().put("AUDIO"))
                put("speechConfig", JSONObject().put("voiceConfig", JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", "Aoede"))))
            })
        }.toString()
        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.1-flash-tts-preview:generateContent?key=${BuildConfig.GEMINI_API_KEY}")
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Google TTS failed (${resp.code}): ${resp.message}")
            val json = JSONObject(resp.body!!.string())
            val candidates = json.optJSONArray("candidates") ?: throw IOException("Google TTS no content")
            val inlineData = candidates.getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getJSONObject("inlineData")
            val pcmBytes = android.util.Base64.decode(inlineData.getString("data"), android.util.Base64.DEFAULT)
            return pcmToWav(pcmBytes, 24000)
        }
    }

    private fun pcmToWav(pcmBytes: ByteArray, sampleRate: Int): ByteArray {
        val header = ByteArray(44)
        val totalDataLen = pcmBytes.size + 36
        val byteRate = sampleRate * 2

        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte(); header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = ((totalDataLen shr 8) and 0xff).toByte()
        header[6] = ((totalDataLen shr 16) and 0xff).toByte()
        header[7] = ((totalDataLen shr 24) and 0xff).toByte()
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte(); header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte(); header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0 // subchunk size
        header[20] = 1; header[21] = 0 // PCM format
        header[22] = 1; header[23] = 0 // mono
        header[24] = (sampleRate and 0xff).toByte(); header[25] = ((sampleRate shr 8) and 0xff).toByte(); header[26] = ((sampleRate shr 16) and 0xff).toByte(); header[27] = ((sampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte(); header[29] = ((byteRate shr 8) and 0xff).toByte(); header[30] = ((byteRate shr 16) and 0xff).toByte(); header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = 2; header[33] = 0 // block align
        header[34] = 16; header[35] = 0 // bits per sample
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte(); header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        header[40] = (pcmBytes.size and 0xff).toByte(); header[41] = ((pcmBytes.size shr 8) and 0xff).toByte(); header[42] = ((pcmBytes.size shr 16) and 0xff).toByte(); header[43] = ((pcmBytes.size shr 24) and 0xff).toByte()
        return header + pcmBytes
    }

    private fun callWhisperForTimings(audioBytes: ByteArray, mimeType: String, ext: String, lang: String): List<WordTiming> {
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", "whisper-1")
            .addFormDataPart("response_format", "verbose_json")
            .addFormDataPart("timestamp_granularities[]", "word")
            .addFormDataPart("language", if (lang == "pa") "pa" else if (lang == "hi") "hi" else "en")
            .addFormDataPart("file", "segment.$ext", audioBytes.toRequestBody(mimeType.toMediaType()))
            .build()

        val req = Request.Builder()
            .url("https://api.openai.com/v1/audio/transcriptions")
            .addHeader("Authorization", "Bearer ${BuildConfig.OPENAI_API_KEY}")
            .post(requestBody)
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Whisper timing failed (${resp.code}): ${resp.message}")
            val json = JSONObject(resp.body!!.string())
            val words = json.optJSONArray("words") ?: return emptyList()
            val list = mutableListOf<WordTiming>()
            for (i in 0 until words.length()) {
                val w = words.getJSONObject(i)
                val wordText = w.getString("word").trim().replace(Regex("^[,.!?:;\\-()\"']+"), "").replace(Regex("[,.!?:;\\-()\"']+$"), "")
                if (wordText.isNotEmpty()) {
                    list.add(WordTiming(
                        word = wordText,
                        start = w.getDouble("start"),
                        end = w.getDouble("end")
                    ))
                }
            }
            return list
        }
    }
}
