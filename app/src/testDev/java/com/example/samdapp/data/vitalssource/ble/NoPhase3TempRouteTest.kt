package com.example.samdapp.data.vitalssource.ble

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * STOP 2 ruling F4. Phase 2 bound the router's BLE side to a stand-in marked with the marker below
 * until the platform adapter existed; that stand-in must never reach a PR. A scan of every text
 * source under `app/src` (the vendored models are skipped), a Gradle test input, so it cannot go
 * UP-TO-DATE after an edit.
 */
class NoPhase3TempRouteTest {

    @Test
    fun `no file under app src carries the Phase 3 temporary marker`() {
        val files = SRC.walkTopDown().filter { it.isFile && it.extension in TEXT }.toList()
        assertTrue("scan root ${SRC.absolutePath} has no text sources", files.isNotEmpty())
        assertTrue("scan root ${SRC.absolutePath} does not hold the dev DI module", files.any { it.name == "DevClinicalMockModule.kt" })
        val hits = files.flatMap { file -> hits(file.path, file.readLines()) }
        assertTrue("A temporary route is still in app/src:\n" + hits.joinToString("\n"), hits.isEmpty())
    }

    @Test
    fun `the matcher reports a planted marker line as a hit`() {
        val planted = listOf("package p", "    // $MARKER: bound until the adapter exists", "val x = 1")

        assertEquals(listOf("planted.kt:2: // $MARKER: bound until the adapter exists"), hits("planted.kt", planted))
        assertEquals(emptyList<String>(), hits("clean.kt", listOf("package p", "val x = 1")))
    }

    private fun hits(path: String, lines: List<String>): List<String> =
        lines.mapIndexedNotNull { i, line -> if (MARKER in line) "$path:${i + 1}: ${line.trim()}" else null }

    private companion object {
        /** Built from two halves so this file does not match itself. */
        val MARKER = "PHASE-3" + "-TEMP"
        val TEXT = setOf("kt", "kts", "java", "xml", "json", "properties", "pro", "txt", "md")
        val SRC: File = listOf(File("src"), File("app/src")).first { File(it, "dev").isDirectory }
    }
}
