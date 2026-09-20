package com.example.samdapp.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * [MIGRATION_19_20] against a real, SQLCipher-encrypted database - same rationale as
 * [MigrationTest18To19].
 *
 * The thing worth proving beyond "the column exists": an existing UNAVAILABLE row, written by the
 * build whose single `catch (e: Exception)` never recorded a cause, must survive the alter with
 * `failureCode` NULL. NULL is the honest value there. A migration that backfilled a
 * [com.example.samdapp.domain.kernel.KernelFailure] onto those rows would be inventing a remedy
 * for a failure nobody classified, and the screen would then show a worker an action to take
 * about a case where we do not know what went wrong.
 */
class MigrationTest19To20 {

    private val testDbName = "migration-19-20-test.db"
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
    fun migration19To20_addsFailureCodeAndLeavesPreExistingRowsNull() {
        helper.createDatabase(testDbName, 19).apply {
            execSQL(
                "INSERT INTO kernel_reports (id, caseRecordId, predictedCondition, confidenceScore, " +
                    "differentials, reasoningSummary, evidenceFor, evidenceAgainst, modelVersion, icdCode, " +
                    "deviceId, softwareVersion, dataQualityScore, uncertaintyScore, riskCategory, " +
                    "urgencyLevel, inferenceStartedAt, inferenceEndedAt, requiredHumanVerification, " +
                    "inferenceSource, syncState, serverVersion, syncErrorCode, lastSyncAttemptAt, " +
                    "localModifiedAt) VALUES " +
                    "('kr-pre', 'case-pre', 'Assessment unavailable', 0.0, '[]', " +
                    "'Assessment unavailable. The AI did not produce a result for this case and no " +
                    "diagnosis was generated. Tap Retry to run the assessment again.', '[]', '[]', " +
                    "'unavailable', NULL, 'device-1', '1.0', 0.6, 1.0, 'MODERATE', 'ROUTINE', " +
                    "1000, 1000, 1, 'UNAVAILABLE', 'PENDING', NULL, NULL, NULL, 1000)",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(testDbName, 20, true, MIGRATION_19_20)

        val existing = migrated.query("SELECT failureCode, inferenceSource FROM kernel_reports WHERE id = 'kr-pre'")
        assertTrue("the pre-existing row must survive the alter", existing.count == 1)
        existing.moveToFirst()
        assertTrue(
            "a row written before the cause was ever classified must stay NULL, not be backfilled",
            existing.isNull(0),
        )
        assertEquals("UNAVAILABLE", existing.getString(1))
        existing.close()

        migrated.execSQL(
            "INSERT INTO kernel_reports (id, caseRecordId, predictedCondition, confidenceScore, " +
                "differentials, reasoningSummary, evidenceFor, evidenceAgainst, modelVersion, icdCode, " +
                "deviceId, softwareVersion, dataQualityScore, uncertaintyScore, riskCategory, " +
                "urgencyLevel, inferenceStartedAt, inferenceEndedAt, requiredHumanVerification, " +
                "inferenceSource, failureCode, syncState, serverVersion, syncErrorCode, " +
                "lastSyncAttemptAt, localModifiedAt) VALUES " +
                "('kr-post', 'case-post', 'Assessment unavailable', 0.0, '[]', 'x', '[]', '[]', " +
                "'unavailable', NULL, 'device-1', '1.0', 0.6, 1.0, 'MODERATE', 'ROUTINE', " +
                "2000, 2000, 1, 'UNAVAILABLE', 'CASE_NOT_ON_SERVER', 'PENDING', NULL, NULL, NULL, 2000)",
        )
        val classified = migrated.query("SELECT failureCode FROM kernel_reports WHERE id = 'kr-post'")
        classified.moveToFirst()
        assertEquals("CASE_NOT_ON_SERVER", classified.getString(0))
        classified.close()
        migrated.close()
    }

    @Test
    fun freshInstallAtV20MatchesTheMigratedSchema() {
        // Same rationale as MigrationTest18To19's identically-named test: a fresh install straight
        // to v20 (Room's createAllTables path) is checked against the same exported schema.
        helper.createDatabase(testDbName, 20).close()
    }
}
