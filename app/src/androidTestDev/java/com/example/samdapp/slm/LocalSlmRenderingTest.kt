package com.example.samdapp.slm

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.samdapp.data.slm.LocalAccelerator
import com.example.samdapp.data.slm.conversationConfig
import com.example.samdapp.data.slm.engineConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * G-1' on the device (on-device SLM memo 5.1, slice S3): for every golden prompt, a fresh
 * conversation configured exactly as `LocalSlmEngine` configures it (`engineConfig`,
 * `conversationConfig`) renders the prompt byte for byte like the server's template minus its
 * leading `<bos>` (`golden/server-render-v1.jsonl`, from the GPU machine's G-6 run).
 *
 * Needs `files/slm-parity/prompts-v1.jsonl` and `server-render-v1.jsonl` on the device. Results go
 * to the instrumentation status stream as counts and offsets only, never text.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalApi::class)
class LocalSlmRenderingTest {

    @Test
    fun everyGoldenPromptRendersByteForByteLikeTheServer() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prompts = readJsonl(File(context.filesDir, "slm-parity/prompts-v1.jsonl"))
        val server = readJsonl(File(context.filesDir, "slm-parity/server-render-v1.jsonl"))
            .associate { it.getValue("id").jsonPrimitive.content to it.getValue("rendering").jsonPrimitive.content }
        assertEquals("golden prompts", 120, prompts.size)
        assertEquals("server renderings", 120, server.size)

        // Rendering does not depend on the accelerator; CPU starts fastest. Its own cache dir, so
        // the engine's manifest-pinned cache is not touched.
        val cache = File(context.cacheDir, "slm-parity-render").apply { deleteRecursively(); mkdirs() }
        val artifact = File(context.filesDir, "slm/$ARTIFACT_SHA256.litertlm")
        ExperimentalFlags.enableBenchmark = true

        val mismatches = mutableListOf<Pair<String, Int>>()
        try {
            Engine(engineConfig(artifact.path, LocalAccelerator.CPU, cache.path)).use { engine ->
                engine.initialize()
                prompts.forEach { row ->
                    val id = row.getValue("id").jsonPrimitive.content
                    val prompt = row.getValue("prompt").jsonPrimitive.content
                    val rendered = engine.createConversation(conversationConfig(512)).use { conversation ->
                        conversation.renderMessageIntoString(Message.user(prompt))
                    }
                    val expected = server.getValue(id)
                    if (rendered != expected) mismatches += id to firstDifference(rendered, expected)
                }
            }
        } finally {
            cache.deleteRecursively()
        }

        instrumentation.sendStatus(0, Bundle().apply {
            putString("slm_render_prompts", prompts.size.toString())
            putString("slm_render_mismatches", mismatches.size.toString())
            putString("slm_render_first_offsets", mismatches.joinToString(",") { "${it.first}@${it.second}" })
        })
        assertEquals("rendering mismatches (id@first differing offset): $mismatches", emptyList<Pair<String, Int>>(), mismatches)
    }

    private fun firstDifference(left: String, right: String): Int {
        val common = minOf(left.length, right.length)
        for (i in 0 until common) if (left[i] != right[i]) return i
        return common
    }

    private fun readJsonl(file: File) =
        file.readLines(Charsets.UTF_8).filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject }

    private companion object {
        const val ARTIFACT_SHA256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"
    }
}
