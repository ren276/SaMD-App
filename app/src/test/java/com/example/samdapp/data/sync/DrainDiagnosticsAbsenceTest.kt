package com.example.samdapp.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The drain diagnostics (`DevDrainDiagnostics`) read sync metadata for every unsent row and log
 * it. They must exist only in the dev source set: staging and prod bind no [DrainObserver], and
 * the class is not even on their classpath. Read as text, like the other source-layout contracts,
 * so it holds in every flavor's JVM run.
 */
class DrainDiagnosticsAbsenceTest {

    private val srcDir: File = File(System.getProperty("user.dir")!!).let { cwd ->
        generateSequence(cwd) { it.parentFile }
            .map { File(it, "app/src") }
            .firstOrNull { it.isDirectory }
            ?: error("could not locate app/src from $cwd")
    }

    private fun kotlinFiles(sourceSet: String): List<File> =
        File(srcDir, sourceSet).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun `the diagnostic class exists in the dev source set and nowhere else`() {
        val definitions = listOf("main", "staging", "prod", "dev").flatMap { set ->
            kotlinFiles(set).filter { it.name == "DevDrainDiagnostics.kt" }.map { set }
        }
        assertEquals(listOf("dev"), definitions)
    }

    @Test
    fun `no shipped source set refers to the diagnostic or its tag`() {
        val offenders = listOf("main", "staging", "prod").flatMap { set ->
            kotlinFiles(set).filter { f ->
                val text = f.readText()
                text.contains("DevDrainDiagnostics") || text.contains("DrainDiag")
            }.map { "$set/${it.name}" }
        }
        assertEquals(emptyList<String>(), offenders)
    }

    @Test
    fun `the shipped seam has no implementation outside the dev source set`() {
        val implementers = listOf("main", "staging", "prod").flatMap { set ->
            kotlinFiles(set).filter { Regex("""(?m)(^\s*\)\s*:|(class|object)\s+\w+\s*:)[^{\n]*\bDrainObserver\b""").containsMatchIn(it.readText()) }.map { "$set/${it.name}" }
        }
        assertEquals(emptyList<String>(), implementers)
        assertTrue(File(srcDir, "dev/java/com/example/samdapp/data/sync/DevDrainDiagnostics.kt").isFile)
    }
}
