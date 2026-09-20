package com.example.samdapp.data.sync

import com.example.samdapp.data.remote.dto.SyncResultDto
import com.example.samdapp.domain.model.CONSERVATIVE_SYNC_RETRY_CLASS
import com.example.samdapp.domain.model.SyncRetryClass
import com.example.samdapp.domain.model.parseSyncRetryClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **The device half of the sync retry-class coupling.** Fails if the backend's `SyncRetryClass`
 * gains a value this enum lacks, or this enum declares one the backend cannot send.
 *
 * Shaped after [com.example.samdapp.domain.audit.AuditActionBackendMirrorTest], deliberately, and
 * for the same reason it exists at all: the backend-side half
 * (`backend/core/tests/test_sync_retry_class_mirror.py`) runs under pytest, and this module's
 * contributors run `testDevDebugUnitTest`. A developer who adds a value on either side, runs the
 * Kotlin suite green and opens a PR gets no signal from the pytest guard unless the backend suite
 * also runs on that change. PR-2 already made this correction once for another coupling.
 *
 * **What a drift actually costs here.** `retry_class` is the field that decides whether a
 * rejected row is resent or abandoned. A value the device does not recognise falls to
 * [CONSERVATIVE_SYNC_RETRY_CLASS], which is TERMINAL, which is permanent loss of exactly the rows
 * this whole change set exists to save. It fails quietly and it fails in the safe-looking
 * direction, which is why it needs a build-time guard rather than a log line.
 *
 * **Parsing, and why it is crude on purpose.** The Python enum is read as text rather than either
 * side being derived from the other, because the point is to compare two independently maintained
 * artifacts; a test that generated one from the other would agree with itself. Comments are
 * stripped first, which in the sibling audit-action test was not a precaution but a bug it
 * actually had: that file's prose quoted a value and the regex read it as a member. This enum's
 * KDoc quotes `RETRYABLE`, `TERMINAL` and `CONFLICT` in prose too, so the same strip is load
 * bearing here. The non-emptiness assertions are what stop a regex that silently stopped matching
 * from reading as agreement.
 */
class SyncRetryClassMirrorTest {

    private val repoRoot: File = File(System.getProperty("user.dir")!!).let { cwd ->
        generateSequence(cwd) { it.parentFile }
            .firstOrNull { File(it, "backend/core/app/models/enums.py").isFile }
            ?: error("could not locate the repo root from $cwd")
    }

    private val enumsFile = File(repoRoot, "backend/core/app/models/enums.py")

    /**
     * The `NAME = "VALUE"` members of the Python `SyncRetryClass` block, comments stripped, and
     * bounded to that class body so a neighbouring StrEnum (`KernelCallOutcome`, `SlmCallOutcome`)
     * cannot bleed in. The body ends at the next top-level `class ` line.
     */
    private fun backendValues(): Set<String> {
        val source = enumsFile.readText()
        val start = source.indexOf("class SyncRetryClass(StrEnum):")
        assertTrue("backend/core/app/models/enums.py no longer declares SyncRetryClass", start >= 0)

        val after = source.substring(start + 1)
        val end = after.indexOf("\nclass ").let { if (it < 0) after.length else it }
        val body = after.substring(0, end)
            .lineSequence()
            .map { line -> line.substringBefore('#') }
            .joinToString("\n")

        return Regex("""^\s{4}([A-Z_]+)\s*=\s*"([A-Z_]+)"""", RegexOption.MULTILINE)
            .findAll(body)
            .map { it.groupValues[2] }
            .toSet()
    }

    @Test
    fun `every backend SyncRetryClass value is declared on the device, and the reverse`() {
        val device = SyncRetryClass.entries.map { it.name }.toSet()
        val backend = backendValues()

        assertTrue("parsed zero values from the device enum", device.isNotEmpty())
        assertTrue("parsed zero values from ${enumsFile.path}; the file shape changed", backend.isNotEmpty())

        assertEquals(
            "The backend can send a retry_class this device does not declare. parseSyncRetryClass " +
                "returns null for it, the ack falls to CONSERVATIVE_SYNC_RETRY_CLASS (TERMINAL), " +
                "and every row carrying it is abandoned instead of retried. Add the value to " +
                "SyncRetryClass.kt in THIS commit.",
            emptySet<String>(),
            backend - device,
        )
        assertEquals(
            "This device declares a retry_class the backend cannot send. Harmless at runtime, but " +
                "the enum is the device's statement of the contract and a stale member is how a " +
                "mirror rots. Remove it, or add it to app/models/enums.py.",
            emptySet<String>(),
            device - backend,
        )
    }

    /**
     * The device enum's own shape, asserted because the BACKEND test parses this Kotlin file with
     * a regex that expects bare `SCREAMING_SNAKE,` members. A value with a digit or a lowercase
     * letter would be invisible to it, which reads as agreement rather than as a failure.
     */
    @Test
    fun `every device value is SCREAMING_SNAKE_CASE, as the backend parser assumes`() {
        val malformed = SyncRetryClass.entries.filterNot { it.name.matches(Regex("[A-Z]+(_[A-Z]+)*")) }
        assertEquals(
            "A SyncRetryClass value is not SCREAMING_SNAKE_CASE, so the backend mirror test's " +
                "regex cannot see it and its absence would go unnoticed.",
            emptyList<SyncRetryClass>(),
            malformed,
        )
    }

    // ── Totality at the seam ────────────────────────────────────────────────

    private fun rejected(retryClass: String?) = SyncResultDto(
        table = "patients",
        id = "K7m2Qx9pR4tZ",
        status = "rejected",
        code = "SAMD-SYNC-6003",
        message = "a referenced record does not exist yet.",
        retryClass = retryClass,
    )

    @Test
    fun `every value the backend can send is parsed, not defaulted`() {
        // Drives the parse from the BACKEND's set, not the device's, so a backend-only value
        // fails here too rather than only in the set comparison above.
        backendValues().forEach { value ->
            assertEquals(
                "the backend value $value must parse to a device enum member, not fall back",
                value,
                parseSyncRetryClass(value)?.name,
            )
        }
    }

    @Test
    fun `an absent retry_class degrades to the conservative value, and does not throw`() {
        assertNull(parseSyncRetryClass(null))
        assertEquals(CONSERVATIVE_SYNC_RETRY_CLASS, rejected(null).retryClassOrConservative())
    }

    @Test
    fun `an unrecognised retry_class degrades to the conservative value, and does not throw`() {
        assertNull(parseSyncRetryClass("RETRY_LATER_MAYBE"))
        assertEquals(
            CONSERVATIVE_SYNC_RETRY_CLASS,
            rejected("RETRY_LATER_MAYBE").retryClassOrConservative(),
        )
        // Not silently successful either: the conservative value is the one that abandons the
        // row, which is today's behaviour, rather than the one that keeps retrying it.
        assertEquals(SyncRetryClass.TERMINAL, CONSERVATIVE_SYNC_RETRY_CLASS)
    }

    @Test
    fun `a lowercase or whitespace-padded value is not silently accepted`() {
        assertNull(parseSyncRetryClass("retryable"))
        assertNull(parseSyncRetryClass(" RETRYABLE "))
    }

    /**
     * **Replaces S-1's inertness assertion, deliberately and in place.**
     *
     * S-1 pinned the opposite of this: that every `rejected` mapped to FAILED whatever the
     * retry_class said, because acting on RETRYABLE without a state to hold it and a drain to
     * re-collect it would have hung `drainLocked`'s `while (true)` loop rather than saved a row.
     * S-2 supplies both, so the pin is inverted rather than deleted. If it had simply been
     * removed, nothing would assert that this seam does anything at all.
     */
    @Test
    fun `a RETRYABLE rejection is no longer terminal, and the other two still are`() {
        assertEquals(
            "a retryable rejection must stay in the queue: this is the row S-1 and S-2 exist to save",
            com.example.samdapp.domain.model.SyncState.RETRYABLE,
            rejected(SyncRetryClass.RETRYABLE.name).toLocalSyncState(),
        )
        assertEquals(
            com.example.samdapp.domain.model.SyncState.FAILED,
            rejected(SyncRetryClass.TERMINAL.name).toLocalSyncState(),
        )
        assertEquals(
            com.example.samdapp.domain.model.SyncState.FAILED,
            rejected(SyncRetryClass.CONFLICT.name).toLocalSyncState(),
        )
    }

    @Test
    fun `an absent or unrecognised retry_class is still terminal, as the conservative default`() {
        // The compatibility half: an older backend, or a value from a newer one, must not
        // accidentally become retryable now that RETRYABLE actually retries.
        assertEquals(
            com.example.samdapp.domain.model.SyncState.FAILED,
            rejected(null).toLocalSyncState(),
        )
        assertEquals(
            com.example.samdapp.domain.model.SyncState.FAILED,
            rejected("RETRY_LATER_MAYBE").toLocalSyncState(),
        )
    }
}
