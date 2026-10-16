package com.example.samdapp.data.slm

import android.content.ComponentCallbacks2
import android.os.Build
import android.os.PowerManager
import com.example.samdapp.domain.slm.MAX_OUTPUT_TOKENS
import com.example.samdapp.domain.slm.SlmEngine
import com.example.samdapp.domain.slm.SlmEngineError
import com.example.samdapp.domain.slm.SlmEngineException
import com.example.samdapp.domain.slm.SlmEngineIdentity
import com.example.samdapp.domain.slm.SlmEngineKind
import com.example.samdapp.domain.slm.SlmFinishReason
import com.example.samdapp.domain.slm.SlmRunConditions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** The pinned artifact (memo 6.1, G-4). */
internal const val LOCAL_SLM_REPO = "litert-community/gemma-4-E2B-it-litert-lm"
internal const val LOCAL_SLM_COMMIT = "b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1"
internal const val LOCAL_SLM_ARTIFACT_SHA256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"

/** `answeredBy().runtimeVersion` for LOCAL (memo 4.1, 6.1). */
internal const val LOCAL_SLM_RUNTIME_VERSION = "litertlm-android 0.18.0"

/**
 * V-1 (memo 4.6, batch 11): the only backend the engine selects is CPU. GPU greedy output is not
 * repeatable on the device (9 of 30 identical on a repeat, against CPU 28 of 28 comparable) and GPU
 * decode throttles under sustained load. The GPU code path stays, unselected.
 */
internal val LOCAL_SLM_DEFAULT_ACCELERATORS: List<LocalAccelerator> = listOf(LocalAccelerator.CPU)

/**
 * V-2 (memo 4.5, batch 11): the local generation deadline is 60 s, not the server's 40 s, because the
 * device decodes slower than the server's GPU (CPU about 9.9 tok/s) and 6 CPU answers timed out at 40 s.
 */
internal val LOCAL_SLM_GENERATION_DEADLINE: Duration = 60.seconds

/** Y-16 (memo 10): the engine is closed after this long without a generation or a start. */
internal val LOCAL_SLM_IDLE_RELEASE: Duration = 5.minutes

/** Memo 10: at this trim level and above the engine is released (`TRIM_MEMORY_BACKGROUND`). */
internal const val LOCAL_SLM_TRIM_RELEASE_LEVEL: Int = ComponentCallbacks2.TRIM_MEMORY_BACKGROUND

/** The server's prompt cap (SAMD-SLM-8003). The Y-20 backstop refuses above it. */
internal const val LOCAL_SLM_PREFILL_LIMIT = 2048

/** Stable reason tokens, carried as the exception message. The seam classifies on the error only. */
internal object LocalSlmReason {
    const val BUSY = "busy"
    const val THERMAL = "thermal"
    const val THERMAL_UNAVAILABLE = "thermal_unavailable"
    const val MAX_OUTPUT_TOKENS_OUT_OF_RANGE = "max_output_tokens_out_of_range"
    const val PROMPT_TEMPLATE_OVERRIDE = "prompt_template_override"
    const val ARTIFACT_MISSING = "artifact_missing"
    const val ARTIFACT_UNREADABLE = "artifact_unreadable"
    const val ARTIFACT_DIGEST_MISMATCH = "artifact_digest_mismatch"
    const val RUNTIME_CACHE_UNCLEARABLE = "runtime_cache_unclearable"
    const val INIT_FAILED = "init_failed"
    const val INIT_TIMEOUT = "init_timeout"
    const val STARTING = "starting"
    const val CONVERSATION_FAILED = "conversation_failed"
    const val GENERATION_FAILED = "generation_failed"
    const val GENERATION_TIMEOUT = "generation_timeout"
    const val PREFILL_OVER_LIMIT_OR_UNREADABLE = "prefill_over_limit_or_unreadable"
    const val INPUT_TOO_LONG = "input_too_long"
}

/**
 * R9-a (memo 5.5): the exact message text of the runtime's input-too-long failure, pinned. The
 * 0.18.0 Kotlin API carries no structured status code for it (`Conversation.kt:656` builds
 * `LiteRtLmJniException("Status Code: $statusCode. Message: $message")`), so the text is the only
 * handle. Exact and case-sensitive; the on-device over-context test fails if a runtime change alters
 * it, and until then the failure falls back to ENGINE_ERROR, which is also a refusal.
 */
internal const val LOCAL_SLM_INPUT_TOO_LONG_TEXT = "Input token ids are too long"

/** True when [error], or a cause up to five deep, carries [LOCAL_SLM_INPUT_TOO_LONG_TEXT]. */
internal fun isInputTooLong(error: Throwable): Boolean {
    var current: Throwable? = error
    repeat(5) {
        if (current?.message?.contains(LOCAL_SLM_INPUT_TOO_LONG_TEXT) == true) return true
        current = current?.cause
    }
    return false
}

/** Memo 6.1: built from the digest of the file actually loaded, never from a constant. */
internal fun localServedModelId(verifiedSha256: String) = "$LOCAL_SLM_REPO@$LOCAL_SLM_COMMIT#$verifiedSha256"

/**
 * Memo 5.3: the runtime reports no finish reason, so it is derived from the decode count. At or
 * above the cap is LENGTH, which also refuses a generation that ended naturally on the last token:
 * the fail-closed direction. Unreadable is null, which the seam refuses.
 */
internal fun finishReasonFor(decodeTokens: Int?, maxOutputTokens: Int): SlmFinishReason? = when {
    decodeTokens == null || decodeTokens < 0 -> null
    decodeTokens < maxOutputTokens -> SlmFinishReason.STOP
    else -> SlmFinishReason.LENGTH
}

/** What the last engine start did, for the dev harness (Y-4, Y-8, Y-16). */
data class LocalSlmStart(
    val accelerator: LocalAccelerator,
    val hashMillis: Long,
    /** What the start found in the derived runtime cache (memo 6.3, C-2). */
    val cacheCheck: LocalSlmCacheCheck,
    val cacheVerifyMillis: Long,
    val initMillis: Long,
    /** The GPU initialisation error, class and message, when GPU was tried and failed. */
    val gpuFailure: String?,
)

/**
 * Gemma 4 E2B on the device through LiteRT-LM, dev flavor only (on-device SLM memo sections 4 to
 * 6, 10). **Not bound to [SlmEngine]**: the product binding stays `RemoteSlmEngine` in every flavor
 * (memo 4.1); this is constructed only by the dev Hilt holder and the dev harness.
 *
 * - Start (lazy, on the first [generate] or [warmUp]): refused below API 29, where no thermal
 *   status can be read (C-4); full SHA-256 of the content-addressed file; the derived runtime cache
 *   in its own `cacheDir/slm/<sha256>/` checked against the manifest of the last clean release and
 *   deleted on any mismatch (C-2); then the CPU backend, at start only (memo 6.3, 4.6, V-1). A failing
 *   latches UNAVAILABLE until [release]. The start has a 60 s wall-clock bound; an abandoned start's
 *   engine is closed when it finishes.
 * - [release] that closes the engine normally writes the cache manifest; any other end leaves none,
 *   so the next start discards whatever the runtime left behind.
 * - Generation: one new conversation per call, closed on every path; 60 s deadline (V-2); on cancel or
 *   timeout `cancelProcess` then close (memo 4.4, 4.5). A runtime error mid-generation is
 *   ENGINE_ERROR, never a fallback.
 * - Output is buffered and emitted as ONE chunk only after the Y-20 backstop and the finish
 *   derivation (memo 4.4), so nothing of a refused generation reaches the seam.
 *
 * **Never logs anything** (prompt and output included); `LocalSlmSourceScanTest` enforces it.
 */
class LocalSlmEngine(
    private val runtime: LocalSlmRuntime,
    private val artifactDir: File,
    /** The app cache directory; the runtime gets its own subdirectory of it (C-2). */
    cacheDir: File,
    /** `Build.VERSION.SDK_INT`; below API 29 the engine never starts (C-4). */
    private val sdkInt: Int,
    /** Called only at API 29 and above. */
    private val thermalStatus: () -> Int,
    /** Single thread: every runtime call runs here (memo 4.3). */
    private val dispatcher: CoroutineDispatcher,
    private val accelerators: List<LocalAccelerator> = LOCAL_SLM_DEFAULT_ACCELERATORS,
    /**
     * Opt-in for investigating the unselected GPU path (the parity harness passes it for a GPU run,
     * nothing else does). Without it a GPU entry in [accelerators] is refused at construction.
     */
    allowUnselectedGpu: Boolean = false,
    private val expectedSha256: String = LOCAL_SLM_ARTIFACT_SHA256,
    private val initTimeout: Duration = 60.seconds,
    private val generationTimeout: Duration = LOCAL_SLM_GENERATION_DEADLINE,
    /** Idle time after which the engine is released (S7, Y-16). */
    private val idleReleaseAfter: Duration = LOCAL_SLM_IDLE_RELEASE,
) : SlmEngine {

    init {
        require(accelerators.isNotEmpty()) { "no accelerator to start on" }
        require(allowUnselectedGpu || LocalAccelerator.GPU !in accelerators) {
            "the GPU backend is not selected (V-1); it can only be constructed for investigation"
        }
    }

    private val runtimeCacheDir = File(cacheDir, "slm/$expectedSha256")
    private val cacheManifest = File(artifactDir, "$expectedSha256.cache-manifest.json")

    private class Loaded(val engine: RuntimeEngine, val digest: String, val start: LocalSlmStart) {
        val servedModelId = localServedModelId(digest)
    }

    /** One generation or start at a time; [Mutex.tryLock] refuses, it never queues (same rule as remote, F-2). */
    private val inFlight = Mutex()

    /** Owns engine starts, so a start that outlives its caller's deadline can still be closed. */
    private val startScope = CoroutineScope(SupervisorJob() + dispatcher)

    /**
     * Counts generations and starts. An idle release fires only if nothing began since the timer was
     * set, so a timer that expires just as a generation begins does not release right after it.
     */
    @Volatile private var activityEpoch = 0L
    private var idleJob: Job? = null

    // Guarded by inFlight.
    private var loaded: Loaded? = null
    private var starting: Deferred<Loaded>? = null
    private var latchedReason: String? = null

    // Per-generation state, cleared before each call and read by the seam after the flow ends.
    @Volatile private var lastServedModelId: String? = null
    @Volatile private var lastFinish: SlmFinishReason? = null
    @Volatile private var lastCounts: LocalSlmCounts? = null
    @Volatile private var lastStart: LocalSlmStart? = null
    @Volatile private var lastIdentity: SlmEngineIdentity? = null
    @Volatile private var lastConditions: SlmRunConditions? = null

    override fun generate(caseRecordId: String, prompt: String, maxOutputTokens: Int): Flow<String> = flow {
        if (!inFlight.tryLock()) throw unavailable(LocalSlmReason.BUSY)
        markActive()
        try {
            lastServedModelId = null
            lastFinish = null
            lastCounts = null
            lastIdentity = null
            lastConditions = null
            // The context is 2048 + 512 (memo 5.5); a larger cap would eat the prompt budget.
            if (maxOutputTokens !in 1..MAX_OUTPUT_TOKENS) {
                throw SlmEngineException(SlmEngineError.PAYLOAD_REJECTED, LocalSlmReason.MAX_OUTPUT_TOKENS_OUT_OF_RANGE)
            }
            val thermal = checkThermal()
            lastConditions = SlmRunConditions(thermalStatus = thermal, gpuInitFailed = null)
            val ready = engineReady()
            // From the start that produced this engine, so a failure after it still says who ran.
            lastIdentity = SlmEngineIdentity(
                engine = SlmEngineKind.LOCAL,
                accelerator = ready.start.accelerator.name,
                modelId = ready.servedModelId,
                artifactSha256 = ready.digest,
                runtimeVersion = LOCAL_SLM_RUNTIME_VERSION,
            )
            lastConditions = SlmRunConditions(thermalStatus = thermal, gpuInitFailed = ready.start.gpuFailure != null)
            val text = run(ready, prompt, maxOutputTokens)
            lastServedModelId = ready.servedModelId
            emit(text)
        } finally {
            scheduleIdleRelease()
            inFlight.unlock()
        }
    }

    override fun servedModelId(): String? = lastServedModelId

    override fun finishReason(): SlmFinishReason? = lastFinish

    override fun answeredBy(): SlmEngineIdentity? = lastIdentity

    override fun runConditions(): SlmRunConditions? = lastConditions

    /** Counts of the generation just collected, for the dev harness. */
    fun lastCounts(): LocalSlmCounts? = lastCounts

    /** The start that produced the current engine, for the dev harness. */
    fun lastStart(): LocalSlmStart? = lastStart

    /** Starts the engine without generating (the harness's warm-up, memo 4.2). */
    suspend fun warmUp() {
        if (!inFlight.tryLock()) throw unavailable(LocalSlmReason.BUSY)
        markActive()
        try {
            checkThermal()
            engineReady()
        } finally {
            scheduleIdleRelease()
            inFlight.unlock()
        }
    }

    /**
     * Closes the engine and clears the init latch. Takes the engine mutex, so it waits for a running
     * generation rather than interrupting it (memo 10); a release while idle writes the cache
     * manifest (C-2).
     */
    suspend fun release() {
        idleJob?.cancel()
        idleJob = null
        inFlight.withLock { releaseLocked() }
    }

    /**
     * Memory pressure (S7, memo 10): at [LOCAL_SLM_TRIM_RELEASE_LEVEL] and above, release. The release
     * waits behind a running generation, so this never interrupts one.
     */
    fun onTrimMemory(level: Int) {
        if (level >= LOCAL_SLM_TRIM_RELEASE_LEVEL) startScope.launch { release() }
    }

    /** Caller holds [inFlight]. */
    private suspend fun releaseLocked() = withContext(NonCancellable) {
        loaded?.let { if (closeOnSlmThread(it.engine)) writeManifestOnSlmThread() }
        starting?.let { abandon(it) }
        loaded = null
        starting = null
        latchedReason = null
        lastStart = null
    }

    /** Called with [inFlight] held, when a generation or a start begins. */
    private fun markActive() {
        activityEpoch++
        idleJob?.cancel()
        idleJob = null
    }

    /** Called with [inFlight] held, when a generation or a start ends: arms the idle release. */
    private fun scheduleIdleRelease() {
        if (idleReleaseAfter == Duration.INFINITE) return
        val epoch = activityEpoch
        idleJob?.cancel()
        idleJob = startScope.launch {
            delay(idleReleaseAfter)
            inFlight.withLock { if (activityEpoch == epoch) releaseLocked() }
        }
    }

    /**
     * Memo 10, Y-15: refuse before a run at SEVERE or above, and below API 29 where it cannot be
     * read (C-4). Returns the status read, which the audit row records.
     */
    private fun checkThermal(): Int {
        if (sdkInt < Build.VERSION_CODES.Q) throw unavailable(LocalSlmReason.THERMAL_UNAVAILABLE)
        val status = thermalStatus()
        if (status >= PowerManager.THERMAL_STATUS_SEVERE) {
            lastConditions = SlmRunConditions(thermalStatus = status, gpuInitFailed = null)
            throw unavailable(LocalSlmReason.THERMAL)
        }
        return status
    }

    /** Caller holds [inFlight]. */
    private suspend fun engineReady(): Loaded {
        loaded?.let { return it }
        latchedReason?.let { throw unavailable(it) }
        val existing = starting
        // A start whose caller was cancelled keeps running; it is adopted once it has finished.
        if (existing != null && existing.isActive) throw unavailable(LocalSlmReason.STARTING)
        val attempt = existing ?: startScope.async { start() }.also { starting = it }
        val ready = try {
            withTimeout(initTimeout) { attempt.await() }
        } catch (e: TimeoutCancellationException) {
            abandon(attempt)
            starting = null
            latchedReason = LocalSlmReason.INIT_TIMEOUT
            throw unavailable(LocalSlmReason.INIT_TIMEOUT)
        } catch (e: SlmEngineException) {
            starting = null
            if (e.message == LocalSlmReason.INIT_FAILED) latchedReason = LocalSlmReason.INIT_FAILED
            throw e
        }
        starting = null
        loaded = ready
        lastStart = ready.start
        return ready
    }

    /** Runs on the slm-local thread. */
    private fun start(): Loaded {
        if (runtime.promptTemplateOverride() != null) throw unavailable(LocalSlmReason.PROMPT_TEMPLATE_OVERRIDE)
        val artifact = File(artifactDir, "$expectedSha256.litertlm")
        if (!artifact.isFile) throw unavailable(LocalSlmReason.ARTIFACT_MISSING)
        val hashStarted = System.nanoTime()
        val digest = try {
            sha256Hex(artifact)
        } catch (e: IOException) {
            throw unavailable(LocalSlmReason.ARTIFACT_UNREADABLE)
        }
        val hashMillis = millisSince(hashStarted)
        if (digest != expectedSha256) throw unavailable(LocalSlmReason.ARTIFACT_DIGEST_MISMATCH)

        val verifyStarted = System.nanoTime()
        val cacheCheck = try {
            verifyRuntimeCache(runtimeCacheDir, cacheManifest)
        } catch (e: IOException) {
            throw unavailable(LocalSlmReason.RUNTIME_CACHE_UNCLEARABLE)
        }
        val cacheVerifyMillis = millisSince(verifyStarted)
        runtimeCacheDir.mkdirs()

        val initStarted = System.nanoTime()
        var gpuFailure: String? = null
        for (accelerator in accelerators) {
            val engine = try {
                runtime.createEngine(artifact.path, accelerator, runtimeCacheDir.path)
            } catch (e: Throwable) {
                // Throwable: a missing native accelerator surfaces as an Error, and it is still an
                // init failure to fall back from, not a crash.
                if (accelerator == LocalAccelerator.GPU) gpuFailure = "${e.javaClass.name}: ${e.message}"
                continue
            }
            return Loaded(
                engine = engine,
                digest = digest,
                start = LocalSlmStart(accelerator, hashMillis, cacheCheck, cacheVerifyMillis, millisSince(initStarted), gpuFailure),
            )
        }
        throw unavailable(LocalSlmReason.INIT_FAILED)
    }

    /** Closes the abandoned start's engine whenever it finishes, now if it already has. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun abandon(attempt: Deferred<Loaded>) {
        attempt.invokeOnCompletion { cause ->
            if (cause == null) startScope.launch { closeQuietly(attempt.getCompleted().engine) }
        }
    }

    private suspend fun run(ready: Loaded, prompt: String, maxOutputTokens: Int): String {
        val conversation = try {
            withContext(dispatcher) { ready.engine.createConversation(maxOutputTokens) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw SlmEngineException(SlmEngineError.ENGINE_ERROR, LocalSlmReason.CONVERSATION_FAILED)
        }
        try {
            val text = StringBuilder()
            try {
                withTimeout(generationTimeout) {
                    conversation.send(prompt).flowOn(dispatcher).collect { text.append(it) }
                }
            } catch (e: TimeoutCancellationException) {
                cancelOnSlmThread(conversation)
                throw SlmEngineException(SlmEngineError.TIMEOUT, LocalSlmReason.GENERATION_TIMEOUT)
            } catch (e: CancellationException) {
                cancelOnSlmThread(conversation)
                throw e
            } catch (e: Exception) {
                // R9-a: the runtime refuses an over-context prompt itself, before any prefill. That
                // is the same refusal the remote path gives for 8003, so it maps to PAYLOAD_REJECTED.
                if (isInputTooLong(e)) {
                    throw SlmEngineException(SlmEngineError.PAYLOAD_REJECTED, LocalSlmReason.INPUT_TOO_LONG)
                }
                // No cause attached: a runtime message is not ours to vouch for. No fallback either:
                // the accelerator is chosen at start only (memo 4.6).
                throw SlmEngineException(
                    SlmEngineError.ENGINE_ERROR,
                    "${LocalSlmReason.GENERATION_FAILED} (${e.javaClass.simpleName})",
                )
            }
            val counts = withContext(dispatcher) { conversation.counts() }
            lastCounts = counts
            // Y-20: a 2049 to 2560 token prompt fits the context but the server refuses it (8003).
            // Unreadable counts cannot prove the prompt fit, so they refuse too.
            if (counts == null || counts.prefillTokens !in 1..LOCAL_SLM_PREFILL_LIMIT) {
                throw SlmEngineException(SlmEngineError.PAYLOAD_REJECTED, LocalSlmReason.PREFILL_OVER_LIMIT_OR_UNREADABLE)
            }
            lastFinish = finishReasonFor(counts.decodeTokens, maxOutputTokens)
            return text.toString()
        } finally {
            closeOnSlmThread(conversation)
        }
    }

    private suspend fun cancelOnSlmThread(conversation: RuntimeConversation) =
        withContext(NonCancellable + dispatcher) {
            try {
                conversation.cancelProcess()
            } catch (e: Exception) {
                // The conversation is closed next either way.
            }
        }

    /** True when the close returned normally. */
    private suspend fun closeOnSlmThread(closeable: AutoCloseable): Boolean =
        withContext(NonCancellable + dispatcher) { closeQuietly(closeable) }

    private fun closeQuietly(closeable: AutoCloseable): Boolean = try {
        closeable.close()
        true
    } catch (e: Exception) {
        // Nothing to do: the handle is unusable either way.
        false
    }

    /** C-2: only after a normal close, when the runtime has nothing left to write. */
    private suspend fun writeManifestOnSlmThread() = withContext(NonCancellable + dispatcher) {
        try {
            writeRuntimeCacheManifest(runtimeCacheDir, cacheManifest)
        } catch (e: Exception) {
            // No manifest, so the next start discards the cache and cold-starts: the safe direction.
        }
    }

    private fun unavailable(reason: String) = SlmEngineException(SlmEngineError.UNAVAILABLE, reason)

    private fun millisSince(startNanos: Long) = (System.nanoTime() - startNanos) / 1_000_000
}
