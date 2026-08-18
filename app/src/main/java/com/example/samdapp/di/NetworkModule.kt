package com.example.samdapp.di

import android.util.Log
import com.example.samdapp.data.remote.BearerInterceptor
import com.example.samdapp.data.remote.RetrofitAbhaSource
import com.example.samdapp.data.remote.RetrofitEvaluateSource
import com.example.samdapp.data.remote.RetrofitKernelSource
import com.example.samdapp.data.remote.RetrofitSyncPushService
import com.example.samdapp.data.remote.SyncPushService
import com.example.samdapp.data.remote.TokenAuthenticator
import com.example.samdapp.data.remote.api.AbhaApiService
import com.example.samdapp.data.remote.api.AuthApiService
import com.example.samdapp.data.remote.api.ClinicalApiService
import com.example.samdapp.data.remote.api.KernelApiService
import com.example.samdapp.data.remote.api.SyncPushApiService
import com.example.samdapp.domain.abha.AbdmAbhaSource
import com.example.samdapp.domain.kernel.EvaluateKernelSource
import com.example.samdapp.domain.kernel.RemoteKernelSource
import com.google.gson.Gson
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * Provides the Retrofit + OkHttp stack for `backend/core`. One `OkHttpClient`/`Retrofit` pair
 * serves auth, kernel-proxy, and clinical-evaluation calls. As of Phase 6a the kernel is no
 * longer reachable directly (`KERNEL_BASE_URL` is deleted, see api-contract.md §5.1); every call
 * goes through the backend, authenticated.
 *
 * Base URL: `BuildConfig.BACKEND_BASE_URL`, dev flavor loaded from local.properties for
 * physical-device testing over Wi-Fi. Requires `android:usesCleartextTraffic="true"` in the
 * manifest for the dev flavor (plain HTTP on the LAN; staging/prod are HTTPS).
 *
 * Timeouts are deliberately conservative (connect 10s, read/write 30s) to give the ML
 * inference time to complete while still surfacing failures quickly enough for the graceful
 * fallback in [GenerateKernelReportUseCase] to kick in.
 *
 * [NetworkModule.Bindings] handles @Binds (abstract methods) since Hilt requires them to live
 * in an abstract class/interface, not a companion object.
 */
@Module(
    includes = [NetworkModule.Bindings::class],
)
@InstallIn(SingletonComponent::class)
object NetworkModule {

    private const val TAG = "BackendNetwork"

    @Provides
    @Singleton
    fun provideHttpLoggingInterceptor(): HttpLoggingInterceptor =
        HttpLoggingInterceptor { message -> Log.d(TAG, message) }.apply {
            // Network logging is gated by a local.properties flag to prevent PHI leaks in logcat.
            // Full request/response tracing is disabled by default. Audit logging handles
            // secure, timestamped persistence of necessary clinical data.
            level = if (com.example.samdapp.BuildConfig.ENABLE_NETWORK_LOGGING) {
                HttpLoggingInterceptor.Level.BODY
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }

    @Provides
    @Singleton
    fun provideOkHttpClient(
        loggingInterceptor: HttpLoggingInterceptor,
        bearerInterceptor: BearerInterceptor,
        tokenAuthenticator: TokenAuthenticator,
    ): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(bearerInterceptor)
            .authenticator(tokenAuthenticator)
            .addInterceptor(loggingInterceptor)
            .build()

    /** `PatientEntity`/etc.'s Room columns go through [com.example.samdapp.data.local.Converters]
     *  instead, a separate converter for a separate boundary — registering these two adapters
     *  here is additive, not a risk to any existing DTO's serialization. Phase 6b's sync payload
     *  DTOs (SyncPayloadDto.kt) were the first users; AbhaDto.kt (Phase 6c) is the second. */
    @Provides
    @Singleton
    fun provideGson(): Gson = com.example.samdapp.data.remote.SyncGson.create()

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient, gson: Gson): Retrofit =
        Retrofit.Builder()
            .baseUrl(com.example.samdapp.BuildConfig.BACKEND_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()

    @Provides
    @Singleton
    fun provideAuthApiService(retrofit: Retrofit): AuthApiService =
        retrofit.create(AuthApiService::class.java)

    @Provides
    @Singleton
    fun provideKernelApiService(retrofit: Retrofit): KernelApiService =
        retrofit.create(KernelApiService::class.java)

    @Provides
    @Singleton
    fun provideClinicalApiService(retrofit: Retrofit): ClinicalApiService =
        retrofit.create(ClinicalApiService::class.java)

    @Provides
    @Singleton
    fun provideSyncPushApiService(retrofit: Retrofit): SyncPushApiService =
        retrofit.create(SyncPushApiService::class.java)

    @Provides
    @Singleton
    fun provideAbhaApiService(retrofit: Retrofit): AbhaApiService =
        retrofit.create(AbhaApiService::class.java)

    /** Separate abstract class to host @Binds methods (Hilt requirement). */
    @Module
    @InstallIn(SingletonComponent::class)
    abstract class Bindings {
        @Binds
        @Singleton
        abstract fun bindRemoteKernelSource(impl: RetrofitKernelSource): RemoteKernelSource

        @Binds
        @Singleton
        abstract fun bindEvaluateKernelSource(impl: RetrofitEvaluateSource): EvaluateKernelSource

        @Binds
        @Singleton
        abstract fun bindSyncPushService(impl: RetrofitSyncPushService): SyncPushService

        @Binds
        @Singleton
        abstract fun bindAbdmAbhaSource(impl: RetrofitAbhaSource): AbdmAbhaSource
    }
}
