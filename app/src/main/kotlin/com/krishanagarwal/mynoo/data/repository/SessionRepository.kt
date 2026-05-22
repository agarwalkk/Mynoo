package com.krishanagarwal.mynoo.data.repository

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import kotlinx.coroutines.tasks.await
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

data class SessionRecord(
    val id:          String = "",
    val date:        String = "",
    val endDate:     String = "",
    val durationMin: Double = 0.0,
    val lang:        String = "en",
    val mood:        String = "",
)

@Singleton
class SessionRepository @Inject constructor(private val db: FirebaseFirestore) {

    private fun sessionsCol(childName: String) =
        db.collection("kids").document(childName).collection("sessions")

    suspend fun saveSession(childName: String, session: SessionRecord) {
        sessionsCol(childName).document(session.id).set(
            mapOf(
                "id"          to session.id,
                "date"        to session.date,
                "endDate"     to session.endDate,
                "durationMin" to session.durationMin,
                "lang"        to session.lang,
            )
        ).await()
    }

    suspend fun saveMood(childName: String, sessionId: String, mood: String) {
        sessionsCol(childName).document(sessionId).update("mood", mood).await()
    }

    suspend fun getSessions(childName: String): List<SessionRecord> = try {
        val snap = sessionsCol(childName)
            .orderBy("date", Query.Direction.DESCENDING)
            .limit(60)
            .get(Source.DEFAULT)
            .await()
        snap.documents.mapNotNull { doc ->
            SessionRecord(
                id          = doc.getString("id") ?: doc.id,
                date        = doc.getString("date") ?: "",
                endDate     = doc.getString("endDate") ?: "",
                durationMin = (doc.get("durationMin") as? Number)?.toDouble() ?: 0.0,
                lang        = doc.getString("lang") ?: "en",
                mood        = doc.getString("mood") ?: "",
            )
        }
    } catch (_: Exception) { emptyList() }

    suspend fun getStreak(childName: String): Int {
        val sessions = getSessions(childName)
        if (sessions.isEmpty()) return 0
        var streak = 0
        val today = java.time.LocalDate.now()
        for (i in 0..59) {
            val day = today.minusDays(i.toLong())
            val has = sessions.any {
                try { Instant.parse(it.date).atZone(java.time.ZoneId.systemDefault()).toLocalDate() == day }
                catch (_: Exception) { false }
            }
            if (has) streak++ else if (i > 0) break
        }
        return streak
    }
}
