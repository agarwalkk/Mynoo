package com.krishanagarwal.mynoo.data.repository

import com.google.firebase.firestore.FirebaseFirestore
import com.krishanagarwal.mynoo.data.model.*
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

private const val SETTINGS_COLLECTION = "settings"
private const val GLOBAL_DOCUMENT     = "global"

@Singleton
class GlobalSettingsRepository @Inject constructor(
    private val db: FirebaseFirestore,
) {
    private val docRef get() = db.collection(SETTINGS_COLLECTION).document(GLOBAL_DOCUMENT)

    @Volatile private var cached: GlobalSettings? = null

    suspend fun load(): GlobalSettings {
        cached?.let { return it }
        return try {
            val doc  = docRef.get().await()
            val raw  = if (doc.exists()) parse(doc.data ?: emptyMap()) else GlobalSettings()
            val clean = raw.sanitize()
            // If the document was missing or in an old format, write back the sanitised
            // structure so Firestore stays in sync with the current schema.
            if (!doc.exists() || clean != raw) {
                try { docRef.set(toMap(clean)).await() } catch (_: Exception) { /* best-effort */ }
            }
            clean.also { cached = it }
        } catch (_: Exception) {
            GlobalSettings().also { cached = it }
        }
    }

    suspend fun save(settings: GlobalSettings) {
        cached = settings
        docRef.set(toMap(settings)).await()
    }

    fun invalidate() { cached = null }

    // ── Firestore deserialisation ──────────────────────────────────────────

    @Suppress("UNCHECKED_CAST")
    private fun parse(data: Map<String, Any>): GlobalSettings = GlobalSettings(
        talk = parseFn(data["talk"] as? Map<*, *>),
        read = parseFn(data["read"] as? Map<*, *>),
        quiz = parseFn(data["quiz"] as? Map<*, *>),
        pin  = data["pin"] as? String ?: "1234",
    )

    private fun parseFn(m: Map<*, *>?): FunctionConfig = FunctionConfig(
        en = parseLang(m?.get("en") as? Map<*, *>, defaultInherit = false),
        hi = parseLang(m?.get("hi") as? Map<*, *>, defaultInherit = true),
        pa = parseLang(m?.get("pa") as? Map<*, *>, defaultInherit = true),
    )

    private fun parseLang(m: Map<*, *>?, defaultInherit: Boolean): LangConfig {
        val inherit = m?.get("inherit") as? Boolean ?: defaultInherit
        return LangConfig(
            inherit = inherit,
            llm = parseModelConfig(m?.get("llm") as? Map<*, *>),
            tts = parseTtsConfig(m?.get("tts") as? Map<*, *>),
            stt = parseSttConfig(m?.get("stt") as? Map<*, *>),
        )
    }

    private fun parseModelConfig(m: Map<*, *>?): ModelConfig = ModelConfig(
        provider       = m?.get("provider") as? String ?: "google",
        model          = m?.get("model") as? String ?: "gemini-3.5-flash",
        reasoningLevel = (m?.get("reasoningLevel") as? Long)?.toInt() ?: 0,
        temperature    = m?.get("temperature") as? Double ?: 0.7,
    )

    private fun parseTtsConfig(m: Map<*, *>?): TtsConfig = TtsConfig(
        provider = m?.get("provider") as? String ?: "google",
        model    = m?.get("model") as? String ?: "gemini-3.1-flash-tts-preview",
    )

    private fun parseSttConfig(m: Map<*, *>?): SttConfig = SttConfig(
        provider = m?.get("provider") as? String ?: "sarvam",
        model    = m?.get("model") as? String ?: "saaras:v3",
    )

    // ── Firestore serialisation ────────────────────────────────────────────

    private fun toMap(s: GlobalSettings): Map<String, Any> = mapOf(
        "talk" to fnMap(s.talk),
        "read" to fnMap(s.read),
        "quiz" to fnMap(s.quiz),
        "pin"  to s.pin,
    )

    private fun fnMap(c: FunctionConfig): Map<String, Any> = mapOf(
        "en" to langMap(c.en),
        "hi" to langMap(c.hi),
        "pa" to langMap(c.pa),
    )

    private fun langMap(c: LangConfig): Map<String, Any> {
        if (c.inherit) return mapOf("inherit" to true)
        return mapOf(
            "inherit" to false,
            "llm" to mapOf(
                "provider"       to c.llm.provider,
                "model"          to c.llm.model,
                "reasoningLevel" to c.llm.reasoningLevel,
                "temperature"    to c.llm.temperature,
            ),
            "tts" to mapOf("provider" to c.tts.provider, "model" to c.tts.model),
            "stt" to mapOf("provider" to c.stt.provider, "model" to c.stt.model),
        )
    }
}
