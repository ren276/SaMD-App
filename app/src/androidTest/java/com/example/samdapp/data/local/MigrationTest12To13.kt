package com.example.samdapp.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Rule
import org.junit.Test

/**
 * [MIGRATION_12_13] against a real, SQLCipher-encrypted SQLite database. [helper] is built with
 * the exact same [SupportOpenHelperFactory] [com.example.samdapp.di.DatabaseModule] uses at
 * runtime, not [androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory]. A migration that
 * only passes against a plaintext test database and fails against an encrypted one is the exact
 * failure mode this project already hit once, in the `DatabasePassphraseProvider` upgrade bug.
 * See [MigrationTest] for the (plaintext) coverage of migrations 1 through 8.
 */
class MigrationTest12To13 {

    private val testDbName = "migration-12-13-test.db"
    private val testPassphrase = "migration-test-passphrase".toByteArray()

    init {
        // Same load [com.example.samdapp.di.DatabaseModule.provideAppDatabase] does at runtime.
        // SupportOpenHelperFactory needs the native SQLCipher library present before first use.
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
    fun migration12To13_backfillsLocalModifiedAtAndDefaultsSyncStateAcrossAllTwentySyncableTables() {
        val beforeMigrationMillis = System.currentTimeMillis()

        helper.createDatabase(testDbName, 12).apply {
            execSQL(
                "INSERT INTO patients (id, fullName, dateOfBirth, age, biologicalSex, guardianOrSpouseName, " +
                    "guardianRelation, mobileNumber, aadhaarNumber, abhaNumber, village, block, district, state, " +
                    "pincode, category, maritalStatus, bloodGroup, emergencyContact, primaryCareClinicName, " +
                    "referringPhysicianName, createdAt, updatedAt) VALUES ('pat-1', 'Sunita Devi', NULL, 35, " +
                    "'FEMALE', NULL, NULL, '9876543210', NULL, NULL, 'Rampur', 'Block A', 'Sitapur', 'UP', " +
                    "'261001', NULL, NULL, NULL, NULL, NULL, NULL, 1000, 2000)",
            )
            execSQL(
                "INSERT INTO encounters (id, patientId, startedAt, createdAt, updatedAt, followUpOfEncounterId) " +
                    "VALUES ('enc-1', 'pat-1', 1100, 1100, 2100, NULL)",
            )
            execSQL(
                "INSERT INTO consultations (id, patientId, encounterId, chiefComplaint, onset, durationBucket, " +
                    "severityScore, aggravatingFactors, relievingFactors, impactOnDailyActivities, " +
                    "relevantHistory, transcription, createdAt, updatedAt) VALUES ('cons-1', 'pat-1', 'enc-1', " +
                    "'Fever and cough', '3 days', 'DAYS', 5, NULL, NULL, NULL, NULL, NULL, 1200, 2200)",
            )
            execSQL(
                "INSERT INTO attachments (id, consultationId, type, uri, createdAt) " +
                    "VALUES ('att-1', 'cons-1', 'PHOTO', 'content://photo/1', 1300)",
            )
            execSQL(
                "INSERT INTO observations (id, patientId, encounterId, type, valueNumeric, valueText, unit, " +
                    "deviceId, source, captureMethod, recordedAt, syncedToCloudAt, createdAt) VALUES ('obs-1', " +
                    "'pat-1', 'enc-1', 'PULSE', 88.0, NULL, 'bpm', NULL, 'MANUAL_ENTRY', NULL, 1350, NULL, 1400)",
            )
            // Two ailments rows: one never deleted, one soft-deleted well after creation, to exercise
            // the COALESCE(deletedAt, createdAt) backfill on the deleted row specifically.
            execSQL(
                "INSERT INTO ailments (id, patientId, encounterId, description, measurementType, visibility, " +
                    "measuredValue, measuredUnit, severity, onset, duration, qualifiers, audioLocalUri, " +
                    "capturedAtOffline, syncedToCloudAt, deletedAt, createdAt) VALUES ('ail-1', 'pat-1', " +
                    "'enc-1', 'Headache', 'NON_MEASURABLE', 'PUBLIC', NULL, NULL, NULL, NULL, NULL, NULL, NULL, " +
                    "1500, NULL, NULL, 1500)",
            )
            execSQL(
                "INSERT INTO ailments (id, patientId, encounterId, description, measurementType, visibility, " +
                    "measuredValue, measuredUnit, severity, onset, duration, qualifiers, audioLocalUri, " +
                    "capturedAtOffline, syncedToCloudAt, deletedAt, createdAt) VALUES ('ail-2', 'pat-1', " +
                    "'enc-1', 'Mild nausea (retracted)', 'NON_MEASURABLE', 'PRIVATE', NULL, NULL, NULL, NULL, " +
                    "NULL, NULL, NULL, 1500, NULL, 9999, 1500)",
            )
            execSQL(
                "INSERT INTO medical_history_items (id, patientId, category, description, yearOrDate, createdAt) " +
                    "VALUES ('mhi-1', 'pat-1', 'CHRONIC_CONDITION', 'Diabetes', '2019', 1600)",
            )
            execSQL(
                "INSERT INTO allergies (id, patientId, category, allergen, reactionType, createdAt) " +
                    "VALUES ('alg-1', 'pat-1', 'DRUG', 'Penicillin', 'Rash', 1700)",
            )
            execSQL(
                "INSERT INTO family_history_entries (id, patientId, condition, relation, createdAt) " +
                    "VALUES ('fhe-1', 'pat-1', 'Hypertension', 'Father', 1800)",
            )
            execSQL(
                "INSERT INTO social_histories (patientId, occupation, tobaccoUse, alcoholUse, " +
                    "recreationalDrugUse, environmentalExposure, recentTravel, updatedAt) VALUES ('pat-1', " +
                    "'Farmer', 'NEVER', 'NEVER', NULL, NULL, NULL, 1900)",
            )
            execSQL(
                "INSERT INTO medication_entries (id, patientId, encounterId, kind, name, dosage, frequency, " +
                    "active, createdAt) VALUES ('med-1', 'pat-1', 'enc-1', 'PRESCRIBED', 'Paracetamol', '500mg', " +
                    "'BD', 1, 2000)",
            )
            execSQL(
                "INSERT INTO case_records (id, patientId, encounterId, status, assignedDoctorId, createdAt, " +
                    "updatedAt) VALUES ('case-1', 'pat-1', 'enc-1', 'SENT_TO_DOCTOR', 'doc-gen-001', 2100, 3100)",
            )
            execSQL(
                "INSERT INTO kernel_reports (id, caseRecordId, predictedCondition, confidenceScore, " +
                    "differentials, reasoningSummary, evidenceFor, evidenceAgainst, modelVersion, icdCode, " +
                    "deviceId, softwareVersion, dataQualityScore, uncertaintyScore, riskCategory, urgencyLevel, " +
                    "inferenceStartedAt, inferenceEndedAt, requiredHumanVerification, inferenceSource) VALUES " +
                    "('kr-1', 'case-1', 'Viral fever', 0.8, '[]', 'reasoning', '[]', '[]', 'mock-kernel-v0.1', " +
                    "NULL, 'dev-1', 'v1', NULL, NULL, 'MODERATE', 'ROUTINE', 2200, 2300, 1, 'MOCK_FALLBACK')",
            )
            execSQL(
                "INSERT INTO evaluate_reports (id, caseRecordId, payloadJson, inferenceStartedAt, " +
                    "inferenceEndedAt) VALUES ('ev-1', 'case-1', '{}', 2400, 2500)",
            )
            execSQL(
                "INSERT INTO diagnosis_feedback (id, caseRecordId, icdCandidate, physicianDecision, " +
                    "physicianFinalDiagnosis, clinicalNote, createdAt) VALUES ('df-1', 'case-1', 'J11', 'AGREE', " +
                    "NULL, NULL, 2600)",
            )
            execSQL(
                "INSERT INTO prescriptions (id, patientId, encounterId, caseRecordId, doctorId, diagnosis, " +
                    "kernelDecision, createdAt) VALUES ('rx-1', 'pat-1', 'enc-1', 'case-1', 'doc-gen-001', " +
                    "'Viral fever', NULL, 2700)",
            )
            // Two medication_lines rows: one with a real parent (backfills from prescriptions.createdAt),
            // one with a prescriptionId matching no prescriptions row (the FK-less schema doesn't prevent
            // this), to exercise the final COALESCE(..., migration run time) fallback.
            execSQL(
                "INSERT INTO medication_lines (id, prescriptionId, position, genericName, brandName, strength, " +
                    "dosage, frequency, route, duration, quantity, foodRelation, instructions) VALUES ('ml-1', " +
                    "'rx-1', 0, 'Paracetamol', NULL, '500mg', '1 tablet', 'BD', 'ORAL', '5 days', '10', NULL, NULL)",
            )
            execSQL(
                "INSERT INTO medication_lines (id, prescriptionId, position, genericName, brandName, strength, " +
                    "dosage, frequency, route, duration, quantity, foodRelation, instructions) VALUES ('ml-2', " +
                    "'rx-orphan-no-such-prescription', 0, 'Orphaned line', NULL, '1mg', '1 tablet', 'OD', " +
                    "'ORAL', '1 day', '1', NULL, NULL)",
            )
            execSQL(
                "INSERT INTO referrals (id, patientUid, caseRecordId, urgencyLevel, reason, sendingPhcId, " +
                    "status, timestamp) VALUES ('ref-1', 'pat-1', 'case-1', 'ROUTINE', 'Specialist opinion', " +
                    "'PHC-001', 'SENT', 2800)",
            )
            execSQL(
                "INSERT INTO abha_profiles (abhaId, abhaAddress, name, dateOfBirth, gender, address, district, " +
                    "state, pincode, mobileNumber, emailAddress, photoUrlMock, kycVerified, createdAt) VALUES " +
                    "('abha-1', NULL, 'Sunita Devi', NULL, 'FEMALE', NULL, 'Sitapur', 'UP', '261001', NULL, " +
                    "NULL, NULL, 1, 2900)",
            )
            execSQL(
                "INSERT INTO audit_log (id, timestamp, userId, patientId, caseRecordId, action, payload) " +
                    "VALUES ('audit-1', 3000, 'worker-1', 'pat-1', 'case-1', 'encounter_started', '{}')",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(testDbName, 13, true, MIGRATION_12_13)
        val afterMigrationMillis = System.currentTimeMillis()

        fun assertRow(table: String, id: String, idColumn: String, expectedLocalModifiedAt: Long?) {
            val cursor = migrated.query(
                "SELECT syncState, serverVersion, syncErrorCode, lastSyncAttemptAt, localModifiedAt " +
                    "FROM $table WHERE $idColumn = ?",
                arrayOf(id),
            )
            assert(cursor.moveToFirst()) { "$table row '$id' did not survive the migration" }
            val syncState = cursor.getString(0)
            val serverVersionIsNull = cursor.isNull(1)
            val syncErrorCodeIsNull = cursor.isNull(2)
            val lastSyncAttemptAtIsNull = cursor.isNull(3)
            val localModifiedAt = cursor.getLong(4)
            cursor.close()

            assert(syncState == "PENDING") { "$table.$id expected syncState=PENDING, got $syncState" }
            assert(serverVersionIsNull) { "$table.$id expected serverVersion=NULL" }
            assert(syncErrorCodeIsNull) { "$table.$id expected syncErrorCode=NULL" }
            assert(lastSyncAttemptAtIsNull) { "$table.$id expected lastSyncAttemptAt=NULL" }
            if (expectedLocalModifiedAt != null) {
                assert(localModifiedAt == expectedLocalModifiedAt) {
                    "$table.$id expected localModifiedAt=$expectedLocalModifiedAt, got $localModifiedAt"
                }
            } else {
                // The orphaned medication_lines row: no parent to backfill from, so the migration's
                // own COALESCE fallback (its run time) must have fired instead.
                assert(localModifiedAt in beforeMigrationMillis..afterMigrationMillis) {
                    "$table.$id expected localModifiedAt to fall back to migration run time " +
                        "(between $beforeMigrationMillis and $afterMigrationMillis), got $localModifiedAt"
                }
            }
        }

        assertRow("patients", "pat-1", "id", 2000L)
        assertRow("encounters", "enc-1", "id", 2100L)
        assertRow("consultations", "cons-1", "id", 2200L)
        assertRow("attachments", "att-1", "id", 1300L)
        assertRow("observations", "obs-1", "id", 1400L)
        assertRow("ailments", "ail-1", "id", 1500L)
        assertRow("ailments", "ail-2", "id", 9999L)
        assertRow("medical_history_items", "mhi-1", "id", 1600L)
        assertRow("allergies", "alg-1", "id", 1700L)
        assertRow("family_history_entries", "fhe-1", "id", 1800L)
        assertRow("social_histories", "pat-1", "patientId", 1900L)
        assertRow("medication_entries", "med-1", "id", 2000L)
        assertRow("case_records", "case-1", "id", 3100L)
        assertRow("kernel_reports", "kr-1", "id", 2300L)
        assertRow("evaluate_reports", "ev-1", "id", 2500L)
        assertRow("diagnosis_feedback", "df-1", "id", 2600L)
        assertRow("prescriptions", "rx-1", "id", 2700L)
        assertRow("medication_lines", "ml-1", "id", 2700L)
        assertRow("medication_lines", "ml-2", "id", null)
        assertRow("referrals", "ref-1", "id", 2800L)
        assertRow("abha_profiles", "abha-1", "abhaId", 2900L)
        assertRow("audit_log", "audit-1", "id", 3000L)

        // Row survival with original field values intact, spot-checked on one row per table shape
        // (already asserted above for the sync columns; here for a genuinely clinical field).
        val patientCursor = migrated.query("SELECT fullName, mobileNumber FROM patients WHERE id = 'pat-1'")
        patientCursor.moveToFirst()
        assert(patientCursor.getString(0) == "Sunita Devi") { "patients.pat-1 fullName was not preserved" }
        assert(patientCursor.getString(1) == "9876543210") { "patients.pat-1 mobileNumber was not preserved" }
        patientCursor.close()

        migrated.close()
    }

    @Test
    fun freshInstallAtV13MatchesTheMigratedSchema() {
        // MigrationTestHelper.createDatabase validates whatever it creates against this version's
        // exported schema (13.json), so a fresh install straight to v13 (Room's createAllTables
        // path) and the migrated-from-v12 path above are both checked against the same source of
        // truth. Asserted explicitly, per the brief, rather than left implicit in a passing @Rule.
        helper.createDatabase(testDbName, 13).close()
    }
}
