package com.example.samdapp.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * On-device SLM memo sections 3 and 14, slice S1: `litertlm-android` is declared through
 * `devImplementation` and nothing else, so staging and prod resolve no `com.google.ai.edge.litertlm`
 * class and package no `liblitertlm_jni.so`. This is the source-level half of the proof; the APK
 * and dex scans are the other half.
 *
 * The test reads `app/build.gradle.kts` and `gradle/libs.versions.toml` as text. Neither is on the
 * test classpath, so both are declared as inputs of the unit Test tasks in `app/build.gradle.kts`;
 * without that an edit to either leaves the task UP-TO-DATE and this never runs on the change it
 * exists to catch.
 */
class LiteRtLmIsDevOnlyTest {

    @Test
    fun litertlmIsDeclaredOnlyThroughDevImplementation() {
        val text = BUILD_FILE.readText()
        val declarations = declarations(text)
        assertTrue(
            "No litertlm dependency found in ${BUILD_FILE.path}; the dev-only declaration was removed " +
                "or renamed, so this guard would pass on nothing.",
            declarations.isNotEmpty(),
        )
        val offenders = violations(text)
        assertTrue(
            "litertlm-android must be declared through \"devImplementation\" only (memo section 3). " +
                "Offending lines:\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun theCatalogDeclaresTheLibraryOnceAtTheVersionTheMemoPins() {
        val entries = CATALOG_FILE.readLines().filter { it.trimStart().startsWith("litertlm-android") }
        assertEquals("Expected exactly one litertlm-android catalog entry: $entries", 1, entries.size)
        assertTrue(
            "The catalog entry must name com.google.ai.edge.litertlm:litertlm-android: ${entries.single()}",
            "com.google.ai.edge.litertlm" in entries.single() && "name = \"litertlm-android\"" in entries.single(),
        )
        assertTrue(
            "The pinned version is 0.18.0 (memo section 3).",
            CATALOG_FILE.readLines().any { it.trim() == "litertlm = \"0.18.0\"" },
        )
    }

    /** Positive control: the checker rejects the shapes that would put the library into every flavour. */
    @Test
    fun theCheckerRejectsAnythingButDevImplementation() {
        val ok = "    \"devImplementation\"(libs.litertlm.android)\n"
        assertTrue("A devImplementation declaration was rejected.", violations(ok).isEmpty())
        listOf(
            "    implementation(libs.litertlm.android)\n",
            "    api(libs.litertlm.android)\n",
            "    \"stagingImplementation\"(libs.litertlm.android)\n",
            "    \"prodImplementation\"(\"com.google.ai.edge.litertlm:litertlm-android:0.18.0\")\n",
            "    \"devImplementation\"(libs.nordic.ble.ktx)\n    implementation(libs.litertlm.android)\n",
        ).forEach { snippet ->
            assertTrue("Checker accepted a non-dev declaration:\n$snippet", violations(snippet).isNotEmpty())
        }
        assertTrue("A comment naming the library was flagged.", violations("    // libs.litertlm.android\n").isEmpty())
    }

    private fun declarations(text: String): List<String> =
        text.lines().filter { !it.trimStart().startsWith("//") && TOKENS.any { token -> token in it } }

    private fun violations(text: String): List<String> =
        declarations(text).filterNot { it.trimStart().startsWith("\"devImplementation\"(") }

    private companion object {
        val BUILD_FILE: File = listOf(File("build.gradle.kts"), File("app/build.gradle.kts"))
            .firstOrNull { it.isFile && "devImplementation" in it.readText() } ?: File("build.gradle.kts")
        val CATALOG_FILE: File = listOf(File("../gradle/libs.versions.toml"), File("gradle/libs.versions.toml"))
            .firstOrNull { it.isFile } ?: File("../gradle/libs.versions.toml")
        val TOKENS = listOf("libs.litertlm", "com.google.ai.edge.litertlm")
    }
}
