package com.example.samdapp.data.remote

import com.example.samdapp.data.remote.api.SlmApiService
import com.example.samdapp.domain.slm.PROMPT_TEMPLATE_VERSION
import com.example.samdapp.domain.slm.SANITIZER_TARGET_MODEL_ID
import com.example.samdapp.domain.slm.SlmEngineError
import com.example.samdapp.domain.slm.SlmEngineException
import com.example.samdapp.domain.slm.SlmFinishReason
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * PR-6, the device-side engine binding: the transport behind [com.example.samdapp.domain.slm.SlmEngine]
 * and, mostly, its failure classification.
 *
 * **Most of this file is about failures, and that is the right proportion.** The happy path is one
 * call and one chunk. Everything else in this class exists because a wrong failure classification on
 * this path reaches a health worker as either a wrong instruction ("you are offline, keep tapping",
 * when the truth is that the connection may be intercepted) or as clinical text that should have
 * been refused (a generation cut off at the token ceiling, which every other gate in the system
 * passes by construction).
 *
 * Classification is tested twice over, deliberately. The two `internal` functions are pure, total
 * and Android-free, so they are driven directly with a table, which is the same construction
 * `classifyKernelFailure` and `KernelFailureClassificationTest` use. Wiring them is then proved
 * end to end over a real [MockWebServer] and a real OkHttp stack, because a table that is correct
 * about a function nothing calls is the characteristic bug this project keeps finding.
 */
class RemoteSlmEngineTest {

    private lateinit var server: MockWebServer
    private lateinit var engine: RemoteSlmEngine

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        engine = RemoteSlmEngine(apiAgainst(server, defaultClient()))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ---------------------------------------------------------------- the call itself

    @Test
    fun aSuccessfulGenerationEmitsExactlyOneChunkCarryingTheWholeText() = runBlocking {
        server.enqueue(ok(envelope(text = "The doctor approved amoxicillin.")))

        val chunks = engine.generate(CASE, PROMPT, 512).toList()

        // One chunk, not "at least one". The interface is a Flow because the sanitizer runs on the
        // stream and because a streaming transport should not be an interface change, but this
        // binding is non-streaming: it holds the envelope and emits its whole text once.
        assertEquals(listOf("The doctor approved amoxicillin."), chunks)
    }

    @Test
    fun theFlowIsColdAndNothingIsSentUntilItIsCollected() = runBlocking {
        server.enqueue(ok(envelope()))

        engine.generate(CASE, PROMPT, 512)

        // "Nothing generates speculatively": building the flow must not cost a generation on a
        // single-worker service, and must not cost an slm_call_log row for a readback nobody asked
        // for. requestCount is the evidence, not the absence of an exception.
        assertEquals(0, server.requestCount)
    }

    @Test
    fun theRequestCarriesThisBuildsPinAndTemplateAndOmitsTheDeterminismFields() = runBlocking {
        server.enqueue(ok(envelope()))

        engine.generate(CASE, PROMPT, 512).toList()

        val body = bodyOf(server.takeRequest())
        assertEquals(CASE, body["case_token"].asString)
        assertEquals(PROMPT, body["prompt"].asString)
        // The pin the sanitizer and the identity gate are calibrated against, stated outward so a
        // service holding something else answers 409 rather than returning text refused a gate
        // later. Compared against the constant, not against a copy of its value.
        assertEquals(SANITIZER_TARGET_MODEL_ID, body["model_id"].asString)
        assertEquals(PROMPT_TEMPLATE_VERSION, body["prompt_template_version"].asString)
        assertEquals(512, body["max_tokens"].asInt)
        assertEquals(SLM_REQUEST_SEED, body["seed"].asInt)

        // The three the backend supplies. Sending any of them would be rejected by StrictModel with
        // a 422, and more to the point they are the determinism guarantee: a guarantee the caller
        // can vary is not one. There is no history or messages field either, which is how the
        // single-turn rule is enforced at every layer of this path.
        listOf("temperature", "do_sample", "stop", "history", "messages").forEach { field ->
            assertFalse("Request must not carry '$field'.", body.has(field))
        }
    }

    @Test
    fun theSeedIsTheSameOnEveryCall() = runBlocking {
        repeat(2) { server.enqueue(ok(envelope())) }

        engine.generate(CASE, PROMPT, 512).toList()
        engine.generate(CASE, PROMPT, 512).toList()

        // Greedy decoding makes the seed inert, so this is not about reproducing output. The
        // envelope echoes the decode parameters actually used, so a fixed seed is a claim the
        // response can be checked against; one drawn per call would make that check meaningless.
        assertEquals(
            bodyOf(server.takeRequest())["seed"].asInt,
            bodyOf(server.takeRequest())["seed"].asInt,
        )
    }

    // ------------------------------------------------- identity: half an identity is none

    @Test
    fun aCompleteIdentityIsReportedVerbatim() = runBlocking {
        server.enqueue(ok(envelope(modelId = "some/other-model")))

        engine.generate(CASE, PROMPT, 512).toList()

        // What the envelope SAID, never this build's own expectation. The whole purpose of the
        // seam's comparison is to catch a server pointed at a different artifact, and a binding
        // that answered with its own pin would report agreement with itself.
        assertEquals("some/other-model", engine.servedModelId())
        assertNotEquals(SANITIZER_TARGET_MODEL_ID, engine.servedModelId())
    }

    @Test
    fun anAbsentModelIdIsNoIdentity() = runBlocking {
        server.enqueue(ok(envelope(modelId = null)))
        engine.generate(CASE, PROMPT, 512).toList()
        assertNull(engine.servedModelId())
    }

    @Test
    fun aBlankModelIdIsNoIdentity() = runBlocking {
        server.enqueue(ok(envelope(modelId = "   ")))
        engine.generate(CASE, PROMPT, 512).toList()
        assertNull(engine.servedModelId())
    }

    @Test
    fun anAbsentDigestIsNoIdentityEvenWhenTheModelIdIsRight() = runBlocking {
        // The likeliest early-integration envelope: the id implemented, the digest not yet. The
        // seam's gate only compares the id, so without this rule that envelope passes the identity
        // check while nothing has confirmed which weights are resident.
        server.enqueue(ok(envelope(modelId = SANITIZER_TARGET_MODEL_ID, digest = null)))
        engine.generate(CASE, PROMPT, 512).toList()
        assertNull(engine.servedModelId())
    }

    @Test
    fun aBlankDigestIsNoIdentityEvenWhenTheModelIdIsRight() = runBlocking {
        server.enqueue(ok(envelope(modelId = SANITIZER_TARGET_MODEL_ID, digest = "")))
        engine.generate(CASE, PROMPT, 512).toList()
        assertNull(engine.servedModelId())
    }

    // ------------------------------------------------------------ completeness

    @Test
    fun finishReasonIsReadFromTheEnvelopeAndNeverSynthesised() = runBlocking {
        mapOf(
            "stop" to SlmFinishReason.STOP,
            "length" to SlmFinishReason.LENGTH,
            "stop_sequence" to SlmFinishReason.STOP_SEQUENCE,
            "error" to SlmFinishReason.ERROR,
        ).forEach { (raw, expected) ->
            server.enqueue(ok(envelope(finishReason = raw)))
            engine.generate(CASE, PROMPT, 512).toList()
            assertEquals("finish_reason '$raw'", expected, engine.finishReason())
        }
    }

    @Test
    fun aTruncatedGenerationArrivesOverA200AndSaysSo() = runBlocking {
        server.enqueue(ok(envelope(finishReason = "length")))

        val chunks = engine.generate(CASE, PROMPT, 512).toList()

        // The binding does not refuse it; the seam does. This is the asymmetry that makes the whole
        // design work: the text is relayed with its envelope intact so that the one layer built to
        // act on finish_reason can, and converting it to a failure here would take the field away
        // from that layer. LENGTH is not in COMPLETE_FINISH_REASONS, so the seam refuses.
        assertEquals(1, chunks.size)
        assertEquals(SlmFinishReason.LENGTH, engine.finishReason())
    }

    @Test
    fun anAbsentFinishReasonIsNoFinishReason() = runBlocking {
        server.enqueue(ok(envelope(finishReason = null)))
        engine.generate(CASE, PROMPT, 512).toList()
        // Not synthesised from the 200. "The service did not say whether the answer is complete"
        // and "the answer is not complete" are the same thing from a worker's side, and the sample
        // serving app hardcodes "stop", so absent and untrustworthy are the same state from here.
        assertNull(engine.finishReason())
    }

    @Test
    fun anUnrecognisedFinishReasonIsNoFinishReason() = runBlocking {
        server.enqueue(ok(envelope(finishReason = "completed")))
        engine.generate(CASE, PROMPT, 512).toList()
        // A service that invents a fifth value has drifted from the contract. Guessing that its new
        // word probably means "complete" is how a truncated readback reaches a worker.
        assertNull(engine.finishReason())
    }

    // --------------------------------------------------------- malformed envelopes

    @Test
    fun anEnvelopeWithNoTextFieldIsMalformed() {
        server.enqueue(ok("""{"success":true,"data":{"finish_reason":"stop"},"meta":null}"""))
        assertEquals(SlmEngineError.MALFORMED_RESPONSE, errorFrom())
    }

    @Test
    fun anEmptyTextFieldIsLegitimateAndNotMalformed() = runBlocking {
        server.enqueue(ok(envelope(text = "")))
        assertEquals(listOf(""), engine.generate(CASE, PROMPT, 512).toList())
    }

    @Test
    fun aBodyThatIsNotTheDeclaredShapeIsMalformedNotUnreachable() {
        server.enqueue(ok("""{"success":true,"data":"a string, not an object","meta":null}"""))
        // A server that answered unintelligibly is not a server that was absent.
        assertEquals(SlmEngineError.MALFORMED_RESPONSE, errorFrom())
    }

    @Test
    fun anEmptyBodyOnA200IsMalformed() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(""))
        assertEquals(SlmEngineError.MALFORMED_RESPONSE, errorFrom())
    }

    // ------------------------------------------- HTTP classification, over the real stack

    @Test
    fun everyFailureRowOfApiContractSection11Point3MapsToItsError() {
        // The table is api-contract.md section 11.3 in full, read row by row. Driven through the
        // real OkHttp and Retrofit stack rather than through httpErrorFor alone, so that a build
        // which classifies correctly and reads the code from the wrong place still fails.
        FAILURE_ROWS.forEach { (row, expected) ->
            val (status, code) = row
            server.enqueue(problem(status, code))
            assertEquals("HTTP $status / $code", expected, errorFrom())
        }
    }

    @Test
    fun theBackendBeingUnableToReachTheServiceIsNotReportedAsTheDeviceBeingOffline() {
        server.enqueue(problem(502, "SAMD-SLM-8010"))

        val error = errorFrom()

        // SAMD-SLM-8010 says the BACKEND could not reach the SERVICE. The device demonstrably
        // reached the backend, because the backend is what answered. UNREACHABLE on this device
        // means "you have no signal", which is a wrong answer to a worker holding a connected
        // phone and invites exactly the wrong retry.
        assertEquals(SlmEngineError.UNAVAILABLE, error)
        assertNotEquals(SlmEngineError.UNREACHABLE, error)
    }

    @Test
    fun aFailureResponseNeverLeaksTheServersDetailIntoTheException() {
        server.enqueue(
            MockResponse().setResponseCode(422).setBody(
                """{"code":"SAMD-SLM-8012","detail":"rejected prompt: Anita Kumari, 9998887776"}""",
            ),
        )

        val message = assertThrows(SlmEngineException::class.java) {
            runBlocking { engine.generate(CASE, PROMPT, 512).toList() }
        }.message.orEmpty()

        // A message relayed from a remote service is untrusted text and may carry anything the
        // service put in it, including the prompt a verbose service echoed back. The code and the
        // status are contract; `detail` is not, and nothing on the refusal path reads it anyway.
        assertTrue("Message must name the code: $message", message.contains("SAMD-SLM-8012"))
        assertFalse("Message must not carry the server's detail: $message", message.contains("Anita"))
        assertFalse(message.contains("9998887776"))
    }

    // ------------------------------------------------- transport classification, pure

    @Test
    fun anSslFailureIsNotClassifiedAsOffline() {
        // The S-4 trap, pinned. SSLException extends IOException, so a classification that reaches
        // a catch-all before this branch reports a TLS failure as "you are offline, try again
        // later" and a worker keeps pushing a physician's free-text diagnosis and a full
        // prescription line set at a connection that may be reading them.
        listOf(
            SSLException("tls"),
            SSLHandshakeException("handshake"),
            SSLPeerUnverifiedException("peer"),
        ).forEach { exception ->
            assertEquals(
                exception.javaClass.simpleName,
                SlmEngineError.SECURE_CONNECTION_FAILED,
                transportErrorFor(exception),
            )
            assertNotEquals(
                "An SSLException must never be UNREACHABLE.",
                SlmEngineError.UNREACHABLE,
                transportErrorFor(exception),
            )
        }
        // And it is an IOException, which is the whole reason the ordering matters.
        assertTrue(SSLException("tls") is IOException)
    }

    @Test
    fun everyTransportExceptionMapsToOneError() {
        assertEquals(SlmEngineError.TIMEOUT, transportErrorFor(SocketTimeoutException("read")))
        // OkHttp raises a bare InterruptedIOException, not a SocketTimeoutException, when
        // callTimeout expires. Without its own branch the one bound covering the whole exchange
        // would be reported as "no route".
        assertEquals(SlmEngineError.TIMEOUT, transportErrorFor(InterruptedIOException("timeout")))
        assertEquals(SlmEngineError.UNREACHABLE, transportErrorFor(UnknownHostException("dns")))
        assertEquals(SlmEngineError.UNREACHABLE, transportErrorFor(ConnectException("refused")))
        // Not UNREACHABLE. A body that started arriving and ended early is a server that answered
        // unreadably, not a server that was absent, and the empty-200 test below is the end-to-end
        // form of the same claim.
        assertEquals(SlmEngineError.MALFORMED_RESPONSE, transportErrorFor(EOFException("closed")))
        assertEquals(SlmEngineError.UNREACHABLE, transportErrorFor(IOException("something else")))
    }

    @Test
    fun aSocketTimeoutIsATimeoutAndNotSimplyAnInterruptedIo() {
        // Ordering guard. SocketTimeoutException extends InterruptedIOException, and both map to
        // TIMEOUT today, so this cannot fail on the value. It fails if someone gives
        // InterruptedIOException a different meaning without noticing which one is the subclass.
        assertTrue(SocketTimeoutException("x") is InterruptedIOException)
        assertEquals(
            transportErrorFor(InterruptedIOException("timeout")),
            transportErrorFor(SocketTimeoutException("x")),
        )
    }

    @Test
    fun theDeviceIsUnreachableWhenNothingIsListening() {
        // End to end, not through the pure function: a server that has been shut down produces a
        // real ConnectException through the real stack.
        val port = server.port
        server.shutdown()
        val dead = RemoteSlmEngine(
            apiAgainst("http://127.0.0.1:$port/", defaultClient()),
        )
        val error = assertThrows(SlmEngineException::class.java) {
            runBlocking { dead.generate(CASE, PROMPT, 512).toList() }
        }.error
        assertEquals(SlmEngineError.UNREACHABLE, error)
        // Restart so @After's shutdown is a no-op on an already-stopped server.
        server = MockWebServer().also { it.start() }
    }

    @Test
    fun theCallTimeoutBoundsTheWholeExchangeAndIsClassifiedAsATimeout() {
        // MEASURED with a shortened budget, and the budget is the only thing shortened: the client
        // is built by the production provider and then re-derived, so the branch under test is the
        // production one. The real 60 s value is asserted structurally in SlmHttpStackTest, because
        // a test that waits 60 s to prove a timeout is a test nobody runs.
        val client = com.example.samdapp.di.SlmNetworkModule
            .provideSlmOkHttpClient(OkHttpClient.Builder().build())
            .newBuilder()
            .callTimeout(300, TimeUnit.MILLISECONDS)
            .build()
        val slow = RemoteSlmEngine(apiAgainst(server, client))
        server.enqueue(ok(envelope()).setBodyDelay(5, TimeUnit.SECONDS))

        val error = assertThrows(SlmEngineException::class.java) {
            runBlocking { slow.generate(CASE, PROMPT, 512).toList() }
        }.error

        // Not UNREACHABLE. The bytes reached the backend and the backend began answering; what
        // expired is this build's own whole-call budget, which is the innermost layer that can
        // still see the cause.
        assertEquals(SlmEngineError.TIMEOUT, error)
        assertNotEquals(SlmEngineError.UNREACHABLE, error)
    }

    // --------------------------------------------------------------- the mutex

    @Test
    fun aSecondGenerationWhileOneIsInFlightIsRefusedAndNeverReachesTheNetwork() = runBlocking {
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                arrived.countDown()
                release.await(10, TimeUnit.SECONDS)
                return ok(envelope())
            }
        }

        val first = async(Dispatchers.IO) { engine.generate(CASE, PROMPT, 512).toList() }
        assertTrue("First generation never reached the server.", arrived.await(10, TimeUnit.SECONDS))

        val error = assertThrows(SlmEngineException::class.java) {
            runBlocking { engine.generate(CASE, PROMPT, 512).toList() }
        }.error

        // Refused, not queued. A mutex that queued would turn a second tap into a second generation
        // running a minute later: the worker sees nothing happen, taps again, and pays for two
        // generations on a single-worker service while the second answer arrives for a screen
        // nobody is looking at. UNAVAILABLE already means "wait, then retry", which is the action.
        assertEquals(SlmEngineError.UNAVAILABLE, error)
        // The evidence that matters. A refusal issued after the call went out is not a guard.
        assertEquals(1, server.requestCount)

        release.countDown()
        assertEquals(1, first.await().size)
    }

    @Test
    fun theMutexIsReleasedSoSequentialGenerationsBothRun() = runBlocking {
        repeat(2) { server.enqueue(ok(envelope())) }

        engine.generate(CASE, PROMPT, 512).toList()
        engine.generate(CASE, PROMPT, 512).toList()

        // tryLock without a matching unlock would deadlock the feature after exactly one readback
        // per process, which is a failure a single happy-path test would never see.
        assertEquals(2, server.requestCount)
    }

    @Test
    fun aFailedGenerationClearsThePreviousGenerationsEnvelope() = runBlocking {
        server.enqueue(ok(envelope(modelId = SANITIZER_TARGET_MODEL_ID, finishReason = "stop")))
        engine.generate(CASE, PROMPT, 512).toList()
        assertEquals(SANITIZER_TARGET_MODEL_ID, engine.servedModelId())

        server.enqueue(problem(502, "SAMD-SLM-8006"))
        assertThrows(SlmEngineException::class.java) {
            runBlocking { engine.generate(CASE, PROMPT, 512).toList() }
        }

        // The state is cleared BEFORE the call, not after it, and this is what that buys. Without
        // it the seam would check the failed generation against the previous answer's envelope, and
        // it is also what makes the residual race between the flow completing and the seam's reads
        // fail closed: a caller that loses it reads null, and null refuses on both gates.
        assertNull(engine.servedModelId())
        assertNull(engine.finishReason())
    }

    // --------------------------------------------------------------- totality

    @Test
    fun everyEngineErrorValueIsReachable() {
        val reached = FAILURE_ROWS.values.toSet() +
            setOf(
                transportErrorFor(SSLException("tls")),
                transportErrorFor(SocketTimeoutException("t")),
                transportErrorFor(UnknownHostException("dns")),
            ) +
            SlmEngineError.MALFORMED_RESPONSE // proved by the malformed-envelope tests above

        // No value of the vocabulary is decoration, and none is unreachable. A value nothing
        // produces is a row in a taxonomy that an operator will never see and will eventually be
        // reused for something else.
        assertEquals(SlmEngineError.entries.toSet(), reached)
    }

    @Test
    fun anUnknownCodeFallsBackToTheStatusClassAndNeverToOneBucket() {
        // A backend newer than this build sends a code this build does not know. The status still
        // carries the class of the failure, and collapsing all of them into one value is the
        // F6B-02 defect: a specific, recoverable, actionable error turned into a generic outage.
        mapOf(
            401 to SlmEngineError.PAYLOAD_REJECTED,
            403 to SlmEngineError.PAYLOAD_REJECTED,
            409 to SlmEngineError.PAYLOAD_REJECTED,
            429 to SlmEngineError.PAYLOAD_REJECTED,
            500 to SlmEngineError.ENGINE_ERROR,
            502 to SlmEngineError.ENGINE_ERROR,
            503 to SlmEngineError.UNAVAILABLE,
            504 to SlmEngineError.TIMEOUT,
        ).forEach { (status, expected) ->
            assertEquals("bare HTTP $status", expected, httpErrorFor(null, status))
            assertEquals("unknown code, HTTP $status", expected, httpErrorFor("SAMD-SLM-8999", status))
        }
    }

    @Test
    fun httpClassificationIsTotalOverEveryStatusItCanSee() {
        // Retrofit routes only non-2xx here, but "total" has to mean total: no status in the range
        // produces a null and none is left to a branch that does not exist.
        (100..599).forEach { status ->
            assertTrue("HTTP $status", httpErrorFor(null, status) in SlmEngineError.entries)
        }
    }

    @Test
    fun finishReasonParsingCoversTheContractsFourValuesAndNothingElse() {
        assertEquals(SlmFinishReason.STOP, finishReasonOf("stop"))
        assertEquals(SlmFinishReason.LENGTH, finishReasonOf("length"))
        assertEquals(SlmFinishReason.STOP_SEQUENCE, finishReasonOf("stop_sequence"))
        assertEquals(SlmFinishReason.ERROR, finishReasonOf("error"))
        // Case matters and whitespace is not trimmed: these are wire literals, not prose.
        listOf(null, "", " ", "STOP", "stop ", "finished", "ok").forEach {
            assertNull("'$it' must not parse", finishReasonOf(it))
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun errorFrom(): SlmEngineError = assertThrows(SlmEngineException::class.java) {
        runBlocking { engine.generate(CASE, PROMPT, 512).toList() }
    }.error

    private fun bodyOf(request: RecordedRequest): JsonObject =
        JsonParser.parseString(request.body.readUtf8()).asJsonObject

    private fun defaultClient(): OkHttpClient =
        OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()

    private fun apiAgainst(server: MockWebServer, client: OkHttpClient): SlmApiService =
        apiAgainst(server.url("/").toString(), client)

    private fun apiAgainst(baseUrl: String, client: OkHttpClient): SlmApiService =
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(Gson()))
            .build()
            .create(SlmApiService::class.java)

    private fun ok(body: String) = MockResponse().setResponseCode(200).setBody(body)

    private fun problem(status: Int, code: String) = MockResponse()
        .setResponseCode(status)
        .setHeader("Content-Type", "application/problem+json")
        .setBody("""{"code":"$code","title":"t","status":$status,"detail":"d"}""")

    /**
     * A section 3.1 envelope inside the section 0.5 success envelope. Fields are nullable here for
     * the same reason they are nullable on the DTO: every test that matters is about one of them
     * being absent, and a builder that could not omit one could not express the case.
     */
    private fun envelope(
        text: String? = "A readback.",
        modelId: String? = SANITIZER_TARGET_MODEL_ID,
        digest: String? = "9b1f0d4a7c2e58c3d06b41f8a5e97d2c3b8046fe1a7d5c92b0e34f681ca7d5b2",
        finishReason: String? = "stop",
    ): String {
        val fields = buildList {
            add(""""generation_id":"gen-1"""")
            text?.let { add(""""text":${Gson().toJson(it)}""") }
            modelId?.let { add(""""model_id":${Gson().toJson(it)}""") }
            digest?.let { add(""""model_sha256":${Gson().toJson(it)}""") }
            add(""""prompt_template_version":"$PROMPT_TEMPLATE_VERSION"""")
            finishReason?.let { add(""""finish_reason":${Gson().toJson(it)}""") }
            add(""""decode":{"temperature":0,"do_sample":false,"seed":$SLM_REQUEST_SEED}""")
            add(""""usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}""")
        }
        return """{"success":true,"data":{${fields.joinToString(",")}},"meta":null}"""
    }

    private companion object {
        const val CASE = "case-1"
        const val PROMPT = "APPROVED RECORD\nQUESTION\nWhat did the doctor say?"

        /**
         * api-contract.md section 11.3's failure table, every row, as (status, code) to the error
         * this build must produce. Kept as one list so the totality assertion and the wiring
         * assertion read the same source rather than two copies that can drift.
         */
        val FAILURE_ROWS: Map<Pair<Int, String>, SlmEngineError> = mapOf(
            (503 to "SAMD-SLM-8020") to SlmEngineError.UNAVAILABLE,
            (404 to "SAMD-ENC-4002") to SlmEngineError.PAYLOAD_REJECTED,
            (422 to "SAMD-SLM-8014") to SlmEngineError.PAYLOAD_REJECTED,
            (503 to "SAMD-SLM-8015") to SlmEngineError.UNAVAILABLE,
            (502 to "SAMD-SLM-8010") to SlmEngineError.UNAVAILABLE,
            (504 to "SAMD-SLM-8011") to SlmEngineError.TIMEOUT,
            (422 to "SAMD-SLM-8012") to SlmEngineError.PAYLOAD_REJECTED,
            // PR-8. The service's injection guard, relayed under this hop's own code. Its own row
            // rather than a second PAYLOAD_REJECTED: the worker-facing answers differ.
            (422 to "SAMD-SLM-8016") to SlmEngineError.CONTROL_TOKENS_REJECTED,
            (503 to "SAMD-SLM-8004") to SlmEngineError.UNAVAILABLE,
            (503 to "SAMD-SLM-8005") to SlmEngineError.UNAVAILABLE,
            (502 to "SAMD-SLM-8006") to SlmEngineError.ENGINE_ERROR,
            (502 to "SAMD-SLM-8013") to SlmEngineError.MALFORMED_RESPONSE,
            (500 to "SAMD-SYS-9005") to SlmEngineError.ENGINE_ERROR,
        )
    }
}
