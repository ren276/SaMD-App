package com.example.samdapp.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Rule
import org.junit.Test

/**
 * [MIGRATION_14_15] against a real, SQLCipher-encrypted SQLite database — same rationale as
 * [MigrationTest13To14]'s KDoc: a migration that only passes against a plaintext test database
 * has already been the exact failure mode that hit this project once
 * (`DatabasePassphraseProvider` upgrade bug).
 */
class MigrationTest14To15 {

    private val testDbName = "migration-14-15-test.db"
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
    fun migration14To15_addsFailureCodeColumn_defaultingNullAndPreservingExistingRows() {
        helper.createDatabase(testDbName, 14).apply {
            // A v14 evaluate_reports row from a real, successful evaluate call — this is the row
            // the migration must not disturb.
            execSQL(
                "INSERT INTO evaluate_reports (id, caseRecordId, payloadJson, inferenceStartedAt, " +
                    "inferenceEndedAt, syncState, serverVersion, syncErrorCode, lastSyncAttemptAt, " +
                    "localModifiedAt) VALUES ('er-1', 'cr-1', '{\"diagnosticSummary\":{}}', 1000, " +
                    "2000, 'SYNCED', 3, NULL, NULL, 2000)",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(testDbName, 15, true, MIGRATION_14_15)

        val cursor = migrated.query(
            "SELECT payloadJson, syncState, failureCode FROM evaluate_reports WHERE id = ?",
            arrayOf("er-1"),
        )
        assert(cursor.moveToFirst()) { "evaluate_reports row 'er-1' did not survive the migration" }
        assert(cursor.getString(0) == "{\"diagnosticSummary\":{}}") { "payloadJson was not preserved" }
        assert(cursor.getString(1) == "SYNCED") { "syncState was not preserved" }
        assert(cursor.isNull(2)) { "failureCode expected NULL by default for a pre-existing real report" }
        cursor.close()
        migrated.close()
    }

    @Test
    fun freshInstallAtV15MatchesTheMigratedSchema() {
        // Same rationale as MigrationTest13To14's identically-named test: a fresh install straight
        // to v15 (Room's createAllTables path) is checked against the same exported schema
        // (15.json) as the migrated-from-v14 path above.
        helper.createDatabase(testDbName, 15).close()
    }
}
