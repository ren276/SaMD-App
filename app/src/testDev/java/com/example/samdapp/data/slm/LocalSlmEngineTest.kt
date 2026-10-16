package com.example.samdapp.data.slm

import android.os.PowerManager
import com.example.samdapp.domain.slm.LOCAL_SERVED_MODEL_ID
import com.example.samdapp.domain.slm.SlmEngineError
import com.example.samdapp.domain.slm.SlmEngineException
import com.example.samdapp.domain.slm.SlmEngineIdentity
import com.example.samdapp.domain.slm.SlmEngineKind
import com.example.samdapp.domain.slm.SlmRunConditions
import com.example.samdapp.domain.slm.servedModelMatchesSanitizer
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
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
    fun theIdTheEngineServesIsExactlyTheSeamsAllowlistEntry() {
        // Two independent sources: the engine builds the id from the digest it verified, the seam
        // holds the id as a literal (memo 6.1, G-4). They must agree for the pinned artifact.
        assertEquals(LOCAL_SERVED_MODEL_ID, localServedModelId(LOCAL_SLM_ARTIFACT_SHA256))
        assertTrue(servedModelMatchesSanitizer(localServedModelId(LOCAL_SLM_ARTIFACT_SHA256)))
        assertTrue("another digest must not pass the seam", !servedModelMatchesSanitizer(localServedModelId(sha256(ARTIFACT))))
    }

    @Test
    fun theStartLoadsTheContentAddressedFileWithItsOwnCacheDir() = runTest {
        val runtime = FakeRuntime()
        engine(runtime).collect()

        assertEquals(File(folder.root, "slm/${sha256(ARTIFACT)}.litertlm").path, runtime.modelPaths.single())
        assertEquals("C-2: dedicated, keyed by the artifact digest", runtimeCache().path, runtime.cacheDirs.single())
        assertTrue("created by us before the runtime starts", runtimeCache().isDirectory)
    }

    // ---- derived runtime cache (C-2) ----

    @Test
    fun aCleanReleaseWritesAManifestOfEveryCacheFile() = runTest {
        val engine = engine(FakeRuntime(cacheWrites = CACHE))
        engine.collect()
        assertTrue("no manifest while the engine is open", !manifest().exists())

        engine.release()

        val listed = manifestEntries()
        assertEquals(CACHE.keys, listed.keys)
        CACHE.forEach { (path, content) ->
            assertEquals(content.length.toLong() to sha256(content), listed.getValue(path))
        }
    }

    @Test
    fun aVerifiedCacheIsHandedToTheRuntimeUntouched() = runTest {
        cleanReleaseWithCache()
        val runtime = FakeRuntime()
        val engine = engine(runtime)

        engine.collect()

        assertEquals(LocalSlmCacheCheck.VERIFIED, engine.lastStart()!!.cacheCheck)
        assertEquals(CACHE.keys, runtime.cacheAtCreate.single())
        assertTrue("a verified manifest is kept", manifest().exists())
    }

    @Test
    fun aChangedCacheFileDiscardsTheCacheAndTheManifest() = runTest {
        cleanReleaseWithCache()
        File(runtimeCache(), "program.bin").writeText("gpu program X") // same size, other bytes
        assertDiscardedBeforeStart(LocalSlmCacheCheck.DISCARDED_MISMATCH)
    }

    @Test
    fun anUnlistedCacheFileDiscardsTheCache() = runTest {
        cleanReleaseWithCache()
        File(runtimeCache(), "sub/extra.bin").writeText("not in the manifest")
        assertDiscardedBeforeStart(LocalSlmCacheCheck.DISCARDED_MISMATCH)
    }

    @Test
    fun aMissingListedCacheFileDiscardsTheCache() = runTest {
        cleanReleaseWithCache()
        File(runtimeCache(), "sub/weights.cache").delete()
        assertDiscardedBeforeStart(LocalSlmCacheCheck.DISCARDED_MISMATCH)
    }

    @Test
    fun aMalformedManifestDiscardsTheCache() = runTest {
        cleanReleaseWithCache()
        manifest().writeText("{\"files\": [")
        assertDiscardedBeforeStart(LocalSlmCacheCheck.DISCARDED_MISMATCH)
    }

    @Test
    fun aNonEmptyCacheWithNoManifestIsDiscarded() = runTest {
        File(runtimeCache(), "left.bin").apply { parentFile!!.mkdirs() }.writeText("after a crash")
        assertDiscardedBeforeStart(LocalSlmCacheCheck.DISCARDED_NO_MANIFEST)
    }

    @Test
    fun anEmptyCacheWithNoManifestIsAColdStart() = runTest {
        val engine = engine(FakeRuntime())
        engine.collect()
        assertEquals(LocalSlmCacheCheck.EMPTY, engine.lastStart()!!.cacheCheck)
    }

    @Test
    fun noManifestIsWrittenWithoutANormalClose() = runTest {
        engine(FakeRuntime(cacheWrites = CACHE, closeError = IllegalStateException("close failed"))).apply {
            collect()
            release()
        }
        assertTrue("a close that threw is not a clean release", !manifest().exists())

        engine(FakeRuntime(cacheWrites = CACHE)).collect() // never released, as after a crash
        assertTrue(!manifest().exists())
    }

    @Test
    fun aCrashBetweenWriteAndRenameLeavesNoValidManifest() = runTest {
        cleanReleaseWithCache()
        assertTrue("an old manifest is present before the crash", manifest().exists())

        try {
            writeRuntimeCacheManifest(runtimeCache(), manifest()) { throw IOException("power lost") }
            fail("the crash must propagate")
        } catch (e: IOException) {
            assertEquals("power lost", e.message)
        }

        assertTrue("no manifest at the target name, old or new", !manifest().exists())
        assertDiscardedBeforeStart(LocalSlmCacheCheck.DISCARDED_NO_MANIFEST)
    }

    private suspend fun TestScope.cleanReleaseWithCache() =
        engine(FakeRuntime(cacheWrites = CACHE)).apply { collect(); release() }

    private suspend fun TestScope.assertDiscardedBeforeStart(expected: LocalSlmCacheCheck) {
        val runtime = FakeRuntime()
        val engine = engine(runtime)

        engine.collect()

        assertEquals(expected, engine.lastStart()!!.cacheCheck)
        assertEquals("the runtime must start on an empty cache", emptySet<String>(), runtime.cacheAtCreate.single())
        assertTrue("the manifest is deleted with the cache", !manifest().exists())
    }

    // ---- answeredBy and run conditions (memo 4.1, 8; C-7) ----

    @Test
    fun answeredByIsTheLocalStartAndRunConditionsCarryThermalAndGpuFallback() = runTest {
        val engine = engine(FakeRuntime(), thermal = PowerManager.THERMAL_STATUS_MODERATE)
        assertNull(engine.answeredBy())
        assertNull(engine.runConditions())

        engine.collect()

        assertEquals(
            SlmEngineIdentity(
                engine = SlmEngineKind.LOCAL,
                accelerator = "GPU",
                modelId = localServedModelId(sha256(ARTIFACT)),
                artifactSha256 = sha256(ARTIFACT),
                runtimeVersion = "litertlm-android 0.18.0",
            ),
            engine.answeredBy(),
        )
        assertEquals(SlmRunConditions(PowerManager.THERMAL_STATUS_MODERATE, gpuInitFailed = false), engine.runConditions())

        val fellBack = engine(FakeRuntime(failingAccelerators = setOf(LocalAccelerator.GPU)))
        fellBack.collect()
        assertEquals("CPU", fellBack.answeredBy()!!.accelerator)
        assertEquals("C-7: a boolean", true, fellBack.runConditions()!!.gpuInitFailed)
    }

    @Test
    fun aFailureAfterTheStartStillSaysWhoRanAndARefusedStartSaysNobody() = runTest {
        val midRun = engine(FakeRuntime(sendError = IllegalStateException("decode failed")))
        assertRefused(SlmEngineError.ENGINE_ERROR, "generation_failed (IllegalStateException)") { midRun.collect() }
        assertEquals(SlmEngineKind.LOCAL, midRun.answeredBy()!!.engine)

        val hot = engine(FakeRuntime(), thermal = PowerManager.THERMAL_STATUS_SEVERE)
        assertRefused(SlmEngineError.UNAVAILABLE, LocalSlmReason.THERMAL) { hot.collect() }
        assertNull(hot.answeredBy())
        assertEquals("the refusing status is recorded", SlmRunConditions(PowerManager.THERMAL_STATUS_SEVERE, null), hot.runConditions())

        val wrongFile = engine(FakeRuntime(), content = "wrong bytes")
        assertRefused(SlmEngineError.UNAVAILABLE, LocalSlmReason.ARTIFACT_DIGEST_MISMATCH) { wrongFile.collect() }
        assertNull(wrongFile.answeredBy())
        assertNull(wrongFile.runConditions()!!.gpuInitFailed)
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
    fun theRuntimesInputTooLongErrorIsThePayloadRefusalAndEmitsNothing() = runTest {
        val real = "Status Code: 3. Message: INVALID_ARGUMENT: Input token ids are too long. Exceeding the maximum number of tokens allowed: 5666 >= 2560."
        listOf<Throwable>(
            RuntimeException(real),
            IllegalStateException("wrapped", RuntimeException(real)),
        ).forEach { failure ->
            val runtime = FakeRuntime(sendError = failure as Exception)
            val engine = engine(runtime)
            val emitted = mutableListOf<String>()

            assertRefused(SlmEngineError.PAYLOAD_REJECTED, LocalSlmReason.INPUT_TOO_LONG) {
                engine.generate("case", "prompt", 512).collect { emitted += it }
            }
            assertEquals(emptyList<String>(), emitted)
            assertNull(engine.finishReason())
            assertEquals("no fallback to another accelerator", listOf(LocalAccelerator.GPU), runtime.created)
        }
    }

    @Test
    fun anyOtherSendErrorStaysEngineErrorIncludingATextThatIsNotTheExactPin() = runTest {
        listOf(
            "decode failed",
            "Status Code: 13. Message: INTERNAL: kernel failed",
            "input token ids are too long",
            "Input token ids are too",
            "Input token ids are  too long",
        ).forEach { text ->
            val engine = engine(FakeRuntime(sendError = RuntimeException(text)))
            assertRefused(SlmEngineError.ENGINE_ERROR, "generation_failed (RuntimeException)") { engine.collect() }
        }
        assertEquals(false, isInputTooLong(RuntimeException(null as String?)))
        // The walk is bounded: the text six causes deep is not found.
        var deep: Throwable = RuntimeException(LOCAL_SLM_INPUT_TOO_LONG_TEXT)
        repeat(6) { deep = RuntimeException("layer", deep) }
        assertEquals(false, isInputTooLong(deep))
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
    fun belowApi29TheEngineNeverStarts() = runTest {
        var thermalReads = 0
        listOf(26, 28).forEach { sdk ->
            val runtime = FakeRuntime()
            val engine = engine(runtime, sdkInt = sdk, thermalRead = { thermalReads++ })
            assertRefused(SlmEngineError.UNAVAILABLE, LocalSlmReason.THERMAL_UNAVAILABLE) { engine.collect() }
            assertRefused(SlmEngineError.UNAVAILABLE, LocalSlmReason.THERMAL_UNAVAILABLE) { engine.warmUp() }
            assertEquals(emptyList<LocalAccelerator>(), runtime.created)
        }
        assertEquals("no thermal read below API 29", 0, thermalReads)
        assertEquals(listOf("hello"), engine(FakeRuntime(), sdkInt = 29).collect())
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
        sdkInt: Int = 37,
        thermalRead: () -> Unit = {},
    ) = engine(runtime, content, thermal, StandardTestDispatcher(testScheduler), initTimeoutMs = 60_000, sdkInt, thermalRead)

    private fun engine(
        runtime: FakeRuntime,
        content: String? = ARTIFACT,
        thermal: Int = PowerManager.THERMAL_STATUS_NONE,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
        initTimeoutMs: Long,
        sdkInt: Int = 37,
        thermalRead: () -> Unit = {},
    ): LocalSlmEngine {
        val expected = sha256(ARTIFACT)
        val dir = File(folder.root, "slm").apply { mkdirs() }
        val file = File(dir, "$expected.litertlm")
        if (content == null) file.delete() else file.writeText(content)
        return LocalSlmEngine(
            runtime = runtime,
            artifactDir = dir,
            cacheDir = File(folder.root, "cache"),
            sdkInt = sdkInt,
            thermalStatus = { thermalRead(); thermal },
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

    private fun runtimeCache() = File(folder.root, "cache/slm/${sha256(ARTIFACT)}")

    private fun manifest() = File(folder.root, "slm/${sha256(ARTIFACT)}.cache-manifest.json")

    /** Path to (size, sha256), parsed independently of the engine's reader. */
    private fun manifestEntries(): Map<String, Pair<Long, String>> =
        Json.parseToJsonElement(manifest().readText()).jsonObject.getValue("files").jsonArray.associate {
            val entry = it.jsonObject
            entry.getValue("path").jsonPrimitive.content to
                (entry.getValue("size").jsonPrimitive.long to entry.getValue("sha256").jsonPrimitive.content)
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
        /** Files the fake "runtime" writes into its cache dir at start, as the GPU program cache does. */
        val cacheWrites: Map<String, String> = emptyMap(),
        val closeError: Exception? = null,
    ) : LocalSlmRuntime {
        val created: MutableList<LocalAccelerator> = Collections.synchronizedList(mutableListOf())
        val modelPaths = mutableListOf<String>()
        val cacheDirs = mutableListOf<String>()
        /** Relative paths present in the cache dir when each engine was created. */
        val cacheAtCreate = mutableListOf<Set<String>>()
        val conversations = mutableListOf<FakeConversation>()
        val engineClosed = CountDownLatch(1)

        override fun promptTemplateOverride(): String? = templateOverride

        override fun createEngine(modelPath: String, accelerator: LocalAccelerator, cacheDir: String): RuntimeEngine {
            created += accelerator
            modelPaths += modelPath
            cacheDirs += cacheDir
            val dir = File(cacheDir)
            cacheAtCreate += dir.walkTopDown().filter { it.isFile }.map { it.relativeTo(dir).invariantSeparatorsPath }.toSet()
            createGate?.await()
            if (accelerator in failingAccelerators) throw IllegalStateException("$accelerator init failed")
            cacheWrites.forEach { (path, content) -> File(dir, path).apply { parentFile!!.mkdirs() }.writeText(content) }
            return object : RuntimeEngine {
                override fun createConversation(maxOutputTokens: Int): RuntimeConversation =
                    FakeConversation(this@FakeRuntime, maxOutputTokens).also { conversations += it }

                override fun close() {
                    engineClosed.countDown()
                    closeError?.let { throw it }
                }
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
        val CACHE = mapOf("program.bin" to "gpu program A", "sub/weights.cache" to "packed weights")
    }
}
