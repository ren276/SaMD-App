package com.example.samdapp.data.vitalssource.ble

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G-B23b (addendum 2.3). ble-ktx's `suspend()` on a WriteRequest, ReadRequest or MtuRequest, and the
 * `suspendForResponse` family built on it, ignore coroutine cancellation, and `close()` does not fail
 * the request in flight: an await parked there would never resume, and the 25 s cap could not end
 * it. The only ktx await allowed in `src/dev` is the cancellable one, reached by upcasting:
 * `(request as TimeoutableRequest).suspend()`. The MTU request is never awaited: it runs queued in the
 * connection's init, which the connect await covers.
 *
 * A text scan, comments included, so the rule stays checkable. `app/src` is a Gradle test input, so
 * this cannot go UP-TO-DATE after an edit.
 */
class NoNonCancellableBleAwaitTest {

    @Test
    fun `every ble-ktx await in src dev is the cancellable one`() {
        val files = DEV.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("scan root ${DEV.absolutePath} has no Kotlin sources", files.isNotEmpty())
        assertTrue("scan root ${DEV.absolutePath} does not hold the adapter", files.any { it.name == "BleHubManager.kt" })
        val hits = files.flatMap { file ->
            file.readLines().mapIndexedNotNull { i, line -> if (violates(line)) "${file.path}:${i + 1}: ${line.trim()}" else null }
        }
        assertTrue(
            "A non-cancellable ble-ktx await is used in src/dev. Upcast to TimeoutableRequest instead " +
                "(addendum 2.3, G-B23b):\n" + hits.joinToString("\n"),
            hits.isEmpty(),
        )
    }

    @Test
    fun `the scanner tells the two forms apart`() {
        assertEquals(true, violates("writeCharacteristic(cp, value, type).suspend()"))
        assertEquals(true, violates("readCharacteristic(serial).with { _, d -> v = d }.suspend()"))
        assertEquals(true, violates("requestMtu(517).suspend()"))
        assertEquals(true, violates("writeCharacteristic(cp, value, type).suspendForResponse<R>()"))
        assertEquals(true, violates("readCharacteristic(c).suspendForValidResponse<R>()"))
        assertEquals(false, violates("(writeCharacteristic(cp, value, type) as TimeoutableRequest).suspend()"))
        assertEquals(false, violates("(connect(device).timeout(10_000) as TimeoutableRequest).suspend()"))
    }

    private fun violates(line: String): Boolean =
        "suspendForResponse" in line || "suspendForValidResponse" in line ||
            line.replace("as TimeoutableRequest).suspend()", "").contains(".suspend()")

    private companion object {
        val DEV: File = listOf(File("src/dev"), File("app/src/dev")).firstOrNull { it.isDirectory } ?: File("src/dev")
    }
}
