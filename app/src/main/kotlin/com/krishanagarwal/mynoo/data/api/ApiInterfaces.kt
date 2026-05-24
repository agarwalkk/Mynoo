package com.krishanagarwal.mynoo.data.api

import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Query

interface GeminiApi {

    /** Standard text generation (chat). */
    @POST("v1beta/models/{model}:generateContent")
    suspend fun generateContent(
        @retrofit2.http.Path("model") model: String,
        @Query("key") apiKey: String,
        @Body request: GeminiRequest,
    ): GeminiResponse

    /** TTS — returns audio/L16 inline data. */
    @POST("v1beta/models/{model}:generateContent")
    suspend fun generateSpeech(
        @retrofit2.http.Path("model") model: String,
        @Query("key") apiKey: String,
        @Body request: GeminiRequest,
    ): GeminiResponse
}

interface SarvamApi {
    @Multipart
    @POST("speech-to-text")
    suspend fun transcribe(
        @Part file: MultipartBody.Part,
        @Part("model") model: RequestBody,
        @Part("language_code") languageCode: RequestBody,
        @retrofit2.http.Header("api-subscription-key") apiKey: String,
    ): SarvamSttResponse
}

data class SarvamSttResponse(
    val transcript: String? = null,
    val language_code: String? = null,
)
