package com.krishanagarwal.mynoo.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.krishanagarwal.mynoo.data.api.GeminiApi
import com.krishanagarwal.mynoo.data.api.GeminiContent
import com.krishanagarwal.mynoo.data.api.GeminiPart
import com.krishanagarwal.mynoo.BuildConfig
import com.krishanagarwal.mynoo.data.api.GeminiRequest
import com.krishanagarwal.mynoo.data.repository.Assessment
import com.krishanagarwal.mynoo.data.repository.AssessmentQuestion
import com.krishanagarwal.mynoo.data.repository.AssessmentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
    val answers:       Map<Int, String> = emptyMap(),
    val revealed:      Set<Int>         = emptySet(),
    val finished:      Boolean          = false,
    val generating:    Boolean          = false,
    val summary:       String           = "",
    val error:         String?          = null,
)

val QuizState.currentQuestion get() = assessment?.questions?.getOrNull(currentIndex)
val QuizState.score get(): Int {
    val qs = assessment?.questions ?: return 0
    return qs.indices.count { i ->
        val q = qs[i]
        val ans = answers[i] ?: return@count false
        if (q.type == "mcq") ans == q.correctIndex.toString() else false
    }
}

@HiltViewModel
class AssessmentViewModel @Inject constructor(
    private val repo:      AssessmentRepository,
    private val geminiApi: GeminiApi,
) : ViewModel() {

    private val _list = MutableStateFlow(AssessmentListState())
    val list: StateFlow<AssessmentListState> = _list

    private val _quiz = MutableStateFlow(QuizState())
    val quiz: StateFlow<QuizState> = _quiz

    private var currentChild = ""

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
                _quiz.update { QuizState(assessment = a) }
            } catch (e: Exception) {
                _quiz.update { QuizState(error = e.message) }
            }
        }
    }

    fun answer(value: String) {
        val idx = _quiz.value.currentIndex
        _quiz.update { it.copy(answers = it.answers + (idx to value)) }
    }

    fun revealAnswer() {
        val idx = _quiz.value.currentIndex
        _quiz.update { it.copy(revealed = it.revealed + idx) }
    }

    fun next() {
        val q = _quiz.value
        val size = q.assessment?.questions?.size ?: 0
        if (q.currentIndex + 1 < size) {
            _quiz.update { it.copy(currentIndex = it.currentIndex + 1) }
        } else {
            _quiz.update { it.copy(finished = true) }
            generateSummary()
        }
    }

    private fun generateSummary() {
        val q = _quiz.value
        val assessment = q.assessment ?: return
        viewModelScope.launch {
            try {
                val prompt = buildSummaryPrompt(assessment, q.answers)
                val req = GeminiRequest(
                    contents = listOf(GeminiContent(parts = listOf(GeminiPart(text = prompt))))
                )
                val resp = geminiApi.generateContent(BuildConfig.GEMINI_API_KEY, req)
                val summary = resp.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text ?: ""
                if (assessment.id.isNotBlank()) {
                    repo.saveSummary(currentChild, assessment.id, summary)
                }
                _quiz.update { it.copy(summary = summary) }
            } catch (_: Exception) { /* best-effort */ }
        }
    }

    private suspend fun callGeminiForQuestions(subject: String, classNum: String, lang: String): List<AssessmentQuestion> {
        val prompt = buildGeneratePrompt(subject, classNum, lang)
        val req = GeminiRequest(
            contents = listOf(GeminiContent(parts = listOf(GeminiPart(text = prompt))))
        )
        val resp = geminiApi.generateContent(BuildConfig.GEMINI_API_KEY, req)
        val text = resp.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text ?: "[]"
        return parseQuestions(text)
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

    private fun buildSummaryPrompt(assessment: Assessment, answers: Map<Int, String>): String {
        val lines = assessment.questions.mapIndexed { i, q ->
            val userAns = answers[i] ?: "(skipped)"
            val correct = if (q.type == "mcq") q.options.getOrNull(q.correctIndex) ?: q.answer else q.answer
            "Q${i + 1} [${q.type}]: ${q.question}\nChild answered: $userAns\nCorrect: $correct"
        }.joinToString("\n\n")
        return "Review this ${assessment.subject} assessment for Class ${assessment.classNum}. " +
                "Write a short 3-sentence summary of performance, strengths, and what to improve.\n\n$lines"
    }

    private fun parseQuestions(text: String): List<AssessmentQuestion> {
        return try {
            val start = text.indexOf('[')
            val end   = text.lastIndexOf(']')
            if (start < 0 || end < 0) return emptyList()
            val arr = JSONArray(text.substring(start, end + 1))
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                val opts = mutableListOf<String>()
                val optArr = obj.optJSONArray("options")
                if (optArr != null) for (j in 0 until optArr.length()) opts += optArr.getString(j)
                val blanks = mutableListOf<String>()
                val bArr = obj.optJSONArray("blanks")
                if (bArr != null) for (j in 0 until bArr.length()) blanks += bArr.getString(j)
                AssessmentQuestion(
                    id           = obj.optString("id", UUID.randomUUID().toString()),
                    type         = obj.optString("type", "short_answer"),
                    question     = obj.optString("question", ""),
                    options      = opts,
                    correctIndex = obj.optInt("correctIndex", -1),
                    answer       = obj.optString("answer", ""),
                    inputSentence= obj.optString("inputSentence", ""),
                    tag          = obj.optString("tag", ""),
                    blanks       = blanks,
                )
            }
        } catch (_: Exception) { emptyList() }
    }
}
