package com.example.samdapp.di

import com.example.samdapp.data.remote.RemoteSlmEngine
import com.example.samdapp.data.remote.api.SlmApiService
import com.example.samdapp.domain.slm.LocalDataOwnerFacility
import com.example.samdapp.domain.slm.SlmEngine
import com.example.samdapp.domain.slm.UnknownLocalDataOwnerFacility
import com.google.gson.Gson
import dagger.BindsOptionalOf
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.Optional
import java.util.concurrent.TimeUnit
import javax.inject.Provider
import javax.inject.Qualifier
import javax.inject.Singleton

/** Marks the SLM readback [OkHttpClient]/[Retrofit] pair. It is a *derivative* of
 *  [NetworkModule.provideOkHttpClient]'s client, not a sibling of it: see
 *  [SlmNetworkModule.provideSlmOkHttpClient]. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SlmHttpStack

/**
 * Marks the dev flavor's override of a readback provision (ruling R-DEV, on-device SLM memo 16).
 * Only the dev `LocalSlmModule` may provide it; staging and prod provide nothing under it, so they
 * get the defaults in [SlmNetworkModule].
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DevReadbackOverride

/**
 * The SLM readback transport, and the binding of [SlmEngine] and [LocalDataOwnerFacility] for
 * every flavor.
 *
 * **Staging and prod: [RemoteSlmEngine] and [UnknownLocalDataOwnerFacility], from this module
 * alone.** No staging or prod source set provides anything under [DevReadbackOverride], so the
 * optionals below are empty there and the defaults apply. The remote engine is the shipping
 * architecture rather than a production-only arrangement.
 *
 * **Dev only (ruling R-DEV): the on-device engine and the session's facility.** The dev
 * `LocalSlmModule` provides both overrides. The choice is made once, when Hilt builds the graph
 * for a flavor, never per request (R3). This shape keeps `src/staging` and `src/prod` free of any
 * readback source, which is what `SlmEngineIsUnreachableFromPresentationTest` rule 3 checks.
 *
 * The dev override is a real model, not a stub. A stub engine is still forbidden in every flavor:
 * a readback is clinical narrative, and a stub one is a fabricated clinical narrative that would
 * read exactly like a real one on a worker's screen, with a `finish_reason` it made up and an
 * identity gate it satisfied by answering with the pin it was compiled against. H-09 and H-13 are
 * both in the hazard register because a plausible fabricated value reachable in a non-dev build is
 * this project's recurring failure mode. The local engine answers from the pinned, digest-verified
 * Gemma artifact through the same seam and the same gates, and only in dev.
 *
 * In staging and prod nothing here is reachable: `SLM_READBACK_ENABLED` is false there.
 */
@Module(includes = [SlmNetworkModule.Bindings::class])
@InstallIn(SingletonComponent::class)
object SlmNetworkModule {

    /** Connect budget, `slm-service-contract.md` §4.3. Same as every other backend call: reaching
     *  the backend is not slower because of what it will do next. */
    private const val CONNECT_TIMEOUT_SECONDS = 10L

    /**
     * Read budget, §4.3, and **55 rather than 60 on purpose**. The contract's own rule is that each
     * layer sits strictly inside the next, so that a timeout is classified at the innermost layer
     * that can still see the cause. Equal read and call bounds break that, and not only in theory:
     * with a 10 s connect allowance, a call that spends any time connecting can have its whole-call
     * bound expire while the read bound still has headroom, so the expiry is reported by the layer
     * that knows least about why.
     */
    private const val READ_TIMEOUT_SECONDS = 55L

    /**
     * The whole-call budget, §4.3, and **the first `callTimeout` anywhere in this app**. F6B-03
     * measured zero across all of `app/src`, which was survivable while every call was a
     * milliseconds-scale request to the backend: `readTimeout` bounds the wait between bytes, and
     * on those calls one stalled read is the only way to hang.
     *
     * This is the first call expected to run for seconds rather than milliseconds, and the first
     * where the gap matters. `readTimeout` is per socket read, so a service dribbling a byte inside
     * every 55 s window keeps the call alive indefinitely, and redirects, connection retries and
     * the body read each get their own fresh budget. `callTimeout` is the one bound over the whole
     * exchange, and it is what makes the worker's wait bounded rather than merely usually short.
     * It is also what makes the backend's `504` / `SAMD-SLM-8011` the expected answer instead of a
     * race: at 60 s the device is the outermost of four nested budgets, behind the backend's 50 s
     * and the service's own 40 s wall clock.
     */
    private const val CALL_TIMEOUT_SECONDS = 60L

    /**
     * The SLM client, **derived from the shared backend client with `newBuilder()`**, not built
     * from scratch.
     *
     * Two reasons, and they are different in kind.
     *
     * The first is correctness. This is a `backend/core` endpoint behind the same bearer
     * credential as every other one, so it needs [com.example.samdapp.data.remote.BearerInterceptor]
     * and [com.example.samdapp.data.remote.TokenAuthenticator], and in the dev flavor it needs the
     * dynamic-host interceptor as well. A fresh `OkHttpClient.Builder()` here would compile, run,
     * and produce `401`s that arrive at the seam as failures of the generation service, which is
     * the class of defect this whole PR exists to keep out of a worker's hands. Deriving means the
     * question "did this client get the auth stack" cannot be answered wrongly, because there is no
     * separate answer to give.
     *
     * The second is resources. F6B-03 found four clients and four connection pools where one would
     * do. `newBuilder()` shares the parent's [okhttp3.Dispatcher], [okhttp3.ConnectionPool] and
     * thread pools, so this adds a configuration, not an infrastructure. A fifth pool on a handset
     * that is also holding ASR weights resident is not free.
     *
     * What is overridden is the timeout budget and nothing else, because that is the one thing
     * about this call that genuinely differs from every other backend call: it waits on a GPU.
     */
    @Provides
    @Singleton
    @SlmHttpStack
    fun provideSlmOkHttpClient(backendClient: OkHttpClient): OkHttpClient =
        backendClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

    @Provides
    @Singleton
    @SlmHttpStack
    fun provideSlmRetrofit(@SlmHttpStack okHttpClient: OkHttpClient, gson: Gson): Retrofit =
        Retrofit.Builder()
            .baseUrl(com.example.samdapp.BuildConfig.BACKEND_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()

    @Provides
    @Singleton
    fun provideSlmApiService(@SlmHttpStack retrofit: Retrofit): SlmApiService =
        retrofit.create(SlmApiService::class.java)

    /** The only binding of [SlmEngine] in the project: the dev override if the flavor has one,
     *  else [RemoteSlmEngine]. [Provider], so dev never constructs the remote engine. */
    @Provides
    @Singleton
    fun provideSlmEngine(
        @DevReadbackOverride devEngine: Optional<SlmEngine>,
        remote: Provider<RemoteSlmEngine>,
    ): SlmEngine = if (devEngine.isPresent) devEngine.get() else remote.get()

    /** The dev override if the flavor has one, else always UNKNOWN, so the seam refuses (Y-21'). */
    @Provides
    fun provideLocalDataOwnerFacility(
        @DevReadbackOverride devOwner: Optional<LocalDataOwnerFacility>,
    ): LocalDataOwnerFacility = devOwner.orElse(UnknownLocalDataOwnerFacility())

    @Module
    @InstallIn(SingletonComponent::class)
    interface Bindings {
        @BindsOptionalOf
        @DevReadbackOverride
        fun devReadbackEngine(): SlmEngine

        @BindsOptionalOf
        @DevReadbackOverride
        fun devLocalDataOwnerFacility(): LocalDataOwnerFacility
    }
}
