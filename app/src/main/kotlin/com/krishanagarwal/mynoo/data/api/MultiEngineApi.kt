package com.krishanagarwal.mynoo.data.api

import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Url

// ── Retrofit Interfaces ──────────────────────────────────────────────────────

interface OpenAiApi {
    @POST("v1/responses")
    suspend fun createResponse(
        @Header("Authorization") authHeader: String,
        @Body request: LlmResponseRequest
    ): LlmResponseResponse
}

interface XaiApi {
    @POST("v1/responses")
    suspend fun createResponse(
        @Header("Authorization") authHeader: String,
        @Body request: LlmResponseRequest
    ): LlmResponseResponse
}

interface SarvamChatApi {
    @POST("v1/chat/completions")
    suspend fun chatCompletions(
        @Header("api-subscription-key") apiKey: String,
        @Body request: SarvamChatRequest
    ): SarvamChatResponse
}

interface GistApi {
    @GET
    suspend fun downloadRawFile(
        @Url url: String,
        @Header("Cache-Control") cacheControl: String = "no-cache"
    ): ResponseBody
}

// ── OpenAI & xAI Responses API Models ────────────────────────────────────────

data class LlmResponseRequest(
    val model: String,
    val input: Any, // Can be String or List<LlmMessage>
    @SerializedName("previous_response_id") val previousResponseId: String? = null,
    val instructions: String? = null,
    val temperature: Double? = null,
    @SerializedName("max_output_tokens") val maxOutputTokens: Int? = null,
    val store: Boolean = true,
    val text: LlmTextFormat? = null,
    val reasoning: LlmReasoning? = null
)

data class LlmMessage(
    val role: String, // "system" | "user" | "assistant"
    val content: String
)

data class LlmTextFormat(
    val format: LlmJsonSchemaFormat
)

data class LlmJsonSchemaFormat(
    val type: String = "json_schema",
    val name: String,
    val strict: Boolean = true,
    val schema: JsonElement
)

data class LlmReasoning(
    val effort: String // "none" | "low" | "high"
)

data class LlmResponseResponse(
    val id: String,
    val output: List<LlmOutputItem>? = null,
    val usage: LlmUsage? = null
)

data class LlmOutputItem(
    val content: List<LlmContentItem>? = null
)

data class LlmContentItem(
    val type: String? = null,
    val text: String? = null
)

data class LlmUsage(
    @SerializedName("input_tokens") val inputTokens: Int? = null,
    @SerializedName("output_tokens") val outputTokens: Int? = null,
    @SerializedName("input_tokens_details") val details: LlmUsageDetails? = null
)

data class LlmUsageDetails(
    @SerializedName("cached_tokens") val cachedTokens: Int? = null
)

// ── Sarvam Chat Completions Models ───────────────────────────────────────────

data class SarvamChatRequest(
    val model: String,
    val messages: List<SarvamChatMessage>,
    val temperature: Double? = null,
    @SerializedName("max_tokens") val maxTokens: Int? = null,
    @SerializedName("response_format") val responseFormat: SarvamResponseFormat? = null
)

data class SarvamChatMessage(
    val role: String, // "system" | "user" | "assistant"
    val content: String
)

data class SarvamResponseFormat(
    val type: String = "json_object"
)

data class SarvamChatResponse(
    val choices: List<SarvamChatChoice>? = null,
    val usage: SarvamChatUsage? = null
)

data class SarvamChatChoice(
    val message: SarvamChatMessage? = null
)

data class SarvamChatUsage(
    @SerializedName("prompt_tokens") val promptTokens: Int? = null,
    @SerializedName("completion_tokens") val completionTokens: Int? = null
)
