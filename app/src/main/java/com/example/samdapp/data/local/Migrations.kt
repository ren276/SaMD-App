package com.example.samdapp.data.local

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `audit_log` (`id` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, " +
                "`userId` TEXT NOT NULL, `patientId` TEXT, `caseRecordId` TEXT, `action` TEXT NOT NULL, " +
                "`payload` TEXT NOT NULL, PRIMARY KEY(`id`))",
        )
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_audit_log_patientId` ON `audit_log` (`patientId`)")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_audit_log_caseRecordId` ON `audit_log` (`caseRecordId`)")
    }
}

/**
 * Phase 0 overhaul schema. Additive only: extends `patients`/`observations` with new nullable
 * columns and creates six new tables (ABHA profile, ailments, prescriptions + medication lines,
 * kernel reports, referrals). No data is dropped or rewritten, so existing rows survive intact.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(connection: SQLiteConnection) {
        // --- Extend existing tables (Phase 0 + pulled-forward Phase 2.5 schema) ---
        connection.execSQL("ALTER TABLE `patients` ADD COLUMN `guardianRelation` TEXT")
        connection.execSQL("ALTER TABLE `observations` ADD COLUMN `captureMethod` TEXT")
        connection.execSQL("ALTER TABLE `observations` ADD COLUMN `syncedToCloudAt` INTEGER")

        // --- abha_profiles ---
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `abha_profiles` (`abhaId` TEXT NOT NULL, `abhaAddress` TEXT, " +
                "`name` TEXT NOT NULL, `dateOfBirth` TEXT, `gender` TEXT NOT NULL, `address` TEXT, " +
                "`district` TEXT, `state` TEXT, `pincode` TEXT, `mobileNumber` TEXT, `emailAddress` TEXT, " +
                "`photoUrlMock` TEXT, `kycVerified` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`abhaId`))",
        )

        // --- ailments ---
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `ailments` (`id` TEXT NOT NULL, `patientId` TEXT NOT NULL, " +
                "`encounterId` TEXT NOT NULL, `description` TEXT NOT NULL, `measurementType` TEXT NOT NULL, " +
                "`visibility` TEXT NOT NULL, `measuredValue` REAL, `measuredUnit` TEXT, `severity` INTEGER, " +
                "`onset` TEXT, `duration` TEXT, `qualifiers` TEXT, `audioLocalUri` TEXT, " +
                "`capturedAtOffline` INTEGER NOT NULL, `syncedToCloudAt` INTEGER, `deletedAt` INTEGER, " +
                "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        )
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_ailments_patientId` ON `ailments` (`patientId`)")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_ailments_encounterId` ON `ailments` (`encounterId`)")

        // --- prescriptions ---
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `prescriptions` (`id` TEXT NOT NULL, `patientId` TEXT NOT NULL, " +
                "`encounterId` TEXT NOT NULL, `caseRecordId` TEXT NOT NULL, `doctorId` TEXT NOT NULL, " +
                "`diagnosis` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        )
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_prescriptions_caseRecordId` ON `prescriptions` (`caseRecordId`)")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_prescriptions_patientId` ON `prescriptions` (`patientId`)")

        // --- medication_lines (child of prescriptions) ---
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `medication_lines` (`id` TEXT NOT NULL, `prescriptionId` TEXT NOT NULL, " +
                "`position` INTEGER NOT NULL, `genericName` TEXT NOT NULL, `brandName` TEXT, " +
                "`strength` TEXT NOT NULL, `dosage` TEXT NOT NULL, `frequency` TEXT NOT NULL, " +
                "`route` TEXT NOT NULL, `duration` TEXT NOT NULL, `quantity` TEXT NOT NULL, " +
                "`foodRelation` TEXT, `instructions` TEXT, PRIMARY KEY(`id`))",
        )
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_medication_lines_prescriptionId` ON `medication_lines` (`prescriptionId`)")

        // --- kernel_reports ---
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `kernel_reports` (`id` TEXT NOT NULL, `caseRecordId` TEXT NOT NULL, " +
                "`predictedCondition` TEXT NOT NULL, `confidenceScore` REAL NOT NULL, `differentials` TEXT NOT NULL, " +
                "`reasoningSummary` TEXT NOT NULL, `evidenceFor` TEXT NOT NULL, `evidenceAgainst` TEXT NOT NULL, " +
                "`modelVersion` TEXT NOT NULL, `inferenceTimestamp` INTEGER NOT NULL, " +
                "`requiredHumanVerification` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        )
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_kernel_reports_caseRecordId` ON `kernel_reports` (`caseRecordId`)")

        // --- referrals ---
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `referrals` (`id` TEXT NOT NULL, `patientUid` TEXT NOT NULL, " +
                "`caseRecordId` TEXT NOT NULL, `urgencyLevel` TEXT NOT NULL, `reason` TEXT NOT NULL, " +
                "`sendingPhcId` TEXT NOT NULL, `status` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))",
        )
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_referrals_patientUid` ON `referrals` (`patientUid`)")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_referrals_caseRecordId` ON `referrals` (`caseRecordId`)")
    }
}

/**
 * Phase 2 "Complaints" → "Ailments" rename (REQ-AIL-01/02/03). Copies every `symptoms` row into
 * `ailments` — `measurementType = NON_MEASURABLE`, `visibility = PUBLIC` (a pre-existing symptom
 * predates the private/public feature, so it can't have been anything but publicly visible), and
 * `capturedAtOffline = createdAt` (the two dual timestamps start equal only for this one-time
 * backfill of historical rows — new rows populate them independently, see [AilmentEntity][com.example.samdapp.data.local.entity.AilmentEntity]).
 * Then drops `symptoms` — nothing in the app reads that table after this migration.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "INSERT INTO `ailments` (`id`, `patientId`, `encounterId`, `description`, `measurementType`, " +
                "`visibility`, `measuredValue`, `measuredUnit`, `severity`, `onset`, `duration`, `qualifiers`, " +
                "`audioLocalUri`, `capturedAtOffline`, `syncedToCloudAt`, `deletedAt`, `createdAt`) " +
                "SELECT `id`, `patientId`, `encounterId`, `description`, 'NON_MEASURABLE', 'PUBLIC', " +
                "NULL, NULL, NULL, NULL, NULL, NULL, NULL, `createdAt`, NULL, NULL, `createdAt` FROM `symptoms`",
        )
        connection.execSQL("DROP TABLE `symptoms`")
    }
}

/**
 * Phase 5 doctor-intake foundation: adds `prescriptions.kernelDecision` (REQ-RX-03 — the doctor's
 * Agree/Modify/Reject on the kernel differential, reported back via the out-of-app doctor channel).
 * Additive only — existing prescription rows get `NULL`.
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE `prescriptions` ADD COLUMN `kernelDecision` TEXT")
    }
}

/**
 * Report-capture schema addendum: fields captured for the exported report artifact even though
 * this app never surfaces them in any doctor-facing UI (out of scope here — see Part A brief).
 * `inferenceTimestamp` is renamed to `inferenceEndedAt` (it was always "when inference finished";
 * the name just didn't say so) and `inferenceStartedAt` is added alongside it so the report can
 * show actual inference duration. Existing rows backfill `inferenceStartedAt` from the same
 * timestamp — the only value available for historical rows — new rows populate them independently.
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE `kernel_reports` RENAME COLUMN `inferenceTimestamp` TO `inferenceEndedAt`")
        connection.execSQL("ALTER TABLE `kernel_reports` ADD COLUMN `inferenceStartedAt` INTEGER NOT NULL DEFAULT 0")
        connection.execSQL("UPDATE `kernel_reports` SET `inferenceStartedAt` = `inferenceEndedAt`")
        connection.execSQL("ALTER TABLE `kernel_reports` ADD COLUMN `icdCode` TEXT")
        connection.execSQL("ALTER TABLE `kernel_reports` ADD COLUMN `deviceId` TEXT NOT NULL DEFAULT ''")
        connection.execSQL("ALTER TABLE `kernel_reports` ADD COLUMN `softwareVersion` TEXT NOT NULL DEFAULT ''")
        connection.execSQL("ALTER TABLE `kernel_reports` ADD COLUMN `dataQualityScore` REAL")
        connection.execSQL("ALTER TABLE `kernel_reports` ADD COLUMN `uncertaintyScore` REAL")
        connection.execSQL("ALTER TABLE `kernel_reports` ADD COLUMN `riskCategory` TEXT NOT NULL DEFAULT 'MODERATE'")
        connection.execSQL("ALTER TABLE `kernel_reports` ADD COLUMN `urgencyLevel` TEXT NOT NULL DEFAULT 'ROUTINE'")
    }
}

/**
 * Doctor-continuity + tracker foundation (Part B). `encounters.followUpOfEncounterId` lets a
 * worker mark a new visit as a follow-up to a specific prior one (PatientSummary consultation
 * history). `doctors` moves off the `doctors.json` asset into Room, seeded with the same 9 mock
 * doctors, so specialty-scoped/least-busy assignment queries can run in SQL.
 */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE `encounters` ADD COLUMN `followUpOfEncounterId` TEXT")

        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `doctors` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                "`specialty` TEXT NOT NULL, `available` INTEGER NOT NULL, `facilityName` TEXT, " +
                "`registrationNumber` TEXT, PRIMARY KEY(`id`))",
        )
        val insertPrefix = "INSERT INTO `doctors` (`id`, `name`, `specialty`, `available`, `facilityName`, `registrationNumber`) VALUES "
        connection.execSQL(insertPrefix + "('doc-gen-001', 'Dr. Anjali Sharma', 'General Physician', 1, 'Community Health Centre, Bhainsa', 'NMC/TS/2011/45231')")
        connection.execSQL(insertPrefix + "('doc-gen-002', 'Dr. Rakesh Verma', 'General Physician', 0, 'District Hospital, Sitapur', 'NMC/UP/2008/33127')")
        connection.execSQL(insertPrefix + "('doc-gyn-001', 'Dr. Priya Nair', 'Gynecology', 1, 'District Hospital, Sitapur', 'NMC/KL/2013/58902')")
        connection.execSQL(insertPrefix + "('doc-ped-001', 'Dr. Suresh Iyer', 'Pediatrics', 1, 'Community Health Centre, Bhainsa', 'NMC/TN/2010/41765')")
        connection.execSQL(insertPrefix + "('doc-ortho-001', 'Dr. Manoj Kumar', 'Orthopedics', 0, 'PHC Rampur', 'NMC/UP/2015/61140')")
        connection.execSQL(insertPrefix + "('doc-derm-001', 'Dr. Kavita Desai', 'Dermatology', 1, 'District Hospital, Sitapur', 'NMC/MH/2012/50318')")
        connection.execSQL(insertPrefix + "('doc-psych-001', 'Dr. Arjun Reddy', 'Psychiatry', 1, 'Community Health Centre, Bhainsa', 'NMC/TS/2014/59477')")
        connection.execSQL(insertPrefix + "('doc-ent-001', 'Dr. Neha Joshi', 'ENT', 0, 'PHC Rampur', 'NMC/MH/2009/37652')")
        connection.execSQL(insertPrefix + "('doc-oph-001', 'Dr. Vikram Singh', 'Ophthalmology', 1, 'District Hospital, Sitapur', 'NMC/UP/2011/46009')")
    }
}

/**
 * Inference-source traceability (REQ-HAN-08): every persisted `kernel_reports` row now records
 * whether it came from the real kernel or the mock fallback — previously only a Logcat line,
 * unqueryable and lost in production. Existing rows predate real inference entirely (it wasn't
 * wired until REQ-HAN-07's 2026-07-21 update), so the conservative backfill for historical rows
 * is `MOCK_FALLBACK`, not a guess at `REAL_INFERENCE`.
 */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE `kernel_reports` ADD COLUMN `inferenceSource` TEXT NOT NULL DEFAULT 'MOCK_FALLBACK'")
    }
}

/**
 * `/api/v1/evaluate` NLEM-treatment kernel output storage — additive-only new table, no existing
 * data touched. `payloadJson` holds the diagnosticSummary/nlemTreatment/brandMapping/
 * safetyAndTriage tree as one Gson blob (see [com.example.samdapp.data.repository.EvaluateReportRepositoryImpl]).
 */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `evaluate_reports` (`id` TEXT NOT NULL, `caseRecordId` TEXT NOT NULL, " +
                "`payloadJson` TEXT NOT NULL, `inferenceStartedAt` INTEGER NOT NULL, `inferenceEndedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))",
        )
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_evaluate_reports_caseRecordId` ON `evaluate_reports` (`caseRecordId`)")
    }
}

/**
 * Physician AGREE/MODIFY/REJECT feedback on the AI's top diagnostic candidate — mirrors
 * `refine_diagnosis.py`'s `DiagnosisFeedback` schema (SaMDClassifier). Captured locally only; no
 * backend capture/reimport endpoint exists yet (see [com.example.samdapp.domain.model.DiagnosisFeedback] KDoc).
 */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `diagnosis_feedback` (`id` TEXT NOT NULL, `caseRecordId` TEXT NOT NULL, " +
                "`icdCandidate` TEXT NOT NULL, `physicianDecision` TEXT NOT NULL, `physicianFinalDiagnosis` TEXT, " +
                "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        )
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_diagnosis_feedback_caseRecordId` ON `diagnosis_feedback` (`caseRecordId`)")
    }
}

/**
 * Adds `diagnosis_feedback.clinicalNote` — a free-text note captured for clinical/audit purposes
 * only, deliberately separate from `physicianFinalDiagnosis` (which must stay one of the 18
 * trained ICD classes or null — see [com.example.samdapp.domain.model.DiagnosisFeedback] KDoc).
 * Never fed into any training-dataset reimport.
 */
val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE `diagnosis_feedback` ADD COLUMN `clinicalNote` TEXT")
    }
}
