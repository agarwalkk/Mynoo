package com.krishanagarwal.mynoo.data.repository

import android.content.Context
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.gson.Gson
import com.krishanagarwal.mynoo.BuildConfig
import com.krishanagarwal.mynoo.data.api.GistApi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlacementRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: FirebaseFirestore,
    private val gistApi: GistApi
) {
    private val gson = Gson()

    private fun configCol(childName: String) =
        db.collection("kids").document(childName).collection("config")

    // ── Firestore: Levels & Assessment Results ──────────────────────────────────

    suspend fun hasBeenAssessed(childName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val doc = configCol(childName).document("langLevels").get().await()
            doc.exists() && doc.getBoolean("assessed") == true
        } catch (e: Exception) {
            Log.e("PlacementRepo", "Error checking assessment status", e)
            false
        }
    }

    suspend fun getLevels(childName: String): Map<String, Any> = withContext(Dispatchers.IO) {
        try {
            val doc = configCol(childName).document("langLevels").get().await()
            if (doc.exists()) doc.data ?: emptyMap() else emptyMap()
        } catch (e: Exception) {
            Log.e("PlacementRepo", "Error getting levels", e)
            emptyMap()
        }
    }

    suspend fun savePlacementLevels(
        childName: String,
        levels: Map<String, Int>,
        expertise: Map<String, String>
    ): Unit = withContext(Dispatchers.IO) {
        val levelsData = levels + ("assessed" to true)
        configCol(childName).document("langLevels").set(levelsData).await()
        configCol(childName).document("langExpertise").set(expertise).await()
    }

    suspend fun savePartialLevel(
        childName: String,
        lang: String,
        level: Int,
        expertise: String,
        history: List<Map<String, Any>>
    ): Unit = withContext(Dispatchers.IO) {
        // 1. Merge level
        configCol(childName).document("langLevels")
            .set(mapOf(lang to level), SetOptions.merge())
            .await()

        // 2. Merge expertise description
        configCol(childName).document("langExpertise")
            .set(mapOf(lang to expertise), SetOptions.merge())
            .await()

        // 3. Merge quiz history
        val historyData = mapOf(
            lang to history,
            "assessedAt" to Instant.now().toString()
        )
        configCol(childName).document("quizHistory")
            .set(historyData, SetOptions.merge())
            .await()
    }

    suspend fun saveQuizHistory(childName: String, histories: Map<String, Any>): Unit = withContext(Dispatchers.IO) {
        configCol(childName).document("quizHistory").set(histories).await()
    }

    suspend fun getQuizHistory(childName: String): Map<String, Any>? = withContext(Dispatchers.IO) {
        try {
            val doc = configCol(childName).document("quizHistory").get().await()
            if (doc.exists()) doc.data else null
        } catch (e: Exception) {
            Log.e("PlacementRepo", "Error getting quiz history", e)
            null
        }
    }

    // ── Firestore: Quiz Drafts (Pause / Resume) ──────────────────────────────────

    suspend fun saveQuizDraft(childName: String, draftJson: String): Unit = withContext(Dispatchers.IO) {
        val data = mapOf(
            "draftJson" to draftJson,
            "savedAt" to Instant.now().toString()
        )
        configCol(childName).document("quizDraft").set(data).await()
    }

    suspend fun getQuizDraft(childName: String): String? = withContext(Dispatchers.IO) {
        try {
            val doc = configCol(childName).document("quizDraft").get().await()
            if (doc.exists()) doc.getString("draftJson") else null
        } catch (e: Exception) {
            Log.e("PlacementRepo", "Error getting quiz draft", e)
            null
        }
    }

    suspend fun clearQuizDraft(childName: String): Unit = withContext(Dispatchers.IO) {
        try {
            configCol(childName).document("quizDraft").delete().await()
        } catch (e: Exception) {
            Log.e("PlacementRepo", "Error clearing quiz draft", e)
        }
    }

    // ── Firestore: Retest Requests ──────────────────────────────────────────────

    suspend fun getRetestRequest(childName: String): List<String>? = withContext(Dispatchers.IO) {
        try {
            val doc = configCol(childName).document("retestRequest").get().await()
            if (doc.exists()) {
                @Suppress("UNCHECKED_CAST")
                doc.get("langs") as? List<String>
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e("PlacementRepo", "Error getting retest request", e)
            null
        }
    }

    suspend fun clearRetestRequest(childName: String): Unit = withContext(Dispatchers.IO) {
        try {
            configCol(childName).document("retestRequest").delete().await()
        } catch (e: Exception) {
            Log.e("PlacementRepo", "Error clearing retest request", e)
        }
    }

    suspend fun mergeRetestLevels(
        childName: String,
        partialLevels: Map<String, Int>,
        partialExpertise: Map<String, String>,
        partialHistories: Map<String, Any>
    ): Unit = withContext(Dispatchers.IO) {
        configCol(childName).document("langLevels").set(partialLevels, SetOptions.merge()).await()
        configCol(childName).document("langExpertise").set(partialExpertise, SetOptions.merge()).await()
        val historyData = partialHistories + ("assessedAt" to Instant.now().toString())
        configCol(childName).document("quizHistory").set(historyData, SetOptions.merge()).await()
    }

    // ── GitHub Gist: Local Cache Loader ──────────────────────────────────────────

    suspend fun getGistFile(filename: String): String = withContext(Dispatchers.IO) {
        val cacheFile = File(context.cacheDir, "gist_$filename")
        val tsFile = File(context.cacheDir, "gist_${filename}.ts")
        val cacheTtlMs = 4 * 60 * 60 * 1000L // 4 hours

        // Check if cache exists and is fresh
        if (cacheFile.exists() && tsFile.exists()) {
            val tsStr = tsFile.readText().trim()
            val ts = tsStr.toLongOrNull() ?: 0L
            val age = System.currentTimeMillis() - ts
            if (age < cacheTtlMs) {
                try {
                    return@withContext cacheFile.readText()
                } catch (e: IOException) {
                    Log.w("PlacementRepo", "Error reading cache for $filename: ${e.message}")
                }
            }
        }

        // Fetch from gist raw URL
        try {
            // Base URL: https://gist.githubusercontent.com/
            // Path: agarwalkk/092b9fe05d4b1bb99d84571cf2f6b3bd/raw/filename
            val relativeUrl = "agarwalkk/${BuildConfig.GIST_ID}/raw/$filename"
            val responseBody = gistApi.downloadRawFile(relativeUrl)
            val content = responseBody.string()

            // Write to cache
            try {
                cacheFile.writeText(content)
                tsFile.writeText(System.currentTimeMillis().toString())
            } catch (e: IOException) {
                Log.w("PlacementRepo", "Error writing cache for $filename: ${e.message}")
            }

            content
        } catch (e: Exception) {
            // Failed to fetch — fall back to stale cache if it exists (regardless of age)
            if (cacheFile.exists()) {
                Log.w("PlacementRepo", "Gist fetch failed for $filename, using stale cache: ${e.message}")
                try {
                    return@withContext cacheFile.readText()
                } catch (readErr: IOException) {
                    throw GistUnavailableException(filename, readErr)
                }
            }
            throw GistUnavailableException(filename, e)
        }
    }
}

class GistUnavailableException(filename: String, cause: Throwable? = null) :
    Exception("Unable to load \"$filename\" — please check your internet connection and try again.", cause)
