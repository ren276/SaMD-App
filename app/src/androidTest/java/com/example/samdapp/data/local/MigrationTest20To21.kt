package com.example.samdapp.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * [MIGRATION_20_21] against a real, SQLCipher-encrypted database - same rationale as
 * [MigrationTest19To20].
 *
 * Two things worth proving beyond "the columns exist":
 *
 * 1. An existing row survives with `syncAttemptCount = 0` and `syncErrorMessage` NULL. Zero is
 *    the true count for a row written before attempts were counted, and a backfilled number
 *    would be a fabricated retry budget: a row could then be abandoned after fewer real tries
 *    than the policy promises.
 * 2. **An existing FAILED row is still FAILED.** Some of those are rows a `RETRYABLE`
 *    classification would have saved, and S-1's absence verdict establishes that re-pushing them
 *    is safe, but that one-time re-push is a separate owner-gated decision. A migration that
 *    quietly moved them to PENDING would make that decision on the owner's behalf and resend
 *    clinical records nobody authorised resending.
 */
class MigrationTest20To21 {

    private val testDbName = "migration-20-21-test.db"
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
    fun migration20To21_addsRetryColumnsAndLeavesExistingRowsUntouched() {
        helper.createDatabase(testDbName, 20).apply {
            execSQL(
                "INSERT INTO patients (id, fullName, biologicalSex, age, district, createdAt, " +
                    "updatedAt, syncState, serverVersion, syncErrorCode, lastSyncAttemptAt, " +
                    "localModifiedAt) VALUES " +
                    "('PATPENDING01', 'x', 'FEMALE', 30, 'Jaipur', 1000, 1000, 'PENDING', " +
                    "NULL, NULL, NULL, 1000)",
            )
            execSQL(
                "INSERT INTO patients (id, fullName, biologicalSex, age, district, createdAt, " +
                    "updatedAt, syncState, serverVersion, syncErrorCode, lastSyncAttemptAt, " +
                    "localModifiedAt) VALUES " +
                    "('PATFAILED001', 'y', 'FEMALE', 31, 'Jaipur', 2000, 2000, 'FAILED', " +
                    "NULL, 'SAMD-SYNC-6003', 2500, 2000)",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(testDbName, 21, true, MIGRATION_20_21)

        val pending = migrated.query(
            "SELECT syncAttemptCount, syncErrorMessage, syncState FROM patients WHERE id = 'PATPENDING01'",
        )
        assertTrue("the pre-existing PENDING row must survive the alter", pending.count == 1)
        pending.moveToFirst()
        assertEquals("a row written before attempts were counted has made zero", 0, pending.getInt(0))
        assertTrue("no message was ever stored for it", pending.isNull(1))
        assertEquals("PENDING", pending.getString(2))
        pending.close()

        val failed = migrated.query(
            "SELECT syncState, syncErrorCode, syncAttemptCount FROM patients WHERE id = 'PATFAILED001'",
        )
        failed.moveToFirst()
        assertEquals(
            "an existing FAILED row must stay FAILED: the one-time re-push is owner-gated and " +
                "does not happen in this migration",
            "FAILED",
            failed.getString(0),
        )
        assertEquals("SAMD-SYNC-6003", failed.getString(1))
        assertEquals(0, failed.getInt(2))
        failed.close()

        // The new columns accept the values the running code will write.
        migrated.execSQL(
            "UPDATE patients SET syncState = 'RETRYABLE', syncAttemptCount = 3, " +
                "syncErrorMessage = 'a referenced record does not exist yet.' " +
                "WHERE id = 'PATPENDING01'",
        )
        val retryable = migrated.query(
            "SELECT syncState, syncAttemptCount, syncErrorMessage FROM patients WHERE id = 'PATPENDING01'",
        )
        retryable.moveToFirst()
        assertEquals("RETRYABLE", retryable.getString(0))
        assertEquals(3, retryable.getInt(1))
        assertEquals("a referenced record does not exist yet.", retryable.getString(2))
        retryable.close()
        migrated.close()
    }

    @Test
    fun migration20To21_coversEveryTableWithSyncColumnsIncludingTheUnwiredOne() {
        // consultation_documents is deliberately not drained, but it is a Room entity carrying
        // the same sync columns, so a migration that skipped it would fail Room's own schema
        // validation on the next open rather than anywhere near this file.
        val migrated = helper.createDatabase(testDbName, 20).let {
            it.close()
            helper.runMigrationsAndValidate(testDbName, 21, true, MIGRATION_20_21)
        }
        val cursor = migrated.query("SELECT syncAttemptCount, syncErrorMessage FROM consultation_documents LIMIT 0")
        assertEquals(2, cursor.columnCount)
        cursor.close()
        migrated.close()
    }

    @Test
    fun freshInstallAtV21MatchesTheMigratedSchema() {
        // Same rationale as MigrationTest19To20's identically-named test.
        helper.createDatabase(testDbName, 21).close()
    }
}
