package com.example.samdapp.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `docs/design/slm-guardrail-service-contract-memo.md` §9.1: "It must be impossible to reach
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
 * **What it does not prove:** nothing about reflection. The property being defended is that
 * generation is reachable only through `SlmReadbackUseCase`, where the scope gates are.
 *
 * **PR-6 added the two files this test's previous KDoc said were expected**, the data-layer binding
 * and the Hilt module that provides it, and the allowlist below grew from two names to four. That
 * growth is the test working: a fourth name appearing was a deliberate edit here, in the same
 * commit, rather than something that happened quietly.
 *
 * The scan is a substring match, so `RemoteSlmEngine` and `SlmEngineIsUnreachableFromPresentationTest`
 * both contain `SlmEngine`. The DTO and the Retrofit service are deliberately written without
 * naming either, which keeps the list at the files that actually hold or implement the type.
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
            "A new file names SlmEngine. The engine is declared in SlmEngine.kt, held by the seam " +
                "in SlmReadbackUseCase.kt, implemented once in RemoteSlmEngine.kt and bound once " +
                "in SlmNetworkModule.kt. A fifth name is a deliberate edit here; if it is a " +
                "ViewModel, memo section 9.1 says no.",
            listOf(
                "RemoteSlmEngine.kt",
                "SlmEngine.kt",
                "SlmNetworkModule.kt",
                "SlmReadbackUseCase.kt",
            ),
            files,
        )
    }

    /**
     * **Step 3's claim, as an assertion rather than a sentence in a memo.** The engine binds to the
     * same remote implementation in dev, staging and prod, because that is the shipping
     * architecture, and **no flavor source set contains an `SlmEngine` implementation of any kind**.
     *
     * The hazard is specific and it is this project's recurring one. `MockBoundaryModule` documents
     * the opposite posture for `VitalsSource` and `KernelFallbackSource`: those bind mock clinical
     * data in `src/dev/` and honest-unavailable implementations in `src/staging/` and `src/prod/`,
     * precisely so fabricated clinical values cannot be reached outside dev. A stub readback engine
     * would be the same hazard in the same clothes, except worse, because the thing it fabricates is
     * clinical narrative that reads exactly like a real answer and that satisfies the identity gate
     * by construction, answering with whatever pin it was compiled against. H-09 and H-13 are both
     * in the register for this shape.
     *
     * Scanning the three flavor trees rather than asserting the binding's location is deliberate: a
     * test that only checked that `src/main/` binds the engine would pass while a flavor bound a
     * second one over the top of it.
     */
    @Test
    fun noFlavorSourceSetImplementsOrBindsTheEngine() {
        val hits = FLAVORS.filter { it.isDirectory }
            .flatMap { flavor -> scan(kotlinSourcesUnder(flavor), listOf("SlmEngine")) }

        assertTrue(
            "A flavor source set names SlmEngine. The engine binds to RemoteSlmEngine in " +
                "src/main/ for every flavor, and a flavor-local implementation or binding would be " +
                "a fabricated clinical narrative reachable in whichever builds ship that flavor " +
                "(H-09, H-13). Found:\n" + hits.joinToString("\n"),
            hits.isEmpty(),
        )
    }

    /**
     * Non-vacuity for the flavor scan, which would otherwise pass by finding no directories at all.
     * The three flavor trees exist and hold real Kotlin, MEASURED here rather than assumed, and the
     * positive control proves the scanner would have found a hit in them if one were there.
     */
    @Test
    fun theFlavorScanRootsResolveAndHoldSources() {
        val missing = FLAVORS.filterNot { it.isDirectory }
        assertTrue(
            "Flavor source roots are missing, so the flavor scan proves nothing: " +
                missing.joinToString { it.path },
            missing.isEmpty(),
        )
        FLAVORS.forEach { flavor ->
            val count = kotlinSourcesUnder(flavor).size
            assertTrue("Scanned only $count Kotlin files under ${flavor.path}.", count > 0)
        }
        // Positive control: the scanner finds a term that IS present in every flavor tree, so a
        // clean SlmEngine result is a real absence rather than a scanner that reads nothing.
        FLAVORS.forEach { flavor ->
            assertTrue(
                "Positive control failed: no file under ${flavor.path} names 'Module', so the " +
                    "scanner is not reading these trees and the flavor assertion proves nothing.",
                scan(kotlinSourcesUnder(flavor), listOf("Module")).isNotEmpty(),
            )
        }
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

        /** Sibling of [MAIN_SOURCES]: `src/main` resolves, so `src/dev` and its siblings do too. */
        val FLAVORS: List<File> =
            listOf("dev", "staging", "prod").map { File(MAIN_SOURCES.parentFile, it) }
    }
}
