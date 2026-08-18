package com.example.samdapp.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Rule
import org.junit.Test

/**
 * [MIGRATION_13_14] against a real, SQLCipher-encrypted SQLite database — same rationale as
 * [MigrationTest12To13]'s KDoc: a migration that only passes against a plaintext test database
 * has already been the exact failure mode that hit this project once
 * (`DatabasePassphraseProvider` upgrade bug).
 */
class MigrationTest13To14 {

    private val testDbName = "migration-13-14-test.db"
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
    fun migration13To14_addsAbhaProvenanceColumns_defaultingNullAndPreservingExistingRows() {
        helper.createDatabase(testDbName, 13).apply {
            // A v13 row with no ABHA involvement at all (the common case: manually registered,
            // already synced once, per MIGRATION_12_13's own sync-state columns).
            execSQL(
                "INSERT INTO patients (id, fullName, dateOfBirth, age, biologicalSex, guardianOrSpouseName, " +
                    "guardianRelation, mobileNumber, aadhaarNumber, abhaNumber, village, block, district, state, " +
                    "pincode, category, maritalStatus, bloodGroup, emergencyContact, primaryCareClinicName, " +
                    "referringPhysicianName, createdAt, updatedAt, syncState, serverVersion, syncErrorCode, " +
                    "lastSyncAttemptAt, localModifiedAt) VALUES ('pat-1', 'Sunita Devi', NULL, 35, " +
                    "'FEMALE', NULL, NULL, '9876543210', NULL, NULL, 'Rampur', 'Block A', 'Sitapur', 'UP', " +
                    "'261001', NULL, NULL, NULL, NULL, NULL, NULL, 1000, 2000, 'SYNCED', 3, NULL, NULL, 2000)",
            )
            // A v13 row that already carries an abhaNumber (pre-6c: linked but with no provenance
            // columns yet, since they didn't exist) — proves the migration doesn't touch abhaNumber.
            execSQL(
                "INSERT INTO patients (id, fullName, dateOfBirth, age, biologicalSex, guardianOrSpouseName, " +
                    "guardianRelation, mobileNumber, aadhaarNumber, abhaNumber, village, block, district, state, " +
                    "pincode, category, maritalStatus, bloodGroup, emergencyContact, primaryCareClinicName, " +
                    "referringPhysicianName, createdAt, updatedAt, syncState, serverVersion, syncErrorCode, " +
                    "lastSyncAttemptAt, localModifiedAt) VALUES ('pat-2', 'Ramesh Kumar', NULL, 40, " +
                    "'MALE', NULL, NULL, '9123456780', NULL, '12345678901234', 'Rampur', 'Block A', 'Sitapur', " +
                    "'UP', '261001', NULL, NULL, NULL, NULL, NULL, NULL, 1500, 2500, 'PENDING', NULL, NULL, " +
                    "NULL, 2500)",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(testDbName, 14, true, MIGRATION_13_14)

        fun assertRow(id: String, expectedFullName: String, expectedAbhaNumber: String?) {
            val cursor = migrated.query(
                "SELECT fullName, abhaNumber, abhaAddress, kycVerified, verificationSource, verifiedAt " +
                    "FROM patients WHERE id = ?",
                arrayOf(id),
            )
            assert(cursor.moveToFirst()) { "patients row '$id' did not survive the migration" }
            assert(cursor.getString(0) == expectedFullName) { "$id.fullName was not preserved" }
            if (expectedAbhaNumber == null) {
                assert(cursor.isNull(1)) { "$id.abhaNumber expected NULL" }
            } else {
                assert(cursor.getString(1) == expectedAbhaNumber) { "$id.abhaNumber was not preserved" }
            }
            assert(cursor.isNull(2)) { "$id.abhaAddress expected NULL by default, got ${cursor.getString(2)}" }
            assert(cursor.isNull(3)) { "$id.kycVerified expected NULL by default" }
            assert(cursor.isNull(4)) { "$id.verificationSource expected NULL by default" }
            assert(cursor.isNull(5)) { "$id.verifiedAt expected NULL by default" }
            cursor.close()
        }

        assertRow("pat-1", "Sunita Devi", null)
        assertRow("pat-2", "Ramesh Kumar", "12345678901234")

        migrated.close()
    }

    @Test
    fun freshInstallAtV14MatchesTheMigratedSchema() {
        // Same rationale as MigrationTest12To13's identically-named test: a fresh install straight
        // to v14 (Room's createAllTables path) is checked against the same exported schema
        // (14.json) as the migrated-from-v13 path above.
        helper.createDatabase(testDbName, 14).close()
    }
}
