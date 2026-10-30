package com.example.samdapp.di

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.samdapp.data.connectivity.PackageAppPermissions
import com.example.samdapp.data.remote.BearerInterceptor
import com.example.samdapp.data.remote.LocalNetworkGateDns
import com.example.samdapp.data.remote.LocalNetworkGateInterceptor
import com.example.samdapp.data.remote.NetworkLoggingInterceptor
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
import com.example.samdapp.domain.connectivity.ACCESS_LOCAL_NETWORK_PERMISSION
import com.example.samdapp.domain.connectivity.AppPermissions
import com.example.samdapp.domain.connectivity.LocalNetworkGate
import com.example.samdapp.domain.kernel.EvaluateKernelSource
import com.example.samdapp.domain.kernel.RemoteKernelSource
import com.google.gson.Gson
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/** Marks the ABHA-only [OkHttpClient]/[Retrofit] pair — see [NetworkModule.provideAbhaOkHttpClient]'s
 *  KDoc for why ABHA traffic cannot share the general-purpose client that
 *  [NetworkModule.provideNetworkLoggingInterceptor] can put in `BODY` logging mode. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AbhaHttpStack

/** Interceptors that exist only in the dev flavor. `src/dev/` provides the dynamic-host
 *  interceptor; `src/staging/` and `src/prod/` provide an empty list, so no dev-only class is
 *  named, constructed, or even present in those builds — the same containment
 *  `MockBoundaryModule` documents for the clinical mock seams. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DevHostInterceptors

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
    fun provideNetworkLoggingInterceptor(): NetworkLoggingInterceptor =
        // Network logging is gated by a local.properties flag to prevent PHI leaks in logcat.
        // Full request/response tracing is disabled by default. Audit logging handles secure,
        // timestamped persistence of necessary clinical data. Even when on, credentials are
        // redacted and auth and read-back bodies are never logged (NetworkLoggingInterceptor).
        NetworkLoggingInterceptor(
            logger = { message -> Log.d(TAG, message) },
            level = if (com.example.samdapp.BuildConfig.ENABLE_NETWORK_LOGGING) {
                HttpLoggingInterceptor.Level.BODY
            } else {
                HttpLoggingInterceptor.Level.NONE
            },
        )

    /** The local-network gate every client in the app is built with (memo 11.1). */
    @Provides
    @Singleton
    fun provideLocalNetworkGate(@ApplicationContext context: Context): LocalNetworkGate =
        LocalNetworkGate(Build.VERSION.SDK_INT) {
            ContextCompat.checkSelfPermission(context, ACCESS_LOCAL_NETWORK_PERMISSION) == PackageManager.PERMISSION_GRANTED
        }

    /** The gate interceptor goes after [DevHostInterceptors], which can rewrite the host, so it
     *  judges the address the call will really use. */
    @Provides
    @Singleton
    fun provideOkHttpClient(
        loggingInterceptor: NetworkLoggingInterceptor,
        bearerInterceptor: BearerInterceptor,
        tokenAuthenticator: TokenAuthenticator,
        @DevHostInterceptors devInterceptors: List<@JvmSuppressWildcards Interceptor>,
        localNetworkGate: LocalNetworkGate,
    ): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .dns(LocalNetworkGateDns(Dns.SYSTEM, localNetworkGate))
            .apply { devInterceptors.forEach(::addInterceptor) }
            .addInterceptor(LocalNetworkGateInterceptor(localNetworkGate))
            .addInterceptor(bearerInterceptor)
            .authenticator(tokenAuthenticator)
            .addInterceptor(loggingInterceptor)
            .build()

    /** ABHA traffic never shares [provideOkHttpClient]: that client's [HttpLoggingInterceptor]
     *  can be put in `Level.BODY` by [BuildConfig.ENABLE_NETWORK_LOGGING], a `local.properties`
     *  flag read into `defaultConfig` — so it can end up on for any flavor or build type,
     *  including a local `ProdRelease` build, not just `dev`. ABHA request/response bodies carry
     *  Aadhaar numbers and OTPs in the clear on the wire to this backend (RSA-OAEP encryption
     *  happens server-side in `backend/abdm-adapter/`, never on Android — see
     *  [com.example.samdapp.domain.abha.AbdmAbhaSource]'s KDoc); logging those bodies to logcat
     *  would defeat that encryption boundary regardless of what the backend does with them. This
     *  client has no [HttpLoggingInterceptor] at all — not gated off, structurally absent — while
     *  keeping the same Bearer/refresh authentication as every other `backend/core` call. */
    @Provides
    @Singleton
    @AbhaHttpStack
    fun provideAbhaOkHttpClient(
        bearerInterceptor: BearerInterceptor,
        tokenAuthenticator: TokenAuthenticator,
        @DevHostInterceptors devInterceptors: List<@JvmSuppressWildcards Interceptor>,
        localNetworkGate: LocalNetworkGate,
    ): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .dns(LocalNetworkGateDns(Dns.SYSTEM, localNetworkGate))
            .apply { devInterceptors.forEach(::addInterceptor) }
            .addInterceptor(LocalNetworkGateInterceptor(localNetworkGate))
            .addInterceptor(bearerInterceptor)
            .authenticator(tokenAuthenticator)
            .build()

    @Provides
    @Singleton
    @AbhaHttpStack
    fun provideAbhaRetrofit(@AbhaHttpStack okHttpClient: OkHttpClient, gson: Gson): Retrofit =
        Retrofit.Builder()
            .baseUrl(com.example.samdapp.BuildConfig.BACKEND_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
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
    fun provideAbhaApiService(@AbhaHttpStack retrofit: Retrofit): AbhaApiService =
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

        /** What login reads to decide whether the memo 5.2 rationale is needed (P2-R2). */
        @Binds
        abstract fun bindAppPermissions(impl: PackageAppPermissions): AppPermissions
    }
}
