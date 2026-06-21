package com.krishanagarwal.mynoo.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.firestore.FirebaseFirestore
import com.krishanagarwal.mynoo.BuildConfig
import com.krishanagarwal.mynoo.data.api.*
import com.krishanagarwal.mynoo.data.model.ReasoningMapper
import com.krishanagarwal.mynoo.data.repository.Assessment
import com.krishanagarwal.mynoo.data.repository.AssessmentQuestion
import com.krishanagarwal.mynoo.data.repository.AssessmentRepository
import com.krishanagarwal.mynoo.data.repository.GlobalSettingsRepository
import com.krishanagarwal.mynoo.data.repository.PlacementRepository
import com.krishanagarwal.mynoo.data.repository.UsageRepository
import com.krishanagarwal.mynoo.data.repository.UsageSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

data class AssessmentListState(
    val assessments: List<Assessment> = emptyList(),
    val loading:     Boolean          = false,
    val error:       String?          = null,
)

data class QuizState(
    val assessment:    Assessment?     = null,
    val currentIndex:  Int             = 0,
    val answers:       List<Map<String, Any>?> = emptyList(),
    val revealed:      Set<Int>         = emptySet(),
    val finished:      Boolean          = false,
    val generating:    Boolean          = false,
    val summary:       String           = "",
    val error:         String?          = null,
    val validating:    Boolean          = false,
    val validationResult: Map<String, Any>? = null,
    val retryUsed:     Set<Int>         = emptySet(),
    val mcqSelectedIndex: Int?          = null,
    val mcqFirstWrongIndex: Int?        = null,
    val mcqPhase:      String           = "idle",
    val debugJsons:    Map<Int, Pair<String, String>> = emptyMap(),
)

val QuizState.currentQuestion get() = assessment?.questions?.getOrNull(currentIndex)
val QuizState.totalMarks get() = assessment?.questions?.sumOf { it.marks } ?: 0.0
val QuizState.earnedMarks get(): Double {
    val qs = assessment?.questions ?: return 0.0
    return qs.indices.sumOf { i ->
        val q = qs[i]
        val a = answers.getOrNull(i) ?: return@sumOf 0.0
        val type = a["type"] as? String ?: ""
        if (type == "mcq") {
            val correct = a["correct"] as? Boolean ?: false
            val attempts = (a["attempts"] as? Number)?.toInt() ?: 1
            if (correct) {
                if (attempts == 2) q.marks / 2.0 else q.marks
            } else 0.0
        } else {
            val aiEarned = (a["aiEarnedMarks"] as? Number)?.toDouble()
            if (aiEarned != null) {
                aiEarned
            } else {
                val selfGrade = a["selfGrade"] as? String ?: ""
                if (selfGrade == "got_it") q.marks else if (selfGrade == "partial") q.marks / 2.0 else 0.0
            }
        }
    }
}
val QuizState.score get(): Int {
    val total = totalMarks
    if (total == 0.0) return 0
    return Math.round((earnedMarks / total) * 100.0).toInt()
}

@HiltViewModel
class AssessmentViewModel @Inject constructor(
    private val repo:          AssessmentRepository,
    private val geminiApi:     GeminiApi,
    private val db:            FirebaseFirestore,
    private val openAiApi:     OpenAiApi,
    private val xaiApi:        XaiApi,
    private val sarvamChatApi: SarvamChatApi,
    private val placementRepo:       PlacementRepository,
    private val globalSettingsRepo:  GlobalSettingsRepository,
    private val usageRepo:          UsageRepository,
) : ViewModel() {

    private val _list = MutableStateFlow(AssessmentListState())
    val list: StateFlow<AssessmentListState> = _list

    private val _quiz = MutableStateFlow(QuizState())
    val quiz: StateFlow<QuizState> = _quiz

    // ── Child-specific placement levels & history states ──────────────────────
    private val _langLevels = MutableStateFlow<Map<String, Any>?>(null)
    val langLevels: StateFlow<Map<String, Any>?> = _langLevels

    private val _langExpertise = MutableStateFlow<Map<String, Any>?>(null)
    val langExpertise: StateFlow<Map<String, Any>?> = _langExpertise

    private val _quizHistory = MutableStateFlow<Map<String, Any>?>(null)
    val quizHistory: StateFlow<Map<String, Any>?> = _quizHistory

    private val _levelsAssessed = MutableStateFlow<Boolean?>(null)
    val levelsAssessed: StateFlow<Boolean?> = _levelsAssessed

    private val _retestSaving = MutableStateFlow(false)
    val retestSaving: StateFlow<Boolean> = _retestSaving

    // ── Child-specific AI Usage stats states ─────────────────────────────────
    private val _usageStats = MutableStateFlow<UsageSummary?>(null)
    val usageStats: StateFlow<UsageSummary?> = _usageStats

    private val _usageLoading = MutableStateFlow(false)
    val usageLoading: StateFlow<Boolean> = _usageLoading

    private var currentChild = ""

    fun loadChildPlacementData(childName: String) {
        viewModelScope.launch {
            try {
                val assessed = placementRepo.hasBeenAssessed(childName)
                _levelsAssessed.value = assessed
                if (assessed) {
                    _langLevels.value = placementRepo.getLevels(childName)
                    _langExpertise.value = db.collection("kids").document(childName)
                        .collection("config").document("langExpertise").get().await().data
                    _quizHistory.value = placementRepo.getQuizHistory(childName)
                } else {
                    _langLevels.value = null
                    _langExpertise.value = null
                    _quizHistory.value = null
                }
            } catch (e: Exception) {
                Log.e("AssessmentVM", "Error loading placement data", e)
            }
        }
    }

    fun saveRetestRequest(childName: String, langs: List<String>, onDone: () -> Unit) {
        _retestSaving.value = true
        viewModelScope.launch {
            try {
                placementRepo.saveRetestRequest(childName, langs)
                onDone()
            } catch (e: Exception) {
                Log.e("AssessmentVM", "Error saving retest request", e)
            } finally {
                _retestSaving.value = false
            }
        }
    }

    fun loadUsageStats(childName: String, periodDays: Int) {
        _usageLoading.value = true
        viewModelScope.launch {
            try {
                val summary = usageRepo.getUsageStats(childName, periodDays)
                _usageStats.value = summary
            } catch (e: Exception) {
                Log.e("AssessmentVM", "Error loading usage stats", e)
            } finally {
                _usageLoading.value = false
            }
        }
    }

    fun resetAssessment(childName: String, assessmentId: String) {
        viewModelScope.launch {
            try {
                repo.resetAssessment(childName, assessmentId)
                loadAssessments(childName)
            } catch (e: Exception) {
                Log.e("AssessmentVM", "Error resetting assessment", e)
            }
        }
    }

    fun deleteAssessment(childName: String, assessmentId: String) {
        viewModelScope.launch {
            try {
                repo.deleteAssessment(childName, assessmentId)
                loadAssessments(childName)
            } catch (e: Exception) {
                Log.e("AssessmentVM", "Error deleting assessment", e)
            }
        }
    }

    fun regenerateSummaryForAssessment(childName: String, assessment: Assessment) {
        _quiz.update { it.copy(validating = true) }
        viewModelScope.launch {
            try {
                val score = assessment.score ?: 0.0
                val total = assessment.questions.sumOf { it.marks }
                val earned = assessment.questions.indices.sumOf { i ->
                    val q = assessment.questions[i]
                    val a = assessment.answers.getOrNull(i) ?: return@sumOf 0.0
                    val type = a["type"] as? String ?: ""
                    if (type == "mcq") {
                        val correct = a["correct"] as? Boolean ?: false
                        val attempts = (a["attempts"] as? Number)?.toInt() ?: 1
                        if (correct) {
                            if (attempts == 2) q.marks / 2.0 else q.marks
                        } else 0.0
                    } else {
                        val aiEarned = (a["aiEarnedMarks"] as? Number)?.toDouble()
                        if (aiEarned != null) {
                            aiEarned
                        } else {
                            val selfGrade = a["selfGrade"] as? String ?: ""
                            if (selfGrade == "got_it") q.marks else if (selfGrade == "partial") q.marks / 2.0 else 0.0
                        }
                    }
                }
                val finalScore = if (total > 0.0) Math.round((earned / total) * 100.0).toDouble() else 0.0
                
                val model = resolveModel(childName, "assessmentModel", assessment.lang)
                val temp = resolveTemperature(childName, assessment.lang)
                val langName = when (assessment.lang.lowercase()) {
                    "hi" -> "Hindi"
                    "pa" -> "Punjabi"
                    else -> "English"
                }

                val gistContent = try {
                    placementRepo.getGistFile("aarav_assessment_summary.json")
                } catch (_: Exception) { null }

                val instructions = if (gistContent != null) {
                    val jsonObj = JSONObject(gistContent)
                    joinLines(jsonObj.opt("INSTRUCTIONS"))
                        .replace("{{score}}", finalScore.toInt().toString())
                        .replace("{{langName}}", langName)
                        .replace("{{chapterContext}}", if (assessment.chapterTitles.isNotEmpty()) "Chapter: ${assessment.chapterTitles.joinToString(", ")}" else "This is a GENERIC (curriculum-wide) assessment. Leave conceptMastery as an empty array [].")
                } else {
                    "Review this ${assessment.subject} assessment for Class ${assessment.classNum}. Write a short 3-sentence summary of performance, strengths, and what to improve."
                }

                val lines = assessment.questions.mapIndexed { i, q ->
                    val ans = assessment.answers.getOrNull(i)
                    val userAns = if (ans != null) {
                        if (q.type == "mcq") {
                            val selectedIndex = (ans["selectedIndex"] as? Number)?.toInt() ?: -1
                            q.options.getOrNull(selectedIndex) ?: ""
                        } else {
                            (ans["textAnswer"] as? String) ?: ""
                        }
                    } else ""
                    val finalUserAns = userAns.ifBlank { "(skipped)" }
                    val correct = if (q.type == "mcq") q.options.getOrNull(q.correctIndex) ?: q.answer else q.answer
                    "Q${i + 1} [${q.type}]: ${q.question}\nChild answered: $finalUserAns\nCorrect: $correct"
                }.joinToString("\n\n")

                val prompt = "$instructions\n\n$lines"

                val resObj: Any = when {
                    model.startsWith("grok-") -> {
                        val request = LlmResponseRequest(
                            model = model,
                            input = prompt,
                            temperature = null,
                            maxOutputTokens = 1024
                        )
                        xaiApi.createResponse("Bearer ${BuildConfig.XAI_API_KEY}", request)
                    }
                    model.startsWith("gpt-") -> {
                        val request = LlmResponseRequest(
                            model = model,
                            input = prompt,
                            temperature = temp,
                            maxOutputTokens = 1024
                        )
                        openAiApi.createResponse("Bearer ${BuildConfig.OPENAI_API_KEY}", request)
                    }
                    model.startsWith("sarvam-") -> {
                        val request = SarvamChatRequest(
                            model = model,
                            messages = listOf(SarvamChatMessage("user", prompt)),
                            temperature = temp,
                            maxTokens = 1024
                        )
                        sarvamChatApi.chatCompletions(BuildConfig.SARVAM_API_KEY, request)
                    }
                    else -> {
                        val req = GeminiRequest(
                            contents = listOf(GeminiContent(parts = listOf(GeminiPart(text = prompt))))
                        )
                        geminiApi.generateContent(model, BuildConfig.GEMINI_API_KEY, req)
                    }
                }

                val summary = when (resObj) {
                    is LlmResponseResponse -> extractTextFromLlmResponse(resObj)
                    is SarvamChatResponse -> resObj.choices?.firstOrNull()?.message?.content ?: ""
                    is GeminiResponse -> resObj.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text ?: ""
                    else -> ""
                }

                logLlmUsage(
                    childName = childName,
                    purpose = "assessment",
                    model = model,
                    lang = assessment.lang,
                    resObj = resObj
                )

                if (assessment.id.isNotBlank()) {
                    repo.saveSummary(childName, assessment.id, summary, finalScore, assessment.answers)
                    loadAssessments(childName)
                }
            } catch (e: Exception) {
                Log.e("AssessmentVM", "regenerateSummary error", e)
            } finally {
                _quiz.update { it.copy(validating = false) }
            }
        }
    }

    fun loadAssessments(childName: String) {
        currentChild = childName
        _list.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val items = repo.getAssessments(childName)
                _list.update { it.copy(assessments = items, loading = false) }
            } catch (e: Exception) {
                _list.update { it.copy(loading = false, error = e.message) }
            }
        }
    }

    fun generateAssessment(childName: String, subject: String, classNum: String, lang: String) {
        currentChild = childName
        _quiz.update { QuizState(generating = true) }
        viewModelScope.launch {
            try {
                val questions = callGeminiForQuestions(subject, classNum, lang)
                val assessment = Assessment(
                    id        = "",
                    subject   = subject,
                    classNum  = classNum,
                    lang      = lang,
                    date      = Instant.now().toString(),
                    status    = "ready",
                    questions = questions,
                )
                val savedId = repo.saveAssessment(childName, assessment)
                _quiz.update { QuizState(assessment = assessment.copy(id = savedId)) }
                loadAssessments(childName)
            } catch (e: Exception) {
                _quiz.update { QuizState(error = e.message) }
            }
        }
    }

    fun loadAssessment(childName: String, assessmentId: String) {
        currentChild = childName
        _quiz.update { QuizState(generating = true) }
        viewModelScope.launch {
            try {
                val all = repo.getAssessments(childName)
                val a   = all.firstOrNull { it.id == assessmentId }
                if (a != null) {
                    val savedAnswers = a.answers
                    val resumeIdx = calculateResumeIndex(savedAnswers, a.questions)
                    _quiz.update {
                        QuizState(
                            assessment = a,
                            finished = a.status == "completed",
                            summary = a.summary,
                            answers = savedAnswers,
                            currentIndex = resumeIdx
                        )
                    }
                    restoreQuestionState(resumeIdx)
                } else {
                    _quiz.update { QuizState(error = "Assessment not found.") }
                }
            } catch (e: Exception) {
                _quiz.update { QuizState(error = e.message) }
            }
        }
    }

    private fun isQuestionAnswered(q: AssessmentQuestion, ans: Map<String, Any>?): Boolean {
        if (ans == null || ans.isEmpty()) return false
        return if (q.type == "mcq") {
            val selected = (ans["selectedIndex"] as? Number)?.toInt() ?: -1
            selected >= 0
        } else {
            val text = ans["textAnswer"] as? String ?: ""
            text.trim().isNotEmpty()
        }
    }

    private fun calculateResumeIndex(answers: List<Map<String, Any>?>, questions: List<AssessmentQuestion>): Int {
        if (answers.isEmpty()) return 0
        val firstUnanswered = questions.indices.indexOfFirst { idx ->
            !isQuestionAnswered(questions[idx], answers.getOrNull(idx))
        }
        return if (firstUnanswered >= 0) {
            firstUnanswered
        } else {
            (answers.size).coerceAtMost(questions.size - 1).coerceAtLeast(0)
        }
    }

    fun restoreQuestionState(idx: Int) {
        val q = _quiz.value
        val savedAns = q.answers.getOrNull(idx)
        val question = q.assessment?.questions?.getOrNull(idx)
        val isRetryUsed = q.retryUsed.contains(idx) || (savedAns?.get("retryUsed") as? Boolean == true)
        
        if (savedAns == null || savedAns.isEmpty() || (question != null && !isQuestionAnswered(question, savedAns))) {
            _quiz.update {
                it.copy(
                    mcqSelectedIndex = null,
                    mcqFirstWrongIndex = null,
                    mcqPhase = "idle",
                    validationResult = null,
                    validating = false
                )
            }
            if (isRetryUsed) {
                _quiz.update { it.copy(retryUsed = it.retryUsed + idx) }
            }
            return
        }
        
        val type = savedAns["type"] as? String ?: "mcq"
        if (type == "mcq") {
            val selected = (savedAns["selectedIndex"] as? Number)?.toInt()
            val firstWrong = (savedAns["firstWrongIndex"] as? Number)?.toInt()
            _quiz.update {
                it.copy(
                    mcqSelectedIndex = selected,
                    mcqFirstWrongIndex = firstWrong,
                    mcqPhase = "done",
                    validationResult = null,
                    validating = false
                )
            }
        } else {
            val textAns = savedAns["textAnswer"] as? String ?: ""
            val selfGrade = savedAns["selfGrade"] as? String ?: ""
            val aiEarned = (savedAns["aiEarnedMarks"] as? Number)?.toDouble() ?: 0.0
            val feedback = savedAns["aiFeedback"] as? String ?: ""
            val corrections = savedAns["corrections"] as? List<*> ?: emptyList<Any>()
            val correctedAns = savedAns["correctedAnswer"] as? String ?: ""
            
            val verdict = when (selfGrade) {
                "got_it" -> "correct"
                "partial" -> "partial"
                else -> "wrong"
            }
            
            val vResult = mapOf(
                "verdict" to verdict,
                "earnedMarks" to aiEarned,
                "feedback" to feedback,
                "corrections" to corrections,
                "correctedAnswer" to correctedAns
            )
            
            _quiz.update {
                it.copy(
                    mcqSelectedIndex = null,
                    mcqFirstWrongIndex = null,
                    mcqPhase = "idle",
                    validationResult = vResult,
                    validating = false
                )
            }
        }
        if (isRetryUsed) {
            _quiz.update { it.copy(retryUsed = it.retryUsed + idx) }
        }
    }

    fun selectMCQOption(selectedOptionIndex: Int) {
        val qState = _quiz.value
        val q = qState.currentQuestion ?: return
        val currentIdx = qState.currentIndex
        val isCorrect = selectedOptionIndex == q.correctIndex
        
        if (qState.mcqPhase == "idle") {
            _quiz.update {
                it.copy(
                    mcqSelectedIndex = selectedOptionIndex,
                    mcqPhase = if (isCorrect) "done" else "first_wrong",
                    mcqFirstWrongIndex = if (isCorrect) null else selectedOptionIndex
                )
            }
            if (isCorrect) {
                saveMCQAnswer(selectedOptionIndex, null, 1, true)
            }
        } else if (qState.mcqPhase == "first_wrong") {
            if (selectedOptionIndex == qState.mcqFirstWrongIndex) return
            _quiz.update {
                it.copy(
                    mcqSelectedIndex = selectedOptionIndex,
                    mcqPhase = "done"
                )
            }
            saveMCQAnswer(selectedOptionIndex, qState.mcqFirstWrongIndex, 2, selectedOptionIndex == q.correctIndex)
        }
    }

    private fun saveMCQAnswer(selected: Int, firstWrong: Int?, attempts: Int, correct: Boolean) {
        val q = _quiz.value
        val idx = q.currentIndex
        val assessment = q.assessment ?: return
        val currentQ = q.currentQuestion ?: return
        
        val ansMap = mutableMapOf<String, Any>(
            "questionId" to currentQ.id,
            "type" to "mcq",
            "selectedIndex" to selected,
            "attempts" to attempts,
            "correct" to correct
        ).apply {
            if (firstWrong != null) {
                put("firstWrongIndex", firstWrong)
            }
        }
        
        val updatedAnswers = q.answers.toMutableList()
        while (updatedAnswers.size <= idx) {
            updatedAnswers.add(null)
        }
        updatedAnswers[idx] = ansMap
        
        _quiz.update { it.copy(answers = updatedAnswers) }
        
        viewModelScope.launch {
            try {
                repo.saveAssessment(currentChild, assessment.copy(status = "in_progress", answers = updatedAnswers))
            } catch (e: Exception) {
                Log.e("AssessmentVM", "Error saving progress in MCQ select", e)
            }
        }
    }

    fun retryCurrentQuestion() {
        val q = _quiz.value
        val idx = q.currentIndex
        val assessment = q.assessment ?: return
        
        val currentAnswers = q.answers.toMutableList()
        if (idx < currentAnswers.size) {
            currentAnswers[idx] = null
        }
        
        _quiz.update { 
            it.copy(
                answers = currentAnswers,
                retryUsed = it.retryUsed + idx,
                validationResult = null,
                validating = false
            ) 
        }
        
        viewModelScope.launch {
            try {
                repo.saveAssessment(currentChild, assessment.copy(status = "in_progress", answers = currentAnswers))
            } catch (e: Exception) {
                Log.e("AssessmentVM", "Error saving progress in retry", e)
            }
        }
    }

    fun prev() {
        val q = _quiz.value
        if (q.currentIndex > 0) {
            _quiz.update { it.copy(currentIndex = it.currentIndex - 1) }
            restoreQuestionState(q.currentIndex - 1)
        }
    }

    fun clearError() {
        _quiz.update { it.copy(error = null) }
    }

    fun next(currentTypedAnswer: String) {
        val q = _quiz.value
        val assessment = q.assessment ?: return
        val currentQ = q.currentQuestion ?: return
        val idx = q.currentIndex
        val total = assessment.questions.size
        
        val isTextQ = currentQ.type != "mcq"
        val isSavedAnswered = isQuestionAnswered(currentQ, q.answers.getOrNull(idx))
        val hasTyped = isTextQ && currentTypedAnswer.trim().isNotEmpty() && q.validationResult == null && !isSavedAnswered
        if (hasTyped) {
            validateCurrentAnswer(currentTypedAnswer)
            return
        }
        
        val updatedAnswers = q.answers.toMutableList()
        val hasAnswered = q.mcqPhase == "done" || q.validationResult != null
        if (hasAnswered && !isSavedAnswered) {
            val ansMap = buildAnswerMap(currentQ, currentTypedAnswer)
            while (updatedAnswers.size <= idx) {
                updatedAnswers.add(null)
            }
            updatedAnswers[idx] = ansMap
            _quiz.update { it.copy(answers = updatedAnswers) }
        }
        
        viewModelScope.launch {
            try {
                repo.saveAssessment(currentChild, assessment.copy(status = "in_progress", answers = updatedAnswers))
            } catch (e: Exception) {
                Log.e("AssessmentVM", "Error saving progress in next", e)
            }
            
            if (idx + 1 < total) {
                _quiz.update { it.copy(currentIndex = idx + 1) }
                restoreQuestionState(idx + 1)
            } else {
                val firstUnanswered = assessment.questions.indices.firstOrNull { i ->
                    !isQuestionAnswered(assessment.questions[i], updatedAnswers.getOrNull(i))
                }
                if (firstUnanswered != null) {
                    _quiz.update { it.copy(currentIndex = firstUnanswered) }
                    restoreQuestionState(firstUnanswered)
                    _quiz.update { it.copy(error = "Please answer all questions before finishing. Taking you to the first skipped question.") }
                } else {
                    _quiz.update { it.copy(finished = true) }
                    generateSummary()
                }
            }
        }
    }

    fun skipCurrentQuestion() {
        val qState = _quiz.value
        val assessment = qState.assessment ?: return
        val currentQ = qState.currentQuestion ?: return
        val idx = qState.currentIndex
        val total = assessment.questions.size
        
        // Build a skipped answer map
        val ansMap = if (currentQ.type == "mcq") {
            mapOf(
                "questionId" to currentQ.id,
                "type" to "mcq",
                "selectedIndex" to -1,
                "attempts" to 0,
                "correct" to false
            )
        } else {
            mapOf(
                "questionId" to currentQ.id,
                "type" to currentQ.type,
                "textAnswer" to "",
                "selfGrade" to "wrong",
                "aiEarnedMarks" to 0.0,
                "corrections" to emptyList<Any>(),
                "correctedAnswer" to "",
                "aiFeedback" to "Skipped",
                "retryUsed" to false
            )
        }
        
        val updatedAnswers = qState.answers.toMutableList()
        while (updatedAnswers.size <= idx) {
            updatedAnswers.add(null)
        }
        updatedAnswers[idx] = ansMap
        
        _quiz.update {
            it.copy(
                answers = updatedAnswers,
                validationResult = null,
                validating = false
            )
        }
        
        viewModelScope.launch {
            try {
                repo.saveAssessment(currentChild, assessment.copy(status = "in_progress", answers = updatedAnswers))
            } catch (e: Exception) {
                Log.e("AssessmentVM", "Error saving progress in skip", e)
            }
            
            if (idx + 1 < total) {
                _quiz.update { it.copy(currentIndex = idx + 1) }
                restoreQuestionState(idx + 1)
            } else {
                val firstUnanswered = assessment.questions.indices.firstOrNull { i ->
                    !isQuestionAnswered(assessment.questions[i], updatedAnswers.getOrNull(i))
                }
                if (firstUnanswered != null) {
                    _quiz.update { it.copy(currentIndex = firstUnanswered) }
                    restoreQuestionState(firstUnanswered)
                    _quiz.update { it.copy(error = "Please answer all questions before finishing. Taking you to the first skipped question.") }
                } else {
                    _quiz.update { it.copy(finished = true) }
                    generateSummary()
                }
            }
        }
    }

    private fun buildAnswerMap(q: AssessmentQuestion, currentTypedAnswer: String): Map<String, Any> {
        val quizState = _quiz.value
        return if (q.type == "mcq") {
            val selected = quizState.mcqSelectedIndex ?: 0
            val firstWrong = quizState.mcqFirstWrongIndex
            val correct = selected == q.correctIndex
            val attempts = if (firstWrong != null) 2 else 1
            mutableMapOf<String, Any>(
                "questionId" to q.id,
                "type" to "mcq",
                "selectedIndex" to selected,
                "attempts" to attempts,
                "correct" to correct
            ).apply {
                if (firstWrong != null) {
                    put("firstWrongIndex", firstWrong)
                }
            }
        } else {
            val vResult = quizState.validationResult ?: emptyMap()
            val verdict = vResult["verdict"] as? String ?: "wrong"
            val selfGrade = when (verdict) {
                "correct" -> "got_it"
                "partial" -> "partial"
                else -> "wrong"
            }
            val earned = vResult["earnedMarks"] as? Double ?: 0.0
            val corrections = vResult["corrections"] as? List<*> ?: emptyList<Any>()
            val correctedAns = vResult["correctedAnswer"] as? String ?: ""
            val feedback = vResult["feedback"] as? String ?: ""
            val retryUsed = quizState.retryUsed.contains(quizState.currentIndex)
            
            mapOf(
                "questionId" to q.id,
                "type" to q.type,
                "textAnswer" to currentTypedAnswer.trim(),
                "selfGrade" to selfGrade,
                "aiEarnedMarks" to earned,
                "corrections" to corrections,
                "correctedAnswer" to correctedAns,
                "retryUsed" to retryUsed,
                "aiFeedback" to feedback
            )
        }
    }

    fun validateCurrentAnswer(childAnswer: String) {
        val qState = _quiz.value
        val q = qState.currentQuestion ?: return
        val idx = qState.currentIndex
        val assessment = qState.assessment ?: return
        
        _quiz.update { it.copy(validating = true, error = null) }
        
        viewModelScope.launch {
            try {
                if (q.answer.isNotBlank() && isExactMatch(childAnswer, q.answer)) {
                    val result = mapOf(
                        "verdict" to "correct",
                        "earnedMarks" to q.marks,
                        "feedback" to "✓ Spot on! That matches the correct answer.",
                        "corrections" to emptyList<Any>(),
                        "correctedAnswer" to "",
                        "allowRetry" to false
                    )
                    onValidationComplete(idx, childAnswer, result)
                    return@launch
                }
                
                if (childAnswer.trim().isEmpty()) {
                    _quiz.update { it.copy(validating = false, error = "Please type or write your answer first!") }
                    return@launch
                }
                
                if (q.type == "match_columns") {
                    val result = gradeMatchColumnsLocally(q, childAnswer)
                    onValidationComplete(idx, childAnswer, result)
                    return@launch
                }
                
                val validationData = try {
                    val gistContent = placementRepo.getGistFile("aarav_assessment_validation.json")
                    val jsonObj = JSONObject(gistContent)
                    val sysInst = jsonObj.optString("SYSTEM_INSTRUCTION")
                    val gradRules = joinLines(jsonObj.opt("GRADING_RULES"))
                    Pair(sysInst, gradRules)
                } catch (e: Exception) {
                    Log.w("AssessmentVM", "Gist load failed: ${e.message}")
                    Pair(
                        "You are a school teacher grading a student answer.",
                        "Award marks between 0 and total marks. Be fair and encouraging."
                    )
                }
                
                val systemInstruction = validationData.first
                val gradingRules = validationData.second
                
                val model = resolveModel(currentChild, "assessmentModel", assessment.lang)
                val temp = resolveTemperature(currentChild, assessment.lang)
                val contextLine = "Subject: ${assessment.subject}, Class: ${assessment.classNum}\n"
                val marksLine = "Marks for this question: ${q.marks} — award earnedMarks between 0 and ${q.marks} in steps of 0.5.\n"
                val passageLine = if (q.passage.isNotBlank()) "Reading passage:\n\"\"\"\n${q.passage}\n\"\"\"\n" else ""
                val inputLine = if (q.inputSentence.isNotBlank()) "Input sentence: \"${q.inputSentence}\"\n" else ""
                val typeLine = if (q.transformationType.isNotBlank()) "Transformation type: ${q.transformationType}\n" else if (q.tag.isNotBlank()) "Grammar focus: ${q.tag}\n" else ""
                
                val prompt = "$systemInstruction\n$contextLine$marksLine" +
                    "Question type: ${q.type}\n$passageLine" +
                    "Question: ${q.question}\n$inputLine$typeLine" +
                    "Model answer: \"${q.answer}\"\n" +
                    "Student's answer: \"$childAnswer\"\n\n$gradingRules"
                
                var reqObj: Any? = null
                var resObj: Any? = null

                val rawResponse = when {
                    model.startsWith("grok-") -> {
                        val request = LlmResponseRequest(
                            model = model,
                            input = prompt,
                            temperature = null, // Grok does not support temperature parameter
                            maxOutputTokens = 1024,
                            text = LlmTextFormat(LlmJsonSchemaFormat(name = "answer_validate", schema = buildValidationSchema()))
                        )
                        reqObj = request
                        val res = xaiApi.createResponse("Bearer ${BuildConfig.XAI_API_KEY}", request)
                        resObj = res
                        extractTextFromLlmResponse(res)
                    }
                    model.startsWith("gpt-") -> {
                        val request = LlmResponseRequest(
                            model = model,
                            input = prompt,
                            temperature = temp,
                            maxOutputTokens = 1024,
                            text = LlmTextFormat(LlmJsonSchemaFormat(name = "answer_validate", schema = buildValidationSchema()))
                        )
                        reqObj = request
                        val res = openAiApi.createResponse("Bearer ${BuildConfig.OPENAI_API_KEY}", request)
                        resObj = res
                        extractTextFromLlmResponse(res)
                    }
                    model.startsWith("sarvam-") -> {
                        val request = SarvamChatRequest(
                            model = model,
                            messages = listOf(
                                SarvamChatMessage("system", "You are a school teacher grading a student answer. Output ONLY raw JSON — no markdown fences, no extra text."),
                                SarvamChatMessage("user", prompt)
                            ),
                            temperature = temp,
                            maxTokens = 1024,
                            responseFormat = SarvamResponseFormat()
                        )
                        reqObj = request
                        val res = sarvamChatApi.chatCompletions(BuildConfig.SARVAM_API_KEY, request)
                        resObj = res
                        res.choices?.firstOrNull()?.message?.content ?: "{}"
                    }
                    else -> {
                        val req = GeminiRequest(
                            contents = listOf(GeminiContent(parts = listOf(GeminiPart(text = prompt)))),
                            generationConfig = GeminiGenConfig(
                                temperature = 0.1,
                                responseMimeType = "application/json",
                                responseSchema = buildGeminiValidationSchema()
                            )
                        )
                        reqObj = req
                        val resp = geminiApi.generateContent(model, BuildConfig.GEMINI_API_KEY, req)
                        resObj = resp
                        resp.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text ?: "{}"
                    }
                }

                saveDebugJson(idx, reqObj!!, resObj!!)
                
                resObj?.let {
                    logLlmUsage(
                        childName = currentChild,
                        purpose = "assessment",
                        model = model,
                        lang = assessment.lang,
                        resObj = it
                    )
                }
                
                val clean = cleanResponseText(rawResponse)
                val parsed = parseValidationResult(clean, q.marks)
                onValidationComplete(idx, childAnswer, parsed)
                
            } catch (e: Exception) {
                Log.e("AssessmentVM", "Validation error", e)
                _quiz.update {
                    it.copy(
                        validating = false,
                        error = "Assessment failed:\n${getFullErrorDescription(e)}"
                    )
                }
            }
        }
    }

    fun validateCurrentHandwrittenAnswer(imageBase64: String, displayKey: String) {
        val qState = _quiz.value
        val q = qState.currentQuestion ?: return
        val idx = qState.currentIndex
        val assessment = qState.assessment ?: return
        
        _quiz.update { it.copy(validating = true, error = null) }
        
        viewModelScope.launch {
            try {
                val validationData = try {
                    val gistContent = placementRepo.getGistFile("aarav_assessment_validation.json")
                    val jsonObj = JSONObject(gistContent)
                    val sysInst = jsonObj.optString("SYSTEM_INSTRUCTION")
                    val gradRules = joinLines(jsonObj.opt("GRADING_RULES"))
                    Pair(sysInst, gradRules)
                } catch (e: Exception) {
                    Log.w("AssessmentVM", "Gist load failed: ${e.message}")
                    Pair(
                        "You are a school teacher grading a student answer.",
                        "Award marks between 0 and total marks. Be fair and encouraging."
                    )
                }
                
                val systemInstruction = validationData.first
                val gradingRules = validationData.second
                
                val model = "gemini-3.5-flash"
                
                val contextLine = "Subject: ${assessment.subject}, Class: ${assessment.classNum}\n"
                val marksLine = "Marks for this question: ${q.marks} — award earnedMarks between 0 and ${q.marks} in steps of 0.5.\n"
                val passageLine = if (q.passage.isNotBlank()) "Reading passage:\n\"\"\"\n${q.passage}\n\"\"\"\n" else ""
                val inputLine = if (q.inputSentence.isNotBlank()) "Input sentence: \"${q.inputSentence}\"\n" else ""
                val typeLine = if (q.transformationType.isNotBlank()) "Transformation type: ${q.transformationType}\n" else if (q.tag.isNotBlank()) "Grammar focus: ${q.tag}\n" else ""
                
                val prompt = "$systemInstruction\n$contextLine$marksLine" +
                    "Question type: ${q.type}\n$passageLine" +
                    "Question: ${q.question}\n$inputLine$typeLine" +
                    "Model answer: \"${q.answer}\"\n" +
                    "The student has handwritten their answer in the attached image. " +
                    "First transcribe exactly what you can read from the handwriting, then grade it.\n" +
                    "In your feedback, start with \"Transcribed: ...\" so the student knows what you read.\n\n$gradingRules"
                
                val req = GeminiRequest(
                    contents = listOf(
                        GeminiContent(
                            parts = listOf(
                                GeminiPart(text = prompt),
                                GeminiPart(inlineData = GeminiInlineData(mimeType = "image/png", data = imageBase64))
                            )
                        )
                    ),
                    generationConfig = GeminiGenConfig(
                        temperature = 0.1,
                        responseMimeType = "application/json",
                        responseSchema = buildGeminiValidationSchema()
                    )
                )
                val resp = geminiApi.generateContent(model, BuildConfig.GEMINI_API_KEY, req)
                saveDebugJson(idx, req, resp)
                logLlmUsage(
                    childName = currentChild,
                    purpose = "assessment",
                    model = model,
                    lang = assessment.lang,
                    resObj = resp
                )
                val rawResponse = resp.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text ?: "{}"
                
                val clean = cleanResponseText(rawResponse)
                val parsed = parseValidationResult(clean, q.marks)
                onValidationComplete(idx, displayKey, parsed)
                
            } catch (e: Exception) {
                Log.e("AssessmentVM", "Vision validation error", e)
                _quiz.update {
                    it.copy(
                        validating = false,
                        error = "Assessment failed:\n${getFullErrorDescription(e)}"
                    )
                }
            }
        }
    }

    private fun buildValidationSchema(): com.google.gson.JsonElement {
        return com.google.gson.JsonObject().apply {
            addProperty("type", "object")
            add("properties", com.google.gson.JsonObject().apply {
                add("earnedMarks", com.google.gson.JsonObject().apply { addProperty("type", "number") })
                add("feedback", com.google.gson.JsonObject().apply { addProperty("type", "string") })
                add("corrections", com.google.gson.JsonObject().apply {
                    addProperty("type", "array")
                    add("items", com.google.gson.JsonObject().apply {
                        addProperty("type", "object")
                        add("properties", com.google.gson.JsonObject().apply {
                            add("original", com.google.gson.JsonObject().apply { addProperty("type", "string") })
                            add("corrected", com.google.gson.JsonObject().apply { addProperty("type", "string") })
                            add("type", com.google.gson.JsonObject().apply { addProperty("type", "string") })
                        })
                        add("required", com.google.gson.JsonArray().apply {
                            add("original")
                            add("corrected")
                            add("type")
                        })
                    })
                })
                add("correctedAnswer", com.google.gson.JsonObject().apply { addProperty("type", "string") })
                add("allowRetry", com.google.gson.JsonObject().apply { addProperty("type", "boolean") })
            })
            add("required", com.google.gson.JsonArray().apply {
                add("earnedMarks")
                add("feedback")
                add("corrections")
                add("correctedAnswer")
                add("allowRetry")
            })
            addProperty("additionalProperties", false)
        }
    }

    private fun buildGeminiValidationSchema(): com.google.gson.JsonElement {
        val schema = buildValidationSchema().asJsonObject.deepCopy()
        schema.remove("additionalProperties")
        return schema
    }

    private fun parseValidationResult(raw: String, questionMarks: Double): Map<String, Any> {
        val parsed = JSONObject(raw)
        val earned = parsed.optDouble("earnedMarks", 0.0)
        val clamped = earned.coerceIn(0.0, questionMarks)
        val rounded = Math.round(clamped * 2.0) / 2.0
        
        val verdict = when {
            rounded >= questionMarks -> "correct"
            rounded > 0.0 -> "partial"
            else -> "wrong"
        }
        
        val correctionsList = mutableListOf<Map<String, String>>()
        val arr = parsed.optJSONArray("corrections")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val orig = obj.optString("original", "")
                val corr = obj.optString("corrected", "")
                val type = obj.optString("type", "spelling")
                if (orig.isNotBlank() && corr.isNotBlank()) {
                    correctionsList.add(mapOf("original" to orig, "corrected" to corr, "type" to type))
                }
            }
        }
        
        val correctedAnswer = parsed.optString("correctedAnswer", "").trim()
        val allowRetry = parsed.optBoolean("allowRetry", false)
        val feedback = parsed.optString("feedback", "")
        
        return mapOf(
            "verdict" to verdict,
            "earnedMarks" to rounded,
            "feedback" to feedback,
            "corrections" to correctionsList,
            "correctedAnswer" to correctedAnswer,
            "allowRetry" to allowRetry
        )
    }

    private suspend fun onValidationComplete(idx: Int, childAnswer: String, result: Map<String, Any>) {
        _quiz.update {
            it.copy(
                validating = false,
                validationResult = result
            )
        }
        
        val q = _quiz.value
        val assessment = q.assessment ?: return
        val currentQ = assessment.questions.getOrNull(idx) ?: return
        
        val ansMap = buildAnswerMap(currentQ, childAnswer)
        val updatedAnswers = q.answers.toMutableList()
        while (updatedAnswers.size <= idx) {
            updatedAnswers.add(null)
        }
        updatedAnswers[idx] = ansMap
        
        _quiz.update { it.copy(answers = updatedAnswers) }
        
        try {
            repo.saveAssessment(currentChild, assessment.copy(status = "in_progress", answers = updatedAnswers))
        } catch (e: Exception) {
            Log.e("AssessmentVM", "Error saving progress in validation complete", e)
        }
    }

    private fun normalise(s: String): String {
        return s.trim()
            .lowercase()
            .replace(Regex("\\s+"), " ")
            .replace(Regex("[.!?]+$"), "")
    }

    private fun isExactMatch(childAnswer: String, correctAnswer: String): Boolean {
        return normalise(childAnswer) == normalise(correctAnswer)
    }

    private fun gradeMatchColumnsLocally(q: AssessmentQuestion, childAnswer: String): Map<String, Any> {
        val totalItems = q.columnA.size
        if (totalItems == 0) {
            return mapOf(
                "verdict" to "wrong",
                "earnedMarks" to 0.0,
                "feedback" to "Not quite.",
                "corrections" to emptyList<Any>(),
                "correctedAnswer" to "",
                "allowRetry" to false
            )
        }
        
        var correctCount = 0
        
        // Normalize child answer for searching: replace all spaces and make lowercase.
        val normalizedAns = childAnswer.lowercase()
            .replace(" ", "")
            .replace("–", "-") // convert en-dash to hyphen
            .replace("—", "-") // convert em-dash to hyphen
            
        q.columnA.forEachIndexed { idx, itemA ->
            val correctLetter = q.correctMatches.getOrNull(idx)?.lowercase() ?: ""
            if (correctLetter.isNotBlank()) {
                val cleanItemA = itemA.lowercase().replace(" ", "")
                val expectedPattern1 = "$cleanItemA-$correctLetter"
                val expectedPattern2 = "${idx + 1}-$correctLetter"
                if (normalizedAns.contains(expectedPattern1) || normalizedAns.contains(expectedPattern2)) {
                    correctCount++
                }
            }
        }
        
        val marksPerMatch = q.marks / totalItems
        var earned = correctCount * marksPerMatch
        
        // Round earned marks to steps of 0.5
        earned = Math.round(earned * 2.0) / 2.0
        
        // If correctCount > 0, ensure it is at least 0.5 marks
        if (correctCount > 0 && earned < 0.5) {
            earned = 0.5
        }
        
        // Ensure it does not exceed total marks
        if (earned > q.marks) {
            earned = q.marks
        }
        
        val verdict = when {
            earned >= q.marks -> "correct"
            earned > 0.0 -> "partial"
            else -> "wrong"
        }
        
        val feedback = when (verdict) {
            "correct" -> "✓ Perfect matching! All $correctCount correct."
            "partial" -> "🟡 Partially correct! You matched $correctCount out of $totalItems correctly."
            else -> "💪 Not quite. Check the correct matches below."
        }
        
        return mapOf(
            "verdict" to verdict,
            "earnedMarks" to earned,
            "feedback" to feedback,
            "corrections" to emptyList<Any>(),
            "correctedAnswer" to "",
            "allowRetry" to false
        )
    }

    private fun generateSummary() {
        val quizState = _quiz.value
        val assessment = quizState.assessment ?: return
        viewModelScope.launch {
            try {
                val model = resolveModel(currentChild, "assessmentModel", assessment.lang)
                val temp = resolveTemperature(currentChild, assessment.lang)

                val langName = when (assessment.lang.lowercase()) {
                    "hi" -> "Hindi"
                    "pa" -> "Punjabi"
                    else -> "English"
                }

                val earned = quizState.earnedMarks
                val total = quizState.totalMarks
                val finalScore = if (total > 0.0) Math.round((earned / total) * 100.0).toDouble() else 0.0

                val gistContent = try {
                    placementRepo.getGistFile("aarav_assessment_summary.json")
                } catch (_: Exception) { null }

                val instructions = if (gistContent != null) {
                    val jsonObj = JSONObject(gistContent)
                    joinLines(jsonObj.opt("INSTRUCTIONS"))
                        .replace("{{score}}", finalScore.toInt().toString())
                        .replace("{{langName}}", langName)
                        .replace("{{chapterContext}}", "This is a GENERIC (curriculum-wide) assessment. Leave conceptMastery as an empty array [].")
                } else {
                    "Review this ${assessment.subject} assessment for Class ${assessment.classNum}. Write a short 3-sentence summary of performance, strengths, and what to improve."
                }

                val lines = assessment.questions.mapIndexed { i, q ->
                    val ans = quizState.answers.getOrNull(i)
                    val userAns = if (ans != null) {
                        if (q.type == "mcq") {
                            val selectedIndex = (ans["selectedIndex"] as? Number)?.toInt() ?: -1
                            q.options.getOrNull(selectedIndex) ?: ""
                        } else {
                            (ans["textAnswer"] as? String) ?: ""
                        }
                    } else ""
                    val finalUserAns = userAns.ifBlank { "(skipped)" }
                    val correct = if (q.type == "mcq") q.options.getOrNull(q.correctIndex) ?: q.answer else q.answer
                    "Q${i + 1} [${q.type}]: ${q.question}\nChild answered: $finalUserAns\nCorrect: $correct"
                }.joinToString("\n\n")

                val prompt = "$instructions\n\n$lines"

                val resObj: Any = when {
                    model.startsWith("grok-") -> {
                        val request = LlmResponseRequest(
                            model = model,
                            input = prompt,
                            temperature = null, // Grok does not support temperature parameter
                            maxOutputTokens = 1024
                        )
                        xaiApi.createResponse("Bearer ${BuildConfig.XAI_API_KEY}", request)
                    }
                    model.startsWith("gpt-") -> {
                        val request = LlmResponseRequest(
                            model = model,
                            input = prompt,
                            temperature = temp,
                            maxOutputTokens = 1024
                        )
                        openAiApi.createResponse("Bearer ${BuildConfig.OPENAI_API_KEY}", request)
                    }
                    model.startsWith("sarvam-") -> {
                        val request = SarvamChatRequest(
                            model = model,
                            messages = listOf(SarvamChatMessage("user", prompt)),
                            temperature = temp,
                            maxTokens = 1024
                        )
                        sarvamChatApi.chatCompletions(BuildConfig.SARVAM_API_KEY, request)
                    }
                    else -> {
                        val req = GeminiRequest(
                            contents = listOf(GeminiContent(parts = listOf(GeminiPart(text = prompt))))
                        )
                        geminiApi.generateContent(model, BuildConfig.GEMINI_API_KEY, req)
                    }
                }

                val summary = when (resObj) {
                    is LlmResponseResponse -> extractTextFromLlmResponse(resObj)
                    is SarvamChatResponse -> resObj.choices?.firstOrNull()?.message?.content ?: ""
                    is GeminiResponse -> resObj.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text ?: ""
                    else -> ""
                }

                logLlmUsage(
                    childName = currentChild,
                    purpose = "assessment",
                    model = model,
                    lang = assessment.lang,
                    resObj = resObj
                )

                if (assessment.id.isNotBlank()) {
                    repo.saveSummary(currentChild, assessment.id, summary, finalScore, quizState.answers)
                }
                _quiz.update { it.copy(summary = summary) }
            } catch (e: Exception) {
                Log.e("AssessmentVM", "generateSummary error", e)
            }
        }
    }

    private suspend fun callGeminiForQuestions(subject: String, classNum: String, lang: String): List<AssessmentQuestion> {
        val model = resolveModel(currentChild, "assessmentModel", lang)
        val temp = resolveTemperature(currentChild, lang)

        // Load prompt from Gist
        val gistContent = try {
            placementRepo.getGistFile("aarav_assessment_generate.json")
        } catch (_: Exception) { null }

        val prompt = if (gistContent != null) {
            val jsonObj = JSONObject(gistContent)
            val preamble = joinLines(jsonObj.opt("PREAMBLE"))
                .replace("{{subject}}", subject)
                .replace("{{classNum}}", classNum)
            
            val isLang = listOf("hindi", "punjabi", "english").contains(subject.lowercase())
            val rulesKey = if (isLang) "LANGUAGE_RULES" else "CONTENT_RULES"
            val langName = when (lang.lowercase()) {
                "hi" -> "Hindi"
                "pa" -> "Punjabi"
                else -> "English"
            }
            val rules = joinLines(jsonObj.opt(rulesKey))
                .replace("{{langName}}", langName)
                .replace("{{targetMarks}}", "8")

            "$preamble\n\n$rules"
        } else {
            buildGeneratePrompt(subject, classNum, lang)
        }

        val resObj: Any = when {
            model.startsWith("grok-") -> {
                val schemaJson = buildGenerateSchema()
                val request = LlmResponseRequest(
                    model = model,
                    input = prompt,
                    temperature = null, // Grok does not support temperature parameter
                    maxOutputTokens = 4096,
                    text = LlmTextFormat(LlmJsonSchemaFormat(name = "assessment_generate", schema = schemaJson))
                )
                xaiApi.createResponse("Bearer ${BuildConfig.XAI_API_KEY}", request)
            }
            model.startsWith("gpt-") -> {
                val schemaJson = buildGenerateSchema()
                val request = LlmResponseRequest(
                    model = model,
                    input = prompt,
                    temperature = temp,
                    maxOutputTokens = 4096,
                    text = LlmTextFormat(LlmJsonSchemaFormat(name = "assessment_generate", schema = schemaJson))
                )
                openAiApi.createResponse("Bearer ${BuildConfig.OPENAI_API_KEY}", request)
            }
            model.startsWith("sarvam-") -> {
                val request = SarvamChatRequest(
                    model = model,
                    messages = listOf(
                        SarvamChatMessage("system", "You are a CBSE test generator. Output ONLY valid JSON array starting with [ and ending with ]."),
                        SarvamChatMessage("user", prompt)
                    ),
                    temperature = temp,
                    maxTokens = 4096,
                    responseFormat = SarvamResponseFormat()
                )
                sarvamChatApi.chatCompletions(BuildConfig.SARVAM_API_KEY, request)
            }
            else -> {
                val req = GeminiRequest(
                    contents = listOf(GeminiContent(parts = listOf(GeminiPart(text = prompt)))),
                    generationConfig = GeminiGenConfig(
                        temperature = temp,
                        responseMimeType = "application/json"
                    )
                )
                geminiApi.generateContent(model, BuildConfig.GEMINI_API_KEY, req)
            }
        }

        val rawResponse = when (resObj) {
            is LlmResponseResponse -> extractTextFromLlmResponse(resObj)
            is SarvamChatResponse -> resObj.choices?.firstOrNull()?.message?.content ?: "[]"
            is GeminiResponse -> resObj.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text ?: "[]"
            else -> "[]"
        }

        logLlmUsage(
            childName = currentChild,
            purpose = "assessment",
            model = model,
            lang = lang,
            resObj = resObj
        )

        val clean = cleanResponseText(rawResponse)
        return parseQuestions(clean)
    }

    private fun buildGeneratePrompt(subject: String, classNum: String, lang: String): String = """
You are an expert Indian school teacher (CBSE Class $classNum, subject: $subject, language: $lang).
Generate exactly 8 assessment questions as a JSON array. The array must start with [ and end with ].
Include a mix of:
- 3 MCQ questions (type="mcq", options=4 strings, correctIndex=0-3)
- 2 fill_blank (type="fill_blank", question has ___ blanks, answer=correct filled sentence)
- 1 short_answer (type="short_answer", answer=expected answer)
- 1 transformation (type="transformation", inputSentence=sentence to transform, answer=transformed)
- 1 error_correction (type="error_correction", inputSentence=erroneous sentence, answer=corrected)

Each object: {"id":"q1","type":"...","question":"...","options":[],"correctIndex":-1,"answer":"...","inputSentence":"","tag":"","blanks":[]}
Return ONLY the JSON array, no markdown, no explanation.
""".trimIndent()

    private fun parseQuestions(text: String): List<AssessmentQuestion> {
        return try {
            val start = text.indexOf('[')
            val end   = text.lastIndexOf(']')
            if (start < 0 || end < 0) return emptyList()
            val arrayJson = text.substring(start, end + 1)
            val arr = JSONArray(arrayJson)
            val list = mutableListOf<AssessmentQuestion>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val opts = obj.optJSONArray("options")
                val optList = if (opts != null) {
                    (0 until opts.length()).map { opts.getString(it) }
                } else emptyList()

                val blanksArr = obj.optJSONArray("blanks")
                val blanksList = if (blanksArr != null) {
                    (0 until blanksArr.length()).map { blanksArr.getString(it) }
                } else emptyList()

                val jumbledArr = obj.optJSONArray("jumbledWords")
                val jumbledList = if (jumbledArr != null) {
                    (0 until jumbledArr.length()).map { jumbledArr.getString(it) }
                } else emptyList()

                val colAArr = obj.optJSONArray("columnA")
                val colAList = if (colAArr != null) {
                    (0 until colAArr.length()).map { colAArr.getString(it) }
                } else emptyList()

                val colBArr = obj.optJSONArray("columnB")
                val colBList = if (colBArr != null) {
                    (0 until colBArr.length()).map { colBArr.getString(it) }
                } else emptyList()

                val matchesArr = obj.optJSONArray("correctMatches")
                val matchesList = if (matchesArr != null) {
                    (0 until matchesArr.length()).map { matchesArr.getString(it) }
                } else emptyList()

                val optExplsArr = obj.optJSONArray("optionExplanations")
                val optExplsList = if (optExplsArr != null) {
                    (0 until optExplsArr.length()).map { optExplsArr.getString(it) }
                } else emptyList()

                list.add(
                    AssessmentQuestion(
                        id            = obj.optString("id", "q${i + 1}"),
                        type          = obj.optString("type", "mcq"),
                        question      = obj.optString("question", ""),
                        options       = optList,
                        correctIndex  = obj.optInt("correctIndex", -1),
                        answer        = obj.optString("answer", ""),
                        inputSentence = obj.optString("inputSentence", ""),
                        tag           = obj.optString("tag", ""),
                        blanks        = blanksList,
                        marks         = obj.optDouble("marks", 1.0),
                        difficulty    = obj.optString("difficulty", "medium"),
                        category      = obj.optString("category", "General"),
                        explanation   = obj.optString("explanation", ""),
                        optionExplanations = optExplsList,
                        passage       = obj.optString("passage", ""),
                        hint          = obj.optString("hint", ""),
                        transformationType = obj.optString("transformationType", ""),
                        jumbledWords  = jumbledList,
                        columnA       = colAList,
                        columnB       = colBList,
                        correctMatches = matchesList,
                    )
                )
            }
            list
        } catch (e: Exception) {
            Log.e("AssessmentVM", "Error parsing questions", e)
            emptyList()
        }
    }

    private fun logLlmUsage(
        childName: String,
        purpose: String,
        model: String,
        lang: String,
        resObj: Any
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (childName.isBlank()) return@launch
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
                    childName = childName,
                    purpose = purpose,
                    provider = model,
                    language = lang,
                    inputTokens = inputTokens,
                    outputTokens = outputTokens,
                    cachedInputTokens = cachedTokens
                )
            } catch (e: Exception) {
                Log.e("AssessmentVM", "Error recording LLM usage in logLlmUsage", e)
            }
        }
    }

    /** Resolve the quiz LLM model from global settings for the given lang. */
    private suspend fun resolveModel(childName: String, field: String, lang: String = "en"): String {
        return try {
            globalSettingsRepo.load().resolve("quiz", lang).llm.model
        } catch (_: Exception) {
            "gemini-3.5-flash"
        }
    }

    private suspend fun resolveTemperature(childName: String, lang: String = "en"): Double {
        return try {
            globalSettingsRepo.load().resolve("quiz", lang).llm.temperature
        } catch (_: Exception) {
            0.7
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

    private fun joinLines(obj: Any?): String {
        if (obj == null) return ""
        if (obj is JSONArray) {
            return (0 until obj.length()).map { obj.getString(it) }.joinToString("\n")
        }
        return obj.toString()
    }

    private fun saveDebugJson(idx: Int, request: Any, response: Any) {
        try {
            val gson = com.google.gson.GsonBuilder().setPrettyPrinting().serializeNulls().create()
            val reqJson = gson.toJson(request)
            val resJson = gson.toJson(response)
            _quiz.update {
                it.copy(
                    debugJsons = it.debugJsons + (idx to (reqJson to resJson))
                )
            }
        } catch (e: Exception) {
            Log.e("AssessmentVM", "Failed to save debug JSON", e)
        }
    }

    private fun cleanResponseText(raw: String): String {
        var cleaned = raw.replace(Regex("<think>[\\s\\S]*?</think>"), "")
        cleaned = cleaned.replace(Regex("```(?:json)?\\s*([\\s\\S]*?)\\s*```"), "$1")
        return cleaned.trim()
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

    private fun buildGenerateSchema(): com.google.gson.JsonElement {
        return com.google.gson.JsonObject().apply {
            addProperty("type", "object")
            add("properties", com.google.gson.JsonObject().apply {
                add("questions", com.google.gson.JsonObject().apply {
                    addProperty("type", "array")
                    add("items", com.google.gson.JsonObject().apply {
                        addProperty("type", "object")
                        add("properties", com.google.gson.JsonObject().apply {
                            add("id", com.google.gson.JsonObject().apply { addProperty("type", "string") })
                            add("type", com.google.gson.JsonObject().apply { addProperty("type", "string") })
                            add("question", com.google.gson.JsonObject().apply { addProperty("type", "string") })
                            add("options", com.google.gson.JsonObject().apply {
                                addProperty("type", "array")
                                add("items", com.google.gson.JsonObject().apply { addProperty("type", "string") })
                            })
                            add("correctIndex", com.google.gson.JsonObject().apply { addProperty("type", "integer") })
                            add("answer", com.google.gson.JsonObject().apply { addProperty("type", "string") })
                            add("inputSentence", com.google.gson.JsonObject().apply { addProperty("type", "string") })
                            add("tag", com.google.gson.JsonObject().apply { addProperty("type", "string") })
                        })
                        add("required", com.google.gson.JsonArray().apply {
                            add("id")
                            add("type")
                            add("question")
                            add("answer")
                        })
                    })
                })
            })
            add("required", com.google.gson.JsonArray().apply {
                add("questions")
            })
            addProperty("additionalProperties", false)
        }
    }
}
