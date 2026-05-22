package com.krishanagarwal.mynoo.data.repository

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

data class AssessmentQuestion(
    val id:           String        = "",
    val type:         String        = "mcq",
    val question:     String        = "",
    val options:      List<String>  = emptyList(),
    val correctIndex: Int           = -1,
    val answer:       String        = "",
    val inputSentence:String        = "",
    val tag:          String        = "",
    val blanks:       List<String>  = emptyList(),
)

data class Assessment(
    val id:        String                  = "",
    val subject:   String                  = "",
    val classNum:  String                  = "",
    val lang:      String                  = "en",
    val date:      String                  = "",
    val status:    String                  = "ready",
    val questions: List<AssessmentQuestion> = emptyList(),
    val summary:   String                  = "",
)

@Singleton
class AssessmentRepository @Inject constructor(
    private val db: FirebaseFirestore,
) {
    private fun col(childName: String) =
        db.collection("kids").document(childName).collection("assessments")

    suspend fun getAssessments(childName: String): List<Assessment> {
        val snap = try {
            col(childName).orderBy("date",
                com.google.firebase.firestore.Query.Direction.DESCENDING
            ).limit(20).get(Source.DEFAULT).await()
        } catch (_: Exception) { return emptyList() }

        return snap.documents.mapNotNull { doc ->
            @Suppress("UNCHECKED_CAST")
            val rawQs = doc.get("questions") as? List<Map<String, Any>> ?: emptyList()
            val questions = rawQs.map { q ->
                AssessmentQuestion(
                    id           = q["id"] as? String ?: "",
                    type         = q["type"] as? String ?: "mcq",
                    question     = q["question"] as? String ?: "",
                    options      = (q["options"] as? List<String>) ?: emptyList(),
                    correctIndex = (q["correctIndex"] as? Number)?.toInt() ?: -1,
                    answer       = q["answer"] as? String ?: "",
                    inputSentence= q["inputSentence"] as? String ?: "",
                    tag          = q["tag"] as? String ?: "",
                    blanks       = (q["blanks"] as? List<String>) ?: emptyList(),
                )
            }
            Assessment(
                id       = doc.id,
                subject  = doc.getString("subject") ?: "",
                classNum = doc.getString("classNum") ?: "",
                lang     = doc.getString("lang") ?: "en",
                date     = doc.getString("date") ?: "",
                status   = doc.getString("status") ?: "ready",
                questions= questions,
                summary  = doc.getString("summary") ?: "",
            )
        }
    }

    suspend fun saveAssessment(childName: String, assessment: Assessment): String {
        val qs = assessment.questions.map { q ->
            mapOf(
                "id"           to q.id,
                "type"         to q.type,
                "question"     to q.question,
                "options"      to q.options,
                "correctIndex" to q.correctIndex,
                "answer"       to q.answer,
                "inputSentence" to q.inputSentence,
                "tag"          to q.tag,
                "blanks"       to q.blanks,
            )
        }
        val data = mapOf(
            "subject"  to assessment.subject,
            "classNum" to assessment.classNum,
            "lang"     to assessment.lang,
            "date"     to assessment.date,
            "status"   to assessment.status,
            "questions" to qs,
            "summary"  to assessment.summary,
        )
        val ref = if (assessment.id.isBlank())
            col(childName).document()
        else
            col(childName).document(assessment.id)
        ref.set(data).await()
        return ref.id
    }

    suspend fun saveSummary(childName: String, assessmentId: String, summary: String) {
        col(childName).document(assessmentId)
            .update("summary", summary, "status", "completed").await()
    }
}
