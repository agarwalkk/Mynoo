package com.krishanagarwal.mynoo.data.repository

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.tasks.await
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

data class ChapterMeta(
    val id:         String = "",
    val title:      String = "",
    val order:      Int    = 0,
    val wordCount:  Int    = 0,
    val published:  Boolean = true,
)

data class ChapterSentence(
    val id:      String = "",
    val text:    String = "",
    val meaning: String = "",
)

data class ChapterParagraph(
    val id:        String = "",
    val type:      String = "prose",
    val text:      String = "",
    val sentences: List<ChapterSentence> = emptyList(),
    val title:     String = "",
    val items:     List<String> = emptyList(),
    val headers:   List<String> = emptyList(),
    val rows:      List<List<String>> = emptyList(),
    val caption:   String = "",
)

data class ChapterContent(val paragraphs: List<ChapterParagraph> = emptyList())

private const val STORAGE_BUCKET = "aaravtutor-1e880.firebasestorage.app"

@Singleton
class ChapterRepository @Inject constructor(
    private val db:     FirebaseFirestore,
    private val client: OkHttpClient,
) {
    private val gson = Gson()

    suspend fun getChapters(classNum: String, subject: String): List<ChapterMeta> {
        val slug = subject.lowercase().replace(' ', '_')
        val col  = db.collection("classes").document(classNum)
            .collection("subjects").document(slug)
            .collection("chapters")
            .whereEqualTo("published", true)

        val docs = try {
            val cached = col.get(Source.CACHE).await()
            val server = col.get(Source.SERVER).await()
            if (server.isEmpty) cached else server
        } catch (_: Exception) {
            try { col.get(Source.DEFAULT).await() } catch (_: Exception) { return emptyList() }
        }

        return docs.documents.mapNotNull { doc ->
            ChapterMeta(
                id        = doc.id,
                title     = doc.getString("title") ?: doc.id,
                order     = (doc.get("order") as? Number)?.toInt() ?: 0,
                wordCount = (doc.get("wordCount") as? Number)?.toInt() ?: 0,
                published = doc.getBoolean("published") ?: true,
            )
        }.sortedBy { it.order }
    }

    suspend fun getContent(classNum: String, subject: String, chapterId: String): ChapterContent {
        val slug = subject.lowercase().replace(' ', '_')
        val path = "classes/$classNum/$slug/$chapterId/content.json"
        val encoded = URLEncoder.encode(path, "UTF-8").replace("+", "%20")
        val url  = "https://firebasestorage.googleapis.com/v0/b/$STORAGE_BUCKET/o/$encoded?alt=media"

        val req  = Request.Builder().url(url).build()
        return try {
            val body = client.newCall(req).execute().use { it.body?.string() ?: "" }
            gson.fromJson(body, ChapterContent::class.java) ?: ChapterContent()
        } catch (_: Exception) { ChapterContent() }
    }
}
