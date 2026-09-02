package com.example.samdapp.data.transcription

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.example.samdapp.domain.transcription.CapturedAudio
import com.example.samdapp.domain.transcription.TranscriptionService
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Sample rate the model was exported at. Not a knob: the encoder's feature extractor is built
 *  for 16 kHz and feeding it anything else produces confident wrong text, not an error. */
private const val SAMPLE_RATE_HZ = 16_000

/** Read granularity, 100 ms. Also the resolution of the trailing-silence decision below. */
private const val CHUNK_SAMPLES = SAMPLE_RATE_HZ / 10

// ── Capture-boundary calibration knobs ────────────────────────────────────────────────────────
// Parakeet is an offline (whole-utterance) recognizer, so unlike a streaming Zipformer it brings
// no endpoint detector of its own — the capture boundary is this file's job. These three values
// are the whole of it, and they are deliberately named rather than inlined because they must be
// tuned against real recordings of the intended speakers, not against a developer speaking
// fluently. Cutting a hesitant or elderly speaker off mid-sentence produces a truncated narrative
// that a worker may then confirm, which is the H-15 failure the confirmation gate exists to
// catch, only quieter. Tune up, never down, on doubt.

/** Trailing silence that ends a capture once speech has been heard. */
private const val TRAILING_SILENCE_MS = 1_500

/** Give up if the speaker never starts. Longer than [TRAILING_SILENCE_MS] because a worker who
 *  taps the mic and then thinks for a moment has not failed. */
private const val LEAD_IN_TIMEOUT_MS = 6_000

/** Hard ceiling on one capture, so a mic left open in a pocket cannot grow unboundedly. */
private const val MAX_CAPTURE_MS = 30_000

/** Mean-absolute-amplitude below which a 100 ms chunk counts as silence, on the [-1, 1] scale.
 *  ponytail: plain amplitude gate, not a VAD. sherpa-onnx ships a Silero VAD but it is a second
 *  model file, a second SOUP item and a second thing to pin, for a boundary decision the
 *  confirmation gate already lets the worker override. Upgrade to the VAD only if PHC background
 *  noise measurably defeats this. */
private const val SILENCE_AMPLITUDE = 0.012f

/** Threads for ONNX Runtime. 4 on the target Pixel; the encoder is the whole cost. */
private const val MODEL_THREADS = 4

/** Vendored under `app/src/main/assets/`. The directory name **is** the pinned `asrModelId`
 *  (`ConsultationViewModel`), and it is pinned per file by SHA-256 in the SBOM model companion
 *  under `docs/sbom/`. Frozen: it changes only by shipping a new APK. There is deliberately no
 *  download-on-first-use, no model CDN and no remote config selecting a model — any of those
 *  would make the weights post-deployment-updatable, which reopens both the off-device-egress
 *  question and the Algorithm Change Protocol gap (`docs/quality/qms-overview.md`). */
internal const val MODEL_ASSET_DIR = "asr/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8"

private val MODEL_ASSET_FILES = listOf(
    "encoder.int8.onnx",
    "decoder.int8.onnx",
    "joiner.int8.onnx",
    "tokens.txt",
)

/**
 * On-device speech-to-text via sherpa-onnx and a vendored NVIDIA Parakeet TDT 0.6B v2 (int8)
 * offline transducer. Replaces the platform-recognizer service, deleted in the same change.
 *
 * The point of the swap is architectural, not a feature: the platform recognizer bound to
 * whatever recognition service the device provided, never asked it to prefer offline, and could
 * therefore send patient narrative to a third-party processor with no data-processing agreement.
 * Recognition here runs in this process through JNI into the sherpa-onnx native library and ONNX
 * Runtime: no `RecognitionService` is bound, no `Intent` is broadcast, no other package is
 * involved, and no socket is opened. The weights are in the APK. The `INTERNET` permission stays
 * because the app syncs to its own backend, so permission removal is not available as evidence —
 * the evidence is the source-level absence of the platform-recognizer symbols and the runtime
 * egress assertion, both of which land with the flag flip, not here. The symbol names themselves
 * are spelled out nowhere in `app/src/main`, comments included, so that scan stays a plain grep.
 *
 * Contract preserved verbatim from the deleted class, so the (still dark) `TranscriptionScreen`
 * path keeps compiling and behaving: [captureAudioAttachment] mints a synthetic
 * `speech-session://<uuid>` key that points at nothing, holds the transcript in an in-memory map,
 * and **writes no audio file anywhere**. Nothing is retained past the process. The pre-existing
 * consequence — [transcribe] cannot answer after process death — carries over unchanged and is
 * not this change's to fix.
 *
 * Callers must hold `RECORD_AUDIO` before [captureAudioAttachment]; requesting that runtime
 * permission is a presentation-layer concern, not this service's.
 */
@Singleton
class SherpaOnnxTranscriptionService internal constructor(
    private val context: Context,
    /** Overridden only by the instrumented test that exercises the missing-asset edge. */
    private val modelAssetDir: String,
) : TranscriptionService {

    @Inject
    constructor(@ApplicationContext context: Context) : this(context, MODEL_ASSET_DIR)

    private val transcriptsByUri = ConcurrentHashMap<String, String>()

    private val recognizerLock = Mutex()

    @Volatile
    private var recognizer: OfflineRecognizer? = null

    override suspend fun captureAudioAttachment(): Result<CapturedAudio> = try {
        val samples = withContext(Dispatchers.IO) { record() }
        val transcript = transcribeSamples(samples).getOrThrow()
        val uri = "speech-session://${UUID.randomUUID()}"
        transcriptsByUri[uri] = transcript
        Result.success(CapturedAudio(uri = uri, transcript = transcript))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    override suspend fun transcribe(audioUri: String): Result<String> {
        val transcript = transcriptsByUri[audioUri]
            ?: return Result.failure(IllegalStateException("No captured transcription for $audioUri"))
        return Result.success(transcript)
    }

    /** Decodes one complete utterance. Separated from the microphone so the instrumented tests
     *  can feed a known clip through the real engine instead of faking one.
     *
     *  The two failure edges the consultation screen's honest-failure paths depend on:
     *  a model or engine fault comes back as [Result.failure], and audio with nothing decodable
     *  in it comes back as `Result.success("")` — never as an error, because "the recognizer
     *  heard nothing" and "the recognizer broke" are different facts and the audit breadcrumbs
     *  record them differently. */
    internal suspend fun transcribeSamples(samples: FloatArray): Result<String> = try {
        val engine = recognizer()
        val text = withContext(Dispatchers.Default) {
            val stream = engine.createStream()
            try {
                stream.acceptWaveform(samples, SAMPLE_RATE_HZ)
                engine.decode(stream)
                engine.getResult(stream).text
            } finally {
                stream.release()
            }
        }
        Result.success(text.trim())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    /** Built on first use, never at app start: in a build with the voice gate off the model must
     *  never be loaded at all, and loading ~630 MB of weights on every cold launch to serve one
     *  optional field on one screen is not a cost worth paying. [Mutex]-guarded so two concurrent
     *  captures cannot build two recognizers, and never on the main thread.
     *
     *  ponytail: once built it is retained for the process lifetime. The ceiling, named rather
     *  than hidden: the weights plus the ONNX Runtime arena stay resident from the first voice
     *  capture until the process dies. If that is measured to matter on the target device, the
     *  upgrade is to release the recognizer on `onTrimMemory` and pay a reload; that is not
     *  built here on speculation. */
    private suspend fun recognizer(): OfflineRecognizer =
        recognizer ?: recognizerLock.withLock {
            recognizer ?: withContext(Dispatchers.IO) { buildRecognizer() }.also { recognizer = it }
        }

    private fun buildRecognizer(): OfflineRecognizer {
        requireModelAssetsReadable()
        return OfflineRecognizer(
            assetManager = context.assets,
            config = OfflineRecognizerConfig(
                // featureDim is overwritten by sherpa-onnx from the encoder's own metadata for
                // NeMo transducers (offline-recognizer-transducer-nemo-impl.h), which is why the
                // model's 128 does not appear here. sampleRate is ours to get right.
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE_HZ),
                modelConfig = OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(
                        encoder = "$modelAssetDir/encoder.int8.onnx",
                        decoder = "$modelAssetDir/decoder.int8.onnx",
                        joiner = "$modelAssetDir/joiner.int8.onnx",
                    ),
                    tokens = "$modelAssetDir/tokens.txt",
                    // Stated rather than left to sherpa-onnx's auto-detection, which otherwise
                    // loads the 622 MiB encoder once just to read its model_type metadata.
                    modelType = "nemo_transducer",
                    numThreads = MODEL_THREADS,
                ),
                decodingMethod = "greedy_search",
            ),
        )
    }

    /** sherpa-onnx's native asset reader does not throw when a model file is missing: it logs and
     *  calls `_Exit(-1)` (`csrc/file-utils.cc` via the `SHERPA_ONNX_EXIT` macro), which kills the
     *  process outright — no exception, no [Result], no crash dialog. Opening each asset first
     *  turns a packaging fault into a [Result.failure] the screen can render honestly.
     *
     *  Known ceiling, stated because it must not be over-claimed: this catches a **missing or
     *  unreadable** asset, not a **present but corrupt** one. A truncated or mis-quantized model
     *  file still reaches ONNX Runtime and still ends in `_Exit`. The guard against that is the
     *  per-file SHA-256 pinned in the SBOM companion and asserted by an instrumented test on the
     *  device that loads it — not a CI gate, since the weights are stored local-only and are not
     *  in the tree CI checks out. */
    private fun requireModelAssetsReadable() {
        MODEL_ASSET_FILES.forEach { name ->
            val path = "$modelAssetDir/$name"
            try {
                context.assets.open(path).use { it.read() }
            } catch (e: Exception) {
                throw IllegalStateException("ASR model asset missing or unreadable: $path", e)
            }
        }
    }

    /** Captures one utterance from the microphone into memory. Ends on [TRAILING_SILENCE_MS] of
     *  quiet once the speaker has started, on [LEAD_IN_TIMEOUT_MS] if they never do, or on
     *  [MAX_CAPTURE_MS] regardless. Audio is returned to the caller and then dropped; it is never
     *  written to storage.
     *
     *  `AudioRecord.read` is a plain blocking call with no suspension point of its own, so a
     *  cancelled [viewModelScope]-scoped job cannot interrupt it mid-read. It reads in
     *  [CHUNK_SAMPLES] (100 ms) pieces specifically so [readUntilBoundary] can call
     *  [ensureActive] between reads: cancellation is noticed within one chunk rather than only
     *  after a capture boundary or [MAX_CAPTURE_MS] fires naturally. The recorder is stopped and
     *  released exactly once in `finally` on every exit path, cancellation included. */
    private suspend fun record(): FloatArray {
        val minBufferBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minBufferBytes > 0) {
            "This device cannot record 16 kHz mono PCM (AudioRecord reported $minBufferBytes)"
        }
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBufferBytes, CHUNK_SAMPLES * 2 * 4),
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            throw IllegalStateException(
                "Microphone unavailable — RECORD_AUDIO may not be granted, or the mic is in use",
            )
        }
        return try {
            recorder.startRecording()
            readUntilBoundary(recorder)
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
    }

    private suspend fun readUntilBoundary(recorder: AudioRecord): FloatArray {
        val captured = ArrayList<Float>(SAMPLE_RATE_HZ * 8)
        val chunk = ShortArray(CHUNK_SAMPLES)
        var elapsedMs = 0
        var heardSpeech = false
        var trailingSilenceMs = 0

        while (elapsedMs < MAX_CAPTURE_MS) {
            coroutineContext.ensureActive()

            val read = recorder.read(chunk, 0, chunk.size)
            if (read <= 0) throw IllegalStateException("Microphone read failed (code $read)")

            var sum = 0f
            for (i in 0 until read) {
                val sample = chunk[i] / 32768f
                captured.add(sample)
                sum += kotlin.math.abs(sample)
            }
            elapsedMs += read * 1_000 / SAMPLE_RATE_HZ

            if (sum / read >= SILENCE_AMPLITUDE) {
                heardSpeech = true
                trailingSilenceMs = 0
            } else {
                trailingSilenceMs += read * 1_000 / SAMPLE_RATE_HZ
                if (heardSpeech && trailingSilenceMs >= TRAILING_SILENCE_MS) break
                if (!heardSpeech && elapsedMs >= LEAD_IN_TIMEOUT_MS) break
            }
        }
        return FloatArray(captured.size) { captured[it] }
    }
}
