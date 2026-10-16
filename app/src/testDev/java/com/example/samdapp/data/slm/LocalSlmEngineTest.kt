package com.example.samdapp.data.slm

import android.os.PowerManager
import com.example.samdapp.domain.slm.SlmEngineError
import com.example.samdapp.domain.slm.SlmEngineException
import com.example.samdapp.domain.slm.SlmFinishReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

/**
 * [LocalSlmEngine] over a fake [LocalSlmRuntime] (on-device SLM memo 4 to 6, 10; slice S2). The
 * artifact is a small file whose digest the engine is told to expect, so the real hashing path runs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocalSlmEngineTest {

    @get:Rule
    val folder = TemporaryFolder()

    // ---- artifact and identity ----

    @Test
    fun aDigestMismatchNeverStartsTheRuntime() = runTest {
        val runtime = FakeRuntime()
        val engine = engine(runtime, content = "wrong bytes")

        assertRefused(SlmEngineError.UNAVAILABLE, LocalSlmReason.ARTIFACT_DIGEST_MISMATCH) { engine.collect() }
        assertEquals(emptyList<LocalAccelerator>(), runtime.created)
        assertNull(engine.servedModelId())
    }

    @Test
    fun aMissingArtifactNeverStartsTheRuntime() = runTest {
        val runtime = FakeRuntime()
        val engine = engine(runtime, content = null)

        assertRefused(SlmEngineError.UNAVAILABLE, LocalSlmReason.ARTIFACT_MISSING) { engine.collect() }
        assertEquals(emptyList<LocalAccelerator>(), runtime.created)
    }

    @Test
    fun theServedIdIsBuiltFromTheDigestOfTheLoadedFile() = runTest {
        val engine = engine(FakeRuntime())
        assertNull(engine.servedModelId())

        engine.collect()

        assertEquals(
            "litert-community/gemma-4-E2B-it-litert-lm@b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1#" + sha256(ARTIFACT),
            engine.servedModelId(),
        )
    }

    @Test
    fun theStartLoadsTheContentAddressedFileWithTheAppCacheDir() = runTest {
        val runtime = FakeRuntime()
        engine(runtime).collect()

        assertEquals(File(folder.root, "slm/${sha256(ARTIFACT)}.litertlm").path, runtime.modelPaths.single())
        assertEquals(File(folder.root, "cache").path, runtime.cacheDirs.single())
    }

    // ---- start: accelerators, latch, flags ----

    @Test
    fun gpuFailingAtStartFallsBackToCpu() = runTest {
        val runtime = FakeRuntime(failingAccelerators = setOf(LocalAccelerator.GPU))
        val engine = engine(runtime)

        assertEquals(listOf("hello"), engine.collect())
        assertEquals(listOf(LocalAccelerator.GPU, LocalAccelerator.CPU), runtime.created)
        val start = engine.lastStart()!!
        assertEquals(LocalAccelerator.CPU, start.accelerator)
        assertEquals("java.lang.IllegalStateException: GPU init failed", start.gpuFailure)
    }

    @Test
    fun bothAcceleratorsFailingLatchesUnavailableUntilRelease() = runTest {
        val runtime = FakeRuntime(failingAccelerators = setOf(LocalAccelerator.GPU, LocalAccelerator.CPU))
        val engine = engine(runtime)

        assertRefused(SlmEngineError.UNAVAILABLE, LocalSlmReason.INIT_FAILED) { engine.collect() }
        assertRefused(SlmEngineError.UNAVAILABLE, LocalSlmReason.INIT_FAILED) { engine.collect() }
        assertEquals("The latch must stop a second slow start", 2, runtime.created.size)

        engine.release()
        assertRefused(SlmEngineError.UNAVAILABLE, LocalSlmReason.INIT_FAILED) { engine.collect() }
        assertEquals("Release clears the latch", 4, runtime.created.size)
    }

    @Test
    fun aRuntimeErrorMidGenerationIsEngineErrorAndNeverAFallback() = runTest {
        val runtime = FakeRuntime(sendError = IllegalStateException("decode failed"))
        val engine = engine(runtime)

        assertRefused(SlmEngineError.ENGINE_ERROR, "generation_failed (IllegalStateException)") { engine.collect() }
        assertRefused(SlmEngineError.ENGINE_ERROR, "generation_failed (IllegalStateException)") { engine.collect() }
        assertEquals(listOf(LocalAccelerator.GPU), runtime.created)
        assertEquals(LocalAccelerator.GPU, engine.lastStart()!!.accelerator)
    }

    @Test
    fun aPromptTemplateOverrideRefusesTheStart() = runTest {
        val runtime = FakeRuntime(templateOverride = "{{ messages }}")
        val engine = engine(runtime)

        assertRefused(SlmEngineError.UNAVAILABLE, LocalSlmReason.PROMPT_TEMPLATE_OVERRIDE) { engine.collect() }
        assertEquals(emptyList<LocalAccelerator>(), runtime.created)
    }

    @Test
    fun aStartPastItsBoundIsAbandonedAndItsEngineClosedWhenItFinishes() {
        val gate = CountDownLatch(1)
        val runtime = FakeRuntime(createGate = gate)
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val engine = engine(runtime, dispatcher = dispatcher, initTimeoutMs = 200)
        try {
            runBlocking {
                assertRefused(SlmEngineError.UNAVAILABLE, LocalSlmReason.INIT_TIMEOUT) { engine.collect() }
                gate.countDown()
                assertTrue(runtime.engineClosed.await(5, TimeUnit.SECONDS))
                assertRefused(SlmEngineError.UNAVAILABLE, LocalSlmReason.INIT_TIMEOUT) { engine.collect() }
            }
        } finally {
            dispatcher.close()
        }
    }

    @Test
    fun thermalSevereOrAboveRefusesBeforeAnyWork() = runTest {
        listOf(
            PowerManager.THERMAL_STATUS_SEVERE,
            PowerManager.THERMAL_STATUS_CRITICAL,
            PowerManager.THERMAL_STATUS_EMERGENCY,
            PowerManager.THERMAL_STATUS_SHUTDOWN,
        ).forEach { status ->
            val runtime = FakeRuntime()
            assertRefused(SlmEngineError.UNAVAILABLE, LocalSlmReason.THERMAL) { engine(runtime, thermal = status).collect() }
            assertEquals(emptyList<LocalAccelerator>(), runtime.created)
        }
        assertEquals(listOf("hello"), engine(FakeRuntime(), thermal = PowerManager.THERMAL_STATUS_MODERATE).collect())
    }

    @Test
    fun anOutputCapOutsideOneTo512IsRefusedBeforeAnyWork() = runTest {
        listOf(0, 513).forEach { cap ->
            val runtime = FakeRuntime()
            assertRefused(SlmEngineError.PAYLOAD_REJECTED, LocalSlmReason.MAX_OUTPUT_TOKENS_OUT_OF_RANGE) {
                engine(runtime).collect(maxOutputTokens = cap)
            }
            assertEquals(emptyList<LocalAccelerator>(), runtime.created)
        }
    }

    // ---- single-flight, cancellation, deadline ----

    @Test
    fun aSecondConcurrentCallIsUnavailable() = runTest {
        val gate = CompletableDeferred<Unit>()
        val runtime = FakeRuntime(send = { flow { gate.await(); emit("first") } })
        val engine = engine(runtime)
        val first = launch { runCatching { engine.collect() } }
        runCurrent()

        assertRefused(SlmEngineError.UNAVAILABLE, LocalSlmReason.BUSY) { engine.collect() }
        assertEquals(1, runtime.conversations.size)
        gate.complete(Unit)
        first.join()
    }

    @Test
    fun cancellingCallsCancelProcessThenClose() = runTest {
        val runtime = FakeRuntime(send = { flow { awaitCancellation() } })
        val engine = engine(runtime)
        val job = launch { engine.collect() }
        runCurrent()

        job.cancel()
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(listOf("send", "cancelProcess", "close"), runtime.conversations.single().events)
    }

    @Test
    fun generationPastFortySecondsIsTimeoutAndCancelsThenCloses() = runTest {
        val runtime = FakeRuntime(send = { flow { awaitCancellation() } })
        val engine = engine(runtime)

        assertRefused(SlmEngineError.TIMEOUT, LocalSlmReason.GENERATION_TIMEOUT) { engine.collect() }
        assertEquals(40_000L, testScheduler.currentTime)
        assertEquals(listOf("send", "cancelProcess", "close"), runtime.conversations.single().events)
        assertNull(engine.finishReason())
    }

    @Test
    fun everyCallGetsItsOwnConversationClosedOnEveryPath() = runTest {
        val ok = LocalSlmCounts(prefillTokens = 16, decodeTokens = 3)
        val outcomes = listOf<FakeRuntime.() -> Unit>(
            { sendError = null; counts = ok },
            { sendError = IllegalStateException("x"); counts = ok },
            { sendError = null; counts = LocalSlmCounts(prefillTokens = 2049, decodeTokens = 3) },
            { sendError = null; counts = null },
        )
        val runtime = FakeRuntime()
        val engine = engine(runtime)
        outcomes.forEach { configure ->
            runtime.configure()
            runCatching { engine.collect() }
        }

        assertEquals(4, runtime.conversations.size)
        runtime.conversations.forEach { assertEquals(1, it.events.count { e -> e == "close" }) }
        assertEquals(listOf(512, 512, 512, 512), runtime.conversations.map { it.maxOutputTokens })
    }

    // ---- post-run checks and the single emission ----

    @Test
    fun theOutputIsEmittedOnceAfterTheChecks() = runTest {
        val runtime = FakeRuntime(send = { flow { emit("BP "); emit("128/84"); emit(" mmHg") } })
        val engine = engine(runtime)

        val chunks = mutableListOf<String>()
        engine.generate("case", "prompt", 512).collect { chunk ->
            chunks += chunk
            runtime.conversations.single().events += "emit"
        }

        assertEquals(listOf("BP 128/84 mmHg"), chunks)
        assertEquals(listOf("send", "counts", "close", "emit"), runtime.conversations.single().events)
    }

    @Test
    fun theFinishReasonIsDerivedFromTheDecodeCount() {
        assertEquals(SlmFinishReason.STOP, finishReasonFor(0, 512))
        assertEquals(SlmFinishReason.STOP, finishReasonFor(1, 512))
        assertEquals(SlmFinishReason.STOP, finishReasonFor(511, 512))
        assertEquals(SlmFinishReason.LENGTH, finishReasonFor(512, 512))
        assertEquals(SlmFinishReason.LENGTH, finishReasonFor(513, 512))
        assertEquals(SlmFinishReason.LENGTH, finishReasonFor(16, 16))
        assertNull(finishReasonFor(-1, 512))
        assertNull(finishReasonFor(null, 512))
    }

    @Test
    fun theEngineReportsTheDerivedFinishReason() = runTest {
        val runtime = FakeRuntime()
        val engine = engine(runtime)

        runtime.counts = LocalSlmCounts(prefillTokens = 16, decodeTokens = 511)
        engine.collect()
        assertEquals(SlmFinishReason.STOP, engine.finishReason())

        runtime.counts = LocalSlmCounts(prefillTokens = 16, decodeTokens = 512)
        engine.collect()
        assertEquals(SlmFinishReason.LENGTH, engine.finishReason())
    }

    @Test
    fun theBackstopAllows2048PrefillTokens() = runTest {
        val runtime = FakeRuntime(counts = LocalSlmCounts(prefillTokens = 2048, decodeTokens = 3))
        assertEquals(listOf("hello"), engine(runtime).collect())
    }

    @Test
    fun theBackstopRefuses2049PrefillTokensAndEmitsNothing() = runTest {
        assertBackstopRefuses(LocalSlmCounts(prefillTokens = 2049, decodeTokens = 3))
    }

    @Test
    fun theBackstopRefusesUnreadableCountsAndEmitsNothing() = runTest {
        assertBackstopRefuses(null)
        assertBackstopRefuses(LocalSlmCounts(prefillTokens = 0, decodeTokens = 3))
    }

    private suspend fun TestScope.assertBackstopRefuses(counts: LocalSlmCounts?) {
        val runtime = FakeRuntime(counts = counts)
        val engine = engine(runtime)
        val emitted = mutableListOf<String>()

        assertRefused(SlmEngineError.PAYLOAD_REJECTED, LocalSlmReason.PREFILL_OVER_LIMIT_OR_UNREADABLE) {
            engine.generate("case", "prompt", 512).collect { emitted += it }
        }
        assertEquals(emptyList<String>(), emitted)
        assertNull(engine.servedModelId())
        assertNull(engine.finishReason())
    }

    // ---- helpers ----

    private fun TestScope.engine(
        runtime: FakeRuntime,
        content: String? = ARTIFACT,
        thermal: Int = PowerManager.THERMAL_STATUS_NONE,
    ) = engine(runtime, content, thermal, StandardTestDispatcher(testScheduler), initTimeoutMs = 60_000)

    private fun engine(
        runtime: FakeRuntime,
        content: String? = ARTIFACT,
        thermal: Int = PowerManager.THERMAL_STATUS_NONE,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
        initTimeoutMs: Long,
    ): LocalSlmEngine {
        val expected = sha256(ARTIFACT)
        val dir = File(folder.root, "slm").apply { mkdirs() }
        val file = File(dir, "$expected.litertlm")
        if (content == null) file.delete() else file.writeText(content)
        return LocalSlmEngine(
            runtime = runtime,
            artifactDir = dir,
            cacheDir = File(folder.root, "cache"),
            thermalStatus = { thermal },
            dispatcher = dispatcher,
            expectedSha256 = expected,
            initTimeout = initTimeoutMs.milliseconds,
        )
    }

    private suspend fun LocalSlmEngine.collect(maxOutputTokens: Int = 512): List<String> =
        generate("case", "prompt", maxOutputTokens).toList()

    private suspend fun assertRefused(error: SlmEngineError, reason: String, block: suspend () -> Unit) {
        try {
            block()
            fail("Expected SlmEngineException($error, $reason)")
        } catch (e: SlmEngineException) {
            assertEquals(error, e.error)
            assertEquals(reason, e.message)
        }
    }

    private fun sha256(text: String) =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private class FakeRuntime(
        val failingAccelerators: Set<LocalAccelerator> = emptySet(),
        var sendError: Exception? = null,
        var counts: LocalSlmCounts? = LocalSlmCounts(prefillTokens = 16, decodeTokens = 3),
        val templateOverride: String? = null,
        val createGate: CountDownLatch? = null,
        val send: (() -> Flow<String>)? = null,
    ) : LocalSlmRuntime {
        val created: MutableList<LocalAccelerator> = Collections.synchronizedList(mutableListOf())
        val modelPaths = mutableListOf<String>()
        val cacheDirs = mutableListOf<String>()
        val conversations = mutableListOf<FakeConversation>()
        val engineClosed = CountDownLatch(1)

        override fun promptTemplateOverride(): String? = templateOverride

        override fun createEngine(modelPath: String, accelerator: LocalAccelerator, cacheDir: String): RuntimeEngine {
            created += accelerator
            modelPaths += modelPath
            cacheDirs += cacheDir
            createGate?.await()
            if (accelerator in failingAccelerators) throw IllegalStateException("$accelerator init failed")
            return object : RuntimeEngine {
                override fun createConversation(maxOutputTokens: Int): RuntimeConversation =
                    FakeConversation(this@FakeRuntime, maxOutputTokens).also { conversations += it }

                override fun close() = engineClosed.countDown()
            }
        }
    }

    private class FakeConversation(private val runtime: FakeRuntime, val maxOutputTokens: Int) : RuntimeConversation {
        val events = mutableListOf<String>()

        override fun send(prompt: String): Flow<String> {
            events += "send"
            runtime.sendError?.let { error -> return flow { throw error } }
            return runtime.send?.invoke() ?: flow { emit("hello") }
        }

        override fun cancelProcess() {
            events += "cancelProcess"
        }

        override fun counts(): LocalSlmCounts? {
            events += "counts"
            return runtime.counts
        }

        override fun close() {
            events += "close"
        }
    }

    private companion object {
        const val ARTIFACT = "not a model, just bytes with a known digest"
    }
}
