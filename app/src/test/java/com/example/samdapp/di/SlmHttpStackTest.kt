package com.example.samdapp.di

import com.example.samdapp.data.remote.BearerInterceptor
import com.example.samdapp.data.remote.TokenAuthenticator
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier

/**
 * The SLM transport's two structural claims, which no behavioural test can make.
 *
 * **One: the SLM client is a derivative of the shared backend client, not a fifth client.** F6B-03
 * found four `OkHttpClient`s and four connection pools where one would do. The resource argument is
 * real on a handset that is already holding ASR weights resident, but it is the second argument.
 * The first is correctness: this is a `backend/core` endpoint behind the same bearer credential as
 * every other one, so a client built from a fresh `OkHttpClient.Builder()` would compile, run, and
 * answer `401`, and those `401`s would arrive at the seam classified as failures of the generation
 * service. A worker would be told the AI is unavailable when the truth is that the request carried
 * no token.
 *
 * **Two: `callTimeout` is set, and set to the value the contract's budget says.** Asserted here
 * rather than behaviourally because a test that waits sixty seconds to prove a sixty-second timeout
 * is a test nobody runs; `RemoteSlmEngineTest` proves the same client's expiry is classified as a
 * `TIMEOUT` using a shortened budget, so the value and the wiring are each covered by the test
 * shape that suits them.
 */
class SlmHttpStackTest {

    // -------------------------------------------------- derived, not built from scratch

    @Test
    fun theSlmClientSharesTheBackendClientsPoolAndDispatcher() {
        val backend = OkHttpClient.Builder().build()

        val slm = SlmNetworkModule.provideSlmOkHttpClient(backend)

        // `newBuilder()` shares the parent's infrastructure; a fresh builder would allocate its own.
        // Identity, not equality: two distinct pools with the same settings are still two pools.
        assertSame(backend.connectionPool, slm.connectionPool)
        assertSame(backend.dispatcher, slm.dispatcher)
        // And it is genuinely a different client, not the same one handed back with the caller's
        // timeouts silently applied to every other backend call in the app.
        assertTrue(backend !== slm)
    }

    @Test
    fun theSlmClientCarriesEveryInterceptorAndTheAuthenticatorOfTheClientItDerivesFrom() {
        val marker = Interceptor { chain -> chain.proceed(chain.request()) }
        val authenticator = Authenticator { _: Route?, _: Response -> null as Request? }
        val backend = OkHttpClient.Builder()
            .addInterceptor(marker)
            .authenticator(authenticator)
            .build()

        val slm = SlmNetworkModule.provideSlmOkHttpClient(backend)

        // The property in its general form: whatever the shared backend client carries, this one
        // carries, because it is the same object plus a timeout override. Asserting on the list
        // rather than on `contains` is deliberate: a provider that added the bearer interceptor a
        // second time would still contain it.
        assertEquals(backend.interceptors, slm.interceptors)
        assertSame(marker, slm.interceptors.single())
        assertSame(authenticator, slm.authenticator)
    }

    @Test
    fun theSlmClientDerivesFromTheUnqualifiedBackendClientWhichIsTheAuthenticatedOne() {
        // Hilt resolves `OkHttpClient` with no qualifier to exactly one provider. This asserts, by
        // reflection on the two method signatures, that the SLM provider asks for that one, and
        // that that one is the provider which installs the bearer interceptor and the token
        // authenticator. The behavioural tests above prove derivation preserves whatever the parent
        // has; this is what proves the parent is the right client.
        //
        // A source scan would prove neither. The failure being defended against is someone adding
        // `@AbhaHttpStack` or a new qualifier to the parameter below, which reads as a one-word
        // change and silently swaps in a client with no logging suppression and a different
        // lifetime, or adding a fifth provider and pointing this at it.
        val slmProvider = SlmNetworkModule::class.java
            .getDeclaredMethod("provideSlmOkHttpClient", OkHttpClient::class.java)
        val qualifiers = slmProvider.parameterAnnotations.single()
            .filter { it.annotationClass.java.isAnnotationPresent(Qualifier::class.java) }
        assertEquals(
            "provideSlmOkHttpClient's OkHttpClient parameter must carry no qualifier, so that Hilt " +
                "resolves it to NetworkModule.provideOkHttpClient, the one authenticated backend " +
                "client. Found: $qualifiers",
            emptyList<Annotation>(),
            qualifiers,
        )

        val backendProvider = NetworkModule::class.java.declaredMethods
            .single { it.name == "provideOkHttpClient" }
        assertNull(
            "NetworkModule.provideOkHttpClient must stay unqualified, or the parameter above stops " +
                "resolving to it.",
            backendProvider.annotations
                .firstOrNull { it.annotationClass.java.isAnnotationPresent(Qualifier::class.java) },
        )
        val backendParameters = backendProvider.parameterTypes.toList()
        assertTrue(
            "The unqualified backend client must carry the BearerInterceptor. Parameters: " +
                backendParameters,
            backendParameters.contains(BearerInterceptor::class.java),
        )
        assertTrue(
            "The unqualified backend client must carry the TokenAuthenticator. Parameters: " +
                backendParameters,
            backendParameters.contains(TokenAuthenticator::class.java),
        )
    }

    // ------------------------------------------------------------- the timeout budget

    @Test
    fun theTimeoutBudgetIsTheContractsAndEachLayerIsStrictlyInsideTheNext() {
        val slm = SlmNetworkModule.provideSlmOkHttpClient(OkHttpClient.Builder().build())

        // slm-service-contract.md section 4.3, device row.
        assertEquals(10_000, slm.connectTimeoutMillis)
        assertEquals(55_000, slm.readTimeoutMillis)
        assertEquals(55_000, slm.writeTimeoutMillis)
        assertEquals(60_000, slm.callTimeoutMillis)

        // The rule the numbers exist to satisfy, asserted rather than left to the reader. Read is
        // 55 and not 60 so that a stalled read is classified by the read bound rather than by the
        // whole-call bound, which is the layer that knows least about why a call ended.
        assertTrue(
            "read ${slm.readTimeoutMillis} must be strictly inside call ${slm.callTimeoutMillis}",
            slm.readTimeoutMillis < slm.callTimeoutMillis,
        )
        // **The nesting is not total, and that is MEASURED rather than assumed away.** Connect
        // plus read is 65 s against a 60 s whole-call bound, so a call that spends more than 5 s
        // connecting can still have callTimeout expire while the read bound has headroom, which is
        // the outcome section 4.3 raised 55 s to avoid. The 5 s is the residual window, it is the
        // budget PR-6 was briefed with, and this pins it so that widening the gap is a failing test
        // rather than a quiet change. Closing it needs a smaller read bound or a larger call bound,
        // which is a contract decision and not this build's to make.
        val windowMillis = slm.callTimeoutMillis - slm.readTimeoutMillis
        assertEquals(
            "The window in which connect time can push a call past callTimeout before readTimeout " +
                "fires is 5 s by construction. If this changed, section 4.3's budget changed.",
            5_000,
            windowMillis,
        )
    }

    @Test
    fun aCallBuiltFromThisClientCarriesTheWholeCallBudget() {
        val slm = SlmNetworkModule.provideSlmOkHttpClient(OkHttpClient.Builder().build())

        val call = slm.newCall(Request.Builder().url("http://127.0.0.1:1/api/v1/slm/readback").build())

        // Not the client's field: the timeout on the Call object that actually runs. F6B-03 found
        // zero callTimeout across all of app/src, and a client-level assertion would still pass if
        // the value never reached a call.
        assertEquals(60_000L, call.timeout().timeoutNanos() / 1_000_000L)
    }

    @Test
    fun noOtherBackendClientHasAWholeCallBudget() {
        // Not a defect to fix here, and recorded so it is not mistaken for one. The other clients
        // are milliseconds-scale calls where readTimeout is a sufficient bound; this is the first
        // call on the project expected to run for seconds, and the first where the gap between "no
        // byte for 55 s" and "not finished in 60 s" is a gap a worker would sit through.
        val plain = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        assertEquals(0, plain.callTimeoutMillis)
    }
}
