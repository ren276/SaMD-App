package com.example.samdapp.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test

/**
 * Runs every real [Migration][androidx.room.migration.Migration] object against a real SQLite
 * database and validates the result against Room's exported schema JSON (`app/schemas/`) — the
 * same DDL-identity guarantee PROGRESS.md already relies on for earlier migrations, now actually
 * exercised on-device instead of only checked by eye. Covers [MIGRATION_5_6] (kernel_reports
 * report-capture addendum), [MIGRATION_6_7] (encounters.followUpOfEncounterId + doctors table),
 * and [MIGRATION_7_8] (kernel_reports.inferenceSource traceability, REQ-HAN-08).
 *
 * [migrateAllTheWayFrom1To8] runs the plaintext (non-SQLCipher) chain through the whole history;
 * `migrateAllTheWayFrom1To16` extends it to [MIGRATION_15_16] so the full chain, not just each
 * migration in isolation, is proven to apply cleanly in order. Per-migration correctness for
 * [MIGRATION_15_16] itself (the de-dup + unique-index behavior) is [MigrationTest15To16], which
 * uses the SQLCipher variant — this file's plaintext helper is for chain-application coverage
 * only, not a substitute for that.
 */
class MigrationTest {

    private val testDbName = "migration-test.db"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        instrumentation = InstrumentationRegistry.getInstrumentation(),
        databaseClass = AppDatabase::class.java,
    )

    @Test
    fun migrateAllTheWayFrom1To8() {
        helper.createDatabase(testDbName, 1).close()

        helper.runMigrationsAndValidate(
            testDbName,
            8,
            true,
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
        )
    }

    @Test
    fun migrateAllTheWayFrom1To16() {
        helper.createDatabase(testDbName, 1).close()

        helper.runMigrationsAndValidate(
            testDbName,
            16,
            true,
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
            MIGRATION_8_9,
            MIGRATION_9_10,
            MIGRATION_10_11,
            MIGRATION_11_12,
            MIGRATION_12_13,
            MIGRATION_13_14,
            MIGRATION_14_15,
            MIGRATION_15_16,
        )
    }

    @Test
    fun migration5To6_renamesInferenceTimestampAndBackfillsInferenceStartedAt() {
        helper.createDatabase(testDbName, 5).apply {
            execSQL(
                "INSERT INTO kernel_reports (id, caseRecordId, predictedCondition, confidenceScore, " +
                    "differentials, reasoningSummary, evidenceFor, evidenceAgainst, modelVersion, " +
                    "inferenceTimestamp, requiredHumanVerification) VALUES ('k1', 'case-1', 'Viral fever', " +
                    "0.8, '[]', 'reasoning', '[]', '[]', 'mock-kernel-v0.1', 1000, 1)",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(testDbName, 6, true, MIGRATION_5_6)
        val cursor = migrated.query("SELECT inferenceStartedAt, inferenceEndedAt, riskCategory, urgencyLevel FROM kernel_reports WHERE id = 'k1'")
        cursor.moveToFirst()
        val startedAt = cursor.getLong(cursor.getColumnIndexOrThrow("inferenceStartedAt"))
        val endedAt = cursor.getLong(cursor.getColumnIndexOrThrow("inferenceEndedAt"))
        val riskCategory = cursor.getString(cursor.getColumnIndexOrThrow("riskCategory"))
        val urgencyLevel = cursor.getString(cursor.getColumnIndexOrThrow("urgencyLevel"))
        cursor.close()
        migrated.close()

        assert(endedAt == 1000L) { "expected backfilled inferenceEndedAt=1000, got $endedAt" }
        assert(startedAt == 1000L) { "expected inferenceStartedAt backfilled from inferenceEndedAt, got $startedAt" }
        assert(riskCategory == "MODERATE") { "expected default riskCategory=MODERATE, got $riskCategory" }
        assert(urgencyLevel == "ROUTINE") { "expected default urgencyLevel=ROUTINE, got $urgencyLevel" }
    }

    @Test
    fun migration6To7_seedsNineDoctorsAndAddsFollowUpColumn() {
        helper.createDatabase(testDbName, 6).close()

        val migrated = helper.runMigrationsAndValidate(testDbName, 7, true, MIGRATION_6_7)
        val cursor = migrated.query("SELECT COUNT(*) FROM doctors")
        cursor.moveToFirst()
        val doctorCount = cursor.getInt(0)
        cursor.close()
        migrated.close()

        assert(doctorCount == 9) { "expected 9 seeded doctors, got $doctorCount" }
    }

    @Test
    fun migration7To8_backfillsInferenceSourceAsMockFallback() {
        helper.createDatabase(testDbName, 7).apply {
            execSQL(
                "INSERT INTO kernel_reports (id, caseRecordId, predictedCondition, confidenceScore, " +
                    "differentials, reasoningSummary, evidenceFor, evidenceAgainst, modelVersion, " +
                    "deviceId, softwareVersion, riskCategory, urgencyLevel, inferenceStartedAt, " +
                    "inferenceEndedAt, requiredHumanVerification) VALUES ('k1', 'case-1', 'Viral fever', " +
                    "0.8, '[]', 'reasoning', '[]', '[]', 'mock-kernel-v0.1', 'dev', 'v1', 'MODERATE', " +
                    "'ROUTINE', 1000, 2000, 1)",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(testDbName, 8, true, MIGRATION_7_8)
        val cursor = migrated.query("SELECT inferenceSource FROM kernel_reports WHERE id = 'k1'")
        cursor.moveToFirst()
        val inferenceSource = cursor.getString(cursor.getColumnIndexOrThrow("inferenceSource"))
        cursor.close()
        migrated.close()

        assert(inferenceSource == "MOCK_FALLBACK") { "expected backfilled inferenceSource=MOCK_FALLBACK, got $inferenceSource" }
    }
}
