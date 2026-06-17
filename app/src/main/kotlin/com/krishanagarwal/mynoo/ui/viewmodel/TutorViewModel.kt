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
import com.krishanagarwal.mynoo.data.model.ReasoningMapper
import com.krishanagarwal.mynoo.data.api.GeminiThinkingConfig
import com.krishanagarwal.mynoo.data.repository.GlobalSettingsRepository
import com.krishanagarwal.mynoo.data.repository.SessionRepository
import com.krishanagarwal.mynoo.data.repository.PlacementRepository
import com.krishanagarwal.mynoo.data.repository.UsageRepository
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
    val id:        String = UUID.randomUUID().toString(),
    val role:      String,   // "child" | "bot"
    val text:      String,
    val propType:  String? = null, // "fact" | "current_affairs" | "exercise"
    val propTitle: String? = null,
    val propText:  String? = null,
)

data class TutorUiState(
    val phase:        SessionPhase  = SessionPhase.IDLE,
    val messages:     List<ChatMessage> = emptyList(),
    val lang:         String        = "en",
    val error:        String?       = null,
    val quickReplies: List<String>  = emptyList(),
    val hasMicPerm:   Boolean       = false,
    val isAssessed:   Boolean       = true,
    val sessionsToday: Int          = 0,
    val skippedDays:   Int          = 0,
    val streak:        Int          = 0,
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
    private val openAiApi:         OpenAiApi,
    private val xaiApi:            XaiApi,
    private val sarvamChatApi:     SarvamChatApi,
    private val globalSettingsRepo: GlobalSettingsRepository,
    private val usageRepo:          UsageRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(TutorUiState())
    val ui: StateFlow<TutorUiState> = _ui

    private val history   = mutableListOf<GeminiContent>()
    private var sessionId = ""
    private var startTime = Instant.now()
    private var activeJob: Job? = null
    private var activeChildState: ChildState? = null
    private var hasPromptedForDurationGoal = false

    private var activeModel        = "gemini-3.5-flash"
    private var activeTemp          = 0.7
    private var activeReasoningLevel = 0
    private var ttsProvider: String? = null
    private var ttsModel: String?    = null
    private var sttProvider: String? = null
    private var sttModel: String?    = null
    private var resolvedSystemPrompt = ""

    // ── Mic permission ────────────────────────────────────────────────────────
    fun onMicPermResult(granted: Boolean) {
        _ui.update { it.copy(hasMicPerm = granted) }
    }

    fun loadChildTutorData(childName: String) {
        if (childName.isBlank()) return
        viewModelScope.launch {
            try {
                val assessed = placementRepo.hasBeenAssessed(childName)
                val sessions = sessionRepo.getSessions(childName)
                val today = java.time.LocalDate.now(java.time.ZoneId.systemDefault())
                
                val sessionsToday = sessions.count {
                    try {
                        val sessionDate = java.time.Instant.parse(it.date)
                            .atZone(java.time.ZoneId.systemDefault())
                            .toLocalDate()
                        sessionDate.isEqual(today)
                    } catch (_: Exception) { false }
                }

                val lastSessionLocalDate = sessions.firstOrNull {
                    try {
                        val sessionDate = java.time.Instant.parse(it.date)
                            .atZone(java.time.ZoneId.systemDefault())
                            .toLocalDate()
                        sessionDate.isBefore(today)
                    } catch (_: Exception) { false }
                }?.let {
                    try {
                        java.time.Instant.parse(it.date)
                            .atZone(java.time.ZoneId.systemDefault())
                            .toLocalDate()
                    } catch (_: Exception) { null }
                }

                val skippedDays = if (lastSessionLocalDate != null) {
                    val diff = java.time.temporal.ChronoUnit.DAYS.between(lastSessionLocalDate, today)
                    (diff - 1).toInt().coerceAtLeast(0)
                } else {
                    0
                }

                val streak = sessionRepo.getStreak(childName)

                _ui.update { it.copy(
                    isAssessed = assessed,
                    sessionsToday = sessionsToday,
                    skippedDays = skippedDays,
                    streak = streak
                ) }
            } catch (e: Exception) {
                Log.e("TutorVM", "Error loading child tutor data", e)
            }
        }
    }

    // ── Session control ───────────────────────────────────────────────────────
    fun startSession(childState: ChildState) {
        if (_ui.value.phase != SessionPhase.IDLE) return
        activeChildState = childState
        hasPromptedForDurationGoal = false
        val lang = "en"
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

                // 2. Fetch parent settings (behavioral flags from per-child; AI model from global)
                var slowSpeechMode    = false
                var sessionDurationMin = 30
                var punjabiBonusEnabled = true

                try {
                    val sDoc = db.collection("kids").document(childState.name)
                        .collection("config").document("settings").get().await()
                    if (sDoc.exists()) {
                        slowSpeechMode     = sDoc.getBoolean("slowSpeechMode") ?: false
                        sessionDurationMin  = sDoc.getLong("sessionDurationMin")?.toInt() ?: 30
                        punjabiBonusEnabled = sDoc.getBoolean("punjabiBonusEnabled") ?: true
                    }
                } catch (e: Exception) {
                    Log.w("TutorVM", "Per-child settings fetch failed", e)
                }

                // AI model, TTS, STT from global settings
                try {
                    val globalSettings = globalSettingsRepo.load()
                    val langConfig     = globalSettings.resolve("talk", lang)
                    activeModel         = langConfig.llm.model
                    activeTemp          = langConfig.llm.temperature
                    activeReasoningLevel = langConfig.llm.reasoningLevel
                    ttsProvider         = langConfig.tts.provider
                    ttsModel            = langConfig.tts.model
                    sttProvider         = langConfig.stt.provider
                    sttModel            = langConfig.stt.model
                } catch (e: Exception) {
                    Log.w("TutorVM", "Global AI settings fetch failed", e)
                }

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

                // Load previous session plan
                var nextSessionPlan: String? = null
                try {
                    nextSessionPlan = sessionRepo.getNextSessionPlan(childState.name)
                } catch (e: Exception) {
                    Log.w("TutorVM", "Next session plan fetch failed", e)
                }

                resolvedSystemPrompt = buildSystemPrompt(
                    child = childState,
                    childAge = childAge,
                    childClass = childClass,
                    enLevel = enLevel,
                    hiLevel = hiLevel,
                    paLevel = paLevel,
                    enExp = enExp,
                    hiExp = hiExp,
                    paExp = paExp,
                    slowSpeechMode = slowSpeechMode,
                    sessionDurationMin = sessionDurationMin,
                    punjabiBonusEnabled = punjabiBonusEnabled,
                    nextSessionPlan = nextSessionPlan,
                    sessionsTodayCount = _ui.value.sessionsToday,
                    skippedDaysCount = _ui.value.skippedDays
                )

                botTurn(resolvedSystemPrompt, lang)
            } catch (e: Exception) {
                Log.e("TutorVM", "startSession error", e)
                _ui.update { it.copy(phase = SessionPhase.IDLE, error = getFullErrorDescription(e)) }
            }
        }
    }

    fun endSession(childState: ChildState) {
        activeJob?.cancel()
        ttsService.stop()
        recorder.requestStop()
        val durMin = (Instant.now().epochSecond - startTime.epochSecond) / 60.0
        val childMessageCount = history.count { it.role == "user" }
        val shouldSave = durMin >= 5.0 && childMessageCount >= 5

        viewModelScope.launch {
            if (shouldSave) {
                runCatching {
                    sessionRepo.saveSession(
                        childState.name,
                        com.krishanagarwal.mynoo.data.repository.SessionRecord(
                            id          = sessionId,
                            date        = startTime.toString(),
                            endDate     = Instant.now().toString(),
                            durationMin = durMin,
                            lang        = _ui.value.lang,
                            transcript  = _ui.value.messages.map { msg ->
                                mapOf(
                                    "role" to msg.role,
                                    "text" to msg.text
                                ) + (if (msg.propType != null) mapOf("propType" to msg.propType) else emptyMap()) +
                                    (if (msg.propTitle != null) mapOf("propTitle" to msg.propTitle) else emptyMap()) +
                                    (if (msg.propText != null) mapOf("propText" to msg.propText) else emptyMap())
                            }
                        )
                    )
                }
                generateAndSaveNextSessionPlan(childState)
            } else {
                Log.i("TutorVM", "Session not saved for baseline: duration=${String.format("%.2f", durMin)}m, messages=$childMessageCount")
            }
            loadChildTutorData(childState.name)
        }
        _ui.update { it.copy(phase = SessionPhase.IDLE) }
    }

    fun onUserAttemptEnd() {
        if (_ui.value.phase == SessionPhase.IDLE) return
        val elapsedSec = Instant.now().epochSecond - startTime.epochSecond
        val elapsedMin = elapsedSec / 60.0

        if (elapsedMin < 30.0 && !hasPromptedForDurationGoal) {
            hasPromptedForDurationGoal = true
            val minLeft = 30 - elapsedMin.toInt()
            val warnMsg = "We've only practiced for ${elapsedMin.toInt()} minutes! Let's try to practice for $minLeft more minutes to reach our 30-minute goal. Do you really want to end?"
            appendMessage(ChatMessage(role = "bot", text = warnMsg))
            _ui.update { it.copy(quickReplies = listOf("No, let's talk!", "Yes, end session")) }
            viewModelScope.launch {
                ttsService.speak(warnMsg, lang = _ui.value.lang, provider = ttsProvider, model = ttsModel, childName = activeChildState?.name, purpose = "talk")
            }
        } else {
            activeChildState?.let { endSession(it) }
        }
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

    fun cancelMic() {
        if (_ui.value.phase != SessionPhase.RECORDING) return
        activeJob?.cancel()
        recorder.requestStop()
        _ui.update { it.copy(phase = SessionPhase.WAITING_CHILD, error = null) }
    }

    fun sendQuickReply(text: String) {
        if (_ui.value.phase != SessionPhase.WAITING_CHILD) return
        appendMessage(ChatMessage(role = "child", text = text))
        history.add(GeminiContent(role = "user", parts = listOf(GeminiPart(text = text))))
        activeJob?.cancel()
        if (text == "Yes, end session") {
            activeChildState?.let { endSession(it) }
            return
        }
        activeJob = viewModelScope.launch {
            botTurn(null, _ui.value.lang)
        }
    }

    // ── Internal turn handling ────────────────────────────────────────────────

    private suspend fun botTurn(systemPrompt: String?, lang: String) {
        _ui.update { it.copy(phase = SessionPhase.BOT_SPEAKING) }
        try {
            val system = systemPrompt ?: resolvedSystemPrompt
            var resObj: Any? = null
            val botText = when {
                activeModel.startsWith("grok-") -> {
                    val messages = if (history.isEmpty()) listOf(LlmMessage("user", "Hello!")) else convertHistoryToLlmMessages()
                    val request = LlmResponseRequest(
                        model           = activeModel,
                        input           = messages,
                        instructions    = system,
                        temperature     = null, // Grok does not support temperature
                        maxOutputTokens = 1024,
                        reasoning       = ReasoningMapper.toReasoningEffortOrNull(activeReasoningLevel)
                            ?.let { LlmReasoning(it) },
                    )
                    val res = xaiApi.createResponse("Bearer ${BuildConfig.XAI_API_KEY}", request)
                    resObj = res
                    extractTextFromLlmResponse(res)
                }
                activeModel.startsWith("gpt-") -> {
                    val messages = if (history.isEmpty()) listOf(LlmMessage("user", "Hello!")) else convertHistoryToLlmMessages()
                    val request = LlmResponseRequest(
                        model           = activeModel,
                        input           = messages,
                        instructions    = system,
                        temperature     = activeTemp,
                        maxOutputTokens = 1024,
                        reasoning       = ReasoningMapper.toReasoningEffortOrNull(activeReasoningLevel)
                            ?.let { LlmReasoning(it) },
                    )
                    val res = openAiApi.createResponse("Bearer ${BuildConfig.OPENAI_API_KEY}", request)
                    resObj = res
                    extractTextFromLlmResponse(res)
                }
                activeModel.startsWith("sarvam-") -> {
                    val messages = listOf(SarvamChatMessage("system", system)) + 
                        if (history.isEmpty()) listOf(SarvamChatMessage("user", "Hello!")) else convertHistoryToSarvamMessages()
                    val request = SarvamChatRequest(
                        model       = activeModel,
                        messages    = messages,
                        temperature = activeTemp,
                        maxTokens   = 1024,
                    )
                    val res = sarvamChatApi.chatCompletions(BuildConfig.SARVAM_API_KEY, request)
                    resObj = res
                    res.choices?.firstOrNull()?.message?.content ?: ""
                }
                else -> {
                    val thinkingBudget = ReasoningMapper.toGeminiThinkingBudget(activeReasoningLevel)
                    val request = GeminiRequest(
                        systemInstruction = GeminiContent(parts = listOf(GeminiPart(text = system))),
                        contents          = if (history.isEmpty()) listOf(GeminiContent(role = "user", parts = listOf(GeminiPart(text = "Hello!")))) else history.toList(),
                        generationConfig  = GeminiGenConfig(
                            temperature   = activeTemp,
                            responseMimeType = "application/json",
                            thinkingConfig = if (thinkingBudget > 0)
                                GeminiThinkingConfig(thinkingBudget) else null,
                        ),
                    )
                    val response = geminiApi.generateContent(activeModel, BuildConfig.GEMINI_API_KEY, request)
                    resObj = response
                    response.candidates
                        ?.firstOrNull()?.content?.parts?.firstOrNull()?.text?.trim()
                        ?: "I'm having trouble thinking right now. Can you try again?"
                }
            }

            try {
                activeChildState?.name?.let { name ->
                    if (name.isNotBlank() && resObj != null) {
                        var inputTokens = 0
                        var outputTokens = 0
                        var cachedTokens = 0
                        when (resObj) {
                            is LlmResponseResponse -> {
                                inputTokens = resObj.usage?.inputTokens ?: 0
                                outputTokens = resObj.usage?.outputTokens ?: 0
                                cachedTokens = resObj.usage?.details?.cachedTokens ?: 0
                            }
                            is SarvamChatResponse -> {
                                inputTokens = resObj.usage?.promptTokens ?: 0
                                outputTokens = resObj.usage?.completionTokens ?: 0
                            }
                            is GeminiResponse -> {
                                inputTokens = resObj.usageMetadata?.promptTokenCount ?: 0
                                outputTokens = resObj.usageMetadata?.candidatesTokenCount ?: 0
                                cachedTokens = resObj.usageMetadata?.cachedContentTokenCount ?: 0
                            }
                        }
                        usageRepo.recordLlm(
                            childName = name,
                            purpose = "talk",
                            provider = activeModel,
                            language = lang,
                            inputTokens = inputTokens,
                            outputTokens = outputTokens,
                            cachedInputTokens = cachedTokens
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("TutorVM", "Error recording LLM usage in botTurn", e)
            }

            val cleanText = botText
                .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()

            val parsed = parseBotResponse(cleanText)

            history.add(GeminiContent(role = "model", parts = listOf(GeminiPart(text = parsed.speech))))
            appendMessage(
                ChatMessage(
                    role = "bot",
                    text = parsed.speech,
                    propType = parsed.propType,
                    propTitle = parsed.propTitle,
                    propText = parsed.propText
                )
            )
            _ui.update { it.copy(quickReplies = parsed.quickReplies) }

            val textToSpeak = if (!parsed.propText.isNullOrBlank()) {
                val cleanTitle = parsed.propTitle?.replace(Regex("[\\p{So}\\p{Cn}]"), "")?.trim().orEmpty()
                if (cleanTitle.isNotEmpty()) {
                    "${parsed.speech}. $cleanTitle. ${parsed.propText}"
                } else {
                    "${parsed.speech}. ${parsed.propText}"
                }
            } else {
                parsed.speech
            }
            ttsService.speak(textToSpeak, lang = lang, provider = ttsProvider, model = ttsModel, childName = activeChildState?.name, purpose = "talk")
            
            if (parsed.requestEnd) {
                activeChildState?.let { endSession(it) }
            } else {
                _ui.update { it.copy(phase = SessionPhase.WAITING_CHILD) }
            }

        } catch (e: Exception) {
            Log.e("TutorVM", "botTurn error", e)
            _ui.update { it.copy(phase = SessionPhase.WAITING_CHILD, error = getFullErrorDescription(e)) }
        }
    }

    private suspend fun childTurn() {
        _ui.update { it.copy(phase = SessionPhase.RECORDING, error = null) }
        try {
            val result = recorder.record()
            _ui.update { it.copy(phase = SessionPhase.PROCESSING) }

            val resolvedSttProvider = when (sttProvider) {
                "google" -> "google-speech-to-text"
                "elevenlabs" -> sttModel ?: "scribe_v2"
                "openai" -> sttModel ?: "gpt-4o-mini-transcribe"
                else -> sttModel ?: "saaras:v3"
            }
            try {
                activeChildState?.name?.let { name ->
                    if (name.isNotBlank()) {
                        usageRepo.recordStt(
                            childName = name,
                            purpose = "talk",
                            provider = resolvedSttProvider,
                            durationMs = result.durationMs
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("TutorVM", "Error recording STT usage in childTurn", e)
            }

            val langCode = when (_ui.value.lang) {
                "hi" -> "hi-IN"; "pa" -> "pa-IN"; else -> "en-IN"
            }

            val transcript = when (sttProvider) {
                "google" -> {
                    val text = callGoogleStt(result.wavFile, langCode)
                    result.wavFile.delete()
                    text
                }
                "elevenlabs" -> {
                    // ElevenLabs Speech-to-Text (scribe_v2)
                    callElevenLabsStt(result.wavFile, sttModel ?: "scribe_v2", langCode).also {
                        result.wavFile.delete()
                    }
                }
                "openai" -> {
                    // OpenAI audio transcription (gpt-4o-transcribe / gpt-4o-mini-transcribe)
                    callOpenAiStt(result.wavFile, sttModel ?: "gpt-4o-mini-transcribe", langCode).also {
                        result.wavFile.delete()
                    }
                }
                else -> {
                    // Default: Sarvam STT — Google STT requires a separate Speech API key
                    val filePart = MultipartBody.Part.createFormData(
                        "file", result.wavFile.name,
                        result.wavFile.asRequestBody("audio/wav".toMediaType()),
                    )
                    val sttResponse = sarvamApi.transcribe(
                        file         = filePart,
                        model        = (sttModel ?: "saaras:v3").toRequestBody("text/plain".toMediaType()),
                        languageCode = langCode.toRequestBody("text/plain".toMediaType()),
                        apiKey       = BuildConfig.SARVAM_API_KEY,
                    )
                    result.wavFile.delete()
                    sttResponse.transcript?.trim().orEmpty()
                }
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
                _ui.update { it.copy(phase = SessionPhase.WAITING_CHILD, error = getFullErrorDescription(e)) }
            }
        }
    }

    private fun appendMessage(msg: ChatMessage) {
        _ui.update { it.copy(messages = it.messages + msg) }
    }

    data class BotParsedResponse(
        val speech: String,
        val quickReplies: List<String>,
        val requestEnd: Boolean = false,
        val propType:  String? = null,
        val propTitle: String? = null,
        val propText:  String? = null,
    )

    private fun parseBotResponse(text: String): BotParsedResponse {
        return try {
            val firstBrace = text.indexOf('{')
            val lastBrace = text.lastIndexOf('}')
            val jsonText = if (firstBrace != -1 && lastBrace != -1 && lastBrace > firstBrace) {
                text.substring(firstBrace, lastBrace + 1)
            } else {
                text
            }

            val gson = com.google.gson.Gson()
            val obj  = gson.fromJson(jsonText, com.google.gson.JsonObject::class.java)
            val speech  = obj.get("speech")?.asString ?: text
            val replies = obj.getAsJsonArray("quickReplies")
                ?.mapNotNull { it.asString } ?: emptyList()
            val requestEnd = obj.get("requestEnd")?.asBoolean ?: false

            val propObj = if (obj.has("prop") && obj.get("prop")?.isJsonObject == true) {
                obj.getAsJsonObject("prop")
            } else {
                null
            }
            val propType = propObj?.get("type")?.asString ?: "fact"
            val propTitle = propObj?.get("title")?.asString
            val propText = propObj?.get("text")?.asString ?: propObj?.get("description")?.asString

            BotParsedResponse(speech, replies, requestEnd, propType, propTitle, propText)
        } catch (e: Exception) {
            Log.w("TutorVM", "JSON parsing failed, fallback used", e)
            BotParsedResponse(text, emptyList(), false, null, null, null)
        }
    }

    private fun buildSystemPrompt(
        child: ChildState,
        childAge: String,
        childClass: String,
        enLevel: Int,
        hiLevel: Int,
        paLevel: Int,
        enExp: String,
        hiExp: String,
        paExp: String,
        slowSpeechMode: Boolean,
        sessionDurationMin: Int,
        punjabiBonusEnabled: Boolean,
        nextSessionPlan: String?,
        sessionsTodayCount: Int,
        skippedDaysCount: Int
    ): String {
        val planSnippet = if (!nextSessionPlan.isNullOrBlank()) {
            "\nFocus areas and progress tracking from the child's PREVIOUS session:\n$nextSessionPlan\n"
        } else ""

        return """
You are Mynoo, a warm, encouraging, and friendly AI tutor for ${child.name}, a Class $childClass student (age: $childAge).
Your goal is to guide them through conversation in an interactive, educational, and fun way.

Current Language Levels:
- English Level: $enLevel/10 (Expertise: $enExp)
- Hindi Level: $hiLevel/10 (Expertise: $hiExp)
- Punjabi Level: $paLevel/10 (Expertise: $paExp)

Tutor Personality:
- Friendly, positive, patient, and uses kid-friendly explanations.
- Speak in simple, clear, age-appropriate sentences.
- Speak at a moderate/slow speed (Slow Speech Mode: ${if (slowSpeechMode) "ON" else "OFF"}).
- Daily Engagement Habit:
  * Sessions completed today: $sessionsTodayCount.
  * Days skipped since last session: $skippedDaysCount.
  * If the child has skipped 1 or more days ($skippedDaysCount > 0), you MUST mention at the very beginning of the session that you (Mynoo) missed them! Say something like "I missed you so much! You didn't come to talk to me yesterday. I'm so glad you are here today!"
  * Encourage the child to complete at least 3-4 sessions per day. Keep track of this: if they have completed $sessionsTodayCount sessions, encourage them to reach their goal of 3-4 sessions (e.g., if this is their first session, mention "Let's try to do 3-4 sessions today!", or if they completed 2, "You are doing great! Let's do 1 or 2 more sessions today!"). Celebrate their progress warmly!

Role and Objectives:
1. Primary Goal: Improve the child's speaking fluency across English, Hindi, and Punjabi, with a heavy emphasis on English grammar, vocabulary, and correct phrasing.
2. English Grammar Challenges & Exercises:
   - Actively challenge the child with grammatical aspects to help them improve their English grammar.
   - Regularly integrate simple, kid-friendly grammar challenges or exercises into the conversation. For example:
     * Fill-in-the-blank questions (e.g., "The bird is flying ___ (in/on) the sky. Which one is correct?").
     * "Find the mistake" questions (e.g., "Can you spot the mistake in: 'She do not like apples'?").
     * Verb tense choices (e.g., "Yesterday, we ___ (go) to the park. How do we say go in the past tense?").
     * Singular/plural challenges (e.g., "One mouse, two ___?").
3. Active Correction & Focusing on What the Child Says:
   - Pay extremely close attention to the child's spoken responses.
   - If they make any grammatical or vocabulary error, you MUST correct it gently, explain the correct usage simply, and challenge them to repeat the correct sentence.
   - Example style: "That's a great thought! By the way, instead of 'I goes', we say 'I go'. Can you try repeating: 'I go to school'?"
4. Hindi Usage Counter-measure:
   - Monitor the child's language use. If the child is speaking Hindi too many times (e.g., for multiple turns, or using it as their default response), gently, warmly, and encouragingly ask them to try speaking or translating their response into English.
   - Example style: "You are speaking Hindi so beautifully! But let's try to practice our English too. Can you try saying that in English for me?" or "That was awesome! Let's see if we can say that same thing in English. What do you think?"
5. Multilingual Script Rules (Devanagari and Gurmukhi):
   - **CRITICAL SCRIPT RULE**: When using Hindi, you MUST write exclusively in Devanagari script (e.g. "आप कैसे हो?"). NEVER write Hindi words/sentences using Roman characters (do NOT write Hinglish like "aap kaise ho").
   - **CRITICAL SCRIPT RULE**: When using Punjabi, you MUST write exclusively in Gurmukhi script (e.g. "ਤੁਸੀਂ ਕਿਵੇਂ ਹੋ?"). NEVER write Punjabi words/sentences using Roman characters (do NOT write Gurmish like "tusi kive ho").
   - Effort allocation: Allocate roughly 50% of your teaching/conversation focus to English grammar/vocabulary, roughly 30% to Hindi speaking fluency, and roughly 20% to Punjabi speaking fluency. Transition between these languages naturally. Start in English, but introduce bilingual translations and exercises.
4. Knowledge Expansion (GK & Current Affairs):
   - Actively integrate interesting general knowledge (GK) and age/class-appropriate current affairs topics into the conversation.
   - Instead of keeping the dialogue entirely fictitious, introduce real-world facts (e.g., space exploration, nature, animal science, historical landmarks, simple technology, and kid-friendly news events) that are true and educational.
   - **Tutor must begin the session with a brief friendly smalltalk (greeting, asking how they are, what they did today) to build a comfortable rapport.** Once the child responds to the smalltalk, smoothly transition into introducing an interesting, real-world general knowledge (GK) fact or kid-friendly current affairs topic.
5. Use Visual Props (UI Level Enhancements):
   - Visual props are displayed as beautiful cards in the chat UI, but they are NOT spoken out loud by the TTS engine.
   - **CRITICAL PROP RULE**: You must ONLY use the "prop" card to illustrate factual General Knowledge, current affairs news, or specific exercises/quizzes.
   - **CRITICAL PROP RULE**: NEVER use the "prop" card for greeting messages, welcome back greetings, or ordinary conversational statements.
   - If you are not presenting a GK fact, news, or quiz in this turn, you MUST omit the "prop" object or set it to null in your JSON response.
6. Session Completion: The target duration for each session is 30 minutes.
   - If the child says goodbye or states they want to end the session before the session is naturally done, gently ask them to stay and talk some more to reach the 30-minute target (e.g., "We still have some time left to reach our 30-minute goal! Let's talk a bit more.").
   - If the child insists on leaving (e.g. they ask to leave a second time), or if the session is naturally completed, politely say goodbye and set "requestEnd" to true in your JSON output.
7. Keep it engaging: Keep your spoken responses concise (2-4 sentences) so the child doesn't get overwhelmed.
8. Interactive flow & Topic Diversion Prevention:
   - Ask one question at a time. Keep the child talking!
   - **CRITICAL QUESTION RETENTION RULE**: Pay attention to the question you asked the child in your previous turn. If the child does not answer it, ignores it, or tries to divert the topic to something else, you MUST gently and playfully remind them that you asked them a question, restate/simplify the question, and guide them back to answering it before letting the topic change.
   - Example style: "I'd love to talk about that next! But tell me first, what about the question I asked? How would you answer that?" or "Oh, that sounds interesting! But wait, you didn't answer my question about [topic]! What do you think?"
9. Quick replies: Suggest 2-3 short, kid-friendly quick reply options that the child can tap to respond (e.g. "Yes!", "No, please explain", "What's next?").

$planSnippet

You MUST output ONLY a valid JSON object matching the format below. Do NOT write any conversational text, notes, markdown blocks, formatting, or reasoning outside of the JSON block. Start your response directly with '{' and end with '}'.

Response format (JSON):
{
  "speech": "Your response text (contains corrections, guidance, questions, translation in Devanagari/Gurmukhi, goodbye message, etc.)",
  "quickReplies": ["Short option 1", "Short option 2", "Short option 3"],
  "requestEnd": false,
  "prop": null // or a prop object if presenting a GK fact, news, or exercise.
}

Start by greeting ${child.name} warmly in English and asking what they'd like to talk about today!
        """.trimIndent()
    }

    private suspend fun generateAndSaveNextSessionPlan(childState: ChildState) {
        if (history.isEmpty()) return
        try {
            // Load previous progress / plan
            val previousPlan = sessionRepo.getNextSessionPlan(childState.name)
            val previousPlanSection = if (!previousPlan.isNullOrBlank()) {
                "\nPrevious Progress and Learning Plan:\n$previousPlan\n"
            } else {
                "\nNo previous progress plan available. This is the first session.\n"
            }

            val historyJson = convertHistoryToLlmMessages().joinToString("\n") { "${it.role}: ${it.content}" }
            val prompt = """
                You are an educational coordinator. Review the previous learning plan and the current conversation between Mynoo (the AI tutor) and the child ${childState.name}.
                
                $previousPlanSection
                
                Current Session Conversation History:
                $historyJson
                
                Your Task:
                1. Analyze the child's performance in this session compared to the previous learning plan. Identify which goals were met.
                2. Continuously track the child's strong and weak areas across English, Hindi, and Punjabi.
                3. Design an updated learning plan for the NEXT session.
                4. Adhere to the long-term language target:
                   - ~50% effort on English fluency, grammar, and vocabulary.
                   - ~30% effort on Hindi fluency and vocabulary.
                   - ~20% effort on Punjabi fluency and vocabulary.
                
                Provide a concise updated summary and next session plan (1-2 short paragraphs), highlighting identified strong/weak areas and action items for the tutor.
            """.trimIndent()

            val request = GeminiRequest(
                systemInstruction = GeminiContent(parts = listOf(GeminiPart(text = "You are a professional educational analyst and progress tracker."))),
                contents = listOf(GeminiContent(role = "user", parts = listOf(GeminiPart(text = prompt)))),
                generationConfig = GeminiGenConfig(temperature = 0.5)
            )
            val response = geminiApi.generateContent(activeModel, BuildConfig.GEMINI_API_KEY, request)
            try {
                val inputTokens = response.usageMetadata?.promptTokenCount ?: 0
                val outputTokens = response.usageMetadata?.candidatesTokenCount ?: 0
                val cachedTokens = response.usageMetadata?.cachedContentTokenCount ?: 0
                usageRepo.recordLlm(
                    childName = childState.name,
                    purpose = "talk",
                    provider = activeModel,
                    language = "en",
                    inputTokens = inputTokens,
                    outputTokens = outputTokens,
                    cachedInputTokens = cachedTokens
                )
            } catch (e: Exception) {
                Log.e("TutorVM", "Error recording LLM usage in generateAndSaveNextSessionPlan", e)
            }
            val summaryText = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text?.trim()
            if (!summaryText.isNullOrBlank()) {
                sessionRepo.saveNextSessionPlan(childState.name, summaryText)
                Log.d("TutorVM", "Updated next session plan saved: $summaryText")
            }
        } catch (e: Exception) {
            Log.e("TutorVM", "Failed to generate next session plan", e)
        }
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

    private suspend fun callElevenLabsStt(audioFile: File, modelId: String, langCode: String): String =
        withContext(Dispatchers.IO) {
            val httpClient = OkHttpClient()
            val req = Request.Builder()
                .url("https://api.elevenlabs.io/v1/speech-to-text")
                .addHeader("xi-api-key", BuildConfig.ELEVENLABS_API_KEY)
                .post(
                    okhttp3.MultipartBody.Builder()
                        .setType(okhttp3.MultipartBody.FORM)
                        .addFormDataPart("model_id", modelId)
                        .addPart(
                            MultipartBody.Part.createFormData(
                                "file", audioFile.name,
                                audioFile.asRequestBody("audio/wav".toMediaType()),
                            )
                        )
                        .build()
                )
                .build()
            val resp = httpClient.newCall(req).execute()
            if (!resp.isSuccessful) throw Exception("ElevenLabs STT error ${resp.code}: ${resp.body?.string()}")
            JSONObject(resp.body!!.string()).optString("text", "").trim()
        }

    private suspend fun callOpenAiStt(audioFile: File, modelId: String, langCode: String): String =
        withContext(Dispatchers.IO) {
            val httpClient = OkHttpClient()
            val languageParam = langCode.split("-").firstOrNull() ?: "en"
            val req = Request.Builder()
                .url("https://api.openai.com/v1/audio/transcriptions")
                .addHeader("Authorization", "Bearer ${BuildConfig.OPENAI_API_KEY}")
                .post(
                    okhttp3.MultipartBody.Builder()
                        .setType(okhttp3.MultipartBody.FORM)
                        .addFormDataPart("model", modelId)
                        .addFormDataPart("language", languageParam)
                        .addPart(
                            MultipartBody.Part.createFormData(
                                "file", audioFile.name,
                                audioFile.asRequestBody("audio/wav".toMediaType()),
                            )
                        )
                        .build()
                )
                .build()
            val resp = httpClient.newCall(req).execute()
            if (!resp.isSuccessful) throw Exception("OpenAI STT error ${resp.code}: ${resp.body?.string()}")
            JSONObject(resp.body!!.string()).optString("text", "").trim()
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
        val state = _ui.value
        if (state.phase != SessionPhase.IDLE) {
            val childState = activeChildState
            if (childState != null) {
                val durMin = (Instant.now().epochSecond - startTime.epochSecond) / 60.0
                val childMessageCount = history.count { it.role == "user" }
                val shouldSave = durMin >= 5.0 && childMessageCount >= 5
                if (shouldSave) {
                    try {
                        kotlinx.coroutines.runBlocking {
                            sessionRepo.saveSession(
                                childState.name,
                                com.krishanagarwal.mynoo.data.repository.SessionRecord(
                                    id          = sessionId,
                                    date        = startTime.toString(),
                                    endDate     = Instant.now().toString(),
                                    durationMin = durMin,
                                    lang        = state.lang,
                                    transcript  = state.messages.map { msg ->
                                        mapOf(
                                            "role" to msg.role,
                                            "text" to msg.text
                                        ) + (if (msg.propType != null) mapOf("propType" to msg.propType) else emptyMap()) +
                                            (if (msg.propTitle != null) mapOf("propTitle" to msg.propTitle) else emptyMap()) +
                                            (if (msg.propText != null) mapOf("propText" to msg.propText) else emptyMap())
                                    }
                                )
                            )
                        }
                    } catch (e: Exception) {
                        Log.e("TutorVM", "onCleared saveSession failed", e)
                    }
                }
            }
        }
    }

    private fun getFullErrorDescription(e: Throwable): String {
        var details = ""
        if (e is retrofit2.HttpException) {
            val errorBody = try {
                e.response()?.errorBody()?.string()
            } catch (_: Exception) {
                null
            }
            if (!errorBody.isNullOrBlank()) {
                details = "API Error: $errorBody\n"
            }
        }
        return if (details.isNotEmpty()) details else "${e.javaClass.simpleName}: ${e.message ?: "Unknown error"}"
    }
}
