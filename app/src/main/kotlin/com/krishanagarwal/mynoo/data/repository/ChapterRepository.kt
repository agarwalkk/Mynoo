package com.krishanagarwal.mynoo.data.repository

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
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

data class MediaItem(
    val mediaType: String = "",   // "video" | "photo"
    val url:       String = "",
    val caption:   String = "",
)

data class ChapterParagraph(
    val id:         String                = "",
    val type:       String                = "prose",
    val text:       String                = "",
    val meaning:    String                = "",
    val sentences:  List<ChapterSentence> = emptyList(),
    val title:      String                = "",
    val items:      List<String>          = emptyList(),
    val mediaItems: List<MediaItem>       = emptyList(),
    val ordered:    Boolean               = false,
    val headers:    List<String>          = emptyList(),
    val rows:       List<List<String>>    = emptyList(),
    val caption:    String                = "",
)

data class ChapterContent(val paragraphs: List<ChapterParagraph> = emptyList())

data class WordTiming(
    val word:  String = "",
    val start: Double = 0.0,
    val end:   Double = 0.0,
)

private const val STORAGE_BUCKET = "aaravtutor-1e880.firebasestorage.app"

@Singleton
class ChapterRepository @Inject constructor(
    private val db:     FirebaseFirestore,
    private val client: OkHttpClient,
) {
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

    /**
     * Fetches chapter content.json from Firebase Storage.
     * Uses Dispatchers.IO for the blocking OkHttp call.
     * JSON is parsed manually to handle mixed-type `items` arrays
     * (string[] for lists, MediaItem[] for media paragraphs).
     */
    suspend fun getContent(classNum: String, subject: String, chapterId: String): ChapterContent {
        val slug    = subject.lowercase().replace(' ', '_')
        val path    = "classes/$classNum/$slug/$chapterId/content.json"
        val encoded = URLEncoder.encode(path, "UTF-8").replace("+", "%20")
        val url     = "https://firebasestorage.googleapis.com/v0/b/$STORAGE_BUCKET/o/$encoded?alt=media"

        val bodyText = withContext(Dispatchers.IO) {
            val req = Request.Builder().url(url).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}: $url")
                resp.body?.string() ?: error("Empty response body from Storage")
            }
        }

        return parseContent(bodyText)
    }

    private fun parseContent(json: String): ChapterContent {
        val root       = JSONObject(json)
        val parasArr   = root.optJSONArray("paragraphs") ?: return ChapterContent()
        val paragraphs = mutableListOf<ChapterParagraph>()

        for (i in 0 until parasArr.length()) {
            val p = parasArr.optJSONObject(i) ?: continue
            paragraphs += ChapterParagraph(
                id         = p.optString("id"),
                type       = p.optString("type", "prose"),
                text       = p.optString("text"),
                meaning    = p.optString("meaning"),
                title      = p.optString("title"),
                caption    = p.optString("caption"),
                ordered    = p.optBoolean("ordered", false),
                sentences  = parseSentences(p.optJSONArray("sentences")),
                items      = parseStringItems(p.optJSONArray("items")),
                mediaItems = parseMediaItems(p.optJSONArray("items")),
                headers    = parseStringItems(p.optJSONArray("headers")),
                rows       = parseRows(p.optJSONArray("rows")),
            )
        }
        return ChapterContent(paragraphs)
    }

    private fun parseSentences(arr: JSONArray?): List<ChapterSentence> {
        arr ?: return emptyList()
        val result = mutableListOf<ChapterSentence>()
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            result += ChapterSentence(
                id      = s.optString("id"),
                text    = s.optString("text"),
                meaning = s.optString("meaning"),
            )
        }
        return result
    }

    private fun parseMediaItems(arr: JSONArray?): List<MediaItem> {
        arr ?: return emptyList()
        val result = mutableListOf<MediaItem>()
        for (i in 0 until arr.length()) {
            val item      = arr.optJSONObject(i) ?: continue
            val mediaType = item.optString("mediaType")
            if (mediaType == "video" || mediaType == "photo") {
                result += MediaItem(
                    mediaType = mediaType,
                    url       = item.optString("url"),
                    caption   = item.optString("caption"),
                )
            }
        }
        return result
    }

    /** Handles both string[] and object[] — objects fall back to empty string. */
    private fun parseStringItems(arr: JSONArray?): List<String> {
        arr ?: return emptyList()
        val result = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            when (val el = arr.get(i)) {
                is String     -> result += el
                is JSONObject -> result += el.optString("caption", el.optString("text"))
                else          -> result += el.toString()
            }
        }
        return result
    }

    private fun parseRows(arr: JSONArray?): List<List<String>> {
        arr ?: return emptyList()
        val result = mutableListOf<List<String>>()
        for (i in 0 until arr.length()) {
            val row = arr.optJSONArray(i) ?: continue
            result += parseStringItems(row)
        }
        return result
    }

    // ── Audio helpers (voices stored in Firebase Storage) ─────────────────────

    /** Build the Firebase Storage download URL for a sentence or paragraph audio file. */
    fun audioUrl(
        classNum:  String,
        subject:   String,
        chapterId: String,
        segId:     String,
        kind:      String,   // "sentence" | "paragraph"
        ext:       String,   // "mp3" | "wav"
    ): String {
        val slug    = subject.lowercase().replace(' ', '_')
        val folder  = if (kind == "sentence") "sentences" else "paragraphs"
        val path    = "classes/$classNum/$slug/$chapterId/audio/$folder/$segId.$ext"
        val encoded = URLEncoder.encode(path, "UTF-8").replace("+", "%20")
        return "https://firebasestorage.googleapis.com/v0/b/$STORAGE_BUCKET/o/$encoded?alt=media"
    }

    /**
     * Lists the audio/ prefix in Storage to confirm audio exists and detect extension.
     * Returns (exists, ext) where ext is "mp3" or "wav".
     */
    suspend fun checkAudioExists(classNum: String, subject: String, chapterId: String): Pair<Boolean, String> {
        val slug    = subject.lowercase().replace(' ', '_')
        val prefix  = "classes/$classNum/$slug/$chapterId/audio/"
        val listUrl = "https://firebasestorage.googleapis.com/v0/b/$STORAGE_BUCKET/o" +
            "?prefix=${URLEncoder.encode(prefix, "UTF-8")}&maxResults=20"
        return withContext(Dispatchers.IO) {
            try {
                val req      = Request.Builder().url(listUrl).build()
                val bodyText = client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext false to "mp3"
                    resp.body?.string() ?: return@withContext false to "mp3"
                }
                val items = JSONObject(bodyText).optJSONArray("items") ?: return@withContext false to "mp3"
                for (i in 0 until items.length()) {
                    val name = items.optJSONObject(i)?.optString("name") ?: continue
                    if (name.endsWith(".mp3")) return@withContext true to "mp3"
                    if (name.endsWith(".wav")) return@withContext true to "wav"
                }
                false to "mp3"
            } catch (_: Exception) {
                false to "mp3"
            }
        }
    }

    /**
     * Fetches the word-timing JSON for a segment.
     * Path: audio/sentences/{sentenceId}.json or audio/paragraphs/{paraId}.json
     * Returns null if the file doesn't exist or can't be parsed.
     */
    suspend fun getWordTimings(
        classNum:  String,
        subject:   String,
        chapterId: String,
        segId:     String,
        kind:      String,
    ): List<WordTiming>? {
        val slug    = subject.lowercase().replace(' ', '_')
        val folder  = if (kind == "sentence") "sentences" else "paragraphs"
        val path    = "classes/$classNum/$slug/$chapterId/audio/$folder/$segId.json"
        val encoded = URLEncoder.encode(path, "UTF-8").replace("+", "%20")
        val url     = "https://firebasestorage.googleapis.com/v0/b/$STORAGE_BUCKET/o/$encoded?alt=media"
        return withContext(Dispatchers.IO) {
            try {
                val req  = Request.Builder().url(url).build()
                val body = client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext null
                    resp.body?.string() ?: return@withContext null
                }
                val arr    = JSONArray(body)
                val result = mutableListOf<WordTiming>()
                for (i in 0 until arr.length()) {
                    val t = arr.optJSONObject(i) ?: continue
                    result += WordTiming(
                        word  = t.optString("word"),
                        start = t.optDouble("start"),
                        end   = t.optDouble("end"),
                    )
                }
                result
            } catch (_: Exception) {
                null
            }
        }
    }

    // ── Storage and Metadata writing (for Parent Dashboard) ───────────────────

    suspend fun uploadChapterJson(classNum: String, subject: String, chapterId: String, json: String): Unit = withContext(Dispatchers.IO) {
        val slug = subject.lowercase().replace(' ', '_')
        val path = "classes/$classNum/$slug/$chapterId/content.json"
        val url = "https://firebasestorage.googleapis.com/v0/b/$STORAGE_BUCKET/o?name=${URLEncoder.encode(path, "UTF-8")}"
        
        val body = json.toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url(url)
            .post(body)
            .build()
            
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Upload content.json failed: ${resp.code} ${resp.message}")
        }
    }

    suspend fun writeChapterMeta(classNum: String, subject: String, chapterId: String, title: String, order: Int, wordCount: Int, sentenceCount: Int): Unit = withContext(Dispatchers.IO) {
        val slug = subject.lowercase().replace(' ', '_')
        val doc = db.collection("classes").document(classNum)
            .collection("subjects").document(slug)
            .collection("chapters").document(chapterId)
            
        val data = mapOf(
            "title" to title,
            "order" to order,
            "published" to true,
            "wordCount" to wordCount,
            "sentenceCount" to sentenceCount,
            "uploadedAt" to java.time.Instant.now().toString()
        )
        
        doc.set(data).await()
    }

    suspend fun writeChapterAudioStatus(classNum: String, subject: String, chapterId: String, status: String, error: String?, totalCount: Int, uploadedCount: Int): Unit = withContext(Dispatchers.IO) {
        val slug = subject.lowercase().replace(' ', '_')
        val doc = db.collection("classes").document(classNum)
            .collection("subjects").document(slug)
            .collection("chapters").document(chapterId)
            
        val data = mutableMapOf<String, Any>(
            "audioStatus" to status,
            "audioExists" to (uploadedCount > 0),
            "voiceSegmentsTotal" to totalCount,
            "voiceSegmentsReady" to uploadedCount,
            "voicePct" to if (totalCount > 0) Math.min(100, Math.round((uploadedCount.toDouble() / totalCount) * 100).toInt()) else 0
        )
        if (error != null) {
            data["audioError"] = error
        } else {
            data["audioError"] = com.google.firebase.firestore.FieldValue.delete()
        }
        
        doc.update(data).await()
    }

    suspend fun uploadAudioBytes(classNum: String, subject: String, chapterId: String, segId: String, kind: String, ext: String, mimeType: String, bytes: ByteArray): Unit = withContext(Dispatchers.IO) {
        val slug = subject.lowercase().replace(' ', '_')
        val folder = if (kind == "sentence") "sentences" else "paragraphs"
        val path = "classes/$classNum/$slug/$chapterId/audio/$folder/$segId.$ext"
        val url = "https://firebasestorage.googleapis.com/v0/b/$STORAGE_BUCKET/o?name=${URLEncoder.encode(path, "UTF-8")}"
        
        val body = bytes.toRequestBody(mimeType.toMediaType())
        val req = Request.Builder()
            .url(url)
            .post(body)
            .build()
            
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Upload audio segment failed: ${resp.code} ${resp.message}")
        }
    }

    suspend fun uploadTimingJson(classNum: String, subject: String, chapterId: String, segId: String, kind: String, json: String): Unit = withContext(Dispatchers.IO) {
        val slug = subject.lowercase().replace(' ', '_')
        val folder = if (kind == "sentence") "sentences" else "paragraphs"
        val path = "classes/$classNum/$slug/$chapterId/audio/$folder/$segId.json"
        val url = "https://firebasestorage.googleapis.com/v0/b/$STORAGE_BUCKET/o?name=${URLEncoder.encode(path, "UTF-8")}"
        
        val body = json.toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url(url)
            .post(body)
            .build()
            
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Upload timing JSON failed: ${resp.code} ${resp.message}")
        }
    }

    suspend fun deleteChapter(classNum: String, subject: String, chapterId: String): Unit = withContext(Dispatchers.IO) {
        val slug = subject.lowercase().replace(' ', '_')
        val prefix = "classes/$classNum/$slug/$chapterId/"
        
        // 1. Delete all storage files
        var pageToken: String? = null
        do {
            val url = "https://firebasestorage.googleapis.com/v0/b/$STORAGE_BUCKET/o?prefix=${URLEncoder.encode(prefix, "UTF-8")}" + 
                if (pageToken != null) "&pageToken=${URLEncoder.encode(pageToken, "UTF-8")}" else ""
            
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val bodyStr = resp.body?.string() ?: ""
                    val json = JSONObject(bodyStr)
                    val items = json.optJSONArray("items")
                    if (items != null) {
                        for (i in 0 until items.length()) {
                            val item = items.getJSONObject(i)
                            val name = item.getString("name")
                            val delUrl = "https://firebasestorage.googleapis.com/v0/b/$STORAGE_BUCKET/o/${URLEncoder.encode(name, "UTF-8")}"
                            val delReq = Request.Builder().url(delUrl).delete().build()
                            client.newCall(delReq).execute().use { delResp ->
                                // ignore failure on individual file deletes, just try best effort
                            }
                        }
                    }
                    val token = json.optString("nextPageToken", "")
                    pageToken = if (token.isNotEmpty()) token else null
                } else {
                    pageToken = null
                }
            }
        } while (pageToken != null)

        // 2. Delete Firestore doc
        db.collection("classes").document(classNum)
            .collection("subjects").document(slug)
            .collection("chapters").document(chapterId)
            .delete()
            .await()
    }
}

