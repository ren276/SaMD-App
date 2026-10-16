package com.example.samdapp.slm

import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.samdapp.data.slm.LocalAccelerator
import com.example.samdapp.di.localSlmEngine
import com.example.samdapp.domain.slm.SlmFinishReason
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device smoke for the dev on-device engine (on-device SLM memo 14, slice S2): one engine start on
 * the requested backend, then G2's probes (a) and (b). Mock text only.
 *
 * `-e backend gpu` (default) uses the product order, GPU then CPU, and reports whether GPU started
 * with the AAR alone (Y-4). `-e backend cpu` forces CPU. Results go to the instrumentation status
 * stream (`am instrument -r`), never to logcat.
 */
@RunWith(AndroidJUnit4::class)
class LocalSlmSmokeTest {

    @Test
    fun startsAndAnswersTheTwoProbes() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val backend = InstrumentationRegistry.getArguments().getString("backend", "gpu")
        val accelerators = when (backend) {
            "gpu" -> listOf(LocalAccelerator.GPU, LocalAccelerator.CPU)
            "cpu" -> listOf(LocalAccelerator.CPU)
            else -> error("backend must be gpu or cpu, got $backend")
        }
        val engine = localSlmEngine(instrumentation.targetContext, accelerators)
        fun report(key: String, value: Any?) =
            instrumentation.sendStatus(0, Bundle().apply { putString("slm_$key", value.toString()) })

        try {
            report("backend_requested", backend)
            engine.warmUp()
            val start = engine.lastStart()!!
            report("accelerator", start.accelerator)
            report("hash_ms", start.hashMillis)
            report("init_ms", start.initMillis)
            report("gpu_failure", start.gpuFailure)

            val results = PROBES.map { (label, prompt) ->
                val started = SystemClock.elapsedRealtime()
                val text = engine.generate("smoke-mock", prompt, 512).toList().joinToString("")
                val millis = SystemClock.elapsedRealtime() - started
                val counts = engine.lastCounts()
                report("${label}_output", escape(text))
                report("${label}_finish", engine.finishReason())
                report("${label}_prefill", counts?.prefillTokens)
                report("${label}_decode", counts?.decodeTokens)
                report("${label}_ms", millis)
                report("${label}_served_model_id", engine.servedModelId())
                Triple(label, text, engine.finishReason())
            }

            assertEquals("OK", results[0].second)
            assertEquals("BP 128/84 mmHg, pulse 76, SpO2 97%.", results[1].second)
            results.forEach { assertEquals("probe ${it.first}", SlmFinishReason.STOP, it.third) }
        } finally {
            engine.release()
        }
    }

    private fun escape(text: String) = text.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r")

    private companion object {
        /** G2 REPORT section 4 probes (a) and (b), engine behaviour tests outside the golden set (Q-ab). */
        val PROBES = listOf(
            "a" to "Reply with the single word OK.",
            "b" to "Repeat exactly: BP 128/84 mmHg, pulse 76, SpO2 97%.",
        )
    }
}
