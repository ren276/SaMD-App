package com.example.samdapp.config

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Two-hub PR-B addendum, section 1.3 step 1 (G-B11 and G-B11b): the sources compiled into staging
 * and prod name no Bluetooth permission, no Bluetooth or companion-device class and no Nordic
 * package. The BLE client lives in `src/dev/` only, and this is the source-level half of the proof
 * that it stays there. The merged-manifest, dependency-graph and APK halves are live check 9.
 *
 * The token is `permission.BLUETOOTH`, not the bare `BLUETOOTH_` prefix. `RejectReason` in
 * `src/main` has a value named `BLUETOOTH_UNAVAILABLE` (a refusal reason, not a permission), and
 * it would trip a bare-prefix scan on the first build that adds it. `permission.BLUETOOTH` matches
 * both `android.permission.BLUETOOTH_CONNECT` in a manifest and `Manifest.permission.BLUETOOTH_CONNECT`
 * in code, and does not match the enum value.
 *
 * The scan is a substring match over text files, comments included. A comment that names one of
 * these tokens in `src/main` would fail it, deliberately: the cheap way to keep the claim
 * checkable is to keep the words out of these trees altogether.
 *
 * All of `app/src` is already a Gradle test input (`inputs.dir(src)` in `app/build.gradle.kts`), so
 * this test cannot go UP-TO-DATE after an edit to one of the files it reads.
 */
class MainHasNoBluetoothSurfaceTest {

    /** G-B11. The manifest every flavour merges from. */
    @Test
    fun mainManifestDeclaresNoBluetoothPermission() {
        val manifest = File(MAIN, "AndroidManifest.xml")
        assertTrue("Main manifest not found at ${manifest.path}; the working directory changed?", manifest.isFile)
        val text = manifest.readText()
        // Positive control: the file is read, and a permission this build does declare is in it.
        assertTrue("Positive control failed: ACCESS_LOCAL_NETWORK is missing from ${manifest.path}", "ACCESS_LOCAL_NETWORK" in text)
        assertTrue(
            "src/main/AndroidManifest.xml declares a Bluetooth permission. Bluetooth permissions " +
                "belong in app/src/dev/AndroidManifest.xml only (PR-B addendum B1, 14.5 item 10).",
            "permission.BLUETOOTH" !in text,
        )
    }

    /** G-B11b. One assertion over the five tokens, so a mutation on any one of them turns it RED. */
    @Test
    fun releaseSourceTreesNameNoBluetoothCompanionOrNordicToken() {
        val hits = RELEASE_TREES.flatMap { tree -> scan(textFilesUnder(tree)) }
        assertTrue(
            "A source tree compiled into staging or prod names a forbidden token. The BLE client " +
                "is dev-only (PR-B addendum B1, section 1.3). Found ${hits.size}:\n" + hits.joinToString("\n"),
            hits.isEmpty(),
        )
    }

    /** Non-vacuity: the scan reads real files in all three trees and would find a token if present. */
    @Test
    fun theScanRootsResolveAndTheScannerReadsContents() {
        RELEASE_TREES.forEach { tree ->
            assertTrue("Scan root is not a directory: ${tree.absolutePath}", tree.isDirectory)
        }
        val mainFiles = textFilesUnder(MAIN).size
        assertTrue("Scanned only $mainFiles text files under src/main; the scan is not reaching the sources.", mainFiles > 100)
        listOf(STAGING, PROD).forEach { tree ->
            assertTrue("Scanned no Kotlin under ${tree.path}", textFilesUnder(tree).any { it.extension == "kt" })
        }
        // Positive control for the scanner itself, on a synthetic line, not on the repo.
        val planted = File.createTempFile("planted", ".kt").apply {
            deleteOnExit()
            writeText("import ${FORBIDDEN_TOKENS.first()}.BluetoothAdapter\n")
        }
        assertTrue("The scanner missed a planted token, so a clean result proves nothing.", scan(listOf(planted)).isNotEmpty())
    }

    private fun textFilesUnder(root: File): List<File> =
        root.walkTopDown().filter { it.isFile && it.extension in TEXT_EXTENSIONS }.toList()

    private fun scan(files: List<File>): List<String> =
        files.flatMap { file ->
            file.readLines().withIndex().flatMap { (index, line) ->
                FORBIDDEN_TOKENS.filter { it in line }.map { token -> "${file.path}:${index + 1}: $token" }
            }
        }

    private companion object {
        val MAIN: File = listOf(File("src/main"), File("app/src/main"))
            .firstOrNull { it.isDirectory } ?: File("src/main")
        val STAGING = File(MAIN.parentFile, "staging")
        val PROD = File(MAIN.parentFile, "prod")
        val RELEASE_TREES = listOf(MAIN, STAGING, PROD)

        val TEXT_EXTENSIONS = setOf("kt", "java", "xml", "kts", "gradle", "pro", "properties", "json", "txt")

        val FORBIDDEN_TOKENS = listOf(
            "android.bluetooth",
            "android.companion",
            "CompanionDeviceManager",
            "no.nordicsemi",
            "permission.BLUETOOTH",
        )
    }
}
