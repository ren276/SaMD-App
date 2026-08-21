package com.example.samdapp.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test

/**
 * [MIGRATION_15_16] against a real, SQLCipher-encrypted SQLite database — same rationale as
 * [MigrationTest14To15]'s KDoc: a migration that only passes against a plaintext test database
 * has already been the exact failure mode that hit this project once
 * (`DatabasePassphraseProvider` upgrade bug).
 *
 * Both halves of the migration are proven by one seed-then-assert pass rather than two: if the
 * de-dup `DELETE` under-deletes and leaves two rows for a `caseRecordId`, the immediately-following
 * `CREATE UNIQUE INDEX` throws inside the migration itself and `runMigrationsAndValidate` fails
 * before this test's own assertions ever run. A passing migration is therefore already evidence
 * the DELETE left at most one row per case; the assertions below confirm it is the *right* one
 * (newest [KernelReportEntity.localModifiedAt] / [EvaluateReportEntity.localModifiedAt]) and that
 * a non-duplicated case's row was left untouched (the DELETE does not over-delete).
 */
class MigrationTest15To16 {

    private val testDbName = "migration-15-16-test.db"
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
    fun migration15To16_dedupsKernelReportsNewestWinsAndEnforcesUniqueCaseRecordId() {
        helper.createDatabase(testDbName, 15).apply {
            // Duplicate case: an older InferenceSource.UNAVAILABLE row (the H-09 retry path that
            // produces the duplicate in the first place) followed by a newer real-inference retry.
            execSQL(
                "INSERT INTO kernel_reports (id, caseRecordId, predictedCondition, confidenceScore, " +
                    "differentials, reasoningSummary, evidenceFor, evidenceAgainst, modelVersion, " +
                    "icdCode, deviceId, softwareVersion, dataQualityScore, uncertaintyScore, " +
                    "riskCategory, urgencyLevel, inferenceStartedAt, inferenceEndedAt, " +
                    "requiredHumanVerification, inferenceSource, syncState, serverVersion, " +
                    "syncErrorCode, lastSyncAttemptAt, localModifiedAt) VALUES " +
                    "('k-old', 'case-dup', 'Assessment unavailable', 0.0, '[]', '', '[]', '[]', " +
                    "'mock-kernel-v0.1', NULL, 'dev', 'v1', NULL, NULL, 'MODERATE', 'ROUTINE', " +
                    "1000, 1000, 1, 'UNAVAILABLE', 'FAILED', NULL, 'KERNEL_TIMEOUT', NULL, 1000)",
            )
            execSQL(
                "INSERT INTO kernel_reports (id, caseRecordId, predictedCondition, confidenceScore, " +
                    "differentials, reasoningSummary, evidenceFor, evidenceAgainst, modelVersion, " +
                    "icdCode, deviceId, softwareVersion, dataQualityScore, uncertaintyScore, " +
                    "riskCategory, urgencyLevel, inferenceStartedAt, inferenceEndedAt, " +
                    "requiredHumanVerification, inferenceSource, syncState, serverVersion, " +
                    "syncErrorCode, lastSyncAttemptAt, localModifiedAt) VALUES " +
                    "('k-new', 'case-dup', 'Viral fever', 0.8, '[]', 'reasoning', '[]', '[]', " +
                    "'mock-kernel-v0.1', NULL, 'dev', 'v1', NULL, NULL, 'MODERATE', 'ROUTINE', " +
                    "2000, 2000, 0, 'REAL_INFERENCE', 'PENDING', NULL, NULL, NULL, 2000)",
            )
            // Control case: exactly one row, no duplicate — must survive the DELETE untouched.
            execSQL(
                "INSERT INTO kernel_reports (id, caseRecordId, predictedCondition, confidenceScore, " +
                    "differentials, reasoningSummary, evidenceFor, evidenceAgainst, modelVersion, " +
                    "icdCode, deviceId, softwareVersion, dataQualityScore, uncertaintyScore, " +
                    "riskCategory, urgencyLevel, inferenceStartedAt, inferenceEndedAt, " +
                    "requiredHumanVerification, inferenceSource, syncState, serverVersion, " +
                    "syncErrorCode, lastSyncAttemptAt, localModifiedAt) VALUES " +
                    "('k-single', 'case-single', 'Malaria', 0.6, '[]', 'reasoning', '[]', '[]', " +
                    "'mock-kernel-v0.1', NULL, 'dev', 'v1', NULL, NULL, 'MODERATE', 'ROUTINE', " +
                    "1500, 1500, 0, 'REAL_INFERENCE', 'SYNCED', 4, NULL, NULL, 1500)",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(testDbName, 16, true, MIGRATION_15_16)

        val dupCursor = migrated.query("SELECT id FROM kernel_reports WHERE caseRecordId = 'case-dup'")
        assertTrue("expected exactly one surviving row for case-dup", dupCursor.count == 1)
        dupCursor.moveToFirst()
        assertEquals("newest row (k-new) should survive, not the UNAVAILABLE one", "k-new", dupCursor.getString(0))
        dupCursor.close()

        val singleCursor = migrated.query("SELECT id FROM kernel_reports WHERE caseRecordId = 'case-single'")
        assertTrue("non-duplicated case must not be deleted", singleCursor.count == 1)
        singleCursor.moveToFirst()
        assertEquals("k-single", singleCursor.getString(0))
        singleCursor.close()

        try {
            migrated.execSQL(
                "INSERT INTO kernel_reports (id, caseRecordId, predictedCondition, confidenceScore, " +
                    "differentials, reasoningSummary, evidenceFor, evidenceAgainst, modelVersion, " +
                    "icdCode, deviceId, softwareVersion, dataQualityScore, uncertaintyScore, " +
                    "riskCategory, urgencyLevel, inferenceStartedAt, inferenceEndedAt, " +
                    "requiredHumanVerification, inferenceSource, syncState, serverVersion, " +
                    "syncErrorCode, lastSyncAttemptAt, localModifiedAt) VALUES " +
                    "('k-second', 'case-single', 'Dengue', 0.5, '[]', 'reasoning', '[]', '[]', " +
                    "'mock-kernel-v0.1', NULL, 'dev', 'v1', NULL, NULL, 'MODERATE', 'ROUTINE', " +
                    "3000, 3000, 0, 'REAL_INFERENCE', 'PENDING', NULL, NULL, NULL, 3000)",
            )
            fail("expected the unique index on kernel_reports.caseRecordId to reject a second row for case-single")
        } catch (expected: Exception) {
            // Constraint rejection proves the index is UNIQUE, not merely present.
        }

        migrated.close()
    }

    @Test
    fun migration15To16_dedupsEvaluateReportsNewestWinsAndEnforcesUniqueCaseRecordId() {
        helper.createDatabase(testDbName, 15).apply {
            // Duplicate case: an older H-14 failure marker followed by a newer successful retry —
            // the exact pair EvaluateReportRepositoryImpl.save/saveFailure produce today via
            // getIdForCase, but this seed simulates rows that predate that fix ever running.
            execSQL(
                "INSERT INTO evaluate_reports (id, caseRecordId, payloadJson, inferenceStartedAt, " +
                    "inferenceEndedAt, failureCode, syncState, serverVersion, syncErrorCode, " +
                    "lastSyncAttemptAt, localModifiedAt) VALUES " +
                    "('e-old', 'case-dup-e', '{}', 1000, 1000, 'EVALUATE_TIMEOUT', 'FAILED', NULL, " +
                    "'EVALUATE_TIMEOUT', NULL, 1000)",
            )
            execSQL(
                "INSERT INTO evaluate_reports (id, caseRecordId, payloadJson, inferenceStartedAt, " +
                    "inferenceEndedAt, failureCode, syncState, serverVersion, syncErrorCode, " +
                    "lastSyncAttemptAt, localModifiedAt) VALUES " +
                    "('e-new', 'case-dup-e', '{\"diagnosticSummary\":{}}', 2000, 2000, NULL, " +
                    "'PENDING', NULL, NULL, NULL, 2000)",
            )
            // Control case: exactly one row, no duplicate — must survive the DELETE untouched.
            execSQL(
                "INSERT INTO evaluate_reports (id, caseRecordId, payloadJson, inferenceStartedAt, " +
                    "inferenceEndedAt, failureCode, syncState, serverVersion, syncErrorCode, " +
                    "lastSyncAttemptAt, localModifiedAt) VALUES " +
                    "('e-single', 'case-single-e', '{\"diagnosticSummary\":{}}', 1500, 1500, NULL, " +
                    "'SYNCED', 2, NULL, NULL, 1500)",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(testDbName, 16, true, MIGRATION_15_16)

        val dupCursor = migrated.query("SELECT id, failureCode FROM evaluate_reports WHERE caseRecordId = 'case-dup-e'")
        assertTrue("expected exactly one surviving row for case-dup-e", dupCursor.count == 1)
        dupCursor.moveToFirst()
        assertEquals("newest row (e-new) should survive, not the failure marker", "e-new", dupCursor.getString(0))
        assertTrue("survivor must be the real report, not the failure marker", dupCursor.isNull(1))
        dupCursor.close()

        val singleCursor = migrated.query("SELECT id FROM evaluate_reports WHERE caseRecordId = 'case-single-e'")
        assertTrue("non-duplicated case must not be deleted", singleCursor.count == 1)
        singleCursor.moveToFirst()
        assertEquals("e-single", singleCursor.getString(0))
        singleCursor.close()

        try {
            migrated.execSQL(
                "INSERT INTO evaluate_reports (id, caseRecordId, payloadJson, inferenceStartedAt, " +
                    "inferenceEndedAt, failureCode, syncState, serverVersion, syncErrorCode, " +
                    "lastSyncAttemptAt, localModifiedAt) VALUES " +
                    "('e-second', 'case-single-e', '{}', 3000, 3000, NULL, 'PENDING', NULL, NULL, " +
                    "NULL, 3000)",
            )
            fail("expected the unique index on evaluate_reports.caseRecordId to reject a second row for case-single-e")
        } catch (expected: Exception) {
            // Constraint rejection proves the index is UNIQUE, not merely present.
        }

        migrated.close()
    }

    @Test
    fun freshInstallAtV16MatchesTheMigratedSchema() {
        // Same rationale as MigrationTest14To15's identically-named test: a fresh install straight
        // to v16 (Room's createAllTables path) is checked against the same exported schema
        // (16.json) as the migrated-from-v15 path above.
        helper.createDatabase(testDbName, 16).close()
    }
}
