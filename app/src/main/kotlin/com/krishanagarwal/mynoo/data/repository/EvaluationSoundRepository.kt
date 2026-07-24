package com.krishanagarwal.mynoo.data.repository

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Base64
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.krishanagarwal.mynoo.BuildConfig
import com.krishanagarwal.mynoo.data.api.GeminiApi
import com.krishanagarwal.mynoo.data.api.GeminiContent
import com.krishanagarwal.mynoo.data.api.GeminiGenConfig
import com.krishanagarwal.mynoo.data.api.GeminiPart
import com.krishanagarwal.mynoo.data.api.GeminiPrebuiltVoice
import com.krishanagarwal.mynoo.data.api.GeminiRequest
import com.krishanagarwal.mynoo.data.api.GeminiSpeechConfig
import com.krishanagarwal.mynoo.data.api.GeminiVoiceConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

data class EvaluationSoundItem(
    val id: String = "",
    val category: String = "", // full_marks, partial_marks, no_marks, retry_available
    val text: String = "",
    val audioBase64: String = "",
    val index: Int = 0,
)

@Singleton
class EvaluationSoundRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: FirebaseFirestore,
    private val geminiApi: GeminiApi,
) {
    private var mediaPlayer: MediaPlayer? = null
    private val cachedInventory = mutableMapOf<String, MutableList<EvaluationSoundItem>>()

    companion object {
        private const val COLLECTION_NAME = "feedback_sounds"

        // 10 prompts per category = 40 total variations synthesized ONE TIME ONLY
        val PROMPTS_FULL_MARKS = listOf(
            "Awesome job! You scored full marks!",
            "Spectacular! All marks obtained!",
            "Fantastic! You earned full credit on this question!",
            "Brilliant performance! Full marks for you!",
            "Outstanding! You got maximum marks!",
            "Way to go! Perfect score on this question!",
            "Superb! Full marks awarded!",
            "Excellent work! Full marks achieved!",
            "Terrific job! You scored all the marks!",
            "Hooray! Maximum marks scored on this question!"
        )

        val PROMPTS_PARTIAL_MARKS = listOf(
            "Good effort! You earned partial marks on this question.",
            "Nice try! You received partial credit.",
            "Not bad at all! You got partial marks.",
            "Keep it up! You scored partial marks here.",
            "Good work! Partial marks awarded.",
            "You're getting closer! Partial marks earned.",
            "Solid effort! You scored partial credit.",
            "Well tried! You got partial marks for your answer.",
            "Good job! You earned some of the marks.",
            "Decent attempt! Partial credit granted."
        )

        val PROMPTS_NO_MARKS = listOf(
            "Oops, zero marks on this one. Don't worry, keep trying!",
            "No marks this time. Learn from the solution and keep going!",
            "Zero marks scored, but every mistake is a chance to learn!",
            "Oh snap, no marks on this question. Keep your head up!",
            "No marks here. Review the correct answer to improve!",
            "Zero marks this time. Don't lose heart!",
            "That's okay, zero marks scored. You will do better on the next one!",
            "No marks for this answer. Stay focused and keep trying!",
            "Zero marks scored. Keep practicing, you've got this!",
            "No points this round. Keep going!"
        )

        val PROMPTS_RETRY_AVAILABLE = listOf(
            "You have a retry available! Give it another shot!",
            "Don't give up! Use your retry to score marks!",
            "Incorrect, but a retry is open! Try once more!",
            "You get another chance! Tap retry and try again!",
            "Retry available! Give it your best shot!",
            "Give it another go! A retry is ready for you!",
            "You can try this question again! Give it a shot!",
            "Bonus chance! Hit retry to try one more time!",
            "Not quite, but you can retry now! Give it another try!",
            "Second chance unlocked! Tap retry to try again!"
        )
    }

    /**
     * Loads the pre-populated sound inventory from Firebase Firestore into memory cache.
     */
    suspend fun ensureInventoryPopulated() = withContext(Dispatchers.IO) {
        try {
            val snapshot = db.collection(COLLECTION_NAME).get().await()
            val existing = snapshot.documents.mapNotNull { doc ->
                val category = doc.getString("category") ?: return@mapNotNull null
                val text = doc.getString("text") ?: ""
                val audioB64 = doc.getString("audioBase64") ?: ""
                val index = (doc.getLong("index") ?: 0L).toInt()
                EvaluationSoundItem(
                    id = doc.id,
                    category = category,
                    text = text,
                    audioBase64 = audioB64,
                    index = index
                )
            }

            val categoryMap = existing.groupBy { it.category }
            cachedInventory.clear()
            categoryMap.forEach { (cat, list) ->
                cachedInventory[cat] = list.sortedBy { it.index }.toMutableList()
            }
            Log.d("EvaluationSoundRepo", "Loaded ${existing.size} evaluation sound items from Firebase Firestore into cache.")
        } catch (e: Exception) {
            Log.e("EvaluationSoundRepo", "Error loading sound inventory from Firebase", e)
        }
    }

    suspend fun getRandomSound(category: String): EvaluationSoundItem? = withContext(Dispatchers.IO) {
        val list = cachedInventory[category]
        if (!list.isNullOrEmpty()) {
            return@withContext list[Random.nextInt(list.size)]
        }

        // Try fetching directly from Firestore if cache empty
        try {
            val snapshot = db.collection(COLLECTION_NAME)
                .whereEqualTo("category", category)
                .get()
                .await()
            val items = snapshot.documents.mapNotNull { doc ->
                EvaluationSoundItem(
                    id = doc.id,
                    category = doc.getString("category") ?: category,
                    text = doc.getString("text") ?: "",
                    audioBase64 = doc.getString("audioBase64") ?: "",
                    index = (doc.getLong("index") ?: 0L).toInt()
                )
            }
            if (items.isNotEmpty()) {
                cachedInventory[category] = items.toMutableList()
                return@withContext items[Random.nextInt(items.size)]
            }
        } catch (e: Exception) {
            Log.e("EvaluationSoundRepo", "Error fetching sounds for category $category", e)
        }
        null
    }

    fun playEvaluationSound(category: String, onSoundSelected: ((String, Int) -> Unit)? = null) {
        stopSound()
        val list = cachedInventory[category]
        val item = if (!list.isNullOrEmpty()) {
            val idx = Random.nextInt(list.size)
            list[idx]
        } else null

        if (item != null && item.audioBase64.isNotBlank()) {
            onSoundSelected?.invoke(item.text, item.index)
            playAudioBase64(item.audioBase64)
        }
    }

    fun stopSound() {
        try {
            mediaPlayer?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
        } catch (_: Exception) {}
        mediaPlayer = null
    }

    private fun playAudioBase64(base64Data: String) {
        try {
            val bytes = Base64.decode(base64Data, Base64.DEFAULT)
            val tempFile = File.createTempFile("eval_sound_", ".wav", context.cacheDir)
            tempFile.writeBytes(bytes)

            val mp = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(tempFile.absolutePath)
                setOnPreparedListener { it.start() }
                setOnCompletionListener {
                    it.release()
                    tempFile.delete()
                    if (mediaPlayer == it) mediaPlayer = null
                }
                setOnErrorListener { mpError, _, _ ->
                    tempFile.delete()
                    if (mediaPlayer == mpError) mediaPlayer = null
                    true
                }
            }
            mediaPlayer = mp
            mp.prepareAsync()
        } catch (e: Exception) {
            Log.e("EvaluationSoundRepo", "Error playing sound audio", e)
        }
    }

    private suspend fun callGeminiTts(text: String, modelId: String = "gemini-3.1-flash-tts-preview"): ByteArray =
        withContext(Dispatchers.IO) {
            val response = geminiApi.generateSpeech(
                model = modelId,
                apiKey = BuildConfig.GEMINI_API_KEY,
                request = GeminiRequest(
                    contents = listOf(GeminiContent(parts = listOf(GeminiPart(text = text)))),
                    generationConfig = GeminiGenConfig(
                        responseModalities = listOf("AUDIO"),
                        speechConfig = GeminiSpeechConfig(
                            voiceConfig = GeminiVoiceConfig(
                                prebuiltVoiceConfig = GeminiPrebuiltVoice(voiceName = "Aoede")
                            )
                        )
                    )
                )
            )

            val inlineData = response.candidates
                ?.firstOrNull()?.content?.parts?.firstOrNull()?.inlineData
                ?: throw Exception("Gemini TTS empty response for evaluation sound")

            val pcmBytes = Base64.decode(inlineData.data, Base64.DEFAULT)
            val sampleRate = parseSampleRate(inlineData.mimeType)
            pcmToWav(pcmBytes, sampleRate)
        }

    private fun parseSampleRate(mimeType: String): Int {
        val rateParam = mimeType.split(";")
            .map { it.trim() }
            .firstOrNull { it.startsWith("rate=") }
        return rateParam?.removePrefix("rate=")?.toIntOrNull() ?: 24000
    }

    private fun pcmToWav(pcmBytes: ByteArray, sampleRate: Int): ByteArray {
        val header = ByteArray(44)
        val totalDataLen = pcmBytes.size + 36
        val byteRate = sampleRate * 2

        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()

        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = ((totalDataLen shr 8) and 0xff).toByte()
        header[6] = ((totalDataLen shr 16) and 0xff).toByte()
        header[7] = ((totalDataLen shr 24) and 0xff).toByte()

        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()

        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()

        header[16] = 16
        header[17] = 0
        header[18] = 0
        header[19] = 0

        header[20] = 1
        header[21] = 0

        header[22] = 1
        header[23] = 0

        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()

        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()

        header[32] = 2
        header[33] = 0

        header[34] = 16
        header[35] = 0

        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()

        header[40] = (pcmBytes.size and 0xff).toByte()
        header[41] = ((pcmBytes.size shr 8) and 0xff).toByte()
        header[42] = ((pcmBytes.size shr 16) and 0xff).toByte()
        header[43] = ((pcmBytes.size shr 24) and 0xff).toByte()

        return header + pcmBytes
    }
}
