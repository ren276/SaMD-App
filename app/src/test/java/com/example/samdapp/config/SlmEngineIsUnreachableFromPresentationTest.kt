package com.example.samdapp.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `scratchpad/slm-guardrail-service-contract-memo.md` §9.1: "It must be impossible to reach
 * [the engine] from a ViewModel: no injection of the engine into presentation, ever."
 *
 * Kotlin cannot express that with a visibility modifier. `internal` is module-scoped and
 * `presentation/` lives in the same module as `domain/`, so `internal` on `SlmEngine` would stop
 * nothing. A source scan is the enforceable form of the rule, and this project already uses one
 * for a claim of the same shape (`NoPlatformRecognizerSourceScanTest`, the layer-1 egress proof).
 *
 * **What this proves:** no file under `presentation/` names the engine interface, and the only
 * files in the shipped module that name it are the interface itself and the guardrail seam that
 * owns it. A ViewModel therefore cannot inject it, construct it, or hold it, because it cannot
 * name it.
 *
 * **What it does not prove:** anything about a later stage's Hilt binding in the data layer (which
 * is expected to appear and is expected to update the allowlist below), and nothing about
 * reflection. The property being defended is that generation is reachable only through
 * `SlmReadbackUseCase`, where the scope gates are.
 */
class SlmEngineIsUnreachableFromPresentationTest {

    @Test
    fun noPresentationSourceNamesTheEngineInterface() {
        val hits = scan(kotlinSourcesUnder(PRESENTATION), listOf("SlmEngine"))

        assertTrue(
            "The SLM engine must be unreachable from presentation (memo section 9.1): every call " +
                "goes through SlmReadbackUseCase, which is where the scope gates are. " +
                "Found ${hits.size} reference(s):\n" + hits.joinToString("\n"),
            hits.isEmpty(),
        )
    }

    /**
     * The stronger statement: across the whole shipped module, the engine is named only where it
     * is declared and where the seam holds it. Adding a third file is a deliberate act that
     * updates this list, not something that happens quietly in a ViewModel.
     */
    @Test
    fun onlyTheSeamAndTheInterfaceItselfNameTheEngine() {
        val files = kotlinSourcesUnder(MAIN_SOURCES)
            .filter { it.readText().contains("SlmEngine") }
            .map { it.name }
            .sorted()

        assertEquals(
            "A new file names SlmEngine. If a later stage is binding the engine in the data layer, " +
                "add it here deliberately; if it is a ViewModel, memo section 9.1 says no.",
            listOf("SlmEngine.kt", "SlmReadbackUseCase.kt"),
            files,
        )
    }

    /**
     * Non-vacuity guards. A source-scanning test's characteristic failure is passing while
     * scanning nothing, which turns every assertion above into "no hits in the empty set".
     * (a) the roots resolve, (b) the file count is above a floor well below the real size, and
     * (c) the positive control proves the scanner reads contents rather than returning blanks.
     */
    @Test
    fun theScanRootsResolveAndTheScannerReadsContents() {
        assertTrue(
            "Scan root is not a directory: ${PRESENTATION.absolutePath}. Unit tests run with the " +
                "module directory as the working directory; if that changed, fix the resolution here.",
            PRESENTATION.isDirectory,
        )
        val count = kotlinSourcesUnder(PRESENTATION).size
        assertTrue("Scanned only $count Kotlin files under presentation; the scan is not reaching the sources.", count > 40)

        val sentinel = scan(listOf(File(MAIN_SOURCES, SEAM)), listOf("SlmEngine"))
        assertTrue(
            "Positive control failed: 'SlmEngine' was not found in $SEAM, so the scanner is not " +
                "reading file contents and every other assertion here proves nothing.",
            sentinel.isNotEmpty(),
        )
    }

    private fun kotlinSourcesUnder(root: File): List<File> =
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun scan(files: List<File>, patterns: List<String>): List<String> =
        files.flatMap { file ->
            file.readLines().withIndex().flatMap { (index, line) ->
                patterns.filter { it in line }.map { pattern -> "${file.path}:${index + 1}: $pattern" }
            }
        }

    private companion object {
        val MAIN_SOURCES: File = listOf(File("src/main"), File("app/src/main"))
            .firstOrNull { it.isDirectory } ?: File("src/main")

        val PRESENTATION = File(MAIN_SOURCES, "java/com/example/samdapp/presentation")

        const val SEAM = "java/com/example/samdapp/domain/slm/SlmReadbackUseCase.kt"
    }
}
