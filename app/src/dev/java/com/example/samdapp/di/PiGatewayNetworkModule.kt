package com.example.samdapp.di

import android.content.Context
import com.example.samdapp.BuildConfig
import com.example.samdapp.data.vitalssource.HubAssignment
import com.example.samdapp.data.vitalssource.HubIdInterceptor
import com.example.samdapp.data.vitalssource.HubIdMismatchException
import com.example.samdapp.data.vitalssource.NsdGatewayDns
import com.example.samdapp.data.vitalssource.PiGatewayApi
import com.example.samdapp.data.vitalssource.PiGatewayVitalsSource
import com.google.gson.Gson
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.Dns
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
 * Base URL is `BuildConfig.PI_GATEWAY_BASE_URL`, a dev-flavour `buildConfigField` that Gradle derives
 * from the Wi-Fi entry of `PI_HUB_ASSIGNMENT` and `local.properties` can override, mirroring what
 * `BACKEND_BASE_URL` already does for physical-device testing.
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
     *  honest "not reachable" beats a 30s hang in front of a patient.
     *
     *  [NsdGatewayDns] is installed here and nowhere else. Android's system resolver has no mDNS
     *  path, so the `.local` host in `PI_GATEWAY_BASE_URL` cannot be resolved by `Dns.SYSTEM`;
     *  every gateway call failed with `UnknownHostException` before this. It resolves names only
     *  and never touches a request, so the no-replay rule still holds. */
    @Provides
    @Singleton
    fun provideHubAssignment(): HubAssignment = HubAssignment.parseOrNone(BuildConfig.PI_HUB_ASSIGNMENT)

    @Provides
    @Singleton
    @PiGatewayHttpStack
    fun providePiGatewayOkHttpClient(
        @ApplicationContext context: Context,
        assignment: HubAssignment,
    ): OkHttpClient = piGatewayOkHttpClient(
        expectedHubId = assignment.wifiHubId.orEmpty(),
        dns = NsdGatewayDns(discover = NsdGatewayDns.nsdDiscovery(context)),
    )

    @Provides
    @Singleton
    fun providePiGatewayVitalsSource(api: PiGatewayApi, assignment: HubAssignment): PiGatewayVitalsSource =
        PiGatewayVitalsSource(api, expectedHubId = assignment.wifiHubId.orEmpty())

    /**
     * The client itself, with the [Dns] left open. Split out so a host unit test can assert this
     * exact configuration — the short timeouts and, more to the point, the interceptors that are
     * absent — without an Android [Context] to hand. Production always gets [NsdGatewayDns]; the
     * default is only ever taken by a test pointing at a MockWebServer on loopback.
     *
     * [HubIdInterceptor] is the one interceptor installed: every response must name
     * [expectedHubId] in `X-SaMDPi-Hub-Id`, errors included, or the call fails with
     * [HubIdMismatchException] before a body is read.
     */
    fun piGatewayOkHttpClient(expectedHubId: String, dns: Dns = Dns.SYSTEM): OkHttpClient =
        OkHttpClient.Builder()
            .dns(dns)
            .addInterceptor(HubIdInterceptor(expectedHubId))
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
            .baseUrl(BuildConfig.PI_GATEWAY_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()

    @Provides
    @Singleton
    fun providePiGatewayApi(@PiGatewayHttpStack retrofit: Retrofit): PiGatewayApi =
        retrofit.create(PiGatewayApi::class.java)
}
