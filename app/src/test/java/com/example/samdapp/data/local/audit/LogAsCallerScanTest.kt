package com.example.samdapp.data.local.audit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * FS-R6: `RoomAuditLogger.logAs` writes an audit row under an actor other than the current session,
 * so exactly one place may call it, the facility check at sign-in (`FacilitySignInGate`). `internal`
 * is the narrowest visibility Kotlin allows for a caller in another file; this scan closes the rest.
 * Every shipped source set is scanned; `app/src` is a declared input of every unit test task
 * (`app/build.gradle.kts`, `inputs.dir(layout.projectDirectory.dir("src"))`), so a new caller cannot
 * leave the task UP-TO-DATE.
 */
class LogAsCallerScanTest {

    private val src: File = listOf(File("src"), File("app/src")).first { File(it, "main").isDirectory }

    private fun shipped(): List<Pair<String, String>> = listOf("main", "dev", "staging", "prod")
        .flatMap { root -> File(src, root).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
        .map { it.relativeTo(src).invariantSeparatorsPath to it.readText() }

    /** Files that call `logAs(`, the declaration excluded. */
    private fun callers(files: List<Pair<String, String>>): List<String> = files
        .filter { (_, text) -> Regex("""(?<!fun )\blogAs\(""").containsMatchIn(text) }
        .map { it.first }.sorted()

    @Test
    fun `only the facility sign-in gate calls logAs`() {
        val files = shipped()
        assertTrue("scan is vacuous: ${files.size} files", files.size > 200)
        assertEquals(listOf(GATE), callers(files))
    }

    /** Positive control: a second caller, in any shipped source set, is reported. */
    @Test
    fun `a second caller is caught`() {
        val second = "main/java/com/example/samdapp/presentation/home/HomeViewModel.kt" to
            "auditLogger.logAs(\"w-1\", AuditAction.REPORT_EXPORTED, \"{}\")"
        assertEquals(listOf(GATE, second.first).sorted(), callers(shipped() + second))
    }

    private companion object {
        const val GATE = "main/java/com/example/samdapp/data/local/auth/FacilitySignInGate.kt"
    }
}
