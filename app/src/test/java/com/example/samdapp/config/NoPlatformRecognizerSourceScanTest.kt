package com.example.samdapp.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Layer 1 of the three-layer egress proof (`scratchpad/pr4b-flag-flip-design-memo.md` A.1): the
 * shipped source tree contains no reference to the platform speech recognition API, and no file
 * on the transcription path imports an HTTP client.
 *
 * This is the standing regression gate, not a one-off audit. PR 4a deleted
 * `AndroidSpeechRecognizerService` and proved the symbols were gone by hand; this test fails the
 * build the day somebody reintroduces a platform-recognizer fallback or wires a network client
 * into the ASR path. That is the whole reason it exists: "no off-device ASR path remains" is
 * otherwise a claim about a moment in time.
 *
 * **Plain substring scan, deliberately not comment-stripped.** 4a reworded every KDoc in
 * `FeatureFlags`, `PermissionAction`, `TranscriptionService` and `ConsultationViewModel` so that
 * none of these symbols survives anywhere in `app/src/main`, comments included. Stripping
 * comments would mean carrying a Kotlin comment parser (or a regex approximation of one, which is
 * worse) in order to make a check *weaker* than the one that already passes. If a future change
 * genuinely needs to name one of these symbols in prose, the right outcome is this test going red
 * and that conversation happening, not a pre-weakened scan.
 *
 * **Scope is `app/src/main` only, not `app/src`.** The property that matters is what the shipped
 * app can reach. `app/src/test` currently holds one KDoc line naming the deleted
 * `SpeechRecognizer` while describing why a test fake exists, which is history rather than a
 * reachable code path.
 *
 * **What this layer proves:** no source-level reference to the platform recognizer API anywhere in
 * the shipped module, and no HTTP-client import on the four files of the transcription path.
 * **What it does NOT prove:** anything about the compiled artifact, the vendored sherpa-onnx AAR,
 * the ONNX Runtime native libraries inside it, or indirect reach through an app class that itself
 * holds a network client. Those are Layers 2 and 3.
 */
class NoPlatformRecognizerSourceScanTest {

    /** Every symbol the deleted `AndroidSpeechRecognizerService` used to reach the platform
     *  recogniser through, plus the package itself so a fresh import cannot slip past the
     *  class-name patterns. */
    private val platformRecognizerPatterns = listOf(
        "createSpeechRecognizer",
        "RecognizerIntent",
        "SpeechRecognizer",
        "isRecognitionAvailable",
        "android.speech",
    )

    /** Anything that could carry bytes off the device. `com.example.samdapp.data.remote` is in the
     *  list because an app-level API service is the likeliest accidental reintroduction, not a raw
     *  socket. */
    private val networkClientPatterns = listOf(
        "okhttp3",
        "retrofit2",
        "java.net.",
        "javax.net.",
        "HttpURLConnection",
        "java.net.Socket",
        "com.example.samdapp.data.remote",
    )

    @Test
    fun noPlatformRecognizerSymbolAppearsAnywhereInMainSources() {
        val hits = scan(kotlinSourcesUnderMain(), platformRecognizerPatterns)

        assertTrue(
            "The platform speech recogniser was deleted in PR 4a and must stay deleted. " +
                "Found ${hits.size} reference(s) in app/src/main:\n" + hits.joinToString("\n"),
            hits.isEmpty(),
        )
    }

    @Test
    fun noNetworkClientAppearsOnTheTranscriptionPath() {
        val hits = scan(transcriptionPathSources(), networkClientPatterns)

        assertTrue(
            "Nothing on the transcription path may import a network client. " +
                "Found ${hits.size} reference(s):\n" + hits.joinToString("\n"),
            hits.isEmpty(),
        )
    }

    /**
     * Non-vacuity guard (a) and (b). A source-scanning test's characteristic failure is passing
     * while scanning nothing: a wrong working directory turns every assertion above into
     * "no hits in the empty set". Asserts the root resolves to a real directory, names the
     * resolved absolute path in the failure message so the fix is obvious, and asserts the file
     * count is above a floor well below the module's real size (304 at the time of writing).
     */
    @Test
    fun theScanRootResolvesToTheWholeModule() {
        assertTrue(
            "Scan root is not a directory: ${MAIN_SOURCES.absolutePath}. " +
                "Unit tests run with the module directory as the working directory; " +
                "if that changed, fix the resolution in this test rather than deleting it.",
            MAIN_SOURCES.isDirectory,
        )

        val count = kotlinSourcesUnderMain().size
        assertTrue(
            "Scanned only $count Kotlin files under ${MAIN_SOURCES.absolutePath}, which is far " +
                "below the module's real size. The scan is not reaching the sources.",
            count > 150,
        )
    }

    /**
     * Non-vacuity guard (c), the positive control. The two guards above prove the scanner found
     * files; this proves it is reading their contents. If it fails, the scanner is returning empty
     * strings and every negative result in this class is meaningless rather than reassuring.
     */
    @Test
    fun theScannerActuallyReadsFileContents() {
        val sentinelHits = scan(
            listOf(File(MAIN_SOURCES, TRANSCRIPTION_SERVICE_IMPL)),
            listOf("OfflineRecognizer"),
        )

        assertTrue(
            "Positive control failed: 'OfflineRecognizer' was not found in " +
                "$TRANSCRIPTION_SERVICE_IMPL. The scanner is not reading file contents, so every " +
                "other assertion in this class proves nothing.",
            sentinelHits.isNotEmpty(),
        )
    }

    /** The transcription path is four files. Asserted rather than globbed so that a new file
     *  appearing in these packages is a deliberate decision that updates this list, not something
     *  that silently escapes the network scan. */
    private fun transcriptionPathSources(): List<File> {
        val files = listOf(
            TRANSCRIPTION_SERVICE_IMPL,
            "java/com/example/samdapp/domain/transcription/TranscriptionService.kt",
            "java/com/example/samdapp/domain/usecase/TranscribeAudioUseCase.kt",
        ).map { File(MAIN_SOURCES, it) }

        files.forEach {
            assertTrue("Transcription-path file has moved or been renamed: ${it.absolutePath}", it.isFile)
        }
        val packageFiles = listOf("java/com/example/samdapp/data/transcription", "java/com/example/samdapp/domain/transcription")
            .flatMap { File(MAIN_SOURCES, it).walkTopDown().filter { f -> f.isFile && f.extension == "kt" } }
        assertEquals(
            "The transcription packages hold files this scan does not cover. Add them to the list.",
            2,
            packageFiles.size,
        )
        return files
    }

    private fun kotlinSourcesUnderMain(): List<File> =
        MAIN_SOURCES.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun scan(files: List<File>, patterns: List<String>): List<String> =
        files.flatMap { file ->
            file.readLines().withIndex().flatMap { (index, line) ->
                patterns.filter { it in line }.map { pattern ->
                    "${file.path}:${index + 1}: matched '$pattern': ${line.trim()}"
                }
            }
        }

    private companion object {
        const val TRANSCRIPTION_SERVICE_IMPL =
            "java/com/example/samdapp/data/transcription/SherpaOnnxTranscriptionService.kt"

        /** Gradle runs unit tests with the module directory (`app/`) as the working directory.
         *  The repository-root fallback keeps the test honest under an IDE runner that chooses
         *  differently, and [theScanRootResolvesToTheWholeModule] fails loudly with the resolved
         *  path if neither exists rather than passing on an empty scan. */
        val MAIN_SOURCES: File = listOf(File("src/main"), File("app/src/main"))
            .firstOrNull { it.isDirectory } ?: File("src/main")
    }
}
