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
     * **Step 3's claim, narrowed for the dev on-device engine (on-device SLM memo 4.1, F-11), and
     * once more for ruling R-DEV (memo 16).** [BINDING_MODULE] binds the engine for every flavor:
     * the remote implementation, unless the flavor provides a `@DevReadbackOverride`. Exactly one
     * flavor file may implement the interface, the dev `LocalSlmEngine.kt`, and exactly one flavor
     * line may provide it, [DEV_ENGINE_BINDING] in [DEV_HOLDER].
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
     * Three rules, each with a positive control below:
     * 1. In `src/dev`, the files naming the interface as a whole word are [DEV_ENGINE] and
     *    [DEV_HOLDER]. Naming is the proxy for implementing: a class cannot implement a type it does
     *    not name. Rule 2 pins what the holder may say.
     * 2. In every shipped source set, no Hilt module but [BINDING_MODULE] names the interface as a
     *    whole word, so nothing else can `@Binds` or `@Provides` it. The one exception is
     *    [DEV_HOLDER], and only while its code names the interface on exactly one line,
     *    [DEV_ENGINE_BINDING], under `@Provides @DevReadbackOverride`.
     * 3. `src/staging` and `src/prod` never name it at all, not even as a substring.
     *
     * Scanning the trees rather than asserting the binding's location is deliberate: a test that
     * only checked that `src/main/` binds the engine would pass while a flavor bound a second one
     * over the top of it. The whole `src` tree is a declared input of the unit Test tasks.
     */
    @Test
    fun onlyTheDevLocalEngineImplementsTheEngineInAFlavor() {
        assertEquals(
            "Exactly one flavor file may implement SlmEngine: $DEV_ENGINE (memo 4.1); only $DEV_HOLDER may also name it.",
            emptyList<String>(),
            devImplementerViolations(sourcesUnder(DEV)),
        )
    }

    @Test
    fun noModuleButSlmNetworkModuleAndTheDevOverrideBindsOrProvidesTheEngine() {
        val shipped = listOf(MAIN_SOURCES, DEV) + STAGING_AND_PROD
        assertEquals(
            "Only $BINDING_MODULE may @Binds or @Provides SlmEngine, plus exactly the dev override " +
                "line in $DEV_HOLDER (memo 4.1, R-DEV).",
            emptyList<String>(),
            bindingViolations(shipped.flatMap { sourcesUnder(it) }),
        )
    }

    @Test
    fun stagingAndProdNeverNameTheEngine() {
        val hits = STAGING_AND_PROD.flatMap { flavor -> scan(kotlinSourcesUnder(flavor), listOf("SlmEngine")) }
        assertTrue(
            "A staging or prod source names SlmEngine. Staging and prod get RemoteSlmEngine from " +
                "src/main/ with no override, and a flavor-local implementation or binding would be " +
                "a fabricated clinical narrative reachable in whichever builds ship that flavor " +
                "(H-09, H-13). Found:\n" + hits.joinToString("\n"),
            hits.isEmpty(),
        )
    }

    /** Positive controls: each rule rejects the shape it exists for, and accepts the real holder. */
    @Test
    fun theNarrowedRulesRejectWhatTheyExistFor() {
        assertEquals("Rule 3 must cover both shipped flavors.", listOf("staging", "prod"), STAGING_AND_PROD.map { it.name })
        val engine = DEV_ENGINE to "class LocalSlmEngine(...) : SlmEngine {"
        val holder = DEV_HOLDER to
            "@Provides @Singleton fun provide(@ApplicationContext c: Context): LocalSlmEngine = localSlmEngine(c)"
        val override = DEV_HOLDER to "import com.example.samdapp.domain.slm.SlmEngine\n" +
            "/** Comments naming SlmEngine are not code. */\n" +
            "    @Provides @DevReadbackOverride\n    $DEV_ENGINE_BINDING\n"
        assertEquals(emptyList<String>(), devImplementerViolations(listOf(engine, holder)))
        assertEquals(emptyList<String>(), devImplementerViolations(listOf(engine, override)))
        assertTrue(
            "A second dev implementer was admitted.",
            devImplementerViolations(
                listOf(engine, "dev/java/com/example/samdapp/data/slm/StubSlmEngine.kt" to "class Stub : SlmEngine"),
            ).isNotEmpty(),
        )
        assertTrue(
            "A fully qualified second implementer was admitted.",
            devImplementerViolations(
                listOf(engine, "dev/java/x/Other.kt" to "class Other : com.example.samdapp.domain.slm.SlmEngine"),
            ).isNotEmpty(),
        )
        assertTrue("A missing dev engine was accepted.", devImplementerViolations(listOf(holder)).isNotEmpty())

        assertEquals(emptyList<String>(), bindingViolations(listOf(holder)))
        assertEquals("The real dev override was rejected.", emptyList<String>(), bindingViolations(listOf(override)))
        listOf(
            "@Provides\n    $DEV_ENGINE_BINDING",
            "@Provides @DevReadbackOverride\n    fun provideDevReadbackEngine(engine: RemoteSlmEngine): SlmEngine = engine",
            "@Provides @DevReadbackOverride\n    $DEV_ENGINE_BINDING\n@Provides fun p(e: LocalSlmEngine): SlmEngine = e",
            "@Provides @DevReadbackOverride\n    $DEV_ENGINE_BINDING\nclass Stub : SlmEngine",
        ).forEach { module ->
            assertTrue(
                "A dev holder binding other than the exact override was accepted: $module",
                bindingViolations(listOf(DEV_HOLDER to module)).isNotEmpty(),
            )
        }
        assertTrue(
            "The exact override line was exempted outside $DEV_HOLDER.",
            bindingViolations(
                listOf("dev/java/com/example/samdapp/di/DevClinicalMockModule.kt" to "@Provides @DevReadbackOverride\n    $DEV_ENGINE_BINDING"),
            ).isNotEmpty(),
        )
        assertEquals(
            emptyList<String>(),
            bindingViolations(listOf(BINDING_MODULE to "@Binds abstract fun b(i: RemoteSlmEngine): SlmEngine")),
        )
        listOf(
            "@Binds abstract fun b(i: LocalSlmEngine): SlmEngine",
            "@Provides fun p(e: LocalSlmEngine): SlmEngine = e",
            "@Provides fun p(e: LocalSlmEngine): com.example.samdapp.domain.slm.SlmEngine = e",
        ).forEach { module ->
            assertTrue(
                "A binding outside $BINDING_MODULE was accepted: $module",
                bindingViolations(listOf("dev/java/com/example/samdapp/di/LocalSlmModule.kt" to module)).isNotEmpty(),
            )
        }
        assertTrue(
            "A same-named module in another source set was exempted.",
            bindingViolations(
                listOf("dev/java/com/example/samdapp/di/SlmNetworkModule.kt" to "@Binds abstract fun b(i: X): SlmEngine"),
            ).isNotEmpty(),
        )
    }

    /** Rule 1 over (path relative to the source set, text) pairs. */
    private fun devImplementerViolations(files: List<Pair<String, String>>): List<String> {
        val naming = files.filter { (_, text) -> WHOLE_WORD.containsMatchIn(text) }.map { it.first }
        return if (DEV_ENGINE in naming && (naming - DEV_HOLDER) == listOf(DEV_ENGINE)) {
            emptyList()
        } else {
            listOf("dev files naming SlmEngine: $naming")
        }
    }

    /** Rule 2 over (path relative to the source set, text) pairs. */
    private fun bindingViolations(files: List<Pair<String, String>>): List<String> =
        files.filter { (path, text) ->
            path != BINDING_MODULE &&
                ("@Binds" in text || "@Provides" in text) &&
                WHOLE_WORD.containsMatchIn(text) &&
                !(path == DEV_HOLDER && isExactlyTheDevOverride(text))
        }.map { it.first }

    /** The holder's code names the engine on [DEV_ENGINE_BINDING] alone, and that line is qualified. */
    private fun isExactlyTheDevOverride(text: String): Boolean {
        val code = text.lines().map { it.trim() }.filterNot {
            it.startsWith("import ") || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*")
        }
        val naming = code.filter { WHOLE_WORD.containsMatchIn(it) }
        val at = code.indexOf(DEV_ENGINE_BINDING)
        return naming == listOf(DEV_ENGINE_BINDING) && at > 0 && code[at - 1] == "@Provides @DevReadbackOverride"
    }

    private fun sourcesUnder(root: File): List<Pair<String, String>> =
        kotlinSourcesUnder(root).map { it.relativeTo(MAIN_SOURCES.parentFile).invariantSeparatorsPath to it.readText() }

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

        val DEV: File = FLAVORS[0]
        val STAGING_AND_PROD: List<File> = FLAVORS.drop(1)

        /** Paths relative to `src`, so a same-named file in another source set is not exempt. */
        const val DEV_ENGINE = "dev/java/com/example/samdapp/data/slm/LocalSlmEngine.kt"
        const val BINDING_MODULE = "main/java/com/example/samdapp/di/SlmNetworkModule.kt"
        const val DEV_HOLDER = "dev/java/com/example/samdapp/di/LocalSlmModule.kt"

        /** The one dev line allowed to provide the engine (ruling R-DEV). */
        const val DEV_ENGINE_BINDING = "fun provideDevReadbackEngine(engine: LocalSlmEngine): SlmEngine = engine"

        /** `SlmEngine` as a whole word: `LocalSlmEngine` and `SlmEngineException` do not match. */
        val WHOLE_WORD = Regex("""\bSlmEngine\b""")
    }
}
