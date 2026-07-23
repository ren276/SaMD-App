package com.example.samdapp.di

import com.example.samdapp.data.remote.GeminiBrandLookupSource
import com.example.samdapp.data.remote.api.GeminiApiService
import com.example.samdapp.domain.kernel.BrandLookupSource
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/** Distinguishes this module's [OkHttpClient]/[Retrofit] from [NetworkModule]'s — without this,
 *  Hilt sees two unqualified bindings for the same type and fails at compile time. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class Gemini

/**
 * Provides the Retrofit stack for the Gemini `generateContent` REST API — a separate host/base URL
 * from [NetworkModule]'s SaMDClassifier kernel stack, so it gets its own [OkHttpClient]/[Retrofit]
 * instance rather than reusing the kernel's.
 *
 * Timeouts (10s connect / 12s read) — brand lookup is a best-effort enrichment
 * ([com.example.samdapp.domain.kernel.BrandLookupSource] never throws), so a slow/unreachable
 * Gemini endpoint should still fail reasonably fast rather than stall the evaluate pipeline. 12s
 * read gives real margin over the ~0.7s typical latency with `thinkingBudget=0` (see
 * [com.example.samdapp.data.remote.GeminiBrandLookupSource]) — the earlier 6s timeout was
 * measured to sit right at the edge of Gemini 2.5 Flash's *thinking-enabled* latency (~5.6s),
 * which is why brand lookups were silently timing out before thinking was disabled.
 */
@Module(includes = [GeminiNetworkModule.Bindings::class])
@InstallIn(SingletonComponent::class)
object GeminiNetworkModule {

    private const val GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/"

    @Gemini
    @Provides
    @Singleton
    fun provideGeminiOkHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()

    @Gemini
    @Provides
    @Singleton
    fun provideGeminiRetrofit(@Gemini okHttpClient: OkHttpClient): Retrofit =
        Retrofit.Builder()
            .baseUrl(GEMINI_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

    @Provides
    @Singleton
    fun provideGeminiApiService(@Gemini retrofit: Retrofit): GeminiApiService =
        retrofit.create(GeminiApiService::class.java)

    /** Separate abstract class to host @Binds methods (Hilt requirement). */
    @Module
    @InstallIn(SingletonComponent::class)
    abstract class Bindings {
        @Binds
        @Singleton
        abstract fun bindBrandLookupSource(impl: GeminiBrandLookupSource): BrandLookupSource
    }
}
