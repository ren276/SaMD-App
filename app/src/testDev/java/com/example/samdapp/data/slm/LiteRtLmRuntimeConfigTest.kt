package com.example.samdapp.data.slm

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The configuration [LiteRtLmRuntime] hands LiteRT-LM, field by field (on-device SLM memo 4.2,
 * 5.1 to 5.5; G-1', G-2', Y-19). These are plain data classes, so no native library loads here.
 */
class LiteRtLmRuntimeConfigTest {

    @Test
    fun theConversationConfigSetsEveryFieldExplicitly() {
        val config = conversationConfig(512)

        assertNull("bundled template, never overridden", config.chatTemplate)
        assertNull(config.systemInstruction)
        assertEquals(emptyList<Any>(), config.initialMessages)
        assertEquals(emptyList<Any>(), config.tools)
        assertFalse(config.automaticToolCalling)
        assertEquals("channels disabled, not defaulted (null would take the model's)", emptyList<Any>(), config.channels)
        assertEquals(emptyMap<String, Any>(), config.extraContext)
        assertNull(config.loraConfig)
        assertFalse(config.prefillPrefaceOnInit)
        assertEquals(512, config.maxOutputToken)
        assertFalse(config.thinkingConfig!!.enableThinking)
        assertEquals(0, config.thinkingConfig!!.thinkingTokenBudget)
        assertFalse(config.enableResponseFormat)
        assertEquals("C-3: pinned false, never inherited", false, config.enableSpeculativeDecoding)
    }

    @Test
    fun theSamplerIsGreedyWithEveryFieldSet() {
        val sampler = conversationConfig(512).samplerConfig!!

        assertEquals(1, sampler.topK)
        assertEquals(1.0, sampler.topP, 0.0)
        assertEquals(0.0, sampler.temperature, 0.0)
        assertEquals("the remote SLM_REQUEST_SEED", 20260918, sampler.seed)
    }

    @Test
    fun theEngineConfigIsContext2560WithNoVisionOrAudio() {
        val gpu = engineConfig("/m/a.litertlm", LocalAccelerator.GPU, "/c")
        assertEquals("/m/a.litertlm", gpu.modelPath)
        assertTrue(gpu.backend is Backend.GPU)
        assertNull(gpu.visionBackend)
        assertNull(gpu.audioBackend)
        assertEquals(2560, gpu.maxNumTokens)
        assertNull(gpu.maxNumImages)
        assertEquals("/c", gpu.cacheDir)
        assertNull(gpu.activationDataType)

        val cpu = engineConfig("/m/a.litertlm", LocalAccelerator.CPU, "/c")
        assertEquals(Backend.CPU(threadCount = 4), cpu.backend)
        assertEquals(2560, cpu.maxNumTokens)
    }

    @OptIn(ExperimentalApi::class)
    @Suppress("DEPRECATION")
    @Test
    fun theRuntimeReadsTheGlobalTemplateOverride() {
        val runtime = LiteRtLmRuntime()
        assertNull(runtime.promptTemplateOverride())
        try {
            ExperimentalFlags.overwritePromptTemplate = "{{ messages }}"
            assertEquals("{{ messages }}", runtime.promptTemplateOverride())
        } finally {
            ExperimentalFlags.overwritePromptTemplate = null
        }
    }
}
