package com.example.samdapp.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Layer 2 of the three-layer egress proof (`scratchpad/pr4b-flag-flip-design-memo.md` A.2):
 * nothing that participates in recognition is constructed with, or can reach, a network client,
 * and the `TranscriptionService` seam has exactly one implementation across every flavor.
 *
 * Two assertions here, both static and both in CI. The third part of Layer 2 (reflection over the
 * class that actually ships) has to run on a device and lives in `AsrEgressTest`.
 *
 * **L2.1, transitive reachability.** Starting from the four files that make up the transcription
 * path, follow `import com.example.samdapp.` lines to the files they name, recursively, and assert
 * that nothing in that closure imports an HTTP client or an app-level remote service. Layer 1
 * covers the same patterns for the four entry files only; this covers everything they can reach.
 *
 * **L2.2, single binding.** Assert exactly one declaration in any `di/` module of any source set
 * binds or provides `TranscriptionService`, and that it names `SherpaOnnxTranscriptionService`.
 * This is the assertion that a flavored fallback which can transmit cannot be reintroduced
 * quietly. A fallback would make "audio never leaves the device" conditional on build
 * configuration, which is the property PR 4a deleted the platform recogniser to avoid; today that
 * reasoning lives only as a comment in `MockBoundaryModule`, and this is its executable form.
 *
 * **Known ceiling of the import walk, stated rather than hidden.** Kotlin needs no import for a
 * same-package reference, so a same-package type is invisible to an import walk. Two things bound
 * that: the transcription packages contain exactly two files between them (asserted below, so a
 * new neighbour is a deliberate decision rather than a silent escape), and L2.2 plus the
 * device-side reflection assert the constructed shape independently of imports. A full
 * type-resolution walk would mean a compiler plugin or a Konsist dependency, which is not worth it
 * for a four-file path; the upgrade path if the path grows is Konsist, not a bigger regex.
 *
 * **What Layer 2 proves:** by construction, at source level, the recognition path reaches no
 * network client, and the seam cannot be rebound per flavor.
 * **What it does NOT prove:** anything about the vendored sherpa-onnx AAR or the ONNX Runtime
 * native libraries inside it. No import walk can see into a `.so`. That distance is closed by
 * observation in Layer 3, not by construction.
 */
class TranscriptionPathHasNoNetworkDependencyTest {

    private val networkPatterns = listOf(
        "okhttp3",
        "retrofit2",
        "java.net.",
        "javax.net.",
        "android.net.",
        "com.example.samdapp.data.remote",
    )

    @Test
    fun noNetworkTypeIsReachableFromTheTranscriptionPath() {
        val hits = reachableFiles().flatMap { file ->
            file.readLines().withIndex()
                .filter { (_, line) -> line.trimStart().startsWith("import ") }
                .flatMap { (index, line) ->
                    networkPatterns.filter { it in line }.map { pattern ->
                        "${file.path}:${index + 1}: matched '$pattern': ${line.trim()}"
                    }
                }
        }

        assertTrue(
            "A network client is reachable from the transcription path. Audio and transcripts " +
                "must not be able to reach one. Found ${hits.size} import(s):\n" +
                hits.joinToString("\n"),
            hits.isEmpty(),
        )
    }

    /**
     * Self-check for the assertion above. An import walk that resolves nothing still reports zero
     * network imports, which reads exactly like a pass. Asserts the closure is strictly larger
     * than the entry set, so a resolution failure surfaces as a red test rather than as false
     * reassurance.
     */
    @Test
    fun theImportWalkReachesBeyondItsEntryFiles() {
        val reached = reachableFiles()

        assertTrue(
            "The import walk reached ${reached.size} file(s) from ${ENTRY_FILES.size} entry " +
                "files, so it resolved nothing and the reachability assertion proves nothing. " +
                "Reached:\n" + reached.joinToString("\n") { it.path },
            reached.size > ENTRY_FILES.size,
        )
    }

    @Test
    fun exactlyOneBindingProvidesTheTranscriptionServiceSeam() {
        val declarations = diModuleSources().flatMap { file ->
            file.readLines().withIndex()
                .filter { (_, line) -> DECLARES_SEAM.containsMatchIn(line) }
                .map { (index, line) -> "${file.path}:${index + 1}: ${line.trim()}" }
        }

        assertEquals(
            "Expected exactly one declaration binding or providing TranscriptionService, across " +
                "every flavor and source set. More than one means the engine can differ by build " +
                "configuration, which makes 'audio never leaves the device' conditional. Found:\n" +
                declarations.joinToString("\n"),
            1,
            declarations.size,
        )
        assertTrue(
            "The single TranscriptionService binding must name SherpaOnnxTranscriptionService, " +
                "the on-device engine. Found:\n" + declarations.first(),
            "SherpaOnnxTranscriptionService" in declarations.first(),
        )
    }

    /** Guards the import walk's known ceiling: same-package references need no import, so a new
     *  file dropped next to the service or the seam would be reachable without ever appearing in
     *  the closure. Two files, both entry points, is the state this test was written against. */
    @Test
    fun theTranscriptionPackagesHoldOnlyTheFilesThisTestCovers() {
        val packageFiles = listOf(
            "java/com/example/samdapp/data/transcription",
            "java/com/example/samdapp/domain/transcription",
        ).flatMap { File(MAIN_SOURCES, it).walkTopDown().filter { f -> f.isFile && f.extension == "kt" } }

        assertEquals(
            "A new file appeared in the transcription packages. Same-package references need no " +
                "import, so the walk cannot see it. Add it to ENTRY_FILES. Found:\n" +
                packageFiles.joinToString("\n") { it.path },
            2,
            packageFiles.size,
        )
    }

    /** Breadth-first over `import com.example.samdapp.` lines, resolved against an index of every
     *  top-level declaration in the module. Resolution by index rather than by path because a file
     *  may declare more than one type (`CapturedAudio` lives in `TranscriptionService.kt`), and a
     *  path-only resolver would silently miss exactly those. */
    private fun reachableFiles(): List<File> {
        val index = topLevelDeclarationIndex()
        val visited = LinkedHashSet<File>()
        val queue = ArrayDeque(ENTRY_FILES.map { File(MAIN_SOURCES, it) })

        queue.forEach {
            assertTrue("Transcription-path entry file has moved or been renamed: ${it.absolutePath}", it.isFile)
        }

        while (queue.isNotEmpty()) {
            val file = queue.removeFirst()
            if (!visited.add(file)) continue
            file.readLines()
                .mapNotNull { line -> IMPORTED_APP_TYPE.find(line.trimStart())?.groupValues?.get(1) }
                .mapNotNull { resolve(it, index) }
                .filterNot { it in visited }
                .forEach { queue.addLast(it) }
        }
        return visited.toList()
    }

    /** Drops trailing segments so a nested class or a member import
     *  (`...AuditAction.VOICE_FIELD_SUGGESTED`) resolves to the file declaring its outer name. */
    private fun resolve(fqName: String, index: Map<String, File>): File? {
        var candidate = fqName
        repeat(3) {
            index[candidate]?.let { return it }
            if (!candidate.contains('.')) return null
            candidate = candidate.substringBeforeLast('.')
        }
        return null
    }

    private fun topLevelDeclarationIndex(): Map<String, File> {
        val index = HashMap<String, File>()
        MAIN_SOURCES.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val lines = file.readLines()
            val pkg = lines.firstNotNullOfOrNull { PACKAGE.find(it)?.groupValues?.get(1) } ?: return@forEach
            lines.forEach { line ->
                TOP_LEVEL_DECLARATION.find(line)?.groupValues?.get(1)?.let { name ->
                    index.putIfAbsent("$pkg.$name", file)
                }
            }
        }
        return index
    }

    private fun diModuleSources(): List<File> =
        SOURCE_SETS_ROOT.listFiles().orEmpty()
            .filter { it.isDirectory }
            .flatMap { sourceSet ->
                File(sourceSet, "java").walkTopDown()
                    .filter { it.isFile && it.extension == "kt" && it.parentFile.name == "di" }
            }
            .sortedBy { it.path }

    private companion object {
        /** The transcription path: the engine, the seam, both use cases, and the one ViewModel
         *  that calls them. `TranscribeAudioUseCase.kt` also declares
         *  `CaptureAudioAttachmentUseCase`. */
        val ENTRY_FILES = listOf(
            "java/com/example/samdapp/data/transcription/SherpaOnnxTranscriptionService.kt",
            "java/com/example/samdapp/domain/transcription/TranscriptionService.kt",
            "java/com/example/samdapp/domain/usecase/TranscribeAudioUseCase.kt",
            "java/com/example/samdapp/presentation/consultation/ConsultationViewModel.kt",
        )

        /** See `NoPlatformRecognizerSourceScanTest`, which asserts this resolves to a real tree. */
        val MAIN_SOURCES: File = listOf(File("src/main"), File("app/src/main"))
            .firstOrNull { it.isDirectory } ?: File("src/main")

        val SOURCE_SETS_ROOT: File = listOf(File("src"), File("app/src"))
            .firstOrNull { it.isDirectory } ?: File("src")

        val PACKAGE = Regex("""^package\s+([\w.]+)""")

        val IMPORTED_APP_TYPE = Regex("""^import\s+(com\.example\.samdapp\.[\w.]+)""")

        /** Top-level only: a declaration indented by even one space belongs to something else. */
        val TOP_LEVEL_DECLARATION = Regex(
            """^(?:@[\w.]+(?:\([^)]*\))?\s+)*""" +
                """(?:(?:public|internal|private|abstract|open|sealed|data|value|enum|annotation|inline|expect|actual|const|external|suspend)\s+)*""" +
                """(?:class|interface|object|fun|val|var|typealias)\s+([A-Za-z_]\w*)""",
        )

        /** `@Binds`/`@Provides` declarations are ordinary functions returning the seam type.
         *  Deliberately not comment-stripped, same reasoning as Layer 1: a commented-out second
         *  binding trips this too, and a false red here gets investigated rather than tolerated. */
        val DECLARES_SEAM = Regex("""fun\s+\w+\s*\([^)]*\)\s*:\s*TranscriptionService\b""")
    }
}
