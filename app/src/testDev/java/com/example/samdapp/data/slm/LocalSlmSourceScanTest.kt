package com.example.samdapp.data.slm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Two source-level rules for the dev on-device engine (on-device SLM memo 12 and 14, slice S2).
 *
 * 1. **Nothing in the engine's package logs.** Not prompt, not output, not anything: a logging call
 *    that prints only a count today is one edit away from printing the text, and logcat on a shared
 *    phone is readable by anyone with adb. Zero calls is the checkable form.
 * 2. **Only the adapter names LiteRT-LM.** `LiteRtLmRuntime.kt` is the one file in any shipped
 *    source set that names `com.google.ai.edge.litertlm`, so the engine stays testable on a fake.
 *
 * The `src` tree is a declared input of every unit Test task (`app/build.gradle.kts`), so an edit
 * to a scanned file reruns this.
 */
class LocalSlmSourceScanTest {

    @Test
    fun nothingInTheLocalEnginePackageLogs() {
        val files = kotlinFiles(ENGINE_PACKAGE)
        assertTrue(
            "Scan is vacuous: ${files.map { it.name }}",
            files.map { it.name }.containsAll(listOf("LocalSlmEngine.kt", "LiteRtLmRuntime.kt", "LocalSlmRuntime.kt")),
        )
        val hits = files.flatMap { file -> loggingCalls(file.readText()).map { "${file.name}: $it" } }
        assertEquals("The local engine must not log (memo 12, rule 9 of slice S2)", emptyList<String>(), hits)
    }

    @Test
    fun theLoggingScannerFindsEveryForm() {
        listOf(
            "Log.d(TAG, text)",
            "android.util.Log.i(\"x\", prompt)",
            "Timber.w(output)",
            "println(text)",
            "print(text)",
            "System.out.write(bytes)",
            "System.err.println(e)",
            "e.printStackTrace()",
            "Logger.getLogger(\"x\").info(text)",
            "logger.warning(text)",
        ).forEach { line -> assertEquals("Not caught: $line", 1, loggingCalls(line).size) }
        assertEquals(emptyList<String>(), loggingCalls("val dialog = catalog.size // no blog here"))
    }

    @Test
    fun onlyTheAdapterNamesLiteRtLm() {
        val shipped = SOURCE_SETS.map { File(SRC, it) }
        assertTrue("Source sets missing under ${SRC.absolutePath}", shipped.all { it.isDirectory })
        val naming = shipped.flatMap { kotlinFiles(it) }
            .filter { LITERTLM in it.readText() }
            .map { it.relativeTo(SRC).path }
        assertEquals(listOf("dev/java/com/example/samdapp/data/slm/LiteRtLmRuntime.kt"), naming)
    }

    /**
     * D1 (parity harness, `-e pss off` is the default): with the in-process memory sampler off the
     * harness makes no memory call at all. Two source facts make that checkable: `Debug.` appears
     * only inside the `PssSampler` class, and a `PssSampler` is only ever constructed under
     * `if (pssOn)`.
     */
    @Test
    fun theParityHarnessMakesNoInProcessMemoryCallUnlessPssIsOn() {
        val file = File(SRC, "androidTestDev/java/com/example/samdapp/slm/LocalSlmParityHarness.kt")
        assertTrue("missing ${file.path}", file.isFile)
        val lines = file.readLines().filterNot { it.trim().startsWith("*") || it.trim().startsWith("//") || it.trim().startsWith("/*") }
        val start = lines.indexOfFirst { "private class PssSampler" in it }
        assertTrue("PssSampler not found: the scan would be vacuous", start >= 0)
        val end = lines.drop(start).indexOfFirst { it == "    }" } + start
        val outside = lines.filterIndexed { i, line -> "Debug." in line && i !in start..end }
        assertEquals("Debug.* used outside PssSampler", emptyList<String>(), outside)
        val constructions = lines.filter { "PssSampler()" in it }
        assertTrue("PssSampler is never constructed, the scan would be vacuous", constructions.isNotEmpty())
        assertEquals(
            "PssSampler constructed without if (pssOn)",
            emptyList<String>(),
            constructions.filterNot { "if (pssOn)" in it },
        )
    }

    /**
     * The local engine has no network path (on-device SLM memo 1, "no egress on the local path"):
     * nothing in the engine's package imports or names okhttp3, retrofit2, java.net or android.net.
     * The on-device offline smoke proves the behaviour; this keeps the code from regaining the
     * capability unnoticed. Comments are ignored, code is not.
     */
    @Test
    fun theLocalEngineSourcesNameNothingNetworked() {
        val files = kotlinFiles(ENGINE_PACKAGE)
        assertTrue(
            "Scan is vacuous: ${files.map { it.name }}",
            files.map { it.name }.containsAll(listOf("LocalSlmEngine.kt", "LiteRtLmRuntime.kt")),
        )
        val hits = files.flatMap { file -> networkedReferences(file.readText()).map { "${file.name}: $it" } }
        assertEquals("The local engine must not name a network API", emptyList<String>(), hits)
    }

    @Test
    fun theNetworkScannerFindsEveryFormAndIgnoresComments() {
        listOf(
            "import okhttp3.OkHttpClient",
            "import retrofit2.Retrofit",
            "import java.net.URL",
            "import android.net.ConnectivityManager",
            "val url = java.net.URL(text)",
            "val client = okhttp3.OkHttpClient()",
            "import android.net.Uri",
        ).forEach { line -> assertEquals("Not caught: $line", 1, networkedReferences(line).size) }
        listOf(
            "import java.nio.file.Files",
            "import javax.net.ssl.SSLContext.Companion",
            "// import java.net.URL is forbidden here",
            " * uses okhttp3 only in the remote engine",
            "/* android.net */",
            "val internet = 1",
        ).forEach { line -> assertEquals("False alarm: $line", emptyList<String>(), networkedReferences(line)) }
    }

    /**
     * S7 (memo 10): the memory-pressure callbacks are registered from the dev source set only. A
     * registration of the local engine's callbacks in main, staging or prod would put the local
     * engine on a shipped build's path.
     */
    @Test
    fun theLocalEngineCallbacksAreRegisteredFromTheDevSourceSetOnly() {
        val users = SOURCE_SETS.flatMap { set -> kotlinFiles(File(SRC, set)).map { set to it } }
            .filter { (_, file) -> "LocalSlmComponentCallbacks" in file.readText() }
            .map { (set, file) -> "$set/${file.name}" }
            .sorted()
        assertEquals(listOf("dev/LocalSlmModule.kt"), users)
    }

    private fun networkedReferences(text: String): List<String> =
        text.lines().filterNot { line ->
            val trimmed = line.trim()
            trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")
        }.filter { line -> NETWORKED.containsMatchIn(line) }

    private fun kotlinFiles(root: File): List<File> =
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.toList()

    private fun loggingCalls(text: String): List<String> =
        text.lines().filter { line -> LOGGING.any { it.containsMatchIn(line) } }

    private companion object {
        val SRC: File = listOf(File("src"), File("app/src")).firstOrNull { File(it, "dev").isDirectory } ?: File("src")
        val ENGINE_PACKAGE = File(SRC, "dev/java/com/example/samdapp/data/slm")
        val SOURCE_SETS = listOf("main", "dev", "staging", "prod")
        const val LITERTLM = "com.google.ai.edge.litertlm"
        val NETWORKED = Regex("""(?<![\w.])(okhttp3|retrofit2|java\.net|android\.net)\b""")
        val LOGGING = listOf(
            Regex("""\bLog\.[a-zA-Z]+\("""),
            Regex("""\bTimber\b"""),
            Regex("""\bprintln\("""),
            Regex("""(^|[^.\w])print\("""),
            Regex("""\bSystem\.(out|err)\b"""),
            Regex("""\bprintStackTrace\("""),
            Regex("""\bLogger\b"""),
            Regex("""\blogger\.\w+\("""),
        )
    }
}
