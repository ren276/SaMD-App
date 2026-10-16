package com.example.samdapp.data.slm

import kotlinx.coroutines.flow.Flow

/** The accelerator an engine start asks the runtime for (on-device SLM memo 4.6). */
enum class LocalAccelerator { GPU, CPU }

/**
 * Token counts the runtime reports for the generation just run: `BenchmarkInfo.lastPrefillTokenCount`
 * and `lastDecodeTokenCount` (memo 5.3, 5.5). The finish reason and the Y-20 backstop read these.
 */
data class LocalSlmCounts(
    val prefillTokens: Int,
    val decodeTokens: Int,
    /** Measurements for the dev parity harness only; nothing in the engine reads them. */
    val prefillTokensPerSecond: Double? = null,
    val decodeTokensPerSecond: Double? = null,
    val timeToFirstTokenSeconds: Double? = null,
)

/**
 * The thin seam between the dev local engine and the LiteRT-LM runtime, so the engine's lifecycle,
 * single-flight, deadline and post-run checks run as JVM tests against a fake with no native
 * library. [LiteRtLmRuntime] is the only production implementation and the only file that names
 * the LiteRT-LM package (`LocalSlmSourceScanTest`).
 *
 * Every call here may block on native code. The engine makes them on its single `slm-local`
 * thread (memo 4.3).
 */
interface LocalSlmRuntime {

    /**
     * The process-global `ExperimentalFlags.overwritePromptTemplate`, read verbatim. A non-null
     * value would silently replace the bundled template on every conversation (`Engine.kt:163`),
     * so the engine refuses to start while it is set (memo 5.1).
     */
    fun promptTemplateOverride(): String?

    /** Creates and initialises an engine on [accelerator]. Throws if initialisation fails. */
    fun createEngine(modelPath: String, accelerator: LocalAccelerator, cacheDir: String): RuntimeEngine
}

interface RuntimeEngine : AutoCloseable {

    /** A new conversation with the fixed configuration of memo 5.1 to 5.5. */
    fun createConversation(maxOutputTokens: Int): RuntimeConversation
}

interface RuntimeConversation : AutoCloseable {

    /** Cold flow of output text chunks for one user turn. Completes when the runtime is done. */
    fun send(prompt: String): Flow<String>

    /** Stops the native generation (`Conversation.cancelProcess`). */
    fun cancelProcess()

    /** Null when the runtime cannot report them. */
    fun counts(): LocalSlmCounts?
}
