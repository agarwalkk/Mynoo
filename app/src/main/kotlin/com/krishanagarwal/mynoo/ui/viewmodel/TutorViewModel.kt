package com.krishanagarwal.mynoo.ui.viewmodel

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.krishanagarwal.mynoo.BuildConfig
import com.krishanagarwal.mynoo.data.api.GeminiApi
import com.krishanagarwal.mynoo.data.api.GeminiContent
import com.krishanagarwal.mynoo.data.api.GeminiGenConfig
import com.krishanagarwal.mynoo.data.api.GeminiPart
import com.krishanagarwal.mynoo.data.api.GeminiRequest
import com.krishanagarwal.mynoo.data.api.SarvamApi
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
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
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
) : ViewModel() {

    private val _ui = MutableStateFlow(TutorUiState())
    val ui: StateFlow<TutorUiState> = _ui

    private val history   = mutableListOf<GeminiContent>()
    private var sessionId = ""
    private var startTime = Instant.now()
    private var activeJob: Job? = null

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
            botTurn(buildSystemPrompt(childState, lang), lang)
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
            val request = GeminiRequest(
                systemInstruction = systemPrompt?.let {
                    GeminiContent(parts = listOf(GeminiPart(text = it)))
                },
                contents = history.toList(),
                generationConfig = GeminiGenConfig(temperature = 0.7),
            )
            val response  = geminiApi.generateContent(BuildConfig.GEMINI_API_KEY, request)
            val botText   = response.candidates
                ?.firstOrNull()?.content?.parts?.firstOrNull()?.text?.trim()
                ?: "I'm having trouble thinking right now. Can you try again?"

            // Strip JSON wrappers if present (model sometimes wraps in code fences)
            val cleanText = botText
                .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()

            // Try parsing quick replies from JSON response shape { speech, quickReplies }
            val (speech, replies) = parseBotResponse(cleanText)

            history.add(GeminiContent(role = "model", parts = listOf(GeminiPart(text = speech))))
            appendMessage(ChatMessage(role = "bot", text = speech))
            _ui.update { it.copy(quickReplies = replies) }

            ttsService.speak(speech)
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

            val transcript = sttResponse.transcript?.trim().orEmpty()
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
        // Try to extract {speech, quickReplies} JSON
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

    override fun onCleared() {
        super.onCleared()
        ttsService.stop()
        recorder.requestStop()
    }
}
