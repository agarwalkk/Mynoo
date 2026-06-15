package com.krishanagarwal.mynoo.data.repository

import android.content.Context
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
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
    val isCached:   Boolean = false,
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
    @ApplicationContext private val context: Context,
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
            val id = doc.id
            ChapterMeta(
                id        = id,
                title     = doc.getString("title") ?: id,
                order     = (doc.get("order") as? Number)?.toInt() ?: 0,
                wordCount = (doc.get("wordCount") as? Number)?.toInt() ?: 0,
                published = doc.getBoolean("published") ?: true,
                isCached  = isChapterCached(classNum, subject, id),
            )
        }.sortedBy { it.order }
    }

    /**
     * Fetches chapter content.json from Firebase Storage.
     * Uses Dispatchers.IO for the blocking OkHttp call.
     * JSON is parsed manually to handle mixed-type `items` arrays
     * (string[] for lists, MediaItem[] for media paragraphs).
     */
    suspend fun getContent(classNum: String, subject: String, chapterId: String, forceRefresh: Boolean = false): ChapterContent {
        val localFile = File(context.filesDir, "chapters_cache/$classNum/$subject/$chapterId/content.json")
        val bodyText = if (localFile.exists() && !forceRefresh) {
            try {
                localFile.readText()
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }

        val finalBodyText = if (bodyText != null) {
            bodyText
        } else {
            val slug    = subject.lowercase().replace(' ', '_')
            val path    = "classes/$classNum/$slug/$chapterId/content.json"
            val encoded = URLEncoder.encode(path, "UTF-8").replace("+", "%20")
            val url     = "https://firebasestorage.googleapis.com/v0/b/$STORAGE_BUCKET/o/$encoded?alt=media"

            val text = withContext(Dispatchers.IO) {
                val req = Request.Builder().url(url).build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code}: $url")
                    resp.body?.string() ?: error("Empty response body from Storage")
                }
            }
            try {
                localFile.parentFile?.mkdirs()
                localFile.writeText(text)
            } catch (_: Exception) {}
            text
        }

        return parseContent(finalBodyText)
    }

    private fun parseContent(json: String): ChapterContent {
        val root       = JSONObject(json)
        val parasArr   = root.optJSONArray("paragraphs") ?: return ChapterContent()
        val paragraphs = mutableListOf<ChapterParagraph>()

        for (i in 0 until parasArr.length()) {
            val p = parasArr.optJSONObject(i) ?: continue
            val pid = p.optString("id")
            val ptype = p.optString("type", "prose")
            var sentences = parseSentences(p.optJSONArray("sentences"))
            val items = parseStringItems(p.optJSONArray("items"))
            if (ptype == "list" && sentences.isEmpty()) {
                sentences = items.mapIndexed { idx, itemText ->
                    ChapterSentence(
                        id = "$pid-item-$idx",
                        text = itemText,
                        meaning = ""
                    )
                }
            }
            paragraphs += ChapterParagraph(
                id         = pid,
                type       = ptype,
                text       = p.optString("text"),
                meaning    = p.optString("meaning"),
                title      = p.optString("title"),
                caption    = p.optString("caption"),
                ordered    = p.optBoolean("ordered", false),
                sentences  = sentences,
                items      = items,
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
    suspend fun checkAudioExists(classNum: String, subject: String, chapterId: String, forceRefresh: Boolean = false): Pair<Boolean, String> {
        val localFile = File(context.filesDir, "chapters_cache/$classNum/$subject/$chapterId/audio_info.json")
        if (localFile.exists() && !forceRefresh) {
            try {
                val json = JSONObject(localFile.readText())
                return json.getBoolean("hasAudio") to json.getString("audioExt")
            } catch (_: Exception) {}
        }

        val slug    = subject.lowercase().replace(' ', '_')
        val prefix  = "classes/$classNum/$slug/$chapterId/audio/"
        val listUrl = "https://firebasestorage.googleapis.com/v0/b/$STORAGE_BUCKET/o" +
            "?prefix=${URLEncoder.encode(prefix, "UTF-8")}&maxResults=20"
        val result = withContext(Dispatchers.IO) {
            try {
                val req      = Request.Builder().url(listUrl).build()
                val bodyText = client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext false to "mp3"
                    resp.body?.string() ?: return@withContext false to "mp3"
                }
                val items = JSONObject(bodyText).optJSONArray("items") ?: return@withContext false to "mp3"
                var hasAudio = false
                var ext = "mp3"
                for (i in 0 until items.length()) {
                    val name = items.optJSONObject(i)?.optString("name") ?: continue
                    if (name.endsWith(".mp3")) {
                        hasAudio = true
                        ext = "mp3"
                        break
                    }
                    if (name.endsWith(".wav")) {
                        hasAudio = true
                        ext = "wav"
                        break
                    }
                }
                hasAudio to ext
            } catch (_: Exception) {
                false to "mp3"
            }
        }

        try {
            localFile.parentFile?.mkdirs()
            val json = JSONObject()
            json.put("hasAudio", result.first)
            json.put("audioExt", result.second)
            localFile.writeText(json.toString())
        } catch (_: Exception) {}

        return result
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
        forceRefresh: Boolean = false,
    ): List<WordTiming>? {
        val folder  = if (kind == "sentence") "sentences" else "paragraphs"
        val localFile = File(context.filesDir, "chapters_cache/$classNum/$subject/$chapterId/audio/$folder/$segId.json")
        
        val bodyText = if (localFile.exists() && !forceRefresh) {
            try { localFile.readText() } catch (_: Exception) { null }
        } else {
            null
        }
        
        val jsonString = if (bodyText != null) {
            bodyText
        } else {
            val slug    = subject.lowercase().replace(' ', '_')
            val folderName = if (kind == "sentence") "sentences" else "paragraphs"
            val path    = "classes/$classNum/$slug/$chapterId/audio/$folderName/$segId.json"
            val encoded = URLEncoder.encode(path, "UTF-8").replace("+", "%20")
            val url     = "https://firebasestorage.googleapis.com/v0/b/$STORAGE_BUCKET/o/$encoded?alt=media"
            withContext(Dispatchers.IO) {
                try {
                    val req  = Request.Builder().url(url).build()
                    client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@withContext null
                        val text = resp.body?.string()
                        if (text != null) {
                            localFile.parentFile?.mkdirs()
                            localFile.writeText(text)
                        }
                        text
                    }
                } catch (_: Exception) {
                    null
                }
            }
        }
        
        if (jsonString == null) return null
        
        return try {
            val arr    = JSONArray(jsonString)
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

    fun isChapterCached(classNum: String, subject: String, chapterId: String): Boolean {
        val contentFile = File(context.filesDir, "chapters_cache/$classNum/$subject/$chapterId/content.json")
        return contentFile.exists()
    }

    fun getCachedImageFile(classNum: String, subject: String, chapterId: String, url: String): File {
        val hash = md5(url)
        val ext = if (url.contains(".png", ignoreCase = true)) "png" else "jpg"
        return File(context.filesDir, "chapters_cache/$classNum/$subject/$chapterId/images/$hash.$ext")
    }

    private fun md5(str: String): String {
        return try {
            val digest = java.security.MessageDigest.getInstance("MD5")
            digest.update(str.toByteArray())
            val messageDigest = digest.digest()
            val hexString = java.lang.StringBuilder()
            for (aMessageDigest in messageDigest) {
                var h = Integer.toHexString(0xFF and aMessageDigest.toInt())
                while (h.length < 2) h = "0$h"
                hexString.append(h)
            }
            hexString.toString()
        } catch (_: Exception) {
            str.hashCode().toString()
        }
    }

    fun getImageSource(classNum: String, subject: String, chapterId: String, url: String): String {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return url
        val file = getCachedImageFile(classNum, subject, chapterId, url)
        return if (file.exists()) {
            file.absolutePath
        } else {
            url
        }
    }

    suspend fun downloadImageFile(
        classNum: String,
        subject: String,
        chapterId: String,
        url: String,
        forceRefresh: Boolean = false
    ) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return
        val localFile = getCachedImageFile(classNum, subject, chapterId, url)
        if (localFile.exists() && !forceRefresh) return

        withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder().url(url).build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        localFile.parentFile?.mkdirs()
                        resp.body?.byteStream()?.use { input ->
                            localFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    suspend fun isChapterFullyCached(
        classNum: String,
        subject: String,
        chapterId: String,
        content: ChapterContent
    ): Boolean {
        val contentFile = File(context.filesDir, "chapters_cache/$classNum/$subject/$chapterId/content.json")
        if (!contentFile.exists()) return false

        // Check images
        for (para in content.paragraphs) {
            if (para.type == "media") {
                for (item in para.mediaItems) {
                    if (item.mediaType == "photo" && item.url.isNotBlank()) {
                        val file = getCachedImageFile(classNum, subject, chapterId, item.url)
                        if (!file.exists()) return false
                    }
                }
            }
        }

        val (hasAudio, ext) = checkAudioExists(classNum, subject, chapterId, forceRefresh = false)
        if (hasAudio) {
            val segments = buildSegments(content)
            for (seg in segments) {
                val folder = if (seg.kind == "sentence") "sentences" else "paragraphs"
                val timingFile = File(context.filesDir, "chapters_cache/$classNum/$subject/$chapterId/audio/$folder/${seg.id}.json")
                val audioFile = File(context.filesDir, "chapters_cache/$classNum/$subject/$chapterId/audio/$folder/${seg.id}.$ext")
                if (!timingFile.exists() || !audioFile.exists()) {
                    return false
                }
            }
        }
        return true
    }

    fun getAudioSource(
        classNum: String,
        subject: String,
        chapterId: String,
        segId: String,
        kind: String,
        ext: String
    ): String {
        val folder = if (kind == "sentence") "sentences" else "paragraphs"
        val localFile = File(context.filesDir, "chapters_cache/$classNum/$subject/$chapterId/audio/$folder/$segId.$ext")
        return if (localFile.exists()) {
            localFile.absolutePath
        } else {
            audioUrl(classNum, subject, chapterId, segId, kind, ext)
        }
    }

    suspend fun downloadAudioFile(
        classNum: String,
        subject: String,
        chapterId: String,
        segId: String,
        kind: String,
        ext: String,
        forceRefresh: Boolean = false
    ) {
        val folder = if (kind == "sentence") "sentences" else "paragraphs"
        val localFile = File(context.filesDir, "chapters_cache/$classNum/$subject/$chapterId/audio/$folder/$segId.$ext")
        if (localFile.exists() && !forceRefresh) return

        val url = audioUrl(classNum, subject, chapterId, segId, kind, ext)
        withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder().url(url).build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        localFile.parentFile?.mkdirs()
                        resp.body?.byteStream()?.use { input ->
                            localFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    suspend fun downloadChapterCache(
        classNum: String,
        subject: String,
        chapterId: String,
        content: ChapterContent,
        forceRefresh: Boolean,
        onProgress: (Float) -> Unit
    ) {
        val (hasAudio, ext) = checkAudioExists(classNum, subject, chapterId, forceRefresh = forceRefresh)
        val segments = if (hasAudio) buildSegments(content) else emptyList()

        val imageUrls = mutableListOf<String>()
        for (para in content.paragraphs) {
            if (para.type == "media") {
                for (item in para.mediaItems) {
                    if (item.mediaType == "photo" && item.url.isNotBlank()) {
                        imageUrls.add(item.url)
                    }
                }
            }
        }

        val totalTasks = segments.size * 2 + imageUrls.size
        if (totalTasks == 0) {
            onProgress(1.0f)
            return
        }

        var completedTasks = 0

        // 1. Download audio segments (if available)
        if (hasAudio) {
            for (seg in segments) {
                getWordTimings(classNum, subject, chapterId, seg.id, seg.kind, forceRefresh = forceRefresh)
                completedTasks++
                onProgress(completedTasks.toFloat() / totalTasks.toFloat())

                downloadAudioFile(classNum, subject, chapterId, seg.id, seg.kind, ext, forceRefresh = forceRefresh)
                completedTasks++
                onProgress(completedTasks.toFloat() / totalTasks.toFloat())
            }
        }

        // 2. Download images
        for (imageUrl in imageUrls) {
            downloadImageFile(classNum, subject, chapterId, imageUrl, forceRefresh = forceRefresh)
            completedTasks++
            onProgress(completedTasks.toFloat() / totalTasks.toFloat())
        }

        onProgress(1.0f)
    }

    private fun buildSegments(content: ChapterContent): List<AudioSegment> {
        val noAudio = setOf("heading", "subheading", "attribution", "table", "media")
        val result = mutableListOf<AudioSegment>()
        for (para in content.paragraphs) {
            if (para.type in noAudio) continue
            val validSents = para.sentences.filter { it.id.isNotBlank() }
            when {
                validSents.isNotEmpty() ->
                    validSents.forEach { sent ->
                        val kind = if (sent.id.contains("-item-")) "paragraph" else "sentence"
                        result += AudioSegment(sent.id, kind)
                    }
                para.id.isNotBlank() ->
                    result += AudioSegment(para.id, "paragraph")
            }
        }
        return result
    }

    private data class AudioSegment(val id: String, val kind: String)
}

