package com.example.samdapp.data.slm

import com.example.samdapp.data.remote.SLM_REQUEST_SEED
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow

/** KV cache size: 2048 prompt plus 512 output tokens, G2's `max_num_tokens` (memo 4.2, Y-7'). */
internal const val LOCAL_SLM_MAX_NUM_TOKENS = 2560

/** CPU threads, matching ASR's `MODEL_THREADS` (memo 4.6, F-14). */
internal const val LOCAL_SLM_CPU_THREADS = 4

/**
 * The production [LocalSlmRuntime]: LiteRT-LM 0.18.0. The only file that names the LiteRT-LM
 * package (`LocalSlmSourceScanTest`).
 */
@OptIn(ExperimentalApi::class)
class LiteRtLmRuntime : LocalSlmRuntime {

    @Suppress("DEPRECATION")
    override fun promptTemplateOverride(): String? = ExperimentalFlags.overwritePromptTemplate

    override fun createEngine(
        modelPath: String,
        accelerator: LocalAccelerator,
        cacheDir: String,
    ): RuntimeEngine {
        // Read by nativeCreateEngine (Engine.kt:85); the finish reason and the backstop need it.
        ExperimentalFlags.enableBenchmark = true
        // A failed initialize() leaves no native handle (Engine.kt:74), so there is nothing to close.
        val engine = Engine(engineConfig(modelPath, accelerator, cacheDir))
        engine.initialize()
        return LiteRtEngine(engine)
    }
}

/** Memo 4.2. Every field written out, including the defaults that stay null. */
internal fun engineConfig(modelPath: String, accelerator: LocalAccelerator, cacheDir: String) = EngineConfig(
    modelPath = modelPath,
    backend = when (accelerator) {
        LocalAccelerator.GPU -> Backend.GPU()
        LocalAccelerator.CPU -> Backend.CPU(threadCount = LOCAL_SLM_CPU_THREADS)
    },
    visionBackend = null,
    audioBackend = null,
    maxNumTokens = LOCAL_SLM_MAX_NUM_TOKENS,
    maxNumImages = null,
    cacheDir = cacheDir,
    activationDataType = null,
)

/**
 * Memo 5.1 to 5.5 (G-1', G-2', Y-19, C-3): bundled template, one user turn and nothing else,
 * thinking off, channels disabled, speculative decoding off, greedy sampler with every field set.
 * Unset sampler fields default to top_p 0.95 and temperature 1.0, which is why none is left unset.
 */
internal fun conversationConfig(maxOutputTokens: Int) = ConversationConfig(
    systemInstruction = null,
    initialMessages = emptyList(),
    tools = emptyList(),
    samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0, seed = SLM_REQUEST_SEED),
    automaticToolCalling = false,
    channels = emptyList(),
    extraContext = emptyMap(),
    loraConfig = null,
    prefillPrefaceOnInit = false,
    maxOutputToken = maxOutputTokens,
    thinkingConfig = ThinkingConfig(enableThinking = false, thinkingTokenBudget = 0),
    enableResponseFormat = false,
    // C-3: pinned, never inherited (null takes the runtime's choice).
    enableSpeculativeDecoding = false,
    chatTemplate = null,
)

private class LiteRtEngine(private val engine: Engine) : RuntimeEngine {

    override fun createConversation(maxOutputTokens: Int): RuntimeConversation =
        LiteRtConversation(engine.createConversation(conversationConfig(maxOutputTokens)))

    override fun close() = engine.close()
}

@OptIn(ExperimentalApi::class)
private class LiteRtConversation(private val conversation: Conversation) : RuntimeConversation {

    // The callback form, not sendMessageAsync's own Flow: that one trySends into a default-capacity
    // channel and drops a message when it is full (Conversation.kt:455). Unlimited never drops.
    override fun send(prompt: String): Flow<String> = callbackFlow {
        conversation.sendMessageAsync(
            Message.user(prompt),
            object : MessageCallback {
                override fun onMessage(message: Message) {
                    trySend(message.contents.toString())
                }

                override fun onDone() {
                    close()
                }

                override fun onError(throwable: Throwable) {
                    close(throwable)
                }
            },
        )
        awaitClose {}
    }.buffer(Channel.UNLIMITED)

    override fun cancelProcess() = conversation.cancelProcess()

    override fun counts(): LocalSlmCounts? = try {
        conversation.getBenchmarkInfo().let {
            LocalSlmCounts(
                prefillTokens = it.lastPrefillTokenCount,
                decodeTokens = it.lastDecodeTokenCount,
                prefillTokensPerSecond = it.lastPrefillTokensPerSecond,
                decodeTokensPerSecond = it.lastDecodeTokensPerSecond,
                timeToFirstTokenSeconds = it.timeToFirstTokenInSecond,
            )
        }
    } catch (e: Exception) {
        null
    }

    override fun close() = conversation.close()
}
