package com.example.samdapp.data.slm

import android.os.PowerManager
import com.example.samdapp.domain.slm.MAX_OUTPUT_TOKENS
import com.example.samdapp.domain.slm.SlmEngine
import com.example.samdapp.domain.slm.SlmEngineError
import com.example.samdapp.domain.slm.SlmEngineException
import com.example.samdapp.domain.slm.SlmFinishReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
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
import java.security.MessageDigest
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The pinned artifact (memo 6.1, G-4). */
internal const val LOCAL_SLM_REPO = "litert-community/gemma-4-E2B-it-litert-lm"
internal const val LOCAL_SLM_COMMIT = "b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1"
internal const val LOCAL_SLM_ARTIFACT_SHA256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"

/** The server's prompt cap (SAMD-SLM-8003). The Y-20 backstop refuses above it. */
internal const val LOCAL_SLM_PREFILL_LIMIT = 2048

/** Stable reason tokens, carried as the exception message. The seam classifies on the error only. */
internal object LocalSlmReason {
    const val BUSY = "busy"
    const val THERMAL = "thermal"
    const val MAX_OUTPUT_TOKENS_OUT_OF_RANGE = "max_output_tokens_out_of_range"
    const val PROMPT_TEMPLATE_OVERRIDE = "prompt_template_override"
    const val ARTIFACT_MISSING = "artifact_missing"
    const val ARTIFACT_UNREADABLE = "artifact_unreadable"
    const val ARTIFACT_DIGEST_MISMATCH = "artifact_digest_mismatch"
    const val INIT_FAILED = "init_failed"
    const val INIT_TIMEOUT = "init_timeout"
    const val STARTING = "starting"
    const val CONVERSATION_FAILED = "conversation_failed"
    const val GENERATION_FAILED = "generation_failed"
    const val GENERATION_TIMEOUT = "generation_timeout"
    const val PREFILL_OVER_LIMIT_OR_UNREADABLE = "prefill_over_limit_or_unreadable"
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
    val initMillis: Long,
    /** The GPU initialisation error, class and message, when GPU was tried and failed. */
    val gpuFailure: String?,
)

/**
 * Gemma 4 E2B on the device through LiteRT-LM, dev flavor only (on-device SLM memo sections 4 to
 * 6, 10). **Not bound to [SlmEngine]**: the product binding stays `RemoteSlmEngine` in every flavor
 * (memo 4.1); this is constructed only by the dev Hilt holder and the dev harness.
 *
 * - Start (lazy, on the first [generate] or [warmUp]): full SHA-256 of the content-addressed file,
 *   then GPU, then CPU, at start only (memo 6.3, 4.6). Both failing latches UNAVAILABLE until
 *   [release]. The start has a 60 s wall-clock bound; an abandoned start's engine is closed when it
 *   finishes.
 * - Generation: one new conversation per call, closed on every path; 40 s deadline; on cancel or
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
    private val cacheDir: File,
    private val thermalStatus: () -> Int,
    /** Single thread: every runtime call runs here (memo 4.3). */
    private val dispatcher: CoroutineDispatcher,
    private val accelerators: List<LocalAccelerator> = listOf(LocalAccelerator.GPU, LocalAccelerator.CPU),
    private val expectedSha256: String = LOCAL_SLM_ARTIFACT_SHA256,
    private val initTimeout: Duration = 60.seconds,
    private val generationTimeout: Duration = 40.seconds,
) : SlmEngine {

    private class Loaded(val engine: RuntimeEngine, val servedModelId: String, val start: LocalSlmStart)

    /** One generation or start at a time; [Mutex.tryLock] refuses, it never queues (same rule as remote, F-2). */
    private val inFlight = Mutex()

    /** Owns engine starts, so a start that outlives its caller's deadline can still be closed. */
    private val startScope = CoroutineScope(SupervisorJob() + dispatcher)

    // Guarded by inFlight.
    private var loaded: Loaded? = null
    private var starting: Deferred<Loaded>? = null
    private var latchedReason: String? = null

    // Per-generation state, cleared before each call and read by the seam after the flow ends.
    @Volatile private var lastServedModelId: String? = null
    @Volatile private var lastFinish: SlmFinishReason? = null
    @Volatile private var lastCounts: LocalSlmCounts? = null
    @Volatile private var lastStart: LocalSlmStart? = null

    override fun generate(caseRecordId: String, prompt: String, maxOutputTokens: Int): Flow<String> = flow {
        if (!inFlight.tryLock()) throw unavailable(LocalSlmReason.BUSY)
        try {
            lastServedModelId = null
            lastFinish = null
            lastCounts = null
            // The context is 2048 + 512 (memo 5.5); a larger cap would eat the prompt budget.
            if (maxOutputTokens !in 1..MAX_OUTPUT_TOKENS) {
                throw SlmEngineException(SlmEngineError.PAYLOAD_REJECTED, LocalSlmReason.MAX_OUTPUT_TOKENS_OUT_OF_RANGE)
            }
            checkThermal()
            val ready = engineReady()
            val text = run(ready, prompt, maxOutputTokens)
            lastServedModelId = ready.servedModelId
            emit(text)
        } finally {
            inFlight.unlock()
        }
    }

    override fun servedModelId(): String? = lastServedModelId

    override fun finishReason(): SlmFinishReason? = lastFinish

    /** Counts of the generation just collected, for the dev harness. */
    fun lastCounts(): LocalSlmCounts? = lastCounts

    /** The start that produced the current engine, for the dev harness. */
    fun lastStart(): LocalSlmStart? = lastStart

    /** Starts the engine without generating (the harness's warm-up, memo 4.2). */
    suspend fun warmUp() {
        if (!inFlight.tryLock()) throw unavailable(LocalSlmReason.BUSY)
        try {
            checkThermal()
            engineReady()
        } finally {
            inFlight.unlock()
        }
    }

    /**
     * Closes the engine and clears the init latch. Waits for a running generation rather than
     * interrupting it (memo 10).
     */
    suspend fun release() = inFlight.withLock {
        withContext(NonCancellable) {
            loaded?.let { closeOnSlmThread(it.engine) }
            starting?.let { abandon(it) }
            loaded = null
            starting = null
            latchedReason = null
            lastStart = null
        }
    }

    /** Memo 10, Y-15: refuse before a run at SEVERE or above. */
    private fun checkThermal() {
        if (thermalStatus() >= PowerManager.THERMAL_STATUS_SEVERE) throw unavailable(LocalSlmReason.THERMAL)
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

        val initStarted = System.nanoTime()
        var gpuFailure: String? = null
        for (accelerator in accelerators) {
            val engine = try {
                runtime.createEngine(artifact.path, accelerator, cacheDir.path)
            } catch (e: Throwable) {
                // Throwable: a missing native accelerator surfaces as an Error, and it is still an
                // init failure to fall back from, not a crash.
                if (accelerator == LocalAccelerator.GPU) gpuFailure = "${e.javaClass.name}: ${e.message}"
                continue
            }
            return Loaded(
                engine = engine,
                servedModelId = localServedModelId(digest),
                start = LocalSlmStart(accelerator, hashMillis, millisSince(initStarted), gpuFailure),
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

    private suspend fun closeOnSlmThread(closeable: AutoCloseable) =
        withContext(NonCancellable + dispatcher) { closeQuietly(closeable) }

    private fun closeQuietly(closeable: AutoCloseable) {
        try {
            closeable.close()
        } catch (e: Exception) {
            // Nothing to do: the handle is unusable either way.
        }
    }

    private fun unavailable(reason: String) = SlmEngineException(SlmEngineError.UNAVAILABLE, reason)

    private fun millisSince(startNanos: Long) = (System.nanoTime() - startNanos) / 1_000_000

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
