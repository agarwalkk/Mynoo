package com.krishanagarwal.mynoo.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.firestore.FirebaseFirestore
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.krishanagarwal.mynoo.BuildConfig
import com.krishanagarwal.mynoo.data.api.*
import com.krishanagarwal.mynoo.data.repository.PlacementRepository
import com.krishanagarwal.mynoo.service.AudioRecorderService
import com.krishanagarwal.mynoo.service.TtsService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

enum class QuizPhase {
    LOADING, RESUME_PROMPT, START_PROMPT, QUIZ, RESULT, SAVING, DONE
}

enum class VoiceStatus {
    IDLE, RECORDING, PROCESSING, ERROR
}

data class QuizQuestion(
    val id: String,
    val prompt: String,
    val displayPrompt: String,
    val options: List<String>,
    val correctIndex: Int,
    val difficulty: Int,
    val topic: String,
    val questionType: String, // "standard" | "listen-prompt" | "voice-answer"
    val listenText: String? = null,
    val debugPrompt: String? = null,
    val debugResponse: String? = null
)

data class AnswerRecord(
    val question: QuizQuestion,
    val userAnswer: String,
    val correct: Boolean,
    val feedback: String
)

data class AssessmentResult(
    val level: Int,
    val summary: String,
    val displaySummary: String
)

data class PlacementQuizState(
    val phase: QuizPhase = QuizPhase.LOADING,
    val langIndex: Int = 0,
    val currentQuestion: QuizQuestion? = null,
    val history: List<AnswerRecord> = emptyList(),
    val loadingQuestion: Boolean = false,
    val questionError: String? = null,
    val selectedOption: Int? = null,
    val showFeedback: String? = null, // Stores correctness feedback text if visible
    val isFeedbackCorrect: Boolean = false,
    val voiceStatus: VoiceStatus = VoiceStatus.IDLE,
    val voiceError: String? = null,
    val adjustedLevel: Int = 50,
    val assessmentResult: AssessmentResult? = null,
    val finalLevels: Map<String, Int> = emptyMap(),
    val finalExpertise: Map<String, String> = emptyMap(),
    val finished: Boolean = false
)

data class LangMetadata(
    val key: String,
    val label: String,
    val emoji: String,
    val colour: String,
    val emojiColour: String? = null
)

data class FallbackQ(
    val prompt: String,
    val options: List<String>,
    val correctIndex: Int,
    val topic: String
)

data class LangLevels(
    val en: Int,
    val hi: Int,
    val pa: Int
)

data class LangExpertise(
    val en: String,
    val hi: String,
    val pa: String
)

// Internal JSON draft serializable structure
data class QuizDraftData(
    val savedAt: String,
    val langIndex: Int,
    val langHistories: Map<String, List<AnswerRecord>>,
    val finalLevels: Map<String, Int>,
    val finalExpertise: Map<String, String>
)

@HiltViewModel
class PlacementViewModel @Inject constructor(
    private val repo: PlacementRepository,
    private val geminiApi: GeminiApi,
    private val sarvamApi: SarvamApi,
    private val sarvamChatApi: SarvamChatApi,
    private val openAiApi: OpenAiApi,
    private val xaiApi: XaiApi,
    private val ttsService: TtsService,
    private val recorder: AudioRecorderService,
    private val db: FirebaseFirestore
) : ViewModel() {

    private val gson = Gson()

    val LANGUAGES = listOf(
        LangMetadata("en", "English", "🇬🇧", "#27AE60"),
        LangMetadata("hi", "Hindi", "🇮🇳", "#E67E22"),
        LangMetadata("pa", "Punjabi", "☬", "#8E44AD", "#FF9933")
    )

    private val _ui = MutableStateFlow(PlacementQuizState())
    val ui: StateFlow<PlacementQuizState> = _ui.asStateFlow()

    private var childName = ""
    private var childAge = "~12"
    private var childClass = "CBSE"

    private var activeLangs = LANGUAGES
    private var retestLangs: List<String>? = null
    private var isRetest = false

    // Settings loaded from kids/{name}/config/settings
    private var geminiModel = "gemini-2.0-flash"
    private var hiModel: String? = null
    private var paModel: String? = null
    private var globalTemperature = 0.9
    private var globalReasoningEffort: String? = null
    private var hiReasoningEffort: String? = null
    private var paReasoningEffort: String? = null

    // Chaining response IDs per language for OpenAI/xAI Responses API
    private val threadResponseIds = mutableMapOf<String, String>()

    // Memory buffer of history for all 3 languages
    private val langHistories = mutableMapOf<String, List<AnswerRecord>>()

    // Fallbacks
    private val FALLBACK_QUESTIONS = mapOf(
        "en" to listOf(
            FallbackQ("Which word means the opposite of \"big\"?", listOf("Small", "Large", "Heavy", "Tall"), 0, "vocabulary"),
            FallbackQ("What comes after D in the alphabet?", listOf("E", "F", "C", "G"), 0, "alphabet"),
            FallbackQ("Which sentence is correct?", listOf("She goes to school", "She go to school", "She going school", "She goed school"), 0, "grammar"),
            FallbackQ("What is the plural of \"cat\"?", listOf("Cats", "Cates", "Catss", "Cattes"), 0, "plurals"),
            FallbackQ("Which of these is a colour?", listOf("Blue", "Chair", "Run", "Heavy"), 0, "vocabulary"),
            FallbackQ("What is 5 + 3?", listOf("8", "7", "9", "6"), 0, "numbers"),
            FallbackQ("Which animal says \"meow\"?", listOf("Cat", "Dog", "Cow", "Bird"), 0, "animals")
        ),
        "hi" to listOf(
            FallbackQ("\"नमस्ते\" का अर्थ क्या है?", listOf("Hello", "Goodbye", "Thank you", "Sorry"), 0, "greetings"),
            FallbackQ("\"पानी\" का अर्थ क्या है?", listOf("Water", "Food", "Fire", "Air"), 0, "vocabulary"),
            FallbackQ("\"शुक्रिया\" का अर्थ क्या है?", listOf("Thank you", "Sorry", "Hello", "Please"), 0, "greetings"),
            FallbackQ("एक सप्ताह में कितने दिन होते हैं?", listOf("सात", "पाँच", "दस", "आठ"), 0, "numbers"),
            FallbackQ("\"कुत्ता\" का अर्थ क्या है?", listOf("Dog", "Cat", "Cow", "Bird"), 0, "animals")
        ),
        "pa" to listOf(
            FallbackQ("\"ਸਤ ਸ੍ਰੀ ਅਕਾਲ\" ਦਾ ਕੀ ਅਰਥ ਹੈ?", listOf("Hello / Greetings", "Goodbye", "Thank you", "Please"), 0, "greetings"),
            FallbackQ("\"ਪਾਣੀ\" ਦਾ ਕੀ ਅਰਥ ਹੈ?", listOf("Water", "Food", "Fire", "Air"), 0, "vocabulary"),
            FallbackQ("\"ਧੰਨਵਾਦ\" ਦਾ ਕੀ ਅਰਥ ਹੈ?", listOf("Thank you", "Sorry", "Hello", "Please"), 0, "greetings"),
            FallbackQ("ਇੱਕ ਹਫ਼ਤੇ ਵਿੱਚ ਕਿੰਨੇ ਦਿਨ ਹੁੰਦੇ ਹਨ?", listOf("ਸੱਤ", "ਪੰਜ", "ਦਸ", "ਅੱਠ"), 0, "numbers"),
            FallbackQ("\"ਕੁੱਤਾ\" ਦਾ ਕੀ ਅਰਥ ਹੈ?", listOf("Dog", "Cat", "Cow", "Bird"), 0, "animals")
        )
    )

    fun initialize(name: String, routeLang: String?) {
        this.childName = name
        _ui.value = PlacementQuizState(phase = QuizPhase.LOADING)

        viewModelScope.launch {
            try {
                // 1. Fetch child profile
                try {
                    val pDoc = db.collection("kids").document(name).get().await()
                    if (pDoc.exists()) {
                        childAge = pDoc.getString("age") ?: "~12"
                        childClass = pDoc.getString("class") ?: "CBSE"
                    }
                } catch (e: Exception) {
                    Log.w("PlacementVM", "Profile fetch failed, using defaults", e)
                }

                // 2. Fetch parent settings
                try {
                    val sDoc = db.collection("kids").document(name).collection("config").document("settings").get().await()
                    if (sDoc.exists()) {
                        geminiModel = sDoc.getString("geminiModel") ?: "gemini-2.0-flash"
                        hiModel = sDoc.getString("hiModel")
                        paModel = sDoc.getString("paModel")
                        globalTemperature = sDoc.getDouble("globalTemperature") ?: 0.9
                        globalReasoningEffort = sDoc.getString("globalReasoningEffort")
                        hiReasoningEffort = sDoc.getString("hiReasoningEffort")
                        paReasoningEffort = sDoc.getString("paReasoningEffort")
                    }
                } catch (e: Exception) {
                    Log.w("PlacementVM", "Settings fetch failed, using defaults", e)
                }

                // 3. Check for retest request
                retestLangs = repo.getRetestRequest(name)
                if (retestLangs != null && retestLangs!!.isNotEmpty()) {
                    isRetest = true
                    activeLangs = LANGUAGES.filter { retestLangs!!.contains(it.key) }
                    repo.clearQuizDraft(name) // Clear in-progress draft for fresh retest
                    _ui.update { it.copy(phase = QuizPhase.START_PROMPT) }
                    return@launch
                }

                // 4. Check single language route override
                if (!routeLang.isNullOrBlank()) {
                    val single = LANGUAGES.filter { it.key == routeLang }
                    if (single.isNotEmpty()) {
                        activeLangs = single
                    }
                }

                // 5. Check draft
                val draftJson = repo.getQuizDraft(name)
                if (draftJson != null) {
                    try {
                        val draft = gson.fromJson(draftJson, QuizDraftData::class.java)
                        langHistories.clear()
                        langHistories.putAll(draft.langHistories)
                        _ui.update {
                            it.copy(
                                phase = QuizPhase.RESUME_PROMPT,
                                finalLevels = draft.finalLevels,
                                finalExpertise = draft.finalExpertise
                            )
                        }
                    } catch (e: Exception) {
                        Log.e("PlacementVM", "Draft parsing error", e)
                        _ui.update { it.copy(phase = QuizPhase.START_PROMPT) }
                    }
                } else {
                    _ui.update { it.copy(phase = QuizPhase.START_PROMPT) }
                }
            } catch (e: Exception) {
                Log.e("PlacementVM", "Initialization error", e)
                _ui.update { it.copy(phase = QuizPhase.START_PROMPT) }
            }
        }
    }

    fun startFresh() {
        viewModelScope.launch {
            repo.clearQuizDraft(childName)
            langHistories.clear()
            _ui.update {
                it.copy(
                    phase = QuizPhase.QUIZ,
                    langIndex = 0,
                    history = emptyList(),
                    finalLevels = emptyMap(),
                    finalExpertise = emptyMap()
                )
            }
            loadQuestion()
        }
    }

    fun resumeDraft() {
        viewModelScope.launch {
            val savedLIndex = _ui.value.langIndex
            val activeL = activeLangs
            // Find first unconfirmed language
            var resumeIdx = activeL.indexOfFirst { _ui.value.finalLevels[it.key] == null }
            if (resumeIdx < 0) resumeIdx = 0

            val currentLangKey = activeL.getOrNull(resumeIdx)?.key ?: "en"
            val history = langHistories[currentLangKey] ?: emptyList()

            _ui.update {
                it.copy(
                    phase = QuizPhase.QUIZ,
                    langIndex = resumeIdx,
                    history = history
                )
            }
            loadQuestion()
        }
    }

    fun startQuiz() {
        _ui.update { it.copy(phase = QuizPhase.QUIZ, langIndex = 0, history = emptyList()) }
        loadQuestion()
    }

    // ── Load Question ─────────────────────────────────────────────────────────

    private fun loadQuestion() {
        val currentLang = activeLangs.getOrNull(_ui.value.langIndex) ?: return
        val qIndex = _ui.value.history.size

        if (qIndex >= 50) {
            viewModelScope.launch { computeResult() }
            return
        }

        _ui.update { it.copy(loadingQuestion = true, questionError = null, selectedOption = null, showFeedback = null) }
        ttsService.stop()

        viewModelScope.launch {
            var loadedQ: QuizQuestion? = null
            var lastError: String? = null

            for (attempt in 1..3) {
                try {
                    loadedQ = fetchNextQuestion(currentLang.key, _ui.value.history, qIndex)
                    break
                } catch (e: Exception) {
                    lastError = e.message ?: "Unknown generation error"
                    Log.w("PlacementVM", "Attempt $attempt failed: $lastError")
                    kotlinx.coroutines.delay(800)
                }
            }

            if (loadedQ != null) {
                _ui.update { it.copy(loadingQuestion = false, currentQuestion = loadedQ) }
                // Speak if listen-prompt or voice-answer
                if (loadedQ.questionType == "listen-prompt" || loadedQ.questionType == "voice-answer") {
                    speakQuestion()
                }
            } else {
                _ui.update { it.copy(loadingQuestion = false, questionError = lastError ?: "Failed to generate question") }
            }
        }
    }

    private suspend fun fetchNextQuestion(
        langKey: String,
        history: List<AnswerRecord>,
        qIndex: Int
    ): QuizQuestion = withContext(Dispatchers.IO) {
        val langMetaRaw = repo.getGistFile("aarav_quiz_lang_$langKey.json")
        val langMeta = gson.fromJson(langMetaRaw, JsonObject::class.java)

        val startDiff = langMeta.get("START_DIFFICULTY")?.asInt ?: 20
        val step = langMeta.get("STREAK_STEP")?.asInt ?: 7
        val expectedDiff = computeNextDifficulty(history, startDiff, step)

        val recentCorrect = history.takeLast(5).count { it.correct }
        val canTerminate = qIndex >= 20
        val onPerfectRun = recentCorrect >= 4 && expectedDiff < 90

        // Determine topics pool
        val topicPoolsArray = langMeta.getAsJsonArray("TOPIC_POOLS")
        val bandIndex = when {
            expectedDiff <= 10 -> 0
            expectedDiff <= 20 -> 1
            expectedDiff <= 40 -> 2
            expectedDiff <= 65 -> 3
            else -> 4
        }
        val bandTopicsJson = topicPoolsArray.get(minOf(bandIndex, topicPoolsArray.size() - 1))
        val bandTopics = gson.fromJson(bandTopicsJson, Array<String>::class.java).toList()
        val usedTopics = history.map { it.question.topic }.distinct()

        val promptTemplateRaw = repo.getGistFile("aarav_quiz_prompt_next_question.json")
        val promptTemplate = gson.fromJson(promptTemplateRaw, JsonObject::class.java)

        val systemRaw = joinLines(promptTemplate.get("SYSTEM_INSTRUCTION"))
        val system = systemRaw
            .replace("{{langName}}", langMeta.get("LANG_NAME").asString)
            .replace("{{langInstruction}}", joinLines(langMeta.get("LANG_INSTRUCTION")))
            .replace("{{childAge}}", childAge)
            .replace("{{childClass}}", childClass)
            .replace("{{previousPromptsBlock}}", "") // Not retesting previous prompt avoidance here

        val templateRaw = joinLines(promptTemplate.get("TEMPLATE"))
        val termination = when {
            canTerminate && !onPerfectRun -> joinLines(promptTemplate.get("TERMINATION_CAN_STOP"))
            canTerminate && onPerfectRun -> joinLines(promptTemplate.get("TERMINATION_PERFECT_RUN"))
                .replace("{{recentScore}}", recentCorrect.toString())
                .replace("{{expectedDifficulty}}", expectedDiff.toString())
            else -> joinLines(promptTemplate.get("TERMINATION_MUST_CONTINUE"))
        }

        val historyText = if (history.isEmpty()) {
            "No questions asked yet — start at difficulty $expectedDiff."
        } else {
            history.mapIndexed { i, a ->
                "Q${i + 1} [diff ${a.question.difficulty}, topic: ${a.question.topic}]: " +
                "${a.question.prompt} → Child answered: \"${a.userAnswer}\" (${if (a.correct) "CORRECT" else "WRONG"})"
            }.joinToString("\n")
        }

        val content = templateRaw
            .replace("{{qIndex}}", qIndex.toString())
            .replace("{{correctCount}}", history.count { it.correct }.toString())
            .replace("{{historyText}}", historyText)
            .replace("{{topicSuggestions}}", bandTopics.joinToString(", "))
            .replace("{{recentTopics}}", if (usedTopics.isEmpty()) "(none yet)" else usedTopics.joinToString(", "))
            .replace("{{recentScore}}", recentCorrect.toString())
            .replace("{{terminationBlock}}", termination)
            .replace("{{expectedDifficulty}}", expectedDiff.toString())

        val fullPrompt = "$system\n\n$content"

        // Load Schema
        val schemaString = repo.getGistFile("aarav_quiz_schema_next_question.json")
        val schemaJson = JsonParser.parseString(schemaString)

        val model = getActiveModelForLang(langKey)
        val temp = getTemperatureForLang(langKey)

        var rawResponse = ""
        var responseId: String? = null

        when {
            // Grok model
            model.startsWith("grok-") -> {
                val auth = "Bearer ${BuildConfig.XAI_API_KEY}"
                val prevId = threadResponseIds[langKey]
                val request = if (prevId != null && history.isNotEmpty()) {
                    val lastAnswer = history.last()
                    val deltaInput = "Child answered Q$qIndex: \"${lastAnswer.userAnswer}\" (${if (lastAnswer.correct) "CORRECT" else "WRONG"}).\n" +
                            "Required difficulty for next question: $expectedDiff. You MUST set \"difficulty\" to exactly $expectedDiff.\n" +
                            "Topics used so far (do NOT repeat any): ${usedTopics.takeLast(8).joinToString(", ")}.\n" +
                            "Pick a DIFFERENT topic. Next question please."
                    LlmResponseRequest(
                        model = model,
                        input = deltaInput,
                        previousResponseId = prevId,
                        temperature = null, // Grok does not support temperature
                        maxOutputTokens = 4096,
                        text = LlmTextFormat(LlmJsonSchemaFormat(name = "next_question", schema = schemaJson))
                    )
                } else {
                    LlmResponseRequest(
                        model = model,
                        input = content,
                        instructions = system,
                        temperature = null, // Grok does not support temperature
                        maxOutputTokens = 4096,
                        text = LlmTextFormat(LlmJsonSchemaFormat(name = "next_question", schema = schemaJson))
                    )
                }
                val res = xaiApi.createResponse(auth, request)
                responseId = res.id
                rawResponse = extractTextFromLlmResponse(res)
            }
            // OpenAI model
            model.startsWith("gpt-") -> {
                val auth = "Bearer ${BuildConfig.OPENAI_API_KEY}"
                val prevId = threadResponseIds[langKey]
                val isReasoning = model.startsWith("gpt-5")
                val request = if (prevId != null && history.isNotEmpty()) {
                    val lastAnswer = history.last()
                    val deltaInput = "Child answered Q$qIndex: \"${lastAnswer.userAnswer}\" (${if (lastAnswer.correct) "CORRECT" else "WRONG"}).\n" +
                            "Required difficulty for next question: $expectedDiff. You MUST set \"difficulty\" to exactly $expectedDiff.\n" +
                            "Topics used so far (do NOT repeat any): ${usedTopics.takeLast(8).joinToString(", ")}.\n" +
                            "Pick a DIFFERENT topic. Next question please."
                    LlmResponseRequest(
                        model = model,
                        input = deltaInput,
                        previousResponseId = prevId,
                        temperature = if (isReasoning) null else temp,
                        maxOutputTokens = 4096,
                        text = LlmTextFormat(LlmJsonSchemaFormat(name = "next_question", schema = schemaJson)),
                        reasoning = if (isReasoning) LlmReasoning(getReasoningEffort(langKey)) else null
                    )
                } else {
                    LlmResponseRequest(
                        model = model,
                        input = content,
                        instructions = system,
                        temperature = if (isReasoning) null else temp,
                        maxOutputTokens = 4096,
                        text = LlmTextFormat(LlmJsonSchemaFormat(name = "next_question", schema = schemaJson)),
                        reasoning = if (isReasoning) LlmReasoning(getReasoningEffort(langKey)) else null
                    )
                }
                val res = openAiApi.createResponse(auth, request)
                responseId = res.id
                rawResponse = extractTextFromLlmResponse(res)
            }
            // Sarvam model
            model.startsWith("sarvam-") -> {
                val request = SarvamChatRequest(
                    model = model,
                    messages = listOf(
                        SarvamChatMessage("system", "You are a language quiz engine. Output ONLY raw JSON. Follow the exact JSON schema specified."),
                        SarvamChatMessage("user", fullPrompt)
                    ),
                    temperature = temp,
                    maxTokens = 4096
                )
                val res = sarvamChatApi.chatCompletions(BuildConfig.SARVAM_API_KEY, request)
                val rawContent = res.choices?.firstOrNull()?.message?.content ?: "{}"
                rawResponse = cleanSarvamResponse(rawContent)
            }
            // Gemini model (default fallback)
            else -> {
                val request = GeminiRequest(
                    contents = listOf(GeminiContent(parts = listOf(GeminiPart(text = fullPrompt)))),
                    generationConfig = GeminiGenConfig(
                        temperature = temp,
                        responseMimeType = "application/json",
                        responseSchema = schemaJson
                    )
                )
                val res = geminiApi.generateContent(model, BuildConfig.GEMINI_API_KEY, request)
                rawResponse = res.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text ?: "{}"
            }
        }

        // Save Response Thread ID
        if (!responseId.isNullOrBlank()) {
            threadResponseIds[langKey] = responseId
        }

        // Parse Question
        val parsed = gson.fromJson(rawResponse, JsonObject::class.java)

        // Check if finished
        if (parsed.has("done") && parsed.get("done").asBoolean) {
            throw QuizEarlyTerminationException()
        }

        val prompt = parsed.get("prompt").asString
        var displayPrompt = if (parsed.has("displayPrompt")) parsed.get("displayPrompt").asString else prompt
        displayPrompt = stripOptions(displayPrompt)

        val qType = if (parsed.has("questionType")) parsed.get("questionType").asString else "standard"
        val listenText = if (parsed.has("listenText")) parsed.get("listenText").asString else null

        val optionsArr = parsed.getAsJsonArray("options")
        var options = optionsArr.map { it.asString.trim() }.distinct().take(4)
        if (options.size < 4) {
            throw Exception("Not enough distinct options from LLM")
        }

        var correctIndex = if (parsed.has("correctIndex")) parsed.get("correctIndex").asInt else 0
        correctIndex = minOf(3, maxOf(0, correctIndex))

        // Shuffling
        val indices = mutableListOf(0, 1, 2, 3)
        indices.shuffle()
        val shuffledOptions = indices.map { options[it] }
        val shuffledCorrectIndex = indices.indexOf(correctIndex)

        QuizQuestion(
            id = "${langKey}_q${qIndex}_${System.currentTimeMillis()}",
            prompt = prompt,
            displayPrompt = displayPrompt,
            options = shuffledOptions,
            correctIndex = shuffledCorrectIndex,
            difficulty = if (parsed.has("difficulty")) parsed.get("difficulty").asInt else 10,
            topic = if (parsed.has("topic")) parsed.get("topic").asString else "general",
            questionType = qType,
            listenText = listenText,
            debugPrompt = fullPrompt,
            debugResponse = rawResponse
        )
    }

    // ── Answer Submission ─────────────────────────────────────────────────────

    fun submitAnswer(optionIndex: Int) {
        val q = _ui.value.currentQuestion ?: return
        if (_ui.value.showFeedback != null) return

        ttsService.stop()
        _ui.update { it.copy(selectedOption = optionIndex) }

        val correct = optionIndex == q.correctIndex
        val correctText = q.options.getOrNull(q.correctIndex) ?: ""
        val feedback = if (correct) {
            "✅ Correct! \"$correctText\" is right."
        } else {
            "❌ The correct answer is \"$correctText\"."
        }

        _ui.update { it.copy(showFeedback = feedback, isFeedbackCorrect = correct) }

        viewModelScope.launch {
            kotlinx.coroutines.delay(1600)
            val record = AnswerRecord(
                question = q,
                userAnswer = if (optionIndex in q.options.indices) q.options[optionIndex] else "None",
                correct = correct,
                feedback = feedback
            )
            val newHistory = _ui.value.history + record
            val currentLang = activeLangs.getOrNull(_ui.value.langIndex) ?: return@launch
            langHistories[currentLang.key] = newHistory

            if (!isRetest) {
                saveQuizDraftState()
            }

            _ui.update { it.copy(history = newHistory) }
            loadQuestion()
        }
    }

    fun submitSkip() {
        val q = _ui.value.currentQuestion ?: return
        if (_ui.value.showFeedback != null) return

        ttsService.stop()
        _ui.update { it.copy(selectedOption = -1) }

        val correctText = q.options.getOrNull(q.correctIndex) ?: ""
        val feedback = "The correct answer is \"$correctText\"."

        _ui.update { it.copy(showFeedback = feedback, isFeedbackCorrect = false) }

        viewModelScope.launch {
            kotlinx.coroutines.delay(1600)
            val record = AnswerRecord(
                question = q,
                userAnswer = "I don't know",
                correct = false,
                feedback = feedback
            )
            val newHistory = _ui.value.history + record
            val currentLang = activeLangs.getOrNull(_ui.value.langIndex) ?: return@launch
            langHistories[currentLang.key] = newHistory

            if (!isRetest) {
                saveQuizDraftState()
            }

            _ui.update { it.copy(history = newHistory) }
            loadQuestion()
        }
    }

    // ── Voice Answer STT ──────────────────────────────────────────────────────

    fun startVoiceAnswer() {
        if (_ui.value.showFeedback != null) return
        ttsService.stop()

        _ui.update { it.copy(voiceStatus = VoiceStatus.RECORDING, voiceError = null) }

        viewModelScope.launch {
            try {
                val result = recorder.record()
                _ui.update { it.copy(voiceStatus = VoiceStatus.PROCESSING) }

                val filePart = MultipartBody.Part.createFormData(
                    "file", result.wavFile.name,
                    result.wavFile.asRequestBody("audio/wav".toMediaType()),
                )
                val sttResponse = sarvamApi.transcribe(
                    file         = filePart,
                    model        = "saarika:v2.5".toRequestBody("text/plain".toMediaType()),
                    languageCode = "en-IN".toRequestBody("text/plain".toMediaType()), // fuzzy checks letters A, B, C, D
                    apiKey       = BuildConfig.SARVAM_API_KEY,
                )
                result.wavFile.delete()

                val transcript = sttResponse.transcript?.trim().orEmpty()
                if (transcript.isNotBlank()) {
                    val matched = matchVoiceToOption(transcript, _ui.value.currentQuestion?.options ?: emptyList())
                    if (matched != null) {
                        _ui.update { it.copy(voiceStatus = VoiceStatus.IDLE) }
                        submitAnswer(matched)
                    } else {
                        _ui.update {
                            it.copy(
                                voiceStatus = VoiceStatus.ERROR,
                                voiceError = "Heard: \"$transcript\" — couldn't match. Try saying A, B, C, or D."
                            )
                        }
                    }
                } else {
                    _ui.update {
                        it.copy(
                            voiceStatus = VoiceStatus.ERROR,
                            voiceError = "Didn't catch that. Tap mic again or tap an option."
                        )
                    }
                }
            } catch (e: Exception) {
                if (e.message == "aborted") {
                    _ui.update { it.copy(voiceStatus = VoiceStatus.IDLE) }
                } else {
                    _ui.update {
                        it.copy(
                            voiceStatus = VoiceStatus.ERROR,
                            voiceError = e.message ?: "Voice recognition failed"
                        )
                    }
                }
            }
        }
    }

    fun stopVoiceAnswer() {
        recorder.requestSend()
    }

    // ── Speak Question TTS ────────────────────────────────────────────────────

    fun speakQuestion() {
        val q = _ui.value.currentQuestion ?: return
        val currentLang = activeLangs.getOrNull(_ui.value.langIndex) ?: return
        val labels = listOf("A", "B", "C", "D")

        val spokenText = when (q.questionType) {
            "listen-prompt" -> q.listenText ?: ""
            "voice-answer" -> {
                "${q.displayPrompt}. Your options are: " +
                q.options.mapIndexed { i, opt -> "${labels[i]}: $opt" }.joinToString(". ") +
                ". Now speak your answer."
            }
            else -> {
                "Question: ${q.prompt}. " +
                q.options.mapIndexed { i, opt -> "Option ${labels[i]}: $opt" }.joinToString(". ")
            }
        }.replace(Regex("_{2,}"), "blank").replace("_", "blank")

        viewModelScope.launch {
            try {
                ttsService.speak(spokenText)
            } catch (e: Exception) {
                Log.e("PlacementVM", "TTS playback failed", e)
            }
        }
    }

    fun stopSpeaking() {
        ttsService.stop()
    }

    // ── Final Result Calculation ──────────────────────────────────────────────

    private suspend fun computeResult() = withContext(Dispatchers.Default) {
        val currentLang = activeLangs.getOrNull(_ui.value.langIndex) ?: return@withContext
        val finalHistory = _ui.value.history
        val total = finalHistory.size
        val correct = finalHistory.count { it.correct }
        val avgDiff = if (total > 0) finalHistory.map { it.question.difficulty }.average() else 10.0

        _ui.update { it.copy(loadingQuestion = true, questionError = null) }

        // Deterministic override for perfect score
        if (correct == total && total >= 20 && avgDiff >= 65) {
            val res = AssessmentResult(
                level = 100,
                summary = "${childName} answered all ${total} ${currentLang.label} questions correctly at average difficulty ${avgDiff.toInt()}/100. Top proficiency.",
                displaySummary = "Perfect score! Every single answer was correct. You're at the very top level in ${currentLang.label}! 🌟"
            )
            onAssessmentComputed(currentLang.key, res)
            return@withContext
        }

        try {
            val schemaString = repo.getGistFile("aarav_quiz_schema_final_assessment.json")
            val schemaJson = JsonParser.parseString(schemaString)

            val template = repo.getGistFile("aarav_quiz_prompt_final_assessment.json")
            val parsedTemplate = gson.fromJson(template, JsonObject::class.java)

            val historyText = finalHistory.mapIndexed { i, a ->
                "Q${i + 1} [diff ${a.question.difficulty}, ${a.question.topic}]: \"${a.question.prompt}\" → \"${a.userAnswer}\" (${if (a.correct) "✓" else "✗"})"
            }.joinToString("\n")

            val prompt = joinLines(parsedTemplate.get("TEMPLATE"))
                .replace("{{childName}}", childName)
                .replace("{{langName}}", currentLang.label)
                .replace("{{total}}", total.toString())
                .replace("{{correct}}", correct.toString())
                .replace("{{avgDiff}}", String.format("%.1f", avgDiff))
                .replace("{{historyText}}", historyText)
                .replace("{{childAge}}", childAge)
                .replace("{{childClass}}", childClass)

            val model = getActiveModelForLang(currentLang.key)
            var rawResponse = ""

            when {
                model.startsWith("grok-") && threadResponseIds[currentLang.key] != null -> {
                    val prevId = threadResponseIds[currentLang.key]
                    val shortPrompt = "Quiz is complete. $childName answered $correct/$total correctly at average difficulty ${String.format("%.1f", avgDiff)}/100 in ${currentLang.label}.\nProduce the final assessment now."
                    val res = xaiApi.createResponse(
                        "Bearer ${BuildConfig.XAI_API_KEY}",
                        LlmResponseRequest(
                            model = model,
                            input = shortPrompt,
                            previousResponseId = prevId,
                            temperature = null, // Grok does not support temperature
                            maxOutputTokens = 2048,
                            text = LlmTextFormat(LlmJsonSchemaFormat(name = "final_assessment", schema = schemaJson))
                        )
                    )
                    rawResponse = extractTextFromLlmResponse(res)
                }
                model.startsWith("gpt-") && threadResponseIds[currentLang.key] != null -> {
                    val prevId = threadResponseIds[currentLang.key]
                    val shortPrompt = "Quiz is complete. $childName answered $correct/$total correctly at average difficulty ${String.format("%.1f", avgDiff)}/100 in ${currentLang.label}.\nProduce the final assessment now."
                    val res = openAiApi.createResponse(
                        "Bearer ${BuildConfig.OPENAI_API_KEY}",
                        LlmResponseRequest(
                            model = model,
                            input = shortPrompt,
                            previousResponseId = prevId,
                            temperature = 0.2,
                            maxOutputTokens = 2048,
                            text = LlmTextFormat(LlmJsonSchemaFormat(name = "final_assessment", schema = schemaJson))
                        )
                    )
                    rawResponse = extractTextFromLlmResponse(res)
                }
                model.startsWith("sarvam-") -> {
                    val request = SarvamChatRequest(
                        model = model,
                        messages = listOf(
                            SarvamChatMessage("system", "You are a language assessment engine. Output ONLY raw JSON."),
                            SarvamChatMessage("user", prompt)
                        ),
                        temperature = 0.2,
                        maxTokens = 2048
                    )
                    val res = sarvamChatApi.chatCompletions(BuildConfig.SARVAM_API_KEY, request)
                    val rawContent = res.choices?.firstOrNull()?.message?.content ?: "{}"
                    rawResponse = cleanSarvamResponse(rawContent)
                }
                else -> {
                    val request = GeminiRequest(
                        contents = listOf(GeminiContent(parts = listOf(GeminiPart(text = prompt)))),
                        generationConfig = GeminiGenConfig(
                            temperature = 0.2,
                            responseMimeType = "application/json",
                            responseSchema = schemaJson
                        )
                    )
                    val res = geminiApi.generateContent(model, BuildConfig.GEMINI_API_KEY, request)
                    rawResponse = res.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text ?: "{}"
                }
            }

            val parsed = gson.fromJson(rawResponse, JsonObject::class.java)
            val level = minOf(100, maxOf(1, parsed.get("level")?.asInt ?: 10))
            val summary = parsed.get("summary")?.asString ?: ""
            val displaySummary = parsed.get("displaySummary")?.asString ?: ""

            val res = AssessmentResult(level, summary, displaySummary)
            onAssessmentComputed(currentLang.key, res)

        } catch (e: Exception) {
            Log.e("PlacementVM", "Final assessment LLM failed", e)
            val pct = correct.toDouble() / maxOf(1, total)
            val level = if (correct == total) {
                minOf(100, maxOf(1, (avgDiff + 40).toInt()))
            } else {
                minOf(100, maxOf(1, (avgDiff * pct).toInt()))
            }
            val res = AssessmentResult(
                level = level,
                summary = "${childName} answered $correct/$total correctly in ${currentLang.label} at an average difficulty of ${avgDiff.toInt()}/100.",
                displaySummary = "You got $correct out of $total right! Great effort!"
            )
            onAssessmentComputed(currentLang.key, res)
        }
    }

    private fun onAssessmentComputed(langKey: String, result: AssessmentResult) {
        _ui.update {
            it.copy(
                phase = QuizPhase.RESULT,
                loadingQuestion = false,
                assessmentResult = result,
                adjustedLevel = result.level
            )
        }
    }

    fun adjustLevel(delta: Int) {
        _ui.update { it.copy(adjustedLevel = minOf(100, maxOf(1, it.adjustedLevel + delta))) }
    }

    fun setAdjustedLevelDirect(level: Int) {
        _ui.update { it.copy(adjustedLevel = minOf(100, maxOf(1, level))) }
    }

    fun confirmResult() {
        val currentLang = activeLangs.getOrNull(_ui.value.langIndex) ?: return
        val result = _ui.value.assessmentResult ?: return

        _ui.update { it.copy(phase = QuizPhase.SAVING) }

        viewModelScope.launch {
            val adjusted = _ui.value.adjustedLevel
            val summary = result.summary + if (adjusted != result.level) {
                " (Child adjusted level from ${result.level} to ${adjusted}.)"
            } else ""

            val levelsMap = _ui.value.finalLevels + (currentLang.key to adjusted)
            val expertiseMap = _ui.value.finalExpertise + (currentLang.key to summary)

            // Convert AnswerRecord to Map for Firestore
            val historyData = _ui.value.history.map { r ->
                mapOf(
                    "prompt" to r.question.prompt,
                    "options" to r.question.options,
                    "correctIndex" to r.question.correctIndex,
                    "difficulty" to r.question.difficulty,
                    "topic" to r.question.topic,
                    "userAnswer" to r.userAnswer,
                    "correct" to r.correct
                )
            }

            // Save partial level to Firestore instantly
            if (!isRetest) {
                try {
                    repo.savePartialLevel(childName, currentLang.key, adjusted, summary, historyData)
                } catch (e: Exception) {
                    Log.w("PlacementVM", "Partial level save failed (offline?)", e)
                }
            }

            _ui.update { it.copy(finalLevels = levelsMap, finalExpertise = expertiseMap) }

            val allDone = activeLangs.all { levelsMap[it.key] != null }

            if (allDone) {
                try {
                    if (isRetest) {
                        // Merge partial results for retested languages
                        val historyMap = mutableMapOf<String, Any>()
                        for (l in activeLangs) {
                            val lHist = langHistories[l.key]?.map { r ->
                                mapOf(
                                    "prompt" to r.question.prompt,
                                    "options" to r.question.options,
                                    "correctIndex" to r.question.correctIndex,
                                    "difficulty" to r.question.difficulty,
                                    "topic" to r.question.topic,
                                    "userAnswer" to r.userAnswer,
                                    "correct" to r.correct
                                )
                            } ?: emptyList()
                            historyMap[l.key] = lHist
                        }
                        repo.mergeRetestLevels(childName, levelsMap, expertiseMap, historyMap)
                        repo.clearRetestRequest(childName)
                    } else {
                        // Complete quiz final save
                        val finalL = LangLevels(
                            en = levelsMap["en"] ?: 5,
                            hi = levelsMap["hi"] ?: 5,
                            pa = levelsMap["pa"] ?: 5
                        )
                        val finalExp = LangExpertise(
                            en = expertiseMap["en"] ?: "",
                            hi = expertiseMap["hi"] ?: "",
                            pa = expertiseMap["pa"] ?: ""
                        )
                        repo.savePlacementLevels(childName, levelsMap, expertiseMap)

                        // Save complete history
                        val historyMap = mutableMapOf<String, Any>()
                        for (key in listOf("en", "hi", "pa")) {
                            val lHist = langHistories[key]?.map { r ->
                                mapOf(
                                    "prompt" to r.question.prompt,
                                    "options" to r.question.options,
                                    "correctIndex" to r.question.correctIndex,
                                    "difficulty" to r.question.difficulty,
                                    "topic" to r.question.topic,
                                    "userAnswer" to r.userAnswer,
                                    "correct" to r.correct
                                )
                            } ?: emptyList()
                            historyMap[key] = lHist
                        }
                        repo.saveQuizHistory(childName, historyMap + ("assessedAt" to Instant.now().toString()))
                        repo.clearQuizDraft(childName)
                    }
                    _ui.update { it.copy(phase = QuizPhase.DONE) }
                } catch (e: Exception) {
                    Log.e("PlacementVM", "Final level save failed", e)
                    _ui.update { it.copy(phase = QuizPhase.RESULT, questionError = e.message ?: "Failed to save final levels") }
                }
            } else {
                // Move to first unconfirmed lang
                val nextIdx = activeLangs.indexOfFirst { levelsMap[it.key] == null }
                val resolvedIdx = if (nextIdx >= 0) nextIdx else (_ui.value.langIndex + 1) % activeLangs.size
                switchLanguage(resolvedIdx)
            }
        }
    }

    fun switchLanguage(newIndex: Int) {
        if (newIndex == _ui.value.langIndex || _ui.value.phase == QuizPhase.SAVING) return
        if (_ui.value.showFeedback != null) return // Wait for feedback animation

        ttsService.stop()

        val nextLang = activeLangs.getOrNull(newIndex) ?: return
        val nextHistory = langHistories[nextLang.key] ?: emptyList()

        _ui.update {
            it.copy(
                langIndex = newIndex,
                history = nextHistory,
                selectedOption = null,
                showFeedback = null,
                questionError = null
            )
        }

        // If next language is already assessed or result is computed, show that screen
        val finalL = _ui.value.finalLevels[nextLang.key]
        if (finalL != null) {
            _ui.update {
                it.copy(
                    phase = QuizPhase.RESULT,
                    adjustedLevel = finalL,
                    assessmentResult = AssessmentResult(
                        level = finalL,
                        summary = _ui.value.finalExpertise[nextLang.key] ?: "",
                        displaySummary = _ui.value.finalExpertise[nextLang.key] ?: "Assessment completed"
                    )
                )
            }
        } else {
            _ui.update { it.copy(phase = QuizPhase.QUIZ) }
            loadQuestion()
        }
    }

    fun saveDraftAndExit(onComplete: () -> Unit) {
        viewModelScope.launch {
            try {
                saveQuizDraftState()
            } catch (e: Exception) {
                Log.e("PlacementVM", "Draft save failed", e)
            }
            onComplete()
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private suspend fun saveQuizDraftState() {
        val draft = QuizDraftData(
            savedAt = Instant.now().toString(),
            langIndex = _ui.value.langIndex,
            langHistories = langHistories,
            finalLevels = _ui.value.finalLevels,
            finalExpertise = _ui.value.finalExpertise
        )
        repo.saveQuizDraft(childName, gson.toJson(draft))
    }

    private fun getActiveModelForLang(lang: String): String {
        return when (lang) {
            "hi" -> hiModel ?: geminiModel
            "pa" -> paModel ?: geminiModel
            else -> geminiModel
        }
    }

    private fun getTemperatureForLang(lang: String): Double {
        return globalTemperature
    }

    private fun getReasoningEffort(lang: String): String {
        return when (lang) {
            "hi" -> hiReasoningEffort ?: globalReasoningEffort ?: "low"
            "pa" -> paReasoningEffort ?: globalReasoningEffort ?: "low"
            else -> globalReasoningEffort ?: "low"
        }
    }

    private fun computeNextDifficulty(history: List<AnswerRecord>, startDiff: Int, step: Int): Int {
        var diff = startDiff
        var streak = 0
        for (a in history) {
            if (a.correct) {
                streak = if (streak > 0) streak + 1 else 1
            } else {
                streak = if (streak < 0) streak - 1 else -1
            }
            if (streak == 2) {
                diff = minOf(100, diff + step)
                streak = 0
            }
            if (streak == -2) {
                diff = maxOf(1, diff - step)
                streak = 0
            }
        }
        return diff
    }

    private fun matchVoiceToOption(transcript: String, options: List<String>): Int? {
        val t = transcript.lowercase().trim().replace(Regex("[^\\w\\s\\u0900-\\u097f\\u0a00-\\u0a7f]"), "")

        val letterMatch = Regex("\\b([abcd])\\b", RegexOption.IGNORE_CASE).find(t)
        if (letterMatch != null) {
            val letter = letterMatch.groupValues[1].lowercase()
            val idx = letter[0].code - 'a'.code
            if (idx in options.indices) return idx
        }

        if (t.contains(Regex("\\bfirst\\b|\\bone\\b"))) return 0
        if (t.contains(Regex("\\bsecond\\b|\\btwo\\b"))) return 1
        if (t.contains(Regex("\\bthird\\b|\\bthree\\b"))) return 2
        if (t.contains(Regex("\\bfourth\\b|\\bfour\\b"))) return 3

        for (i in options.indices) {
            val opt = options[i].lowercase().trim()
            if (opt.isNotEmpty() && (t.contains(opt) || opt.contains(t))) return i
        }

        val tWords = t.split(Regex("\\s+")).filter { it.isNotEmpty() }.toSet()
        if (tWords.isEmpty()) return null
        var bestIdx = -1
        var bestScore = 0
        for (i in options.indices) {
            val optWords = options[i].lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
            val score = optWords.count { tWords.contains(it) }
            if (score > bestScore) {
                bestScore = score
                bestIdx = i
            }
        }
        return if (bestScore > 0) bestIdx else null
    }

    private fun cleanSarvamResponse(raw: String): String {
        var cleaned = raw.replace(Regex("<think>[\\s\\S]*?</think>"), "")
        cleaned = cleaned.replace(Regex("```(?:json)?\\s*([\\s\\S]*?)\\s*```"), "$1")
        return cleaned.trim()
    }

    private fun stripOptions(text: String): String {
        return text.replace(Regex("\\n\\s*\\(?[Aa]\\)?[).\\s].*", RegexOption.DOT_MATCHES_ALL), "").trim()
    }

    private fun joinLines(elem: JsonElement?): String {
        if (elem == null) return ""
        return if (elem.isJsonArray) {
            elem.asJsonArray.map { it.asString }.joinToString("\n")
        } else {
            elem.asString
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

    override fun onCleared() {
        ttsService.stop()
        super.onCleared()
    }
}

class QuizEarlyTerminationException : Exception("Early termination triggered by engine")
