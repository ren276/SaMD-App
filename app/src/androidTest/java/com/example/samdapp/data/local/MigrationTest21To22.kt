package com.example.samdapp.data.local

import android.database.sqlite.SQLiteConstraintException
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test

/**
 * [MIGRATION_21_22] against a real, SQLCipher-encrypted database - same rationale as
 * [MigrationTest19To20].
 *
 * `kernel_reports` is rebuilt (SQLite cannot relax `modelVersion` NOT NULL in place), so the risk
 * is a rebuild that drops or rewrites something. Five v21 rows cover the shapes that exist in the
 * field: a real classifier version, the `"remote-kernel"` sentinel, the `"unavailable"` sentinel
 * with a `failureCode`, a mock fallback, and a FAILED sync row with every retry column set. Every
 * one of the 28 old columns must come out value-equal, both sentinels verbatim: a migration that
 * "cleaned" them to NULL would rewrite a clinical record's provenance on the worker's phone.
 */
class MigrationTest21To22 {

    private val testDbName = "migration-21-22-test.db"
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

    private val v21Columns = listOf(
        "id", "caseRecordId", "predictedCondition", "confidenceScore", "differentials",
        "reasoningSummary", "evidenceFor", "evidenceAgainst", "modelVersion", "icdCode",
        "deviceId", "softwareVersion", "dataQualityScore", "uncertaintyScore", "riskCategory",
        "urgencyLevel", "inferenceStartedAt", "inferenceEndedAt", "requiredHumanVerification",
        "inferenceSource", "failureCode", "syncState", "serverVersion", "syncErrorCode",
        "syncErrorMessage", "syncAttemptCount", "lastSyncAttemptAt", "localModifiedAt",
    )

    private fun SupportSQLiteDatabase.insertV21Report(
        id: String,
        caseRecordId: String,
        modelVersion: String,
        inferenceSource: String,
        failureCode: String? = null,
        syncState: String = "SYNCED",
        serverVersion: Int? = 1,
        icdCode: String? = null,
        dataQualityScore: Double? = 0.6,
        syncErrorCode: String? = null,
        syncErrorMessage: String? = null,
        syncAttemptCount: Int = 0,
        lastSyncAttemptAt: Long? = null,
    ) {
        execSQL(
            "INSERT INTO kernel_reports (${v21Columns.joinToString()}) VALUES " +
                "(?, ?, 'Viral fever', 0.82, '[\"Dengue\"]', 'reasoning', '[\"fever\"]', '[]', ?, ?, " +
                "'dev-1', '1.0', ?, 0.18, 'MODERATE', 'ROUTINE', 1000, 2000, 0, ?, ?, ?, ?, ?, ?, ?, ?, 3000)",
            arrayOf<Any?>(
                id, caseRecordId, modelVersion, icdCode, dataQualityScore, inferenceSource,
                failureCode, syncState, serverVersion, syncErrorCode, syncErrorMessage,
                syncAttemptCount, lastSyncAttemptAt,
            ),
        )
    }

    private fun SupportSQLiteDatabase.snapshotV21Columns(): List<List<String?>> {
        val cursor = query("SELECT ${v21Columns.joinToString()} FROM kernel_reports ORDER BY id")
        val rows = mutableListOf<List<String?>>()
        while (cursor.moveToNext()) {
            rows += (0 until v21Columns.size).map { if (cursor.isNull(it)) null else cursor.getString(it) }
        }
        cursor.close()
        return rows
    }

    private fun SupportSQLiteDatabase.scalarString(sql: String): String? =
        query(sql).use { if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null }

    @Test
    fun migration21To22_keepsEveryOldColumnAndAddsThreeNullOnes() {
        val before: List<List<String?>>
        helper.createDatabase(testDbName, 21).apply {
            insertV21Report("kr-real", "case-1", "toy-v0.6-observed-glucose-4tier", "REAL_INFERENCE", icdCode = "E11")
            insertV21Report("kr-sentinel", "case-2", "remote-kernel", "REAL_INFERENCE", dataQualityScore = null)
            insertV21Report(
                "kr-unavail", "case-3", "unavailable", "UNAVAILABLE", failureCode = "OFFLINE",
                syncState = "PENDING", serverVersion = null,
            )
            insertV21Report("kr-mock", "case-4", "mock-kernel-v0.1", "MOCK_FALLBACK", syncState = "RETRYABLE")
            insertV21Report(
                "kr-failed", "case-5", "toy-v0.6", "REAL_INFERENCE", syncState = "FAILED",
                serverVersion = 2, syncErrorCode = "SAMD-SYNC-6003", syncErrorMessage = "rejected",
                syncAttemptCount = 3, lastSyncAttemptAt = 2500,
            )
            // Control: the rebuild must touch no other table.
            execSQL(
                "INSERT INTO case_records (id, patientId, encounterId, status, assignedDoctorId, " +
                    "createdAt, updatedAt, syncState, serverVersion, syncErrorCode, syncErrorMessage, " +
                    "syncAttemptCount, lastSyncAttemptAt, localModifiedAt) VALUES " +
                    "('control-case', 'pat-1', 'enc-1', 'SENT_TO_DOCTOR', 'doc-1', 2100, 3100, 'SYNCED', " +
                    "4, NULL, NULL, 0, NULL, 3100)",
            )
            before = snapshotV21Columns()
            close()
        }
        assertEquals("all five seeded rows are readable before the migration", 5, before.size)

        val migrated = helper.runMigrationsAndValidate(testDbName, 22, true, MIGRATION_21_22)

        assertEquals(
            "every one of the 28 old columns is value-equal per row, sentinels included",
            before,
            migrated.snapshotV21Columns(),
        )
        assertEquals("remote-kernel", migrated.scalarString("SELECT modelVersion FROM kernel_reports WHERE id = 'kr-sentinel'"))
        assertEquals("unavailable", migrated.scalarString("SELECT modelVersion FROM kernel_reports WHERE id = 'kr-unavail'"))

        val newColumns = migrated.query(
            "SELECT COUNT(*) FROM kernel_reports WHERE requestId IS NULL AND modelCalibrated IS NULL " +
                "AND derivationRuleVersion IS NULL",
        )
        newColumns.moveToFirst()
        assertEquals("the three new columns are NULL on all five rows", 5, newColumns.getInt(0))
        newColumns.close()

        val control = migrated.query("SELECT status, serverVersion, localModifiedAt FROM case_records WHERE id = 'control-case'")
        assertTrue(control.moveToFirst())
        assertEquals("SENT_TO_DOCTOR", control.getString(0))
        assertEquals(4, control.getInt(1))
        assertEquals(3100L, control.getLong(2))
        control.close()

        // A NULL modelVersion is now accepted, and the new columns take the values the app writes.
        migrated.execSQL(
            "INSERT INTO kernel_reports (${v21Columns.joinToString()}, requestId, modelCalibrated, derivationRuleVersion) " +
                "VALUES ('kr-new', 'case-6', 'Viral fever', 0.82, '[]', 'r', '[]', '[]', NULL, NULL, 'dev-1', " +
                "'1.0', NULL, NULL, 'MODERATE', 'ROUTINE', 1000, 2000, 0, 'UNAVAILABLE', NULL, 'PENDING', " +
                "NULL, NULL, NULL, 0, NULL, 4000, '3f2b8c1e-9a4d-4e6f-8b7a-1c2d3e4f5a6b', 0, 'HAN-07/08-v2')",
        )
        assertEquals(
            "3f2b8c1e-9a4d-4e6f-8b7a-1c2d3e4f5a6b",
            migrated.scalarString("SELECT requestId FROM kernel_reports WHERE id = 'kr-new' AND modelVersion IS NULL"),
        )

        // The unique index survived the rebuild: a second row for an existing case is refused.
        try {
            migrated.insertV21Report("kr-dup", "case-1", "toy-v0.6", "REAL_INFERENCE")
            fail("expected the unique index on caseRecordId to refuse a second row for case-1")
        } catch (expected: SQLiteConstraintException) {
            // the index is intact
        }

        assertEquals("ok", migrated.scalarString("PRAGMA integrity_check"))
        migrated.close()
    }

    @Test
    fun freshInstallAtV22MatchesTheMigratedSchema() {
        // Same rationale as MigrationTest19To20's identically-named test.
        helper.createDatabase(testDbName, 22).close()
    }
}
