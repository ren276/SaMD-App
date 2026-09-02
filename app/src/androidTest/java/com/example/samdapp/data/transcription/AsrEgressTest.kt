package com.example.samdapp.data.transcription

import android.net.TrafficStats
import android.os.Process
import android.os.StrictMode
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.lang.reflect.Modifier

/**
 * Layers 2c and 3 of the three-layer egress proof
 * (`scratchpad/pr4b-flag-flip-design-memo.md` A.2/A.3). Layers 1 and 2 establish by construction,
 * in CI, that our own code holds no path off the device. This class runs the real engine against
 * the real vendored weights on a real device and witnesses the property, which is the only
 * statement available about the vendored native code.
 *
 * **Device-local, never CI.** The weights are gitignored (622 MiB encoder, over GitHub's per-file
 * limit), so the tree CI checks out cannot build an APK containing the model, let alone decode
 * with it. That is a known consequence of the storage decision recorded in PR 4a, not a new gap.
 *
 * **What this class proves:** on this device, this build, this run, a complete transcription
 * happened, the app's UID moved zero bytes across it, no Java-layer socket was opened, and no file
 * appeared under `filesDir` or `cacheDir` (the snapshot in [storageSnapshot]; it does not cover
 * `noBackupFilesDir`, `codeCacheDir` or external storage).
 * **What it does NOT prove:** it is an observation over one execution with one input, not a proof
 * over all executions. It cannot rule out a dormant network path in sherpa-onnx or ONNX Runtime
 * that different input or a different build would activate; the controls for that are the exact
 * version pins, the per-file SHA-256 in the SOUP companion, and the absence of any
 * download-on-first-use, model CDN or remote model selection. It also runs on `x86_64`
 * (emulator-5554) while the shipped ABI is `arm64-v8a`, so the binary exercised here is not the
 * binary that ships. Closing that gap is the operator's airplane-mode run on the physical Pixel,
 * which is required before any APK leaves the machine and is recorded as a pre-distribution gate.
 */
@RunWith(AndroidJUnit4::class)
class AsrEgressTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * L2.3, the compiled-artifact half of Layer 2. Layers 1 and 2 read source; this reads the
     * class that actually ships, so a dependency introduced by a code generator, a synthetic
     * field or a build-time rewrite would still be caught.
     *
     * **Honest ceiling, stated because it must not be over-claimed:** `android.content.Context` is
     * in the allowed set and a `Context` can reach the network. This proves *no network client and
     * no app-level remote service is injected into the service*, which is not the same as *this
     * service cannot open a socket*. Recursing deeper is not a fix: the walk would terminate in
     * the Android framework, where everything reaches everything. That remaining distance is
     * closed by observation in [aFullTranscriptionCompletesWithNoNetworkEgress], not by
     * construction.
     */
    @Test
    fun theServiceIsConstructedWithNoNetworkDependency() {
        // Instance fields only. Static fields are compiler-generated, never injected: the Compose
        // compiler adds a `$stable` int to every class it sees, and a dependency arrives through
        // the constructor and lands on an instance field. Filtering statics keeps this assertion
        // about dependencies rather than about which compiler plugins are on the classpath.
        val fieldTypes = SherpaOnnxTranscriptionService::class.java.declaredFields
            .filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) }
            .associate { it.name to it.type.name }
        val constructorTypes = SherpaOnnxTranscriptionService::class.java.declaredConstructors
            .flatMap { it.parameterTypes.asList() }
            .map { it.name }

        val unexpected = fieldTypes.filterValues { it !in ALLOWED_TYPES }
        assertTrue(
            "The ASR service gained a dependency this test does not know about. Every field on " +
                "the recognition path must be justified, because an injected client is how a " +
                "transmission path returns. Unexpected: $unexpected",
            unexpected.isEmpty(),
        )

        val constructorOutliers = constructorTypes.filterNot { it in ALLOWED_TYPES }
        assertTrue(
            "The ASR service is constructed with a type this test does not know about: " +
                "$constructorOutliers",
            constructorOutliers.isEmpty(),
        )

        val networkTypes = (fieldTypes.values + constructorTypes)
            .filter { type -> NETWORK_PACKAGES.any { type.startsWith(it) } }
        assertTrue(
            "A network type is injected into the ASR service: $networkTypes",
            networkTypes.isEmpty(),
        )
    }

    /**
     * L3.1 plus L3.2 plus L3.3, in one test because they are one claim: **a real transcription
     * completes and no bytes leave the device while it does.**
     *
     * The positive half is not decoration. Without it the whole thing degenerates into "nothing
     * happened, therefore nothing leaked", which is the classic vacuous egress test: a decode that
     * silently failed would report zero traffic and pass.
     *
     * **L3.2, per-UID byte accounting, is the load-bearing observation.** `TrafficStats` counters
     * are maintained by the kernel per UID, so a socket opened directly from JNI or from ONNX
     * Runtime through libc is counted, where a Java-layer instrumentation hook would miss it
     * entirely. Its three ceilings: the counters are per-UID, so any other component of this
     * process transmitting during the window pollutes the measurement (nothing else is started
     * here, and this test must not be given work that talks to the network); they are totals, so a
     * delta of exactly 0 is a strong signal while a small non-zero delta is ambiguous and must be
     * investigated rather than tolerated (do not weaken this to a threshold, because a few hundred
     * bytes is precisely the shape an exfiltrated transcript would have); and they carry no call
     * stack, so a failure says "something in this UID transmitted", not "the ASR path transmitted".
     *
     * **L3.3, StrictMode, is deliberately the weaker of the two and is included anyway.** Network
     * detection is implemented through `BlockGuard`, which instruments the Java/libcore socket
     * layer, so native `socket()` does not trip it: it is strictly weaker than L3.2 for exactly
     * the vendored native library we most want covered, and stronger for our own Kotlin, which
     * Layers 1 and 2 already cover. The thread policy covers this thread; the decode itself hops
     * to `Dispatchers.Default` inside the service, which is why the process-wide VM policy is
     * installed alongside it rather than relying on the thread policy alone. Both are restored in
     * `finally`. Two observations that fail in different ways are the point.
     */
    @Test
    fun aFullTranscriptionCompletesWithNoNetworkEgress() {
        val samples = readPcm16Wav("asr-test/known-clip.wav")
        val priorThreadPolicy = StrictMode.getThreadPolicy()
        val priorVmPolicy = StrictMode.getVmPolicy()

        val text: String
        val txDelta: Long
        val rxDelta: Long
        val decodeMs: Long
        try {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder().detectNetwork().penaltyDeath().build(),
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder().detectUntaggedSockets().penaltyDeath().build(),
            )

            val txBefore = TrafficStats.getUidTxBytes(Process.myUid())
            val rxBefore = TrafficStats.getUidRxBytes(Process.myUid())
            assertTrue(
                "TrafficStats returned UNSUPPORTED for this UID, so the egress observation cannot " +
                    "be made on this device. Do not read this as a pass: run it somewhere the " +
                    "counters work.",
                txBefore >= 0 && rxBefore >= 0,
            )

            val startedAt = System.nanoTime()
            text = runBlocking { sharedAsrService.transcribeSamples(samples) }.getOrThrow()
            decodeMs = (System.nanoTime() - startedAt) / 1_000_000

            txDelta = TrafficStats.getUidTxBytes(Process.myUid()) - txBefore
            rxDelta = TrafficStats.getUidRxBytes(Process.myUid()) - rxBefore
        } finally {
            StrictMode.setThreadPolicy(priorThreadPolicy)
            StrictMode.setVmPolicy(priorVmPolicy)
        }

        // Reported, not asserted. There is no agreed latency threshold, and the byte deltas are
        // the evidence a reviewer needs to see rather than infer from a green tick. A decode that
        // includes the lazy 622 MiB model load is the cold number the flag flip pays on first tap.
        Log.i(TAG, "decode=${decodeMs}ms txDelta=${txDelta}B rxDelta=${rxDelta}B")

        val lowered = text.lowercase()
        assertTrue("transcript was blank, so nothing was actually decoded: '$text'", lowered.isNotBlank())
        listOf("wish", "see", "observed", "portrait").forEach { word ->
            assertTrue("expected content word '$word' in: '$lowered'", word in lowered)
        }

        assertEquals(
            "The app's UID transmitted $txDelta byte(s) during an on-device transcription. " +
                "Nothing on this path may send anything. Investigate; do not raise a threshold.",
            0L,
            txDelta,
        )
        assertEquals(
            "The app's UID received $rxDelta byte(s) during an on-device transcription. " +
                "Investigate; do not raise a threshold.",
            0L,
            rxDelta,
        )
    }

    /**
     * L3.4. `SherpaOnnxTranscriptionService`'s contract says it writes no audio file anywhere;
     * this asserts it rather than trusting the KDoc. Egress to disk is not egress off device, but
     * an audio file that exists is an audio file the sync outbox or a platform backup agent could
     * later move, and this is the only place that property is checked.
     *
     * Exercises the microphone path, not just the decoder, because `record()` is where a file
     * would be written if one ever were. On an emulator the mic yields silence and the capture
     * ends at the lead-in timeout, which is the honest-failure edge, so the result is asserted to
     * be a success carrying nothing rather than an error.
     */
    @Test
    fun aCaptureAndDecodeWritesNoFileToAppStorage() {
        val before = storageSnapshot()

        val startedAt = System.nanoTime()
        val captured = runBlocking { sharedAsrService.captureAudioAttachment() }
        runBlocking { sharedAsrService.transcribeSamples(readPcm16Wav("asr-test/known-clip.wav")) }
        Log.i(TAG, "capture+decode=${(System.nanoTime() - startedAt) / 1_000_000}ms")

        assertTrue(
            "capture must not surface as an engine error: ${captured.exceptionOrNull()?.message}",
            captured.isSuccess,
        )
        assertEquals(
            "Capture and decode wrote to app storage. Audio is held in memory and dropped; " +
                "nothing on this path may leave a file behind.",
            before,
            storageSnapshot(),
        )
    }

    private fun storageSnapshot(): Set<String> =
        listOf(context.filesDir, context.cacheDir)
            .flatMap { root -> root.walkTopDown().filter(File::isFile).map { it.absolutePath } }
            .toSet()

    private companion object {
        const val TAG = "AsrEgressTest"

        val ALLOWED_TYPES = setOf(
            "android.content.Context",
            "java.lang.String",
            "java.util.concurrent.ConcurrentHashMap",
            "kotlinx.coroutines.sync.Mutex",
            "com.k2fsa.sherpa.onnx.OfflineRecognizer",
        )

        val NETWORK_PACKAGES = listOf(
            "okhttp3",
            "retrofit2",
            "java.net",
            "javax.net",
            "com.example.samdapp.data.remote",
        )
    }
}
