package com.krishanagarwal.mynoo.data.repository

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import com.krishanagarwal.mynoo.data.model.Child
import kotlinx.coroutines.tasks.await
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChildRepository @Inject constructor(
    private val db: FirebaseFirestore,
) {
    private val col get() = db.collection("kids")

    /**
     * Cache-first load: tries local cache first (instant), then always
     * fetches from server to keep data fresh.
     */
    suspend fun loadChildren(): List<Child> {
        var result: List<Child> = emptyList()

        // 1. Try cache for instant display
        try {
            val cached = col.get(Source.CACHE).await()
            if (cached.documents.isNotEmpty()) {
                result = cached.documents.mapNotNull { it.toChild() }
            }
        } catch (_: Exception) {}

        // 2. Always verify against server
        try {
            val server = col.get(Source.SERVER).await()
            result = server.documents.mapNotNull { it.toChild() }
        } catch (_: Exception) {}

        return result.sortedBy { it.name.lowercase() }
    }

    suspend fun addChild(name: String, age: String, classNum: String) {
        col.document(name).set(
            mapOf(
                "name"      to name,
                "age"       to age.ifBlank { null },
                "class"     to classNum.ifBlank { null },
                "createdAt" to Instant.now().toString(),
            )
        ).await()
    }

    suspend fun deleteChild(name: String) {
        col.document(name).delete().await()
    }

    suspend fun getClassNum(name: String): String {
        return try {
            val doc = col.document(name).get().await()
            doc.getString("class") ?: ""
        } catch (_: Exception) { "" }
    }

    private fun com.google.firebase.firestore.DocumentSnapshot.toChild(): Child? {
        val n = getString("name") ?: id
        return Child(
            name      = n,
            age       = getString("age") ?: "",
            classNum  = getString("class") ?: "",
            createdAt = getString("createdAt") ?: "",
        )
    }
}
