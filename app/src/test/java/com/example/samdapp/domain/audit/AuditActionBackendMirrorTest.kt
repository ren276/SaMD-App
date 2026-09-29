package com.example.samdapp.domain.audit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **The device half of the audit-vocabulary coupling.** Fails if a device [AuditAction] has no
 * entry in the backend mirror, or the mirror has an entry the device enum does not declare.
 *
 * A backend-side test already asserts this equality:
 * `backend/core/tests/test_audit_actions_device.py::test_mirror_matches_the_android_source_when_reachable`
 * parses this same Kotlin enum and compares it to `DEVICE_AUDIT_ACTIONS`. **So why a second one.**
 * That test runs under pytest. This module's contributors run `testDevDebugUnitTest`. A developer
 * who adds an `AuditAction` value, runs the Kotlin suite green and opens a PR gets no signal at all
 * from the existing guard unless the backend suite also runs on that change. The coupling this
 * protects is not a style rule, so it should fail on whichever side the edit was made:
 *
 * 1. A device action the backend mirror does not accept is **rejected** at sync push
 *    (`app/services/sync.py` validates against `DEVICE_AUDIT_ACTIONS`).
 * 2. `"rejected"` maps to `SyncState.FAILED` (`data/sync/SyncAckMapping.kt`, the locked Phase 6b
 *    decision: malformed, stop retrying forever).
 * 3. No drain re-collects a FAILED row. Every syncable DAO selects `syncState = 'PENDING'` only,
 *    and there is no `FAILED -> PENDING` transition anywhere in `app/src/main`.
 * 4. Nothing renders `SyncState.failedCount`. It is queryable and has no consumer under
 *    `presentation/`.
 *
 * Composed, those four are silent permanent loss of every row carrying the new action, with no
 * signal on the device that produced it. The perf audit recorded 1 to 4 as F6B-01 and recorded the
 * cost of the same vocabulary drifting once before: a hand-typed 7-value guess in the backend left
 * 27 of 28 real device actions rejected for four phases before anyone noticed.
 *
 * **Parsing, and why it is crude on purpose.** Both files are read as text rather than either side
 * being imported, because the point is to compare two independently maintained artifacts. A test
 * that derived one from the other would agree with itself. The Python side is a flat
 * `frozenset({...})` of string literals, so a literal-extracting regex is sufficient and no real
 * parser is needed. The non-emptiness assertions are what stop a regex that silently stopped
 * matching from reading as agreement.
 *
 * **Comments are stripped first, and that is not a precaution, it is a bug this test already
 * had.** The first version regexed the whole module and read `"rejected"` out of a prose comment
 * about sync-ack handling, reporting it as a mirror entry the device enum does not declare. The
 * mirror file is heavily commented by design, every entry carries its reasoning, so quoted words
 * in prose are the normal case there rather than an edge one. Everything from an unquoted `#` to
 * end of line goes before the values are read.
 */
class AuditActionBackendMirrorTest {

    private val repoRoot: File = File(System.getProperty("user.dir")!!).let { cwd ->
        generateSequence(cwd) { it.parentFile }
            .firstOrNull { File(it, "backend/core/app/domain/audit_actions_device.py").isFile }
            ?: error("could not locate the repo root from $cwd")
    }

    private val mirrorFile = File(repoRoot, "backend/core/app/domain/audit_actions_device.py")

    /**
     * Quoted string literals inside the frozenset body, with comments stripped first.
     *
     * The strip is line-based and deliberately simple: this file has no multi-line strings and no
     * `#` inside a value, so "drop from the first `#` to end of line" is exact here without
     * pretending to be a Python lexer. If the mirror ever grows a construct that breaks that
     * assumption, the non-emptiness assertion below is what turns it red rather than silent.
     */
    private fun mirrorValues(): Set<String> {
        val source = mirrorFile.readText()
        val start = source.indexOf("DEVICE_AUDIT_ACTIONS")
        assertTrue("the mirror module no longer declares DEVICE_AUDIT_ACTIONS", start >= 0)

        val body = source.substring(start)
            .lineSequence()
            .map { line -> line.substringBefore('#') }
            .joinToString("\n")

        return Regex("\"([a-z_]+)\"").findAll(body).map { it.groupValues[1] }.toSet()
    }

    @Test
    fun `every device audit action has a backend mirror entry, and the reverse`() {
        val device = AuditAction.entries.map { it.value }.toSet()
        val mirror = mirrorValues()

        assertTrue("parsed zero values from the device enum", device.isNotEmpty())
        assertTrue("parsed zero values from ${mirrorFile.path}; the file shape changed", mirror.isNotEmpty())

        assertEquals(
            "A device AuditAction has no entry in backend/core/app/domain/audit_actions_device.py. " +
                "Sync push rejects it, rejected maps to SyncState.FAILED, no drain re-collects a " +
                "FAILED row and nothing renders failedCount, so every row carrying this action is " +
                "lost silently and permanently. Add the value to the mirror in THIS commit.",
            emptySet<String>(),
            device - mirror,
        )
        assertEquals(
            "The backend mirror accepts a value the device enum does not declare. Harmless at " +
                "runtime, but the mirror is documentation of the device vocabulary and a stale " +
                "entry is how the previous hand-typed guess rotted. Remove it or add the enum value.",
            emptySet<String>(),
            mirror - device,
        )
    }

    /**
     * The device enum's own shape, asserted because the backend test parses it with a regex that
     * requires `NAME("lower_snake"),` exactly. A value carrying a digit or a capital would be
     * skipped by that regex, which reads as agreement rather than as a failure: the mirror would
     * appear to match while the new action was never checked at all.
     */
    @Test
    fun `every audit action value is lower snake case, as the backend parser assumes`() {
        val malformed = AuditAction.entries.filterNot { it.value.matches(Regex("[a-z]+(_[a-z]+)*")) }

        assertEquals(
            "An AuditAction value is not lower_snake_case. The backend mirror test parses " +
                "AuditLogger.kt with the regex [A-Z_]+\\(\"([a-z_]+)\"\\), so this value is invisible " +
                "to it and its absence from the mirror would go unnoticed.",
            emptyList<AuditAction>(),
            malformed,
        )
    }
}
