package com.example.samdapp.data.remote

import com.example.samdapp.R
import com.example.samdapp.data.remote.api.AbhaApiService
import com.example.samdapp.data.remote.api.SyncPushApiService
import com.example.samdapp.data.remote.dto.SyncPushRequestDto
import com.example.samdapp.domain.abha.AbhaApiResult
import com.example.samdapp.domain.abha.AbhaEnrolResult
import com.example.samdapp.domain.abha.toEnrolResult
import com.example.samdapp.domain.connectivity.LocalNetworkGate
import com.example.samdapp.domain.connectivity.LocalNetworkNotAllowedException
import com.example.samdapp.domain.kernel.KernelApiResult
import com.example.samdapp.domain.kernel.KernelFailure
import com.example.samdapp.domain.kernel.KernelRetryAdvice
import com.example.samdapp.domain.kernel.classifyKernelFailure
import com.example.samdapp.domain.slm.SlmEngineError
import com.example.samdapp.domain.slm.SlmRefusal
import com.example.samdapp.presentation.kernelassessment.kernelFailureCopy
import com.example.samdapp.presentation.report.SlmRetryOffer
import com.example.samdapp.presentation.report.slmRefusalCopy
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/**
 * Memo 11.4: every consumer of the app's OkHttp clients turns [LocalNetworkNotAllowedException]
 * into local-network copy or into its documented true fallback. The sync drain, the refresh path
 * and the Wi-Fi hub have their own rows in `SyncOutboxDrainerTest`, `TokenAuthenticatorTest` and
 * `PiGatewayVitalsSourceTest`.
 */
class LocalNetworkConsumerMappingTest {

    /** A real Retrofit stack whose client is gated shut, aimed at a LAN literal: every call fails
     *  with the typed exception before a socket, exactly as on a denied phone. */
    private fun blockedRetrofit(): Retrofit = Retrofit.Builder()
        .baseUrl("http://10.203.2.52:8080/")
        .client(
            OkHttpClient.Builder()
                .addInterceptor(LocalNetworkGateInterceptor(LocalNetworkGate(sdkInt = 37) { false }))
                .build(),
        )
        .addConverterFactory(GsonConverterFactory.create(SyncGson.create()))
        .build()

    @Test
    fun `kernel classifies the typed exception as its own retryable failure with its own copy`() {
        val failure = classifyKernelFailure(KernelApiResult.Unreachable(LocalNetworkNotAllowedException()))
        assertEquals(KernelFailure.LOCAL_NETWORK_NOT_ALLOWED, failure)
        assertEquals(KernelRetryAdvice.RETRY_NOW, failure.advice)
        assertEquals(R.string.kernel_failure_local_network_title, kernelFailureCopy(failure).titleRes)
        assertEquals(R.string.kernel_failure_local_network_body, kernelFailureCopy(failure).bodyRes)
    }

    @Test
    fun `SLM transport maps the typed exception to its own refusal, never to unreachable`() {
        assertEquals(SlmEngineError.LOCAL_NETWORK_NOT_ALLOWED, transportErrorFor(LocalNetworkNotAllowedException()))
        val copy = slmRefusalCopy(SlmRefusal.LOCAL_NETWORK_NOT_ALLOWED)
        assertEquals(R.string.readback_refusal_local_network_title, copy.titleRes)
        assertEquals(R.string.readback_refusal_local_network_body, copy.bodyRes)
        assertEquals(SlmRetryOffer.NONE, copy.retry)
    }

    @Test
    fun `sync push keeps the typed exception as the failure cause`() {
        val service = RetrofitSyncPushService(blockedRetrofit().create(SyncPushApiService::class.java), SyncGson.create())
        val result = runBlocking { service.push(SyncPushRequestDto(batchId = "b", deviceId = "d", clientTime = java.time.Instant.EPOCH, records = emptyList())) }
        assertTrue("was $result", result is SyncPushResult.Failure && result.cause is LocalNetworkNotAllowedException)
    }

    @Test
    fun `ABHA reaches its documented fallback, retryable, never a protocol violation`() {
        val source = RetrofitAbhaSource(blockedRetrofit().create(AbhaApiService::class.java))
        val api = runBlocking { source.startRegistrationSession() }
        assertTrue("was $api", api is AbhaApiResult.Failure && api.code == null)
        val enrol = api.toEnrolResult()
        assertEquals(
            AbhaEnrolResult.Error(message = "No connection to the SaMD server. Check the network and try again.", retryable = true),
            enrol,
        )
    }
}
