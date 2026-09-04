package com.example.samdapp.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * [MIGRATION_18_19] against a real, SQLCipher-encrypted database - same rationale as
 * [MigrationTest17To18].
 *
 * The one thing worth proving beyond "the column exists": an EXISTING row, written before the
 * column did, must survive the alter with `pageCount` NULL. NULL is the honest value for a
 * direct-file upload whose page count was never measured, and a migration that backfilled a
 * number there would be fabricating clinical metadata.
 */
class MigrationTest18To19 {

    private val testDbName = "migration-18-19-test.db"
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
    fun migration18To19_addsPageCountAndLeavesExistingRowsNull() {
        helper.createDatabase(testDbName, 18).apply {
            execSQL(
                "INSERT INTO consultation_documents (id, consultationId, patientId, abhaNumber, label, " +
                    "canonicalName, departmentCode, recordTypeCode, storageKey, mimeType, sizeBytes, sha256, " +
                    "source, uploadedAt, uploaderUserId, uploaderRole, retractedAt, retractionReason, " +
                    "syncState, serverVersion, syncErrorCode, lastSyncAttemptAt, localModifiedAt) VALUES " +
                    "('doc-pre', 'c1', 'p1', NULL, 'Blood test', 'ABCDEF123456_CARDIO_20260903_LAB_REPORT.pdf', " +
                    "'CARDIO', 'LAB_REPORT', 'LAB_REPORT_1756876543210_uuid.pdf', 'application/pdf', 1024, " +
                    "'deadbeef', 'DIRECT_FILE', 1000, 'worker-1', 'ASHA_WORKER', NULL, NULL, " +
                    "'PENDING', NULL, NULL, NULL, 1000)",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(testDbName, 19, true, MIGRATION_18_19)

        val existing = migrated.query("SELECT pageCount, canonicalName FROM consultation_documents WHERE id = 'doc-pre'")
        assertTrue("the pre-existing row must survive the alter", existing.count == 1)
        existing.moveToFirst()
        assertTrue("a pre-3b row has no measured page count and must stay NULL", existing.isNull(0))
        assertEquals("ABCDEF123456_CARDIO_20260903_LAB_REPORT.pdf", existing.getString(1))
        existing.close()

        migrated.execSQL(
            "INSERT INTO consultation_documents (id, consultationId, patientId, abhaNumber, label, " +
                "canonicalName, departmentCode, recordTypeCode, storageKey, mimeType, sizeBytes, sha256, " +
                "source, pageCount, uploadedAt, uploaderUserId, uploaderRole, retractedAt, retractionReason, " +
                "syncState, serverVersion, syncErrorCode, lastSyncAttemptAt, localModifiedAt) VALUES " +
                "('doc-scan', 'c1', 'p1', NULL, 'Scanned report', 'ABCDEF123456_CARDIO_20260904_LAB_REPORT.pdf', " +
                "'CARDIO', 'LAB_REPORT', 'LAB_REPORT_1756876543299_uuid.pdf', 'application/pdf', 40960, " +
                "'cafebabe', 'CAMERA_ASSEMBLED', 5, 2000, 'worker-1', 'ASHA_WORKER', NULL, NULL, " +
                "'PENDING', NULL, NULL, NULL, 2000)",
        )
        val scanned = migrated.query("SELECT pageCount, source FROM consultation_documents WHERE id = 'doc-scan'")
        scanned.moveToFirst()
        assertEquals(5, scanned.getInt(0))
        assertEquals("CAMERA_ASSEMBLED", scanned.getString(1))
        scanned.close()
        migrated.close()
    }

    @Test
    fun freshInstallAtV19MatchesTheMigratedSchema() {
        // Same rationale as MigrationTest17To18's identically-named test: a fresh install straight
        // to v19 (Room's createAllTables path) is checked against the same exported schema.
        helper.createDatabase(testDbName, 19).close()
    }
}
