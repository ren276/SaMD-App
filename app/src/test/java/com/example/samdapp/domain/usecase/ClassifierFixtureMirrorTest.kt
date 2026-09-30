package com.example.samdapp.domain.usecase

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The classifier fixtures and their shared expectations exist twice, once per test suite, and
 * must stay byte-identical: the device and the backend are only proven to agree if they read the
 * same bytes. The backend directory is a declared Gradle input (app/build.gradle.kts), so an edit
 * to only that copy re-runs this test instead of leaving it UP-TO-DATE.
 */
class ClassifierFixtureMirrorTest {

    private val repoRoot: File = generateSequence(File(System.getProperty("user.dir")!!)) { it.parentFile }
        .firstOrNull { File(it, "backend/core/tests/fixtures/classifier").isDirectory }
        ?: error("could not locate the repository root from ${System.getProperty("user.dir")}")

    private val deviceDir = File(repoRoot, "app/src/test/resources/classifier-fixtures")
    private val backendDir = File(repoRoot, "backend/core/tests/fixtures/classifier")

    private fun names(dir: File) = dir.listFiles { f -> f.isFile }!!.map { it.name }.toSortedSet()

    @Test
    fun `both fixture directories hold the same files with the same bytes`() {
        val names = names(deviceDir)
        assertTrue("no fixtures found in $deviceDir", names.isNotEmpty())
        assertTrue("expectations.json is missing", "expectations.json" in names)
        assertEquals("the two directories hold different files", names, names(backendDir))
        names.forEach { name ->
            assertArrayEquals(
                "$name differs between $deviceDir and $backendDir",
                File(deviceDir, name).readBytes(),
                File(backendDir, name).readBytes(),
            )
        }
    }
}
