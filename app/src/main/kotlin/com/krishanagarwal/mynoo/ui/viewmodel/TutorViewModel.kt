package com.krishanagarwal.mynoo.ui.viewmodel

import android.Manifest
import android.content.pm.PackageManager
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.firestore.FirebaseFirestore
import com.krishanagarwal.mynoo.BuildConfig
import com.krishanagarwal.mynoo.data.api.*
import com.krishanagarwal.mynoo.data.model.ChildState
import com.krishanagarwal.mynoo.data.repository.SessionRepository
import com.krishanagarwal.mynoo.data.repository.PlacementRepository
import com.krishanagarwal.mynoo.service.AudioRecorderService
import com.krishanagarwal.mynoo.service.TtsService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

enum class SessionPhase {
    IDLE, STARTING, BOT_SPEAKING, WAITING_CHILD, RECORDING, PROCESSING, ENDED
}

data class ChatMessage(
    val id:   String = UUID.randomUUID().toString(),
    val role: String,   // "child" | "bot"
    val text: String,
)

data class TutorUiState(
    val phase:        SessionPhase  = SessionPhase.IDLE,
    val messages:     List<ChatMessage> = emptyList(),
    val lang:         String        = "en",
    val error:        String?       = null,
    val quickReplies: List<String>  = emptyList(),
    val hasMicPerm:   Boolean       = false,
    val isAssessed:   Boolean       = true,
)

@HiltViewModel
class TutorViewModel @Inject constructor(
    private val geminiApi:     GeminiApi,
    private val sarvamApi:     SarvamApi,
    private val ttsService:    TtsService,
    private val recorder:      AudioRecorderService,
    private val sessionRepo:   SessionRepository,
    private val placementRepo: PlacementRepository,
    private val db:            FirebaseFirestore,
    private val openAiApi:     OpenAiApi,
    private val xaiApi:        XaiApi,
    private val sarvamChatApi: SarvamChatApi,
) : ViewModel() {

    private val _ui = MutableStateFlow(TutorUiState())
    val ui: StateFlow<TutorUiState> = _ui

    private val history   = mutableListOf<GeminiContent>()
    private var sessionId = ""
    private var startTime = Instant.now()
    private var activeJob: Job? = null

    private var activeModel = "gemini-3.5-flash"
    private var activeTemp = 0.7
    private var ttsProvider: String? = null
    private var sttProvider: String? = null
    private var resolvedSystemPrompt = ""

    // ── Mic permission ────────────────────────────────────────────────────────
    fun onMicPermResult(granted: Boolean) {
        _ui.update { it.copy(hasMicPerm = granted) }
    }

    fun checkAssessedStatus(childName: String) {
        if (childName.isBlank()) return
        viewModelScope.launch {
            try {
                val assessed = placementRepo.hasBeenAssessed(childName)
                _ui.update { it.copy(isAssessed = assessed) }
            } catch (e: Exception) {
                Log.e("TutorVM", "Error checking assessment status", e)
            }
        }
    }

    // ── Session control ───────────────────────────────────────────────────────
    fun startSession(childState: ChildState, lang: String) {
        if (_ui.value.phase != SessionPhase.IDLE) return
        _ui.update { it.copy(phase = SessionPhase.STARTING, lang = lang, messages = emptyList(), error = null) }
        history.clear()
        sessionId = UUID.randomUUID().toString()
        startTime = Instant.now()

        activeJob = viewModelScope.launch {
            try {
                // 1. Fetch child profile
                var childAge = "~12"
                var childClass = childState.classNum.ifBlank { "7" }
                try {
                    val pDoc = db.collection("kids").document(childState.name).get().await()
                    if (pDoc.exists()) {
                        childAge = pDoc.getString("age") ?: "~12"
                        childClass = pDoc.getString("class") ?: pDoc.getString("classNum") ?: childClass
                    }
                } catch (e: Exception) {
                    Log.w("TutorVM", "Profile fetch failed", e)
                }

                // 2. Fetch parent settings
                var geminiModel = "gemini-3.5-flash"
                var hiModel: String? = null
                var paModel: String? = null
                var globalTemperature = 0.7
                var slowSpeechMode = false
                var sessionDurationMin = 30
                var punjabiBonusEnabled = true

                try {
                    val sDoc = db.collection("kids").document(childState.name)
                        .collection("config").document("settings").get().await()
                    if (sDoc.exists()) {
                        geminiModel = sDoc.getString("geminiModel") ?: "gemini-3.5-flash"
                        hiModel = sDoc.getString("hiModel")
                        paModel = sDoc.getString("paModel")
                        globalTemperature = sDoc.getDouble("globalTemperature") ?: 0.7
                        slowSpeechMode = sDoc.getBoolean("slowSpeechMode") ?: false
                        sessionDurationMin = sDoc.getLong("sessionDurationMin")?.toInt() ?: 30
                        punjabiBonusEnabled = sDoc.getBoolean("punjabiBonusEnabled") ?: true

                        val ttsMap = sDoc.get("ttsProvider") as? Map<*, *>
                        ttsProvider = ttsMap?.get("tutorSession") as? String

                        val sttMap = sDoc.get("sttProvider") as? Map<*, *>
                        sttProvider = sttMap?.get("tutorSession") as? String
                    }
                } catch (e: Exception) {
                    Log.w("TutorVM", "Settings fetch failed", e)
                }

                activeModel = when (lang.lowercase()) {
                    "hi" -> hiModel ?: geminiModel
                    "pa" -> paModel ?: geminiModel
                    else -> geminiModel
                }
                activeTemp = globalTemperature

                // 3. Load lang levels and expertise
                var enLevel = 5
                var hiLevel = 5
                var paLevel = 5
                try {
                    val lDoc = db.collection("kids").document(childState.name)
                        .collection("config").document("langLevels").get().await()
                    if (lDoc.exists()) {
                        enLevel = lDoc.getLong("en")?.toInt() ?: 5
                        hiLevel = lDoc.getLong("hi")?.toInt() ?: 5
                        paLevel = lDoc.getLong("pa")?.toInt() ?: 5
                    }
                } catch (_: Exception) {}

                var enExp = ""
                var hiExp = ""
                var paExp = ""
                try {
                    val eDoc = db.collection("kids").document(childState.name)
                        .collection("config").document("langExpertise").get().await()
                    if (eDoc.exists()) {
                        enExp = eDoc.getString("en") ?: ""
                        hiExp = eDoc.getString("hi") ?: ""
                        paExp = eDoc.getString("pa") ?: ""
                    }
                } catch (_: Exception) {}

                // 4. Fetch system prompt from Gist
                val gistFilename = when (lang.lowercase()) {
                    "hi" -> "aarav_tutor_system_prompt_hi.json"
                    "pa" -> "aarav_tutor_system_prompt_pa.json"
                    else -> "aarav_tutor_system_prompt_en.json"
                }

                val gistContent = try {
                    placementRepo.getGistFile(gistFilename)
                } catch (e: Exception) {
                    Log.w("TutorVM", "Gist prompt fetch failed, using fallback", e)
                    null
                }

                val systemPromptTemplate = if (gistContent != null) {
                    val jsonObj = JSONObject(gistContent)
                    val pArray = jsonObj.optJSONArray("prompt")
                    if (pArray != null) {
                        (0 until pArray.length()).map { pArray.getString(it) }.joinToString("\n")
                    } else {
                        jsonObj.optString("prompt", "")
                    }
                } else {
                    buildSystemPrompt(childState, lang)
                }

                // Resolve placeholders
                resolvedSystemPrompt = systemPromptTemplate
                    .replace("{{child_name}}", childState.name)
                    .replace("{{child_age}}", childAge)
                    .replace("{{child_class}}", childClass)
                    .replace("{{en_level}}", enLevel.toString())
                    .replace("{{hi_level}}", hiLevel.toString())
                    .replace("{{pa_level}}", paLevel.toString())
                    .replace("{{en_expertise}}", enExp)
                    .replace("{{hi_expertise}}", hiExp)
                    .replace("{{pa_expertise}}", paExp)
                    .replace("{{slow_speech}}", if (slowSpeechMode) "yes" else "no")
                    .replace("{{session_duration_min}}", sessionDurationMin.toString())
                    .replace("{{punjabi_bonus}}", if (punjabiBonusEnabled) "yes" else "no")
                    .replace("{{streak}}", "0")
                    .replace("{{total_sessions}}", "0")
                    .replace("{{last_mood}}", "")
                    .replace("{{recent_moods}}", "")
                    .replace("{{hard_session_count}}", "0")
                    .replace("{{difficulty}}", "5")

                botTurn(resolvedSystemPrompt, lang)
            } catch (e: Exception) {
                Log.e("TutorVM", "startSession error", e)
                _ui.update { it.copy(phase = SessionPhase.WAITING_CHILD, error = e.message) }
            }
        }
    }

    fun endSession(childState: ChildState) {
        activeJob?.cancel()
        ttsService.stop()
        recorder.requestStop()
        val durMin = (Instant.now().epochSecond - startTime.epochSecond) / 60.0
        viewModelScope.launch {
            runCatching {
                sessionRepo.saveSession(
                    childState.name,
                    com.krishanagarwal.mynoo.data.repository.SessionRecord(
                        id          = sessionId,
                        date        = startTime.toString(),
                        endDate     = Instant.now().toString(),
                        durationMin = durMin,
                        lang        = _ui.value.lang,
                    )
                )
            }
        }
        _ui.update { it.copy(phase = SessionPhase.IDLE) }
    }

    fun pressMic() {
        if (_ui.value.phase != SessionPhase.WAITING_CHILD) return
        if (!_ui.value.hasMicPerm) { _ui.update { it.copy(error = "Microphone permission needed") }; return }
        activeJob?.cancel()
        activeJob = viewModelScope.launch { childTurn() }
    }

    fun stopMicAndSend() {
        recorder.requestSend()
    }

    fun sendQuickReply(text: String) {
        if (_ui.value.phase != SessionPhase.WAITING_CHILD) return
        appendMessage(ChatMessage(role = "child", text = text))
        history.add(GeminiContent(role = "user", parts = listOf(GeminiPart(text = text))))
        activeJob?.cancel()
        activeJob = viewModelScope.launch {
            botTurn(null, _ui.value.lang)
        }
    }

    // ── Internal turn handling ────────────────────────────────────────────────

    private suspend fun botTurn(systemPrompt: String?, lang: String) {
        _ui.update { it.copy(phase = SessionPhase.BOT_SPEAKING) }
        try {
            val system = systemPrompt ?: resolvedSystemPrompt
            val botText = when {
                activeModel.startsWith("grok-") -> {
                    val messages = convertHistoryToLlmMessages()
                    val request = LlmResponseRequest(
                        model = activeModel,
                        input = messages,
                        instructions = system,
                        temperature = activeTemp,
                        maxOutputTokens = 1024
                    )
                    val res = xaiApi.createResponse("Bearer ${BuildConfig.XAI_API_KEY}", request)
                    extractTextFromLlmResponse(res)
                }
                activeModel.startsWith("gpt-") -> {
                    val messages = convertHistoryToLlmMessages()
                    val request = LlmResponseRequest(
                        model = activeModel,
                        input = messages,
                        instructions = system,
                        temperature = activeTemp,
                        maxOutputTokens = 1024
                    )
                    val res = openAiApi.createResponse("Bearer ${BuildConfig.OPENAI_API_KEY}", request)
                    extractTextFromLlmResponse(res)
                }
                activeModel.startsWith("sarvam-") -> {
                    val messages = listOf(SarvamChatMessage("system", system)) + convertHistoryToSarvamMessages()
                    val request = SarvamChatRequest(
                        model = activeModel,
                        messages = messages,
                        temperature = activeTemp,
                        maxTokens = 1024
                    )
                    val res = sarvamChatApi.chatCompletions(BuildConfig.SARVAM_API_KEY, request)
                    res.choices?.firstOrNull()?.message?.content ?: ""
                }
                else -> {
                    val request = GeminiRequest(
                        systemInstruction = system.let {
                            GeminiContent(parts = listOf(GeminiPart(text = it)))
                        },
                        contents = history.toList(),
                        generationConfig = GeminiGenConfig(temperature = activeTemp),
                    )
                    val response = geminiApi.generateContent(activeModel, BuildConfig.GEMINI_API_KEY, request)
                    response.candidates
                        ?.firstOrNull()?.content?.parts?.firstOrNull()?.text?.trim()
                        ?: "I'm having trouble thinking right now. Can you try again?"
                }
            }

            val cleanText = botText
                .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()

            val (speech, replies) = parseBotResponse(cleanText)

            history.add(GeminiContent(role = "model", parts = listOf(GeminiPart(text = speech))))
            appendMessage(ChatMessage(role = "bot", text = speech))
            _ui.update { it.copy(quickReplies = replies) }

            ttsService.speak(speech, lang = lang, provider = ttsProvider)
            _ui.update { it.copy(phase = SessionPhase.WAITING_CHILD) }

        } catch (e: Exception) {
            Log.e("TutorVM", "botTurn error", e)
            _ui.update { it.copy(phase = SessionPhase.WAITING_CHILD, error = e.message) }
        }
    }

    private suspend fun childTurn() {
        _ui.update { it.copy(phase = SessionPhase.RECORDING, error = null) }
        try {
            val result = recorder.record()
            _ui.update { it.copy(phase = SessionPhase.PROCESSING) }

            val langCode = when (_ui.value.lang) {
                "hi" -> "hi-IN"; "pa" -> "pa-IN"; else -> "en-IN"
            }

            val transcript = if (sttProvider == "google") {
                val text = callGoogleStt(result.wavFile, langCode)
                result.wavFile.delete()
                text
            } else {
                val filePart = MultipartBody.Part.createFormData(
                    "file", result.wavFile.name,
                    result.wavFile.asRequestBody("audio/wav".toMediaType()),
                )
                val sttResponse = sarvamApi.transcribe(
                    file         = filePart,
                    model        = "saarika:v2.5".toRequestBody("text/plain".toMediaType()),
                    languageCode = langCode.toRequestBody("text/plain".toMediaType()),
                    apiKey       = BuildConfig.SARVAM_API_KEY,
                )
                result.wavFile.delete()
                sttResponse.transcript?.trim().orEmpty()
            }

            if (transcript.isNotBlank()) {
                appendMessage(ChatMessage(role = "child", text = transcript))
                history.add(GeminiContent(role = "user", parts = listOf(GeminiPart(text = transcript))))
                botTurn(null, _ui.value.lang)
            } else {
                _ui.update { it.copy(phase = SessionPhase.WAITING_CHILD, error = "Didn't catch that — try again") }
            }
        } catch (e: Exception) {
            if (e.message == "aborted") {
                _ui.update { it.copy(phase = SessionPhase.WAITING_CHILD) }
            } else {
                Log.e("TutorVM", "childTurn error", e)
                _ui.update { it.copy(phase = SessionPhase.WAITING_CHILD, error = e.message) }
            }
        }
    }

    private fun appendMessage(msg: ChatMessage) {
        _ui.update { it.copy(messages = it.messages + msg) }
    }

    private fun parseBotResponse(text: String): Pair<String, List<String>> {
        return try {
            val gson = com.google.gson.Gson()
            val obj  = gson.fromJson(text, com.google.gson.JsonObject::class.java)
            val speech  = obj.get("speech")?.asString ?: text
            val replies = obj.getAsJsonArray("quickReplies")
                ?.mapNotNull { it.asString } ?: emptyList()
            Pair(speech, replies)
        } catch (_: Exception) {
            Pair(text, emptyList())
        }
    }

    private fun buildSystemPrompt(child: ChildState, lang: String): String {
        val langName = when (lang) { "hi" -> "Hindi"; "pa" -> "Punjabi"; else -> "English" }
        val langInstr = when (lang) {
            "hi" -> "Speak primarily in Hindi (Devanagari script). Use simple, encouraging language."
            "pa" -> "Speak primarily in Punjabi (Gurmukhi script). Use simple, encouraging language."
            else -> "Speak in English. Use simple, clear sentences appropriate for a student."
        }
        return """
You are Mynoo, a warm and encouraging AI tutor for ${child.name}, a Class ${child.classNum.ifBlank { "school" }} student.
Your primary language for this session is $langName.
$langInstr

Guidelines:
• Keep responses concise — 2-4 sentences for casual chat, longer for explanations.
• Ask one question at a time. Wait for the child to respond.
• Be encouraging, patient, and age-appropriate.
• If asked about a subject, explain it simply with a real-world example.
• Optionally suggest 2-3 short quick reply options the child can say.

Response format (JSON):
{
  "speech": "Your spoken response here",
  "quickReplies": ["Option 1", "Option 2", "Option 3"]
}

Start by greeting ${child.name} warmly and asking what they'd like to learn or talk about today.
        """.trimIndent()
    }

    private fun convertHistoryToLlmMessages(): List<LlmMessage> {
        val result = mutableListOf<LlmMessage>()
        for (item in history) {
            val role = when (item.role) {
                "user" -> "user"
                "model", "assistant" -> "assistant"
                else -> "system"
            }
            val text = item.parts?.firstOrNull()?.text ?: ""
            result.add(LlmMessage(role, text))
        }
        return result
    }

    private fun convertHistoryToSarvamMessages(): List<SarvamChatMessage> {
        val result = mutableListOf<SarvamChatMessage>()
        for (item in history) {
            val role = when (item.role) {
                "user" -> "user"
                "model", "assistant" -> "assistant"
                else -> "system"
            }
            val text = item.parts?.firstOrNull()?.text ?: ""
            result.add(SarvamChatMessage(role, text))
        }
        return result
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

    private suspend fun callGoogleStt(audioFile: File, langCode: String): String =
        withContext(Dispatchers.IO) {
            val bytes = audioFile.readBytes()
            val pcmBytes = if (bytes.size > 44) bytes.copyOfRange(44, bytes.size) else bytes
            val base64Audio = Base64.encodeToString(pcmBytes, Base64.NO_WRAP)
            
            val body = JSONObject().apply {
                put("config", JSONObject().apply {
                    put("encoding", "LINEAR16")
                    put("sampleRateHertz", 16000)
                    put("languageCode", langCode)
                    put("alternativeLanguageCodes", JSONArray().put("hi-IN").put("pa-IN"))
                })
                put("audio", JSONObject().apply {
                    put("content", base64Audio)
                })
            }.toString()
            
            val req = Request.Builder()
                .url("https://speech.googleapis.com/v1/speech:recognize?key=${BuildConfig.GEMINI_API_KEY}")
                .addHeader("Content-Type", "application/json")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
                
            val client = OkHttpClient()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) throw Exception("Google STT error ${resp.code}: ${resp.body?.string()}")
            val respBody = resp.body?.string() ?: ""
            val json = JSONObject(respBody)
            val results = json.optJSONArray("results")
            if (results == null || results.length() == 0) return@withContext ""
            results.getJSONObject(0)
                .getJSONArray("alternatives").getJSONObject(0)
                .optString("transcript", "").trim()
        }

    override fun onCleared() {
        super.onCleared()
        ttsService.stop()
        recorder.requestStop()
    }
}
