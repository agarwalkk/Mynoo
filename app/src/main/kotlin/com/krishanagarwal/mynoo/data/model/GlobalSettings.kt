package com.krishanagarwal.mynoo.data.model

// ── Provider / Model catalogue ─────────────────────────────────────────────

data class ModelInfo(
    val id: String,
    val name: String,
    val supportsReasoning: Boolean = false,
    val supportsTemperature: Boolean = true,
    val supportsThreading: Boolean = false,
    val badge: String? = null,
)

data class ProviderInfo(
    val id: String,
    val name: String,
    val models: List<ModelInfo>,
)

object AiCatalogue {
    val llmProviders = listOf(
        ProviderInfo("google", "Google", listOf(
            ModelInfo("gemini-3.5-flash", "Gemini 3.5 Flash", supportsReasoning = true, badge = "Recommended"),
            ModelInfo("gemini-3.1-flash-lite", "Gemini 3.1 Flash Lite", supportsReasoning = true, badge = "Fast"),
            ModelInfo("gemini-3.1-pro-preview", "Gemini 3.1 Pro", supportsReasoning = true, badge = "Smart"),
            ModelInfo("gemini-2.5-flash", "Gemini 2.5 Flash", supportsReasoning = true),
            ModelInfo("gemini-2.5-flash-lite", "Gemini 2.5 Flash Lite", supportsReasoning = true),
            ModelInfo("gemini-2.5-pro", "Gemini 2.5 Pro", supportsReasoning = true, badge = "Smart"),
        )),
        ProviderInfo("xai", "Grok / xAI", listOf(
            ModelInfo("grok-4.3", "Grok 4.3", supportsReasoning = true, supportsTemperature = false, badge = "Recommended"),
        )),
        ProviderInfo("openai", "OpenAI", listOf(
            // Note: gpt-* models do not support reasoning.effort (only o-series does)
            ModelInfo("gpt-5.5", "GPT-5.5", supportsReasoning = false, supportsThreading = true, badge = "Flagship"),
            ModelInfo("gpt-5.4", "GPT-5.4", supportsReasoning = false, supportsThreading = true),
            ModelInfo("gpt-5.4-mini", "GPT-5.4 Mini", supportsReasoning = false, supportsThreading = true, badge = "Fast"),
        )),
    )

    val ttsProviders = listOf(
        ProviderInfo("google", "Google", listOf(
            ModelInfo("gemini-3.1-flash-tts-preview", "Gemini 3.1 Flash TTS", badge = "Recommended"),
            ModelInfo("gemini-2.5-flash-preview-tts", "Gemini 2.5 Flash TTS"),
            ModelInfo("gemini-2.5-pro-preview-tts", "Gemini 2.5 Pro TTS", badge = "Hi-Fi"),
        )),
        ProviderInfo("elevenlabs", "ElevenLabs", listOf(
            ModelInfo("eleven_v3", "ElevenLabs v3", badge = "70+ langs"),
        )),
        ProviderInfo("sarvam", "Sarvam AI", listOf(
            ModelInfo("bulbul:v3", "Bulbul v3", badge = "Indian langs"),
        )),
    )

    val sttProviders = listOf(
        ProviderInfo("google", "Google", listOf(
            ModelInfo("speech-v1", "Google STT v1", badge = "Recommended"),
        )),
        ProviderInfo("elevenlabs", "ElevenLabs", listOf(
            ModelInfo("scribe_v2", "Scribe v2", badge = "90+ langs"),
        )),
        ProviderInfo("sarvam", "Sarvam AI", listOf(
            ModelInfo("saaras:v3", "Saaras v3", badge = "Indian langs"),
        )),
        ProviderInfo("openai", "OpenAI", listOf(
            ModelInfo("gpt-4o-transcribe", "GPT-4o Transcribe"),
            ModelInfo("gpt-4o-mini-transcribe", "GPT-4o Mini Transcribe", badge = "Fast"),
        )),
    )

    val reasoningLabels = listOf("Off", "Light", "Medium", "Heavy")

    fun llmProvider(id: String): ProviderInfo? = llmProviders.find { it.id == id }
    fun ttsProvider(id: String): ProviderInfo? = ttsProviders.find { it.id == id }
    fun sttProvider(id: String): ProviderInfo? = sttProviders.find { it.id == id }

    /** Infer provider ID from model string prefix */
    fun providerForModel(model: String): String = when {
        model.startsWith("grok-")  -> "xai"
        model.startsWith("gpt-")   -> "openai"
        model.startsWith("eleven") -> "elevenlabs"
        model.startsWith("scribe") -> "elevenlabs"
        model.startsWith("bulbul") || model.startsWith("saaras") -> "sarvam"
        else                       -> "google"
    }
}

// ── Settings data model ────────────────────────────────────────────────────

data class ModelConfig(
    val provider: String = "google",
    val model: String = "gemini-3.5-flash",
    val reasoningLevel: Int = 0,        // 0=Off  1=Light  2=Medium  3=Heavy
    val temperature: Double = 0.7,
)

data class TtsConfig(
    val provider: String = "google",
    val model: String = "gemini-3.1-flash-tts-preview",
)

data class SttConfig(
    // Default to Sarvam: Google STT requires a separate Speech API key (not the Gemini key)
    val provider: String = "sarvam",
    val model: String = "saaras:v3",
)

data class LangConfig(
    val inherit: Boolean = false,
    val llm: ModelConfig = ModelConfig(),
    val tts: TtsConfig = TtsConfig(),
    val stt: SttConfig = SttConfig(),
)

data class FunctionConfig(
    val en: LangConfig = LangConfig(),
    val hi: LangConfig = LangConfig(inherit = true),
    val pa: LangConfig = LangConfig(inherit = true),
) {
    /** Effective config for the given lang — falls back to EN if inherit = true */
    fun resolve(lang: String): LangConfig = when (lang.lowercase()) {
        "hi" -> if (hi.inherit) en else hi
        "pa" -> if (pa.inherit) en else pa
        else -> en
    }
}

data class GlobalSettings(
    val talk: FunctionConfig = FunctionConfig(),
    val read: FunctionConfig = FunctionConfig(),
    val quiz: FunctionConfig = FunctionConfig(),
    val pin: String = "1234",
) {
    fun resolve(function: String, lang: String): LangConfig = when (function) {
        "read" -> read.resolve(lang)
        "quiz" -> quiz.resolve(lang)
        else   -> talk.resolve(lang)
    }
}

// ── Reasoning level mapper ─────────────────────────────────────────────────

object ReasoningMapper {
    /** 0-3 → Gemini thinkingBudget (token count; 0 disables thinking) */
    fun toGeminiThinkingBudget(level: Int): Int = when (level) {
        1    -> 1024
        2    -> 8192
        3    -> 24576
        else -> 0
    }

    /** 0-3 → xAI / OpenAI reasoning_effort string */
    fun toReasoningEffort(level: Int): String = when (level) {
        1    -> "low"
        2    -> "medium"
        3    -> "high"
        else -> "none"
    }

    /** null means "do not include reasoning param" (off) */
    fun toReasoningEffortOrNull(level: Int): String? =
        if (level == 0) null else toReasoningEffort(level)
}

// ── Migration / validation ─────────────────────────────────────────────────

/**
 * Validates all provider+model combinations against AiCatalogue and migrates
 * any legacy/invalid values to safe defaults. Called on every settings load so
 * old Firestore documents (written by previous app versions) are automatically
 * corrected at runtime and written back to Firestore.
 */
fun GlobalSettings.sanitize(): GlobalSettings = copy(
    talk = talk.sanitizeFn(),
    read = read.sanitizeFn(),
    quiz = quiz.sanitizeFn(),
)

private fun FunctionConfig.sanitizeFn(): FunctionConfig = copy(
    en = en.sanitizeLang(),
    hi = hi.sanitizeLang(),
    pa = pa.sanitizeLang(),
)

private fun LangConfig.sanitizeLang(): LangConfig =
    if (inherit) this   // inherited langs fall back to EN at runtime — skip validation
    else copy(llm = llm.sanitizeLlm(), tts = tts.sanitizeTts(), stt = stt.sanitizeStt())

private fun ModelConfig.sanitizeLlm(): ModelConfig {
    val p = AiCatalogue.llmProvider(provider) ?: AiCatalogue.llmProviders.first()
    val m = p.models.find { it.id == model }  ?: p.models.first()
    return copy(provider = p.id, model = m.id, reasoningLevel = reasoningLevel.coerceIn(0, 3))
}

private fun TtsConfig.sanitizeTts(): TtsConfig {
    val p = AiCatalogue.ttsProvider(provider) ?: AiCatalogue.ttsProviders.first()
    val m = p.models.find { it.id == model }  ?: p.models.first()
    return copy(provider = p.id, model = m.id)
}

private fun SttConfig.sanitizeStt(): SttConfig {
    // "google" STT requires a separate Cloud Speech API key (not the Gemini key) —
    // migrate any old stored "google" entries to Sarvam.
    val effectiveProvider = if (provider == "google") "sarvam" else provider
    val effectiveModel    = if (provider == "google") "saaras:v3" else model
    val p = AiCatalogue.sttProvider(effectiveProvider)
        ?: AiCatalogue.sttProviders.find { it.id == "sarvam" }!!
    val m = p.models.find { it.id == effectiveModel } ?: p.models.first()
    return copy(provider = p.id, model = m.id)
}
