package com.krishanagarwal.mynoo.data.repository

import com.google.firebase.firestore.FirebaseFirestore
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
    val outputTokens: Int = 0
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
    val byProvider: Map<String, TtsProviderStats> = emptyMap()
)

data class SttStats(
    val calls: Int = 0,
    val durationMs: Long = 0L
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

        var sttCalls = 0
        var sttDur = 0L

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
                        outputTokens = prevPurpose.outputTokens + out
                    )

                    // Aggregate by provider
                    val prevProvider = llmProvider[provider] ?: ProviderStats()
                    llmProvider[provider] = ProviderStats(
                        calls = prevProvider.calls + calls,
                        inputTokens = prevProvider.inputTokens + inp,
                        outputTokens = prevProvider.outputTokens + out
                    )

                    // Aggregate by language
                    val prevLang = llmLanguage[language] ?: ProviderStats()
                    llmLanguage[language] = ProviderStats(
                        calls = prevLang.calls + calls,
                        inputTokens = prevLang.inputTokens + inp,
                        outputTokens = prevLang.outputTokens + out
                    )

                    dayMap[date] = currentDay.copy(
                        llmTokens = currentDay.llmTokens + inp + out
                    )
                }
                "tts" -> {
                    val calls = (doc.get("calls") as? Number)?.toInt() ?: 0
                    val chars = (doc.get("charCount") as? Number)?.toInt() ?: 0
                    val provider = doc.getString("provider") ?: "unknown"

                    ttsCalls += calls
                    ttsChar += chars

                    val prevTts = ttsProvider[provider] ?: TtsProviderStats()
                    ttsProvider[provider] = TtsProviderStats(
                        calls = prevTts.calls + calls,
                        charCount = prevTts.charCount + chars
                    )

                    dayMap[date] = currentDay.copy(
                        ttsCalls = currentDay.ttsCalls + calls
                    )
                }
                "stt" -> {
                    val calls = (doc.get("calls") as? Number)?.toInt() ?: 0
                    val dur = (doc.get("durationMs") as? Number)?.toLong() ?: 0L

                    sttCalls += calls
                    sttDur += dur

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
                byProvider = ttsProvider
            ),
            stt = SttStats(
                calls = sttCalls,
                durationMs = sttDur
            ),
            days = dayMap.values.sortedBy { it.date }
        )
    }
}
