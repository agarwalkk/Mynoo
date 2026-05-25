package com.krishanagarwal.mynoo.data.repository

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

data class AssessmentQuestion(
    val id:            String        = "",
    val type:          String        = "mcq",
    val question:      String        = "",
    val options:       List<String>  = emptyList(),
    val correctIndex:  Int           = -1,
    val answer:        String        = "",
    val inputSentence: String        = "",
    val tag:           String        = "",
    val blanks:        List<String>  = emptyList(),
    val marks:         Double        = 1.0,
    val difficulty:    String        = "",
    val category:      String        = "",
    val explanation:   String        = "",
    val optionExplanations: List<String> = emptyList(),
    val passage:       String        = "",
    val hint:          String        = "",
    val transformationType: String   = "",
    val jumbledWords:  List<String>  = emptyList(),
    val columnA:       List<String>  = emptyList(),
    val columnB:       List<String>  = emptyList(),
    val correctMatches: List<String> = emptyList(),
)

data class Assessment(
    val id:            String                  = "",
    val subject:       String                  = "",
    val classNum:      String                  = "",
    val lang:          String                  = "en",
    val date:          String                  = "",
    val status:        String                  = "ready",
    val questions:     List<AssessmentQuestion> = emptyList(),
    val summary:       String                  = "",
    val createdAt:     String                  = "",
    val completedAt:   String                  = "",
    val title:         String                  = "",
    val chapterTitles: List<String>            = emptyList(),
    val score:         Double?                 = null,
    val answers:       List<Map<String, Any>?> = emptyList(),
)

@Singleton
class AssessmentRepository @Inject constructor(
    private val db: FirebaseFirestore,
) {
    private suspend fun resolveChildDocId(childName: String): String {
        // 1. Try case-sensitive exact match
        try {
            val doc = db.collection("kids").document(childName).get(Source.DEFAULT).await()
            if (doc.exists()) return childName
        } catch (_: Exception) {}

        // 2. Try lowercase match
        val lower = childName.lowercase()
        try {
            val doc = db.collection("kids").document(lower).get(Source.DEFAULT).await()
            if (doc.exists()) return lower
        } catch (_: Exception) {}

        // 3. Match case-insensitively across all kids
        try {
            val snap = db.collection("kids").get(Source.DEFAULT).await()
            for (doc in snap.documents) {
                if (doc.id.equals(childName, ignoreCase = true)) {
                    return doc.id
                }
                val nameField = doc.getString("name")
                if (nameField != null && nameField.equals(childName, ignoreCase = true)) {
                    return doc.id
                }
            }
        } catch (_: Exception) {}

        return childName
    }

    private suspend fun col(childName: String) =
        db.collection("kids").document(resolveChildDocId(childName)).collection("assessments")

    suspend fun getAssessments(childName: String): List<Assessment> {
        val assessmentsCol = try {
            col(childName)
        } catch (_: Exception) {
            return emptyList()
        }

        // Try querying using createdAt first, fallback to date, fallback to unordered
        val snap = try {
            assessmentsCol.orderBy("createdAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
                .limit(40).get(Source.DEFAULT).await()
        } catch (e1: Exception) {
            try {
                assessmentsCol.orderBy("date", com.google.firebase.firestore.Query.Direction.DESCENDING)
                    .limit(40).get(Source.DEFAULT).await()
            } catch (e2: Exception) {
                try {
                    assessmentsCol.limit(40).get(Source.DEFAULT).await()
                } catch (e3: Exception) {
                    return emptyList()
                }
            }
        }

        val list = snap.documents.mapNotNull { doc ->
            @Suppress("UNCHECKED_CAST")
            val rawQs = doc.get("questions") as? List<Map<String, Any>> ?: emptyList()
            val questions = rawQs.map { q ->
                AssessmentQuestion(
                    id            = q["id"] as? String ?: "",
                    type          = q["type"] as? String ?: "mcq",
                    question      = q["question"] as? String ?: "",
                    options       = (q["options"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
                    correctIndex  = (q["correctIndex"] as? Number)?.toInt() ?: -1,
                    answer        = q["answer"] as? String ?: "",
                    inputSentence = q["inputSentence"] as? String ?: "",
                    tag           = q["tag"] as? String ?: "",
                    blanks        = (q["blanks"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
                    marks         = (q["marks"] as? Number)?.toDouble() ?: 1.0,
                    difficulty    = q["difficulty"] as? String ?: "",
                    category      = q["category"] as? String ?: "",
                    explanation   = q["explanation"] as? String ?: "",
                    optionExplanations = (q["optionExplanations"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
                    passage       = q["passage"] as? String ?: "",
                    hint          = q["hint"] as? String ?: "",
                    transformationType = q["transformationType"] as? String ?: "",
                    jumbledWords  = (q["jumbledWords"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
                    columnA       = (q["columnA"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
                    columnB       = (q["columnB"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
                    correctMatches = (q["correctMatches"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
                )
            }
            @Suppress("UNCHECKED_CAST")
            val rawAnswers = doc.get("answers") as? List<Map<String, Any>?> ?: emptyList()

            val summaryObj = doc.get("summary")
            val summaryText = if (summaryObj is String) {
                summaryObj
            } else if (summaryObj is Map<*, *>) {
                summaryObj["encouragement"] as? String ?: ""
            } else {
                ""
            }

            @Suppress("UNCHECKED_CAST")
            val chapterTitlesList = doc.get("chapterTitles") as? List<String> ?: emptyList()

            Assessment(
                id            = doc.id,
                subject       = doc.getString("subject") ?: "",
                classNum      = doc.getString("classNum") ?: "",
                lang          = doc.getString("lang") ?: "en",
                date          = doc.getString("date") ?: "",
                status        = doc.getString("status") ?: "ready",
                questions     = questions,
                summary       = summaryText,
                createdAt     = doc.getString("createdAt") ?: "",
                completedAt   = doc.getString("completedAt") ?: "",
                title         = doc.getString("title") ?: "",
                chapterTitles = chapterTitlesList,
                score         = (doc.get("score") as? Number)?.toDouble(),
                answers       = rawAnswers,
            )
        }

        // Sort client-side to ensure newest first regardless of the query ordering
        return list.sortedWith { a, b ->
            val timeA = if (a.createdAt.isNotBlank()) a.createdAt else a.date
            val timeB = if (b.createdAt.isNotBlank()) b.createdAt else b.date
            timeB.compareTo(timeA)
        }
    }

    suspend fun saveAssessment(childName: String, assessment: Assessment): String {
        val qs = assessment.questions.map { q ->
            mapOf(
                "id"            to q.id,
                "type"          to q.type,
                "question"      to q.question,
                "options"       to q.options,
                "correctIndex"  to q.correctIndex,
                "answer"        to q.answer,
                "inputSentence" to q.inputSentence,
                "tag"           to q.tag,
                "blanks"        to q.blanks,
                "marks"         to q.marks,
                "difficulty"    to q.difficulty,
                "category"      to q.category,
                "explanation"   to q.explanation,
                "optionExplanations" to q.optionExplanations,
                "passage"       to q.passage,
                "hint"          to q.hint,
                "transformationType" to q.transformationType,
                "jumbledWords"  to q.jumbledWords,
                "columnA"       to q.columnA,
                "columnB"       to q.columnB,
                "correctMatches" to q.correctMatches,
            )
        }
        val data = mutableMapOf<String, Any>(
            "subject"        to assessment.subject,
            "classNum"       to assessment.classNum,
            "lang"           to assessment.lang,
            "date"           to assessment.date,
            "status"         to assessment.status,
            "questions"      to qs,
            "summary"        to assessment.summary,
            "createdAt"      to assessment.createdAt.ifBlank { assessment.date },
            "completedAt"    to assessment.completedAt,
            "title"          to assessment.title,
            "chapterTitles"  to assessment.chapterTitles,
            "totalQuestions" to assessment.questions.size,
            "totalMarks"     to assessment.questions.sumOf { it.marks },
        )
        assessment.score?.let { data["score"] = it }
        if (assessment.answers.isNotEmpty()) {
            data["answers"] = assessment.answers
        }

        val ref = if (assessment.id.isBlank())
            col(childName).document()
        else
            col(childName).document(assessment.id)
        ref.set(data).await()
        return ref.id
    }

    suspend fun saveSummary(childName: String, assessmentId: String, summary: String, score: Double, answers: List<Map<String, Any>?>) {
        col(childName).document(assessmentId)
            .update(
                "summary", summary,
                "status", "completed",
                "score", score,
                "answers", answers
            ).await()
    }

    suspend fun deleteAssessment(childName: String, assessmentId: String) {
        col(childName).document(assessmentId).delete().await()
    }

    suspend fun resetAssessment(childName: String, assessmentId: String) {
        col(childName).document(assessmentId)
            .update(
                "status", "ready",
                "score", com.google.firebase.firestore.FieldValue.delete(),
                "answers", com.google.firebase.firestore.FieldValue.delete(),
                "summary", com.google.firebase.firestore.FieldValue.delete(),
                "completedAt", com.google.firebase.firestore.FieldValue.delete()
            ).await()
    }
}
