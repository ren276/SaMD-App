package com.example.samdapp.data.transcription

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.InputStream
import java.security.MessageDigest

/**
 * Runs the real engine against the real vendored weights. There is deliberately no canned-
 * transcript double: a fake would assert that this file's own expectations are self-consistent,
 * which is not the thing anybody needs to know before shipping a recogniser.
 *
 * Requires an emulator or device with the `x86_64` or `arm64-v8a` ABI (see `abiFilters`), and
 * `RECORD_AUDIO` granted — AGP installs the test APK with runtime permissions pre-granted by
 * default, so no `GrantPermissionRule` is declared here.
 */
@RunWith(AndroidJUnit4::class)
class SherpaOnnxTranscriptionServiceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val testAssets = InstrumentationRegistry.getInstrumentation().context.assets

    /** One instance for every test that actually decodes, mirroring the single `@Singleton` the
     *  app binds. Not a tidiness point: the service retains its recognizer for the process
     *  lifetime by design, so a fresh instance per test method would hold a second and a third
     *  copy of 622 MiB of weights simultaneously and the instrumentation process gets killed
     *  partway through the run. That is the resident-memory ceiling this model carries, showing
     *  up in the one place that stresses it. */
    private fun service() = shared

    private fun serviceFor(modelDir: String) =
        SherpaOnnxTranscriptionService(context, modelDir)

    /** Content words, not the exact string. Asserting the full sentence would turn any future
     *  runtime or quantisation change into a failure over a comma, which is a word-error-rate
     *  tripwire dressed up as a unit test, and it would be silenced by whoever hit it. */
    @Test
    fun known_clip_transcribes_to_text_containing_the_spoken_content_words() = runBlocking {
        val result = service().transcribeSamples(readPcm16Wav("asr-test/known-clip.wav"))

        val text = result.getOrThrow().lowercase()
        assertTrue("transcript was blank: '$text'", text.isNotBlank())
        listOf("wish", "see", "observed", "portrait").forEach { word ->
            assertTrue("expected content word '$word' in: '$text'", word in text)
        }
    }

    /** The honest-failure edge the consultation screen depends on: silence is a **success**
     *  carrying "", not an error. The screen renders those two differently and the audit
     *  breadcrumbs record them differently, so conflating them here would hide a real regression.
     *  With an offline recogniser this is the ordinary outcome of a mis-tapped mic, not a rarity. */
    @Test
    fun a_silent_clip_comes_back_as_an_empty_success_not_a_failure() = runBlocking {
        val result = service().transcribeSamples(readPcm16Wav("asr-test/silence-clip.wav"))

        assertTrue("silence must not surface as an engine error", result.isSuccess)
        assertEquals("", result.getOrThrow())
    }

    /** A packaging fault must reach the worker as the screen's honest-failure state, not as a
     *  dead app. Worth an explicit test because sherpa-onnx's own asset reader does not throw on
     *  a missing model file — it calls `_Exit(-1)` — so this passes only while the service keeps
     *  checking the assets open before it constructs the recogniser.
     *
     *  Known ceiling: this covers a **missing** asset. A **present but corrupt** one still
     *  reaches ONNX Runtime and still ends in `_Exit`, which cannot be asserted from inside the
     *  process that dies. The guard for that case is
     *  [shipped_model_assets_match_the_hashes_pinned_in_the_sbom_companion], which fails in CI
     *  before any device loads it. */
    @Test
    fun an_absent_model_asset_fails_the_capture_instead_of_killing_the_process() = runBlocking {
        val result = serviceFor("asr/no-such-model-dir")
            .transcribeSamples(FloatArray(16_000))

        assertTrue("a missing model must not be reported as a successful capture", result.isFailure)
        assertTrue(
            "expected a message naming the missing asset, got: ${result.exceptionOrNull()?.message}",
            result.exceptionOrNull()?.message.orEmpty().contains("missing or unreadable"),
        )
    }

    /**
     * Cancellation must reach the blocking `AudioRecord.read` loop within about one chunk
     * (100 ms), not only at the next capture boundary. Before the `ensureActive()` fix this
     * could stay open until [TRAILING_SILENCE_MS], [LEAD_IN_TIMEOUT_MS] or [MAX_CAPTURE_MS] —
     * seconds, not milliseconds — every time a worker navigated away mid-capture.
     *
     * Exercises `captureAudioAttachment()` directly against the mic rather than going through
     * `VOICE_INPUT_ENABLED` / the ViewModel: that flag is a compile-time `const val`, so a test
     * cannot flip it without editing `FeatureFlags.kt`, which this change does not touch.
     */
    @Test
    fun cancelling_mid_capture_stops_the_microphone_read_promptly() = runBlocking(Dispatchers.Default) {
        val job = launch { service().captureAudioAttachment() }
        delay(250)

        val start = System.nanoTime()
        job.cancelAndJoin()
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        assertTrue(
            "cancellation took ${elapsedMs}ms; expected well under the multi-second capture timeouts",
            elapsedMs < 2_000,
        )
    }

    /**
     * Turns the SBOM model companion from a claim into something CI re-checks. `tokens.txt` is
     * pinned in its own right and matters most: a mismatched vocabulary yields plausible wrong
     * text rather than an error, which is exactly the failure mode the confirmation gate exists
     * to catch and exactly the one nobody notices in review.
     *
     * If this fails, do not update the constants below. The shipped bytes changed; the SBOM entry
     * is the record of what was validated.
     */
    @Test
    fun shipped_model_assets_match_the_hashes_pinned_in_the_sbom_companion() {
        PINNED_ASSET_SHA256.forEach { (name, expected) ->
            val actual = context.assets.open("$MODEL_ASSET_DIR/$name").use(::sha256)
            assertEquals("$MODEL_ASSET_DIR/$name", expected, actual)
        }
    }

    private fun sha256(stream: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val read = stream.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** 16-bit PCM mono little-endian, which is what both fixtures are and what the model wants.
     *  Skips to the `data` chunk rather than assuming a 44-byte header. */
    private fun readPcm16Wav(assetPath: String): FloatArray {
        val bytes = testAssets.open(assetPath).use { it.readBytes() }
        var offset = 12 // past "RIFF" <size> "WAVE"
        while (offset + 8 <= bytes.size) {
            val id = String(bytes, offset, 4, Charsets.US_ASCII)
            val size = littleEndianInt(bytes, offset + 4)
            if (id == "data") {
                val samples = FloatArray(size / 2)
                for (i in samples.indices) {
                    val lo = bytes[offset + 8 + i * 2].toInt() and 0xff
                    val hi = bytes[offset + 9 + i * 2].toInt()
                    samples[i] = ((hi shl 8) or lo) / 32768f
                }
                return samples
            }
            offset += 8 + size + (size and 1)
        }
        throw IllegalArgumentException("no data chunk in $assetPath")
    }

    private fun littleEndianInt(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xff) or
            ((b[at + 1].toInt() and 0xff) shl 8) or
            ((b[at + 2].toInt() and 0xff) shl 16) or
            ((b[at + 3].toInt() and 0xff) shl 24)

    private companion object {
        private val shared: SherpaOnnxTranscriptionService by lazy {
            SherpaOnnxTranscriptionService(
                InstrumentationRegistry.getInstrumentation().targetContext,
                MODEL_ASSET_DIR,
            )
        }

        /** Mirrors `docs/sbom/model-soup-2026-09-02-v1.0.json`. Both are edited together or
         *  neither is. */
        val PINNED_ASSET_SHA256 = mapOf(
            "encoder.int8.onnx" to
                "a32b12d17bbbc309d0686fbbcc2987b5e9b8333a7da83fa6b089f0a2acd651ab",
            "decoder.int8.onnx" to
                "b6bb64963457237b900e496ee9994b59294526439fbcc1fecf705b31a15c6b4e",
            "joiner.int8.onnx" to
                "7946164367946e7f9f29a122407c3252b680dbae9a51343eb2488d057c3c43d2",
            "tokens.txt" to
                "ec182b70dd42113aff6c5372c75cac58c952443eb22322f57bbd7f53977d497d",
        )
    }
}
