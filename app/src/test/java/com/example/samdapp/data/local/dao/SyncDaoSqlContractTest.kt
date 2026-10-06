package com.example.samdapp.data.local.dao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **This test exists because two deliberate mutations went green.**
 *
 * S-2's behaviour tests (`SyncOutboxRetryBehaviourTest`) run against `FakeSyncOutboxRepository`,
 * which re-implements the retry policy in Kotlin. MEASURED during S-2's mutation pass: removing
 * the attempt cap from all twenty-one `applySyncResult` queries, and reverting all twenty drain
 * predicates from `syncState IN ('PENDING', 'RETRYABLE')` back to `syncState = 'PENDING'`, each
 * left the entire JVM suite green. The fake agreed with itself while the real SQL said the
 * opposite, which is the exact failure this sequence has now hit four times.
 *
 * The SQL is otherwise covered only by instrumented tests, which need a device and do not run in
 * this environment, so between the fake and the device there was nothing at all.
 *
 * **What this proves and what it does not.** It reads the DAO sources as text and asserts the
 * twenty-one statements SAY the right thing, and that the counts have not moved. It does not
 * execute SQLite, so it cannot prove SQLite AGREES; that is what `SyncStateResetTest` and
 * `MigrationTest20To21` are for. A crude guard that runs beats a precise one that does not.
 *
 * Counts are pinned rather than merely compared, for the reason S-1's AST guard pins its own:
 * a comparison between two numbers derived the same way passes when both are wrong. Adding a
 * syncable table must be a deliberate act that edits this file.
 */
class SyncDaoSqlContractTest {

    private val daoDir: File = File(System.getProperty("user.dir")!!).let { cwd ->
        generateSequence(cwd) { it.parentFile }
            .map { File(it, "app/src/main/java/com/example/samdapp/data/local/dao") }
            .firstOrNull { it.isDirectory }
            ?: error("could not locate the DAO directory from $cwd")
    }

    /** Comments stripped, for the reason `AuditActionBackendMirrorTest` records: these files are
     *  heavily commented and several comments quote the very SQL fragments matched below. */
    private fun sources(): Map<String, String> = daoDir.listFiles { f -> f.extension == "kt" }!!
        .associate { f ->
            val stripped = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL).replace(f.readText(), "")
                .lineSequence().joinToString("\n") { it.substringBefore("//") }
            f.name to stripped
        }

    private fun countAcross(needle: String) = sources().values.sumOf { s ->
        Regex(Regex.escape(needle)).findAll(s).count()
    }

    /** The one table with sync columns that is deliberately NOT drained. */
    private val unwiredDao = "ConsultationDocumentDao.kt"

    private fun stripComments(text: String): String =
        Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL).replace(text, "")
            .lineSequence().joinToString("\n") { it.substringBefore("//") }

    /** The tables the outbox drains, read from the place that decides it: the calls in
     *  `RoomSyncOutboxRepository.collectPendingRecords`. Not a number typed into this test, so
     *  wiring in a twenty-first table changes the expectation here without anyone editing it. */
    private val drainRegistry: List<Pair<String, String>> by lazy {
        val samdapp = daoDir.parentFile.parentFile.parentFile
        val repo = File(samdapp, "data/sync/RoomSyncOutboxRepository.kt")
        assertTrue("could not read $repo", repo.isFile)
        Regex("""(\w+Dao)\.(getPending\w*ForSync)\(retryEligibleBefore\)""")
            .findAll(stripComments(repo.readText()))
            .map { it.groupValues[1] to it.groupValues[2] }
            .toList()
    }

    /** The `@Query` text that decorates [method] inside the DAO interface named for [daoProperty]. */
    private fun drainQueryFor(daoProperty: String, method: String): String {
        val iface = "interface " + daoProperty.replaceFirstChar { it.uppercase() }
        val file = sources().values.firstOrNull { it.contains(Regex("""$iface\b""")) }
            ?: error("no DAO interface for $daoProperty")
        val start = file.indexOf(iface)
        val next = file.indexOf("\ninterface ", start + iface.length)
        val body = file.substring(start, if (next == -1) file.length else next)
        val fn = body.indexOf("fun $method(retryEligibleBefore")
        assertTrue("$daoProperty.$method not found with a retryEligibleBefore parameter", fn >= 0)
        return body.substring(body.lastIndexOf("@Query", fn), fn)
    }

    @Test
    fun `every drained table builds its drain query from the one shared eligibility fragment`() {
        val registry = drainRegistry
        assertTrue("the drain registry parsed empty", registry.isNotEmpty())
        assertEquals("a table is registered twice", registry.size, registry.toSet().size)

        registry.forEach { (dao, method) ->
            assertTrue(
                "$dao.$method must build its query from SyncSql.PENDING_ELIGIBILITY_FRAGMENT",
                drainQueryFor(dao, method).contains("SyncSql.PENDING_ELIGIBILITY_FRAGMENT"),
            )
        }

        // No DAO may carry a drain query the registry does not know about, or the reverse.
        assertEquals(
            "a DAO declares a retryEligibleBefore drain query that collectPendingRecords never calls",
            registry.size,
            countAcross("(retryEligibleBefore: Instant)"),
        )

        // The old inline copies must not come back, in either spelling.
        // SyncSql.kt legitimately holds the one definition, so it is the only file left out.
        fun countOutsideSyncSql(needle: String) = sources().filterKeys { it != "SyncSql.kt" }.values
            .sumOf { Regex(Regex.escape(needle)).findAll(it).count() }
        assertEquals("an inline PENDING/RETRYABLE predicate is back", 0, countOutsideSyncSql("syncState IN ('PENDING', 'RETRYABLE')"))
        assertEquals("an inline cutoff predicate is back", 0, countOutsideSyncSql("lastSyncAttemptAt <= :retryEligibleBefore"))

        val unwired = sources().getValue(unwiredDao)
        assertTrue(
            "$unwiredDao must keep its bare PENDING select: it is deliberately not drained.",
            unwired.contains("WHERE syncState = 'PENDING'"),
        )
        assertTrue("$unwiredDao must not use the shared fragment", !unwired.contains("SyncSql."))
    }

    @Test
    fun `the shared fragment lets PENDING bypass the cutoff and makes RETRYABLE honour it`() {
        val fragment = SyncSql.PENDING_ELIGIBILITY_FRAGMENT
        val pendingBranch = fragment.substringBefore("'RETRYABLE'")
        val retryableBranch = fragment.substring(fragment.indexOf("'RETRYABLE'"))

        assertTrue("PENDING must be a bare, always-eligible branch", pendingBranch.contains("syncState = 'PENDING' OR"))
        assertTrue("the PENDING branch must not mention the cutoff", !pendingBranch.contains("lastSyncAttemptAt"))
        assertTrue(
            "the RETRYABLE branch must wait on the cutoff",
            retryableBranch.contains("lastSyncAttemptAt <= :retryEligibleBefore"),
        )
        assertTrue("a never-attempted RETRYABLE row must be eligible", retryableBranch.contains("lastSyncAttemptAt IS NULL"))
    }

    @Test
    fun `every applySyncResult applies the attempt cap and stores the exhausted reason`() {
        val capState = countAcross(
            "CASE WHEN :syncState = 'RETRYABLE' AND syncAttemptCount + 1 >= :maxAttempts THEN 'FAILED' ELSE :syncState END",
        )
        val capCode = countAcross(
            "CASE WHEN :syncState = 'RETRYABLE' AND syncAttemptCount + 1 >= :maxAttempts THEN :retryExhaustedCode ELSE :syncErrorCode END",
        )
        assertEquals(
            "All twenty-one applySyncResult statements must turn an exhausted RETRYABLE row into " +
                "FAILED. A table missing this retries forever, which is the failure mode the cap " +
                "exists to prevent.",
            21,
            capState,
        )
        assertEquals(
            "The state and the reason must be written by the same condition. A table that flips to " +
                "FAILED without the exhausted code is indistinguishable from a server refusal, " +
                "which is the distinction S-3 needs.",
            21,
            capCode,
        )
    }

    @Test
    fun `every applySyncResult resets the attempt count on success and increments otherwise`() {
        assertEquals(
            "A row that succeeds must start its next failure series from zero. Without the reset, " +
                "a row that syncs after three failures and later fails again gets two tries, not five.",
            21,
            countAcross("syncAttemptCount = CASE WHEN :syncState = 'SYNCED' THEN 0 ELSE syncAttemptCount + 1 END"),
        )
    }

    @Test
    fun `every applySyncResult persists the backend message`() {
        assertEquals(
            "The PHI-safe message is the only thing that can tell a worker WHY, rather than just " +
                "that something failed. It was carried on the wire and dropped at this seam until S-2.",
            21,
            countAcross("syncErrorMessage = :syncErrorMessage"),
        )
    }

    @Test
    fun `every table can be requeued out of FAILED, guarded and budget-clearing`() {
        assertEquals(
            "All twenty-one tables need the FAILED to PENDING transition, including the unwired " +
                "one, so the state machine has no table-shaped hole in it.",
            21,
            countAcross("syncState = 'PENDING', syncAttemptCount = 0"),
        )
        assertEquals(
            "Every requeue must be guarded on FAILED, so it cannot yank a mid-flight row back " +
                "into the queue.",
            21,
            countAcross("AND syncState = 'FAILED'"),
        )
        assertEquals(
            "A requeue must clear the previous reason, or the next failure is reported under the " +
                "last one's code.",
            21,
            countAcross("syncErrorCode = NULL, syncErrorMessage = NULL"),
        )
    }

    @Test
    fun `the guarded localModifiedAt ack condition survived the S-2 rewrite`() {
        // Pre-existing property, re-asserted because S-2 rewrote all twenty-one of these
        // statements and this clause is what stops an ack stamping SYNCED onto content the
        // server never saw.
        assertEquals(
            21,
            countAcross("AND localModifiedAt = :sentLocalModifiedAt"),
        )
    }

    // ── S-3: the review projection ──────────────────────────────────────────

    @Test
    fun `every drained table can list its FAILED rows for review, and the unwired one cannot`() {
        assertEquals(
            "Twenty tables are drained and twenty must be reviewable. A table missing this has " +
                "FAILED rows that are counted on the Home card and then not in the list it " +
                "opens, so the number and the list disagree and the worker is told a number " +
                "they cannot act on.",
            20,
            countAcross("): List<FailedSyncRow>"),
        )
        assertTrue(
            "$unwiredDao must not gain a review query: it is never drained, so it can never " +
                "hold a FAILED row, and adding one here would be the first half of wiring it in.",
            !sources().getValue(unwiredDao).contains("FailedSyncRow"),
        )
    }

    /** The twenty review statements, flattened out of the DAO house style's concatenated
     *  string literals. Scoped deliberately: an earlier draft counted `AS patientId` across the
     *  whole DAO directory and got twenty-one, because `CaseRecordDao.observeDoctorTrackerRows`
     *  projects the same alias for an unrelated screen. A needle wide enough to catch an
     *  unrelated query is a needle that will pass when the thing it guards is gone. */
    private fun reviewStatements(): List<String> = sources().values.flatMap { s ->
        val flat = s.replace(Regex("\"\\s*\\+\\s*\""), "")
        Regex("SELECT '\\w+' AS tableName[^\"]*").findAll(flat).map { it.value }.toList()
    }

    @Test
    fun `every review query selects the same seven columns under the same names`() {
        // Room maps a projection onto FailedSyncRow by column name. A typo in one alias is not a
        // compile error in the DAO source; it is a runtime failure on a device, in a screen that
        // only appears when something has already gone wrong. Checked here instead.
        val statements = reviewStatements()
        assertEquals("expected one review statement per drained table", 20, statements.size)
        listOf("AS tableName", "AS syncState", "AS recordId", "AS patientId", "AS recordedAt", "AS syncErrorCode", "AS syncErrorMessage")
            .forEach { alias ->
                assertEquals(
                    "all twenty review queries must project $alias",
                    20,
                    statements.count { it.contains(alias) },
                )
            }
    }

    @Test
    fun `every review query selects exactly FAILED and CONFLICT, never RETRYABLE`() {
        // CONFLICT is surfaced next to FAILED: neither is ever resent on its own, so both need a
        // person. RETRYABLE rows are deliberately absent from both the count and the list: the
        // device is still working on them and a worker has no action for one. The counters say
        // the same, below; these must agree, or the list shows rows the count does not.
        val statements = reviewStatements()
        assertEquals("expected one review statement per drained table", 20, statements.size)
        statements.forEach { sql ->
            assertTrue(
                "a review query does not restrict to FAILED and CONFLICT: $sql",
                sql.contains("syncState IN ('FAILED', 'CONFLICT')"),
            )
            assertTrue(
                "a review query mentions RETRYABLE. Those rows are still being retried by the " +
                    "device and putting one in front of a worker asks them to act on something " +
                    "that needs nothing from them: $sql",
                !sql.contains("RETRYABLE"),
            )
        }
    }

    @Test
    fun `every drained table has one grouped state counter that excludes only SYNCED`() {
        // One grouped query per table feeds Home every number it shows: pending (PENDING plus
        // RETRYABLE), and the card's FAILED plus CONFLICT, which must match the review list. A
        // counter that dropped a state, or a second per-table counter, would break one of those or
        // double the observers on the launch path (perf audit F2A-01).
        val counters = sources().values.flatMap { s ->
            val flat = s.replace(Regex("\"\\s*\\+\\s*\""), "")
            Regex("SELECT [^\"]*COUNT\\(\\*\\)[^\"]*FROM \\w+ WHERE syncState[^\"]*").findAll(flat).map { it.value }.toList()
        }
        assertEquals("expected one state counter per drained table", 20, counters.size)
        counters.forEach { sql ->
            assertTrue(
                "a counter is not the grouped, SYNCED-excluding shape: $sql",
                Regex("^SELECT syncState AS syncState, COUNT\\(\\*\\) AS rowCount FROM \\w+ WHERE syncState != 'SYNCED' GROUP BY syncState$").matches(sql),
            )
        }
    }

    @Test
    fun `each review query's table literal is one requeueFailed can dispatch on`() {
        // The literal in the SELECT is what FailedSyncRecord.table carries, and it is what
        // RoomSyncOutboxRepository.requeueFailed switches on. If the two ever differ, the list
        // still renders and "Send again" silently does nothing for that table, which is the
        // worst shape a bug can take on this screen: a worker presses the button, is told
        // nothing, and the record stays stuck.
        //
        // MEASURED during S-3's mutation pass: renaming one literal was invisible to every
        // other test here and to Room, which validates columns but has no opinion about what a
        // string literal means to Kotlin code two layers away.
        val literals = reviewStatements()
            .mapNotNull { Regex("^SELECT '(\\w+)' AS tableName").find(it)?.groupValues?.get(1) }
            .toSet()

        val repository = File(repoRoot, "app/src/main/java/com/example/samdapp/data/sync/RoomSyncOutboxRepository.kt")
            .readText()
        val start = repository.indexOf("override suspend fun requeueFailed")
        assertTrue("requeueFailed is gone", start >= 0)
        val body = repository.substring(start, repository.indexOf("\n    }", start))
        val dispatched = Regex("\"(\\w+)\" ->").findAll(body).map { it.groupValues[1] }.toSet()

        assertEquals("expected twenty table literals", 20, literals.size)
        assertEquals(
            "a review query names a table requeueFailed does not handle, so Send again would be " +
                "a silent no-op for every row of it",
            emptySet<String>(),
            literals - dispatched,
        )
    }

    // ── The migration ───────────────────────────────────────────────────────

    private val repoRoot: File = File(System.getProperty("user.dir")!!).let { cwd ->
        generateSequence(cwd) { it.parentFile }
            .firstOrNull { File(it, "app/src/main/java/com/example/samdapp/data/local/Migrations.kt").isFile }
            ?: error("could not locate the repo root from $cwd")
    }

    /**
     * MEASURED during S-2's mutation pass: deleting one `addRetryColumns(...)` line from
     * MIGRATION_20_21 left the whole JVM suite green. That omission is not a cosmetic bug. Room
     * validates the live schema against the entity definitions when the database opens, so a
     * table whose columns the migration skipped makes every existing install fail to open after
     * the upgrade, and the only test that would have caught it needs a device.
     *
     * So the migration's table list is checked against the entities' own `tableName` annotations,
     * which is the same source-of-truth technique the mirror tests use. Both lists are read as
     * text; neither is derived from the other.
     */
    @Test
    fun `MIGRATION_20_21 adds the retry columns to every entity that has sync columns`() {
        val entityDir = File(repoRoot, "app/src/main/java/com/example/samdapp/data/local/entity")
        assertTrue("entity directory not found at ${entityDir.path}", entityDir.isDirectory)

        val tablesNeedingColumns = entityDir.listFiles { f -> f.extension == "kt" }!!
            .flatMap { f ->
                val text = f.readText()
                // Only entities that actually carry the outbox columns need the migration.
                // `tableName = "..."` anywhere in the annotation: some entities put it on the
                // line after `@Entity(` alongside `indices`, and a single-line-only pattern
                // silently skipped those, which this very test caught on its first run.
                Regex("""tableName = "(\w+)"""").findAll(text)
                    .map { it.groupValues[1] to text }
                    .filter { (_, t) -> t.contains("val syncAttemptCount: Int = 0") }
                    .map { it.first }
                    .toList()
            }
            .toSet()

        val migrations = File(repoRoot, "app/src/main/java/com/example/samdapp/data/local/Migrations.kt")
            .readText()
        val start = migrations.indexOf("val MIGRATION_20_21")
        assertTrue("MIGRATION_20_21 is gone", start >= 0)
        // Bounded at the next top-level declaration so a future MIGRATION_21_22 cannot be read
        // as part of this one's table list.
        val body = migrations.substring(start).let { rest ->
            val next = rest.indexOf("\nval ", startIndex = 1)
            if (next < 0) rest else rest.substring(0, next)
        }
        val migrated = Regex("""addRetryColumns\("(\w+)"\)""").findAll(body)
            .map { it.groupValues[1] }.toSet()

        assertTrue("parsed zero entities; the entity file shape changed", tablesNeedingColumns.isNotEmpty())
        assertTrue("parsed zero addRetryColumns calls; the migration shape changed", migrated.isNotEmpty())

        assertEquals(
            "An entity carries syncAttemptCount but MIGRATION_20_21 never adds the column to its " +
                "table. Room validates the schema on open, so every existing install would fail " +
                "to open the database after this upgrade.",
            emptySet<String>(),
            tablesNeedingColumns - migrated,
        )
        assertEquals(
            "MIGRATION_20_21 alters a table no entity declares. Either the entity was removed and " +
                "the migration line was not, or the table name is misspelled, which is the same " +
                "failure wearing a different hat.",
            emptySet<String>(),
            migrated - tablesNeedingColumns,
        )
        assertEquals(
            "Twenty-one tables carry the outbox columns: the twenty the drain pushes plus " +
                "consultation_documents, which is deliberately unwired but is still a Room entity.",
            21,
            migrated.size,
        )
    }
}
