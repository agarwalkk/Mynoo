package com.krishanagarwal.mynoo.data.repository

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

data class UsageDay(
    val date: String,
    val llmTokens: Int,
    val ttsCalls: Int,
    val sttCalls: Int
)

data class ProviderStats(
    val calls: Int = 0,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val cachedTokens: Int = 0
)

data class LlmStats(
    val calls: Int = 0,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val cachedInputTokens: Int = 0,
    val byPurpose: Map<String, ProviderStats> = emptyMap(),
    val byProvider: Map<String, ProviderStats> = emptyMap(),
    val byLanguage: Map<String, ProviderStats> = emptyMap()
)

data class TtsProviderStats(
    val calls: Int = 0,
    val charCount: Int = 0
)

data class TtsStats(
    val calls: Int = 0,
    val charCount: Int = 0,
    val byProvider: Map<String, TtsProviderStats> = emptyMap(),
    val byPurpose: Map<String, TtsProviderStats> = emptyMap()
)

data class SttProviderStats(
    val calls: Int = 0,
    val durationMs: Long = 0L
)

data class SttStats(
    val calls: Int = 0,
    val durationMs: Long = 0L,
    val byProvider: Map<String, SttProviderStats> = emptyMap(),
    val byPurpose: Map<String, SttProviderStats> = emptyMap()
)

data class UsageSummary(
    val llm: LlmStats = LlmStats(),
    val tts: TtsStats = TtsStats(),
    val stt: SttStats = SttStats(),
    val days: List<UsageDay> = emptyList()
)

@Singleton
class UsageRepository @Inject constructor(
    private val db: FirebaseFirestore
) {
    private val col = db.collection("usageLogs")

    suspend fun recordLlm(
        childName: String,
        purpose: String,
        provider: String,
        language: String,
        inputTokens: Int,
        outputTokens: Int,
        cachedInputTokens: Int = 0
    ) = withContext(Dispatchers.IO) {
        if (childName.isBlank()) return@withContext
        val date = LocalDate.now().format(DateTimeFormatter.ISO_DATE)
        val docId = "${childName}_${date}_llm_${purpose}_${provider}_${language}"
        try {
            col.document(docId).set(
                mapOf(
                    "childName" to childName,
                    "date" to date,
                    "service" to "llm",
                    "purpose" to purpose,
                    "provider" to provider,
                    "language" to language,
                    "calls" to FieldValue.increment(1),
                    "inputTokens" to FieldValue.increment(inputTokens.toLong()),
                    "outputTokens" to FieldValue.increment(outputTokens.toLong()),
                    "cachedInputTokens" to FieldValue.increment(cachedInputTokens.toLong())
                ),
                SetOptions.merge()
            ).await()
        } catch (e: Exception) {
            android.util.Log.e("UsageRepo", "Error recording LLM usage", e)
        }
    }

    suspend fun recordTts(
        childName: String,
        purpose: String,
        provider: String,
        charCount: Int
    ) = withContext(Dispatchers.IO) {
        if (childName.isBlank()) return@withContext
        val date = LocalDate.now().format(DateTimeFormatter.ISO_DATE)
        val docId = "${childName}_${date}_tts_${purpose}_${provider}"
        try {
            col.document(docId).set(
                mapOf(
                    "childName" to childName,
                    "date" to date,
                    "service" to "tts",
                    "purpose" to purpose,
                    "provider" to provider,
                    "calls" to FieldValue.increment(1),
                    "charCount" to FieldValue.increment(charCount.toLong())
                ),
                SetOptions.merge()
            ).await()
        } catch (e: Exception) {
            android.util.Log.e("UsageRepo", "Error recording TTS usage", e)
        }
    }

    suspend fun recordStt(
        childName: String,
        purpose: String,
        provider: String,
        durationMs: Long
    ) = withContext(Dispatchers.IO) {
        if (childName.isBlank()) return@withContext
        val date = LocalDate.now().format(DateTimeFormatter.ISO_DATE)
        val docId = "${childName}_${date}_stt_${purpose}_${provider}"
        try {
            col.document(docId).set(
                mapOf(
                    "childName" to childName,
                    "date" to date,
                    "service" to "stt",
                    "purpose" to purpose,
                    "provider" to provider,
                    "calls" to FieldValue.increment(1),
                    "durationMs" to FieldValue.increment(durationMs)
                ),
                SetOptions.merge()
            ).await()
        } catch (e: Exception) {
            android.util.Log.e("UsageRepo", "Error recording STT usage", e)
        }
    }

    suspend fun getUsageStats(childName: String, periodDays: Int): UsageSummary = withContext(Dispatchers.IO) {
        val formatter = DateTimeFormatter.ISO_DATE
        val today = LocalDate.now()
        val endDateStr = today.format(formatter)
        val startDateStr = today.minusDays((periodDays - 1).toLong()).format(formatter)

        // Query usageLogs within the date range
        val snap = try {
            col.whereGreaterThanOrEqualTo("date", startDateStr)
                .whereLessThanOrEqualTo("date", endDateStr)
                .get(Source.DEFAULT)
                .await()
        } catch (e: Exception) {
            return@withContext UsageSummary()
        }

        // Filter client-side by childName
        val docs = if (childName.isNotBlank() && childName != "all") {
            snap.documents.filter { it.getString("childName") == childName }
        } else {
            snap.documents
        }

        var llmCalls = 0
        var llmIn = 0
        var llmOut = 0
        var llmCach = 0
        val llmPurpose = mutableMapOf<String, ProviderStats>()
        val llmProvider = mutableMapOf<String, ProviderStats>()
        val llmLanguage = mutableMapOf<String, ProviderStats>()

        var ttsCalls = 0
        var ttsChar = 0
        val ttsProvider = mutableMapOf<String, TtsProviderStats>()
        val ttsPurpose = mutableMapOf<String, TtsProviderStats>()

        var sttCalls = 0
        var sttDur = 0L
        val sttProvider = mutableMapOf<String, SttProviderStats>()
        val sttPurpose = mutableMapOf<String, SttProviderStats>()

        // Initialize day map
        val dayMap = mutableMapOf<String, UsageDay>()
        for (i in 0 until periodDays) {
            val d = today.minusDays(i.toLong()).format(formatter)
            dayMap[d] = UsageDay(d, 0, 0, 0)
        }

        for (doc in docs) {
            val date = doc.getString("date") ?: ""
            val service = doc.getString("service") ?: ""

            val currentDay = dayMap[date] ?: UsageDay(date, 0, 0, 0)

            when (service) {
                "llm" -> {
                    val calls = (doc.get("calls") as? Number)?.toInt() ?: 0
                    val inp = (doc.get("inputTokens") as? Number)?.toInt() ?: 0
                    val out = (doc.get("outputTokens") as? Number)?.toInt() ?: 0
                    val cach = (doc.get("cachedInputTokens") as? Number)?.toInt() ?: 0
                    val purpose = doc.getString("purpose") ?: "unknown"
                    val provider = doc.getString("provider") ?: "unknown"
                    val language = doc.getString("language") ?: "unknown"

                    llmCalls += calls
                    llmIn += inp
                    llmOut += out
                    llmCach += cach

                    // Aggregate by purpose
                    val prevPurpose = llmPurpose[purpose] ?: ProviderStats()
                    llmPurpose[purpose] = ProviderStats(
                        calls = prevPurpose.calls + calls,
                        inputTokens = prevPurpose.inputTokens + inp,
                        outputTokens = prevPurpose.outputTokens + out,
                        cachedTokens = prevPurpose.cachedTokens + cach
                    )

                    // Aggregate by provider
                    val prevProvider = llmProvider[provider] ?: ProviderStats()
                    llmProvider[provider] = ProviderStats(
                        calls = prevProvider.calls + calls,
                        inputTokens = prevProvider.inputTokens + inp,
                        outputTokens = prevProvider.outputTokens + out,
                        cachedTokens = prevProvider.cachedTokens + cach
                    )

                    // Aggregate by language
                    val prevLang = llmLanguage[language] ?: ProviderStats()
                    llmLanguage[language] = ProviderStats(
                        calls = prevLang.calls + calls,
                        inputTokens = prevLang.inputTokens + inp,
                        outputTokens = prevLang.outputTokens + out,
                        cachedTokens = prevLang.cachedTokens + cach
                    )

                    dayMap[date] = currentDay.copy(
                        llmTokens = currentDay.llmTokens + inp + out
                    )
                }
                "tts" -> {
                    val calls = (doc.get("calls") as? Number)?.toInt() ?: 0
                    val chars = (doc.get("charCount") as? Number)?.toInt() ?: 0
                    val provider = doc.getString("provider") ?: "unknown"
                    val purpose = doc.getString("purpose") ?: "unknown"

                    ttsCalls += calls
                    ttsChar += chars

                    // Aggregate by provider
                    val prevTts = ttsProvider[provider] ?: TtsProviderStats()
                    ttsProvider[provider] = TtsProviderStats(
                        calls = prevTts.calls + calls,
                        charCount = prevTts.charCount + chars
                    )

                    // Aggregate by purpose
                    val prevPurpose = ttsPurpose[purpose] ?: TtsProviderStats()
                    ttsPurpose[purpose] = TtsProviderStats(
                        calls = prevPurpose.calls + calls,
                        charCount = prevPurpose.charCount + chars
                    )

                    dayMap[date] = currentDay.copy(
                        ttsCalls = currentDay.ttsCalls + calls
                    )
                }
                "stt" -> {
                    val calls = (doc.get("calls") as? Number)?.toInt() ?: 0
                    val dur = (doc.get("durationMs") as? Number)?.toLong() ?: 0L
                    val provider = doc.getString("provider") ?: "unknown"
                    val purpose = doc.getString("purpose") ?: "unknown"

                    sttCalls += calls
                    sttDur += dur

                    // Aggregate by provider
                    val prevStt = sttProvider[provider] ?: SttProviderStats()
                    sttProvider[provider] = SttProviderStats(
                        calls = prevStt.calls + calls,
                        durationMs = prevStt.durationMs + dur
                    )

                    // Aggregate by purpose
                    val prevPurpose = sttPurpose[purpose] ?: SttProviderStats()
                    sttPurpose[purpose] = SttProviderStats(
                        calls = prevPurpose.calls + calls,
                        durationMs = prevPurpose.durationMs + dur
                    )

                    dayMap[date] = currentDay.copy(
                        sttCalls = currentDay.sttCalls + calls
                    )
                }
            }
        }

        UsageSummary(
            llm = LlmStats(
                calls = llmCalls,
                inputTokens = llmIn,
                outputTokens = llmOut,
                cachedInputTokens = llmCach,
                byPurpose = llmPurpose,
                byProvider = llmProvider,
                byLanguage = llmLanguage
            ),
            tts = TtsStats(
                calls = ttsCalls,
                charCount = ttsChar,
                byProvider = ttsProvider,
                byPurpose = ttsPurpose
            ),
            stt = SttStats(
                calls = sttCalls,
                durationMs = sttDur,
                byProvider = sttProvider,
                byPurpose = sttPurpose
            ),
            days = dayMap.values.sortedBy { it.date }
        )
    }
}
