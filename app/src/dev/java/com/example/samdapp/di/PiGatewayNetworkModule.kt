package com.example.samdapp.di

import com.example.samdapp.data.vitalssource.PiGatewayApi
import com.google.gson.Gson
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

/** Marks the Pi-gateway-only [OkHttpClient]/[Retrofit] pair, following the `@AbhaHttpStack`
 *  precedent in [NetworkModule]. See [PiGatewayNetworkModule.providePiGatewayOkHttpClient] for why
 *  this traffic cannot share the general-purpose client. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PiGatewayHttpStack

/**
 * Dev-flavour-only network stack for the Raspberry Pi instrument gateway on the LAN. This module
 * and everything it provides live in `src/dev/`, so a staging or prod build cannot compile a path
 * to the gateway; there is no flag to flip, the classes do not exist outside this source set.
 *
 * Base URL is `BuildConfig.PI_GATEWAY_BASE_URL`, a dev-flavour `buildConfigField` overridable from
 * `local.properties`, mirroring what `BACKEND_BASE_URL` already does for physical-device testing.
 */
@Module
@InstallIn(SingletonComponent::class)
object PiGatewayNetworkModule {

    /** This client MUST NOT be [NetworkModule.provideOkHttpClient]. That one installs
     *  `BearerInterceptor` and `TokenAuthenticator`, so reusing it would attach the backend access
     *  token to every request sent to an accessory sitting on the same Wi-Fi as the handset, and a
     *  401 from that accessory would drive a token refresh against the backend. Neither
     *  interceptor is present here at all: structural absence, not a disabled flag, the same
     *  posture as the ABHA client's absent logging interceptor.
     *
     *  Timeouts are deliberately short. The worker is standing at the instrument waiting; a fast,
     *  honest "not reachable" beats a 30s hang in front of a patient. */
    @Provides
    @Singleton
    @PiGatewayHttpStack
    fun providePiGatewayOkHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .build()

    @Provides
    @Singleton
    @PiGatewayHttpStack
    fun providePiGatewayRetrofit(
        @PiGatewayHttpStack okHttpClient: OkHttpClient,
        gson: Gson,
    ): Retrofit =
        Retrofit.Builder()
            .baseUrl(com.example.samdapp.BuildConfig.PI_GATEWAY_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()

    @Provides
    @Singleton
    fun providePiGatewayApi(@PiGatewayHttpStack retrofit: Retrofit): PiGatewayApi =
        retrofit.create(PiGatewayApi::class.java)
}
