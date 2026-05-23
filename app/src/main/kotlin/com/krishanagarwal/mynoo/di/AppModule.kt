package com.krishanagarwal.mynoo.di

import android.content.Context
import com.google.firebase.firestore.FirebaseFirestore
import com.google.gson.GsonBuilder
import com.krishanagarwal.mynoo.data.api.GeminiApi
import com.krishanagarwal.mynoo.data.api.GistApi
import com.krishanagarwal.mynoo.data.api.OpenAiApi
import com.krishanagarwal.mynoo.data.api.SarvamApi
import com.krishanagarwal.mynoo.data.api.SarvamChatApi
import com.krishanagarwal.mynoo.data.api.XaiApi
import com.krishanagarwal.mynoo.data.store.MynooPrefsStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun provideFirestore(): FirebaseFirestore = FirebaseFirestore.getInstance()

    @Provides @Singleton
    fun providePrefsStore(@ApplicationContext ctx: Context): MynooPrefsStore =
        MynooPrefsStore(ctx)

    @Provides @Singleton
    fun provideOkHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        })
        .build()

    @Provides @Singleton @Named("gemini")
    fun provideGeminiRetrofit(okHttp: OkHttpClient): Retrofit = Retrofit.Builder()
        .baseUrl("https://generativelanguage.googleapis.com/")
        .client(okHttp)
        .addConverterFactory(GsonConverterFactory.create(GsonBuilder().create()))
        .build()

    @Provides @Singleton @Named("sarvam")
    fun provideSarvamRetrofit(okHttp: OkHttpClient): Retrofit = Retrofit.Builder()
        .baseUrl("https://api.sarvam.ai/")
        .client(okHttp)
        .addConverterFactory(GsonConverterFactory.create(GsonBuilder().create()))
        .build()

    @Provides @Singleton
    fun provideGeminiApi(@Named("gemini") retrofit: Retrofit): GeminiApi =
        retrofit.create(GeminiApi::class.java)

    @Provides @Singleton
    fun provideSarvamApi(@Named("sarvam") retrofit: Retrofit): SarvamApi =
        retrofit.create(SarvamApi::class.java)

    @Provides @Singleton @Named("gist")
    fun provideGistRetrofit(okHttp: OkHttpClient): Retrofit = Retrofit.Builder()
        .baseUrl("https://gist.githubusercontent.com/")
        .client(okHttp)
        .addConverterFactory(GsonConverterFactory.create(GsonBuilder().create()))
        .build()

    @Provides @Singleton
    fun provideGistApi(@Named("gist") retrofit: Retrofit): GistApi =
        retrofit.create(GistApi::class.java)

    @Provides @Singleton @Named("openai")
    fun provideOpenAiRetrofit(okHttp: OkHttpClient): Retrofit = Retrofit.Builder()
        .baseUrl("https://api.openai.com/")
        .client(okHttp)
        .addConverterFactory(GsonConverterFactory.create(GsonBuilder().create()))
        .build()

    @Provides @Singleton
    fun provideOpenAiApi(@Named("openai") retrofit: Retrofit): OpenAiApi =
        retrofit.create(OpenAiApi::class.java)

    @Provides @Singleton @Named("xai")
    fun provideXaiRetrofit(okHttp: OkHttpClient): Retrofit = Retrofit.Builder()
        .baseUrl("https://api.x.ai/")
        .client(okHttp)
        .addConverterFactory(GsonConverterFactory.create(GsonBuilder().create()))
        .build()

    @Provides @Singleton
    fun provideXaiApi(@Named("xai") retrofit: Retrofit): XaiApi =
        retrofit.create(XaiApi::class.java)

    @Provides @Singleton
    fun provideSarvamChatApi(@Named("sarvam") retrofit: Retrofit): SarvamChatApi =
        retrofit.create(SarvamChatApi::class.java)
}


