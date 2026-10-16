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

    private fun kotlinFiles(root: File): List<File> =
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.toList()

    private fun loggingCalls(text: String): List<String> =
        text.lines().filter { line -> LOGGING.any { it.containsMatchIn(line) } }

    private companion object {
        val SRC: File = listOf(File("src"), File("app/src")).firstOrNull { File(it, "dev").isDirectory } ?: File("src")
        val ENGINE_PACKAGE = File(SRC, "dev/java/com/example/samdapp/data/slm")
        val SOURCE_SETS = listOf("main", "dev", "staging", "prod")
        const val LITERTLM = "com.google.ai.edge.litertlm"
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
