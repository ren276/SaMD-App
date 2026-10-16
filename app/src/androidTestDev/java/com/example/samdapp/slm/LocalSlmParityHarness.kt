package com.example.samdapp.slm

import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.samdapp.data.slm.LocalAccelerator
import com.example.samdapp.data.slm.LocalSlmEngine
import com.example.samdapp.data.slm.LocalSlmStart
import com.example.samdapp.di.localSlmEngine
import com.example.samdapp.domain.slm.SlmEngineException
import com.example.samdapp.domain.slm.SlmStreamSanitizer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.Writer
import kotlin.concurrent.thread

/**
 * Pixel parity harness (on-device SLM memo 11.4, slice S5b). Calls `LocalSlmEngine` directly, not
 * the seam: the comparator re-applies the seam's gates. Mock data only.
 *
 * Runs only with `-e backend gpu` or `-e backend cpu`, so a plain androidTest run skips it. One run
 * does, in order: a cold start (derived cache deleted) and a warm start (cache verified) with their
 * timings; the 120 golden prompts through the engine and `SlmStreamSanitizer`; then the performance
 * batches. Reads `files/slm-parity/prompts-v1.jsonl`; writes `pixel-<backend>.jsonl` and
 * `pixel-perf-<backend>.jsonl` beside it. Output text goes to those files only, never to logcat
 * and never to the status stream (which gets counts).
 *
 * `-e backend gpu` requires the engine to start on GPU: a silent fall back to CPU would label CPU
 * numbers as GPU, so it fails the run instead.
 *
 * `-e limit N` is a smoke mode: only the first N golden prompts, no performance batches, and
 * `-smoke` file names, so a check of the harness never overwrites a full run's results.
 */
@RunWith(AndroidJUnit4::class)
class LocalSlmParityHarness {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun runsTheGoldenSetAndThePerformanceBatches() = runBlocking {
        val backend = InstrumentationRegistry.getArguments().getString("backend")
        assumeTrue("pass -e backend gpu|cpu to run the parity harness", backend == "gpu" || backend == "cpu")
        backend!!
        val requested = if (backend == "gpu") LocalAccelerator.GPU else LocalAccelerator.CPU
        val accelerators = if (backend == "gpu") listOf(LocalAccelerator.GPU, LocalAccelerator.CPU) else listOf(LocalAccelerator.CPU)
        val dir = File(context.filesDir, "slm-parity")
        val limit = InstrumentationRegistry.getArguments().getString("limit")?.toInt()
        val all = File(dir, "prompts-v1.jsonl").readLines(Charsets.UTF_8).filter { it.isNotBlank() }
            .map { Json.parseToJsonElement(it).jsonObject }
        assertEquals("golden prompts", 120, all.size)
        val prompts = if (limit != null) all.take(limit) else all
        val suffix = if (limit != null) "-smoke" else ""

        fun progress(key: String, value: Any?) = instrumentation.sendStatus(0, Bundle().apply { putString("slm_$key", value.toString()) })

        File(dir, "pixel-$backend$suffix.jsonl").bufferedWriter(Charsets.UTF_8).use { goldenOut ->
            File(dir, "pixel-perf-$backend$suffix.jsonl").bufferedWriter(Charsets.UTF_8).use { perfOut ->
                fun write(out: Writer, row: JsonObject) { out.write(row.toString()); out.write("\n"); out.flush() }

                // 1. Cold start, then warm start, with timings. Release writes the cache manifest.
                File(context.cacheDir, "slm/$ARTIFACT_SHA256").deleteRecursively()
                File(context.filesDir, "slm/$ARTIFACT_SHA256.cache-manifest.json").delete()
                val cold = localSlmEngine(context, accelerators)
                cold.warmUp()
                write(perfOut, initRow(backend, "cold", cold.lastStart()!!))
                cold.release()
                val engine = localSlmEngine(context, accelerators)
                engine.warmUp()
                val warm = engine.lastStart()!!
                write(perfOut, initRow(backend, "warm", warm))
                assertEquals("the $backend run must start on $requested", requested, warm.accelerator)
                progress("init", "cold/warm started on ${warm.accelerator}")

                try {
                    // 2. The golden set.
                    val results = mutableListOf<GoldenResult>()
                    prompts.forEachIndexed { index, row ->
                        val id = row.getValue("id").jsonPrimitive.content
                        val prompt = row.getValue("prompt").jsonPrimitive.content
                        val result = generateOnce(engine, backend, id, prompt)
                        results += GoldenResult(id, result.prefillTokens, result.ok)
                        write(goldenOut, result.row)
                        if ((index + 1) % 20 == 0) progress("golden", "${index + 1} of ${prompts.size}")
                    }

                    // 3. Performance: the median-token golden prompt and the longest one within 2048.
                    if (limit != null) return@runBlocking
                    val eligible = results.filter { it.ok && (it.prefillTokens ?: 0) in 1..2048 }.sortedBy { it.prefillTokens }
                    val median = eligible[eligible.size / 2]
                    val longest = eligible.last()
                    listOf("median" to median, "longest" to longest).forEach { (label, chosen) ->
                        val prompt = prompts.single { it.getValue("id").jsonPrimitive.content == chosen.id }
                            .getValue("prompt").jsonPrimitive.content
                        write(perfOut, perfBatch(engine, backend, label, chosen, prompt) { write(perfOut, it) })
                        progress("perf_$label", "${chosen.id} prefill=${chosen.prefillTokens}")
                    }
                } finally {
                    engine.release()
                }
            }
        }
    }

    private class GoldenResult(val id: String, val prefillTokens: Int?, val ok: Boolean)
    private class Generated(val row: JsonObject, val prefillTokens: Int?, val ok: Boolean, val totalMillis: Long)

    private suspend fun generateOnce(engine: LocalSlmEngine, backend: String, id: String, prompt: String): Generated {
        awaitCoolDown()
        val thermalBefore = thermal()
        val sampler = PssSampler().also { it.start() }
        val sanitizer = SlmStreamSanitizer()
        val raw = StringBuilder()
        val visible = StringBuilder()
        var errorKind: String? = null
        var errorReason: String? = null
        val started = SystemClock.elapsedRealtime()
        try {
            engine.generate(id, prompt, 512).collect { chunk ->
                raw.append(chunk)
                visible.append(sanitizer.accept(chunk))
            }
            visible.append(sanitizer.finish())
        } catch (e: SlmEngineException) {
            errorKind = e.error.name
            errorReason = e.message
        } catch (e: Exception) {
            errorKind = "UNCLASSIFIED"
            errorReason = e.javaClass.simpleName
        }
        val totalMillis = SystemClock.elapsedRealtime() - started
        val peak = sampler.stop()
        val ok = errorKind == null
        val counts = engine.lastCounts()
        val identity = engine.answeredBy()
        val start = engine.lastStart()
        val suppression = sanitizer.suppression
        val row = buildJsonObject {
            put("id", id)
            put("backend", backend)
            put("outcome", if (ok) "ok" else "error")
            put("raw_text", if (ok) raw.toString() else null)
            put("sanitized_text", if (ok) visible.toString() else null)
            put("finish_reason", if (ok) engine.finishReason()?.name else null)
            put("served_model_id", if (ok) engine.servedModelId() else null)
            put("error_kind", errorKind)
            put("error_reason", errorReason)
            put("identity", buildJsonObject {
                put("engine", identity?.engine?.name)
                put("accelerator", identity?.accelerator)
                put("model_id", identity?.modelId)
                put("artifact_sha256", identity?.artifactSha256)
                put("runtime_version", identity?.runtimeVersion)
            })
            put("hash_ms", start?.hashMillis)
            put("init_ms", start?.initMillis)
            put("ttft_ms", counts?.timeToFirstTokenSeconds?.let { Math.round(it * 1000) })
            put("prefill_tokens", counts?.prefillTokens)
            put("prefill_tps", counts?.prefillTokensPerSecond)
            put("decode_tokens", counts?.decodeTokens)
            put("decode_tps", counts?.decodeTokensPerSecond)
            put("total_ms", totalMillis)
            put("pss_kb_peak", peak)
            put("thermal_before", thermalBefore)
            put("thermal_after", thermal())
            put("sanitizer_spans", suppression.spans)
            put("sanitizer_dropped_tokens", suppression.droppedTokens)
            put("sanitizer_unknown_tokens", suppression.unknownTokens)
        }
        return Generated(row, counts?.prefillTokens, ok, totalMillis)
    }

    /** One warm-up, then 20 consecutive generations. Returns the batch summary row. */
    private suspend fun perfBatch(
        engine: LocalSlmEngine,
        backend: String,
        label: String,
        chosen: GoldenResult,
        prompt: String,
        emit: (JsonObject) -> Unit,
    ): JsonObject {
        generateOnce(engine, backend, chosen.id, prompt) // warm-up, not recorded
        val thermalBefore = thermal()
        val sampler = PssSampler().also { it.start() }
        val totals = mutableListOf<Long>()
        var errors = 0
        repeat(20) { i ->
            val g = generateOnce(engine, backend, chosen.id, prompt)
            if (g.ok) totals += g.totalMillis else errors++
            emit(buildJsonObject {
                put("kind", "gen")
                put("backend", backend)
                put("batch", label)
                put("prompt_id", chosen.id)
                put("i", i)
                put("outcome", g.row.getValue("outcome"))
                put("error_kind", g.row.getValue("error_kind"))
                listOf("total_ms", "ttft_ms", "prefill_tokens", "prefill_tps", "decode_tokens", "decode_tps", "pss_kb_peak", "thermal_before", "thermal_after")
                    .forEach { put(it, g.row.getValue(it)) }
            })
        }
        val sorted = totals.sorted()
        return buildJsonObject {
            put("kind", "batch")
            put("backend", backend)
            put("batch", label)
            put("prompt_id", chosen.id)
            put("prefill_tokens", chosen.prefillTokens)
            put("generations", 20)
            put("errors", errors)
            put("total_ms_min", sorted.firstOrNull())
            put("total_ms_median", sorted.getOrNull(sorted.size / 2))
            put("total_ms_max", sorted.lastOrNull())
            put("pss_kb_peak", sampler.stop())
            put("thermal_before", thermalBefore)
            put("thermal_after", thermal())
        }
    }

    private fun initRow(backend: String, phase: String, start: LocalSlmStart) = buildJsonObject {
        put("kind", "init")
        put("backend", backend)
        put("phase", phase)
        put("accelerator", start.accelerator.name)
        put("hash_ms", start.hashMillis)
        put("cache_check", start.cacheCheck.name)
        put("cache_verify_ms", start.cacheVerifyMillis)
        put("init_ms", start.initMillis)
        put("gpu_init_failed", start.gpuFailure != null)
    }

    /** At SEVERE or above the engine refuses (Y-15); wait it out, up to ten minutes, so a refusal is not the result. */
    @SuppressLint("NewApi")
    private suspend fun awaitCoolDown() {
        repeat(20) {
            if (thermal() < PowerManager.THERMAL_STATUS_SEVERE) return
            delay(30_000)
        }
    }

    @SuppressLint("NewApi")
    private fun thermal(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) context.getSystemService(PowerManager::class.java).currentThermalStatus else -1

    /** Samples `Debug.getPss()` every 250 ms on its own thread and reports the peak in kB. */
    private class PssSampler {
        @Volatile private var running = false
        @Volatile private var peak = 0L
        private var worker: Thread? = null

        fun start() {
            running = true
            peak = Debug.getPss()
            worker = thread(isDaemon = true, name = "pss-sampler") {
                while (running) {
                    peak = maxOf(peak, Debug.getPss())
                    Thread.sleep(250)
                }
            }
        }

        fun stop(): Long {
            running = false
            worker?.join(1000)
            return maxOf(peak, Debug.getPss())
        }
    }

    private companion object {
        const val ARTIFACT_SHA256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"
    }
}
