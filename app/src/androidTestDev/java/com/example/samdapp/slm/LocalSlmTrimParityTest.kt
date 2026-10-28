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
 * Trim parity on the device (G-1' trim rule, on-device SLM memo 5.1, 11.2): the 28 probes of
 * `trim-probes-v1.jsonl` (a whitespace-like character at the start, the end or both of the question,
 * built the way the seam builds the prompt) are rendered by a fresh conversation configured exactly
 * as `LocalSlmEngine` configures it, and each rendering is compared byte for byte with the server's
 * rendering from the GPU machine (`server-render-trim-v1.jsonl`).
 *
 * A mismatch is reported with its probe label (character and end) and the first differing offset.
 * Trimming and the guard are NOT to be changed on a mismatch: that needs a ruling.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalApi::class)
class LocalSlmTrimParityTest {

    @Test
    fun everyTrimProbeRendersByteForByteLikeTheServer() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val probes = readJsonl(File(context.filesDir, "slm-parity/trim-probes-v1.jsonl"))
        val server = readJsonl(File(context.filesDir, "slm-parity/server-render-trim-v1.jsonl"))
            .associate { it.getValue("id").jsonPrimitive.content to it.getValue("rendering").jsonPrimitive.content }
        assertEquals("trim probes", 28, probes.size)
        assertEquals("server trim renderings", 28, server.size)

        val cache = File(context.cacheDir, "slm-parity-trim").apply { deleteRecursively(); mkdirs() }
        val artifact = File(context.filesDir, "slm/$ARTIFACT_SHA256.litertlm")
        ExperimentalFlags.enableBenchmark = true

        val mismatches = mutableListOf<String>()
        try {
            Engine(engineConfig(artifact.path, LocalAccelerator.CPU, cache.path)).use { engine ->
                engine.initialize()
                probes.forEach { row ->
                    val id = row.getValue("id").jsonPrimitive.content
                    val prompt = row.getValue("prompt").jsonPrimitive.content
                    val rendered = engine.createConversation(conversationConfig(512)).use { conversation ->
                        conversation.renderMessageIntoString(Message.user(prompt))
                    }
                    val expected = server.getValue(id)
                    if (rendered != expected) {
                        val label = "${row.getValue("probe_char").jsonPrimitive.content}/${row.getValue("probe_position").jsonPrimitive.content}"
                        mismatches += "$id $label @${firstDifference(rendered, expected)} (device ${rendered.length} chars, server ${expected.length})"
                    }
                }
            }
        } finally {
            cache.deleteRecursively()
        }

        instrumentation.sendStatus(0, Bundle().apply {
            putString("slm_trim_probes", probes.size.toString())
            putString("slm_trim_mismatches", mismatches.size.toString())
            putString("slm_trim_detail", mismatches.joinToString(" | "))
        })
        assertEquals("trim probe mismatches (id label @first differing offset): $mismatches", emptyList<String>(), mismatches)
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
