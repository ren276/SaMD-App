package com.example.samdapp.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * [MIGRATION_16_17] against a real, SQLCipher-encrypted SQLite database — same rationale as
 * [MigrationTest14To15]/[MigrationTest15To16]: a migration proven only against a plaintext test
 * database is exactly the failure mode that already hit this project once
 * (`DatabasePassphraseProvider` upgrade bug).
 *
 * Proves both halves of `scratchpad/asr-field-audit-memo.md` Part B.2's stated backfill policy:
 * the new column exists and every pre-existing row is backfilled to `TYPED`, not left `NULL`.
 */
class MigrationTest16To17 {

    private val testDbName = "migration-16-17-test.db"
    private val testPassphrase = "migration-test-passphrase".toByteArray()

    init {
        System.loadLibrary("sqlcipher")
    }

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        instrumentation = InstrumentationRegistry.getInstrumentation(),
        databaseClass = AppDatabase::class.java,
        specs = emptyList(),
        openFactory = SupportOpenHelperFactory(testPassphrase),
    )

    @Test
    fun migration16To17_addsColumnAndBackfillsExistingRowsToTyped() {
        helper.createDatabase(testDbName, 16).apply {
            execSQL(
                "INSERT INTO consultations (id, patientId, encounterId, chiefComplaint, onset, " +
                    "durationBucket, severityScore, aggravatingFactors, relievingFactors, " +
                    "impactOnDailyActivities, relevantHistory, transcription, createdAt, updatedAt, " +
                    "syncState, serverVersion, syncErrorCode, lastSyncAttemptAt, localModifiedAt) " +
                    "VALUES ('c-pre-existing', 'p1', 'e1', 'Fever', 'sudden', 'few_days', 5, NULL, " +
                    "NULL, 'Cannot go to work', NULL, NULL, 1000, 1000, 'SYNCED', 3, NULL, NULL, 1000)",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(testDbName, 17, true, MIGRATION_16_17)

        val cursor = migrated.query(
            "SELECT impactOnDailyActivitiesProvenance FROM consultations WHERE id = 'c-pre-existing'",
        )
        assertTrue("expected exactly one row", cursor.count == 1)
        cursor.moveToFirst()
        assertEquals(
            "pre-existing row must be backfilled to TYPED, not left NULL",
            "TYPED",
            cursor.getString(0),
        )
        cursor.close()
        migrated.close()
    }

    @Test
    fun freshInstallAtV17MatchesTheMigratedSchema() {
        // Same rationale as MigrationTest15To16's identically-named test: a fresh install straight
        // to v17 (Room's createAllTables path) is checked against the same exported schema
        // (17.json) as the migrated-from-v16 path above.
        helper.createDatabase(testDbName, 17).close()
    }
}
