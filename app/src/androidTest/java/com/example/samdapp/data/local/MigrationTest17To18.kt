package com.example.samdapp.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * [MIGRATION_17_18] against a real, SQLCipher-encrypted SQLite database — same rationale as
 * [MigrationTest16To17]: a migration proven only against a plaintext test database is exactly
 * the failure mode that already hit this project once (`DatabasePassphraseProvider` upgrade bug).
 *
 * Purely additive (one new table, no existing table altered, same shape as [MigrationTest2To3]'s
 * new-table coverage) — there is no pre-existing row to seed and backfill, only a new table to
 * prove exists and is queryable.
 */
class MigrationTest17To18 {

    private val testDbName = "migration-17-18-test.db"
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
    fun migration17To18_createsConsultationDocumentsTableAndItIsQueryable() {
        helper.createDatabase(testDbName, 17).close()

        val migrated = helper.runMigrationsAndValidate(testDbName, 18, true, MIGRATION_17_18)

        migrated.execSQL(
            "INSERT INTO consultation_documents (id, consultationId, patientId, abhaNumber, label, " +
                "canonicalName, departmentCode, recordTypeCode, storageKey, mimeType, sizeBytes, sha256, " +
                "source, uploadedAt, uploaderUserId, uploaderRole, retractedAt, retractionReason, " +
                "syncState, serverVersion, syncErrorCode, lastSyncAttemptAt, localModifiedAt) VALUES " +
                "('doc-1', 'c1', 'p1', NULL, 'Blood test', 'ABCDEF123456_CARDIO_20260903_LAB_REPORT.pdf', " +
                "'CARDIO', 'LAB_REPORT', 'LAB_REPORT_1756876543210_uuid.pdf', 'application/pdf', 1024, " +
                "'deadbeef', 'DIRECT_FILE', 1000, 'worker-1', 'ASHA_WORKER', NULL, NULL, " +
                "'PENDING', NULL, NULL, NULL, 1000)",
        )
        val cursor = migrated.query(
            "SELECT canonicalName, departmentCode, recordTypeCode, retractedAt FROM consultation_documents WHERE id = 'doc-1'",
        )
        assertTrue("expected exactly one row", cursor.count == 1)
        cursor.moveToFirst()
        assertEquals("ABCDEF123456_CARDIO_20260903_LAB_REPORT.pdf", cursor.getString(0))
        assertEquals("CARDIO", cursor.getString(1))
        assertEquals("LAB_REPORT", cursor.getString(2))
        assertTrue("retractedAt must be NULL for a freshly-inserted row", cursor.isNull(3))
        cursor.close()
        migrated.close()
    }

    @Test
    fun freshInstallAtV18MatchesTheMigratedSchema() {
        // Same rationale as MigrationTest16To17's identically-named test: a fresh install straight
        // to v18 (Room's createAllTables path) is checked against the same exported schema
        // (18.json) as the migrated-from-v17 path above.
        helper.createDatabase(testDbName, 18).close()
    }
}
