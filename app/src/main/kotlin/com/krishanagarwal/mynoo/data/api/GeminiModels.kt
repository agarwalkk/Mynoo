package com.krishanagarwal.mynoo.data.api

import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName

// ── Request ──────────────────────────────────────────────────────────────────

data class GeminiRequest(
    val systemInstruction: GeminiContent? = null,
    val contents: List<GeminiContent>,
    val generationConfig: GeminiGenConfig? = null,
)

data class GeminiContent(
    val role: String? = null,           // "user" | "model"
    val parts: List<GeminiPart>,
)

data class GeminiPart(
    val text: String? = null,
    val inlineData: GeminiInlineData? = null,
)

data class GeminiInlineData(
    val mimeType: String,
    val data: String,                   // base64
)

data class GeminiGenConfig(
    val temperature: Double? = null,
    @SerializedName("responseModalities")
    val responseModalities: List<String>? = null,
    val speechConfig: GeminiSpeechConfig? = null,
    val responseMimeType: String? = null,
    val responseSchema: JsonElement? = null,
)

data class GeminiSpeechConfig(
    val voiceConfig: GeminiVoiceConfig,
)

data class GeminiVoiceConfig(
    val prebuiltVoiceConfig: GeminiPrebuiltVoice,
)

data class GeminiPrebuiltVoice(
    val voiceName: String,
)

// ── Response ─────────────────────────────────────────────────────────────────

data class GeminiUsageMetadata(
    @SerializedName("promptTokenCount") val promptTokenCount: Int = 0,
    @SerializedName("candidatesTokenCount") val candidatesTokenCount: Int = 0,
    @SerializedName("totalTokenCount") val totalTokenCount: Int = 0,
)

data class GeminiResponse(
    val candidates: List<GeminiCandidate>? = null,
    val error: GeminiError? = null,
    val usageMetadata: GeminiUsageMetadata? = null,
)

data class GeminiCandidate(
    val content: GeminiContent? = null,
)

data class GeminiError(
    val code: Int? = null,
    val message: String? = null,
)
