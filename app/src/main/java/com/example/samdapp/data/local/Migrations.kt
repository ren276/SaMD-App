package com.example.samdapp.data.local

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import java.time.Instant

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

/**
 * Adds missing mock doctors to support AI Kernel disease routing (REQ-DOC-MAP).
 * The specialties cover Endocrinology, Cardiology, Neurology, Pulmonology, Infectious Disease, Urology, Gastroenterology, and Critical Care.
 */
val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(connection: SQLiteConnection) {
        val insertPrefix = "INSERT OR IGNORE INTO `doctors` (`id`, `name`, `specialty`, `available`, `facilityName`, `registrationNumber`) VALUES "
        connection.execSQL(insertPrefix + "('doc-cardio-001', 'Dr. Ramesh Gupta', 'Cardiology', 1, 'District Hospital, Sitapur', 'NMC/UP/2005/11234')")
        connection.execSQL(insertPrefix + "('doc-endo-001', 'Dr. Sunita Patel', 'Endocrinology', 1, 'City General Hospital, Ahmedabad', 'NMC/GJ/2010/45678')")
        connection.execSQL(insertPrefix + "('doc-neuro-001', 'Dr. Amit Trivedi', 'Neurology', 1, 'District Hospital, Sitapur', 'NMC/UP/2012/89012')")
        connection.execSQL(insertPrefix + "('doc-pulmo-001', 'Dr. Rajesh Khanna', 'Pulmonology', 1, 'Community Health Centre, Bhainsa', 'NMC/TS/2008/34567')")
        connection.execSQL(insertPrefix + "('doc-id-001', 'Dr. Meena Kumar', 'Infectious Disease', 1, 'District Hospital, Sitapur', 'NMC/UP/2015/67890')")
        connection.execSQL(insertPrefix + "('doc-uro-001', 'Dr. Prakash Rao', 'Urology', 1, 'PHC Rampur', 'NMC/MH/2011/23456')")
        connection.execSQL(insertPrefix + "('doc-gastro-001', 'Dr. Anita Desai', 'Gastroenterology', 1, 'District Hospital, Sitapur', 'NMC/UP/2014/90123')")
        connection.execSQL(insertPrefix + "('doc-crit-001', 'Dr. Sanjay Singh', 'Critical Care', 1, 'District Hospital, Sitapur', 'NMC/UP/2009/56789')")
        connection.execSQL(insertPrefix + "('doc-int-001', 'Dr. Vivek Sharma', 'Internal Medicine', 1, 'Community Health Centre, Bhainsa', 'NMC/TS/2016/12345')")
    }
}

/**
 * Per-record sync-state foundation for Phase 6 (REQ-SYN-02). Five new columns on every one of
 * the 20 syncable entities in api-contract.md §6.1 (everything except `doctors`, which is
 * server-owned reference data with no push path):
 *
 * - `syncState` (`PENDING`/`SYNCED`/`CONFLICT`/`FAILED`, see [com.example.samdapp.domain.model.SyncState]).
 *   `PENDING` for every existing row, deliberately: no device has ever pushed anything, so marking
 *   pre-upgrade rows `SYNCED` would silently guarantee those records never reach the server. The
 *   consequence is that the first sync after Phase 6 has to drain the full local history and will
 *   exceed the 500-record/5 MB batch limit (api-contract.md §6.1); that is the outbox's job to
 *   chunk and is out of scope here.
 * - `serverVersion`, NULL for every row, matching `base_version: null` (api-contract.md §6.1) for
 *   a record the server has never seen. Not defaulted to 0, since 0 is a real version.
 * - `syncErrorCode`, `lastSyncAttemptAt`: both NULL, unused until Phase 6's outbox writes them.
 * - `localModifiedAt`: sync metadata ("when this row's bytes last changed on this device"),
 *   distinct from the five entities' existing `updatedAt` (a clinical fact) even where the two
 *   start equal. The redundancy is deliberate: it buys Phase 6 one uniform column to read
 *   instead of a per-entity lookup of which timestamp means "last write." Backfilled per entity
 *   below; every value is an approximation for pre-existing rows, not a forensic reconstruction,
 *   and that is acceptable because every migrated row also carries `serverVersion = NULL`, so per
 *   api-contract.md §6.1 it is applied unconditionally on its first push. The backfilled
 *   `localModifiedAt` is never the deciding input to a conflict comparison.
 *
 * Backfill source per entity: `updatedAt` for the five entities that already have it (`patients`,
 * `encounters`, `consultations`, `social_histories`, `case_records`); `createdAt`/`timestamp` for
 * entities confirmed insert-only via their DAOs (`attachments`, `medical_history_items`,
 * `medication_entries`, `allergies`, `family_history_entries`, `observations`, `audit_log`,
 * `prescriptions`, `abha_profiles`, `diagnosis_feedback`); `COALESCE(deletedAt, createdAt)` for
 * `ailments` (soft delete via [com.example.samdapp.data.local.dao.AilmentDao.markDeleted] moves
 * the real last-touch time later than `createdAt`); `timestamp` for `referrals` (status-transition
 * history predates any timestamp column and is unrecoverable, flagged in PROGRESS.md, not
 * fixed); `COALESCE(inferenceEndedAt, inferenceStartedAt)` for `kernel_reports` and
 * `evaluate_reports` (neither has a write-time column of its own); and, for `medication_lines`
 * (which has no timestamp of its own at all), a correlated subquery to its parent
 * `prescriptions.createdAt`, with a final `COALESCE(..., <this migration's run time>)` fallback
 * for the one case the FK-less schema doesn't prevent: a `medication_lines` row whose
 * `prescriptionId` matches no `prescriptions` row. No row in this migration's own test data hits
 * that fallback; it exists only so a real orphaned row cannot fail this NOT NULL column.
 *
 * Mechanics: SQLite can't `ALTER TABLE ... ADD COLUMN` a NOT NULL column without a constant
 * default, so `syncState` and `localModifiedAt` are added with a placeholder default and then
 * backfilled by `UPDATE`, the same two-step pattern [MIGRATION_5_6] already used for
 * `inferenceStartedAt`. The three nullable columns need no such placeholder.
 */
val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(connection: SQLiteConnection) {
        val migrationRunMillis = Instant.now().toEpochMilli()

        fun addSyncColumns(table: String, localModifiedAtBackfillSql: String) {
            connection.execSQL("ALTER TABLE `$table` ADD COLUMN `syncState` TEXT NOT NULL DEFAULT 'PENDING'")
            connection.execSQL("ALTER TABLE `$table` ADD COLUMN `serverVersion` INTEGER")
            connection.execSQL("ALTER TABLE `$table` ADD COLUMN `syncErrorCode` TEXT")
            connection.execSQL("ALTER TABLE `$table` ADD COLUMN `lastSyncAttemptAt` INTEGER")
            connection.execSQL("ALTER TABLE `$table` ADD COLUMN `localModifiedAt` INTEGER NOT NULL DEFAULT 0")
            connection.execSQL("UPDATE `$table` SET `localModifiedAt` = $localModifiedAtBackfillSql")
        }

        addSyncColumns("patients", "`updatedAt`")
        addSyncColumns("encounters", "`updatedAt`")
        addSyncColumns("consultations", "`updatedAt`")
        addSyncColumns("attachments", "`createdAt`")
        addSyncColumns("observations", "`createdAt`")
        addSyncColumns("ailments", "COALESCE(`deletedAt`, `createdAt`)")
        addSyncColumns("medical_history_items", "`createdAt`")
        addSyncColumns("allergies", "`createdAt`")
        addSyncColumns("family_history_entries", "`createdAt`")
        addSyncColumns("social_histories", "`updatedAt`")
        addSyncColumns("medication_entries", "`createdAt`")
        addSyncColumns("case_records", "`updatedAt`")
        addSyncColumns("kernel_reports", "COALESCE(`inferenceEndedAt`, `inferenceStartedAt`)")
        addSyncColumns("evaluate_reports", "COALESCE(`inferenceEndedAt`, `inferenceStartedAt`)")
        addSyncColumns("diagnosis_feedback", "`createdAt`")
        addSyncColumns("prescriptions", "`createdAt`")
        addSyncColumns(
            "medication_lines",
            "COALESCE((SELECT p.`createdAt` FROM `prescriptions` p WHERE p.`id` = `medication_lines`.`prescriptionId`), $migrationRunMillis)",
        )
        addSyncColumns("referrals", "`timestamp`")
        addSyncColumns("abha_profiles", "`createdAt`")
        addSyncColumns("audit_log", "`timestamp`")
    }
}

/**
 * Phase 6c: ABDM-verified identity provenance on `patients` (REQ-ABH-01/02), so a real
 * `AbhaIdentity` response's verification fields (api-contract.md §8) survive autofill instead
 * of being silently dropped. Four new nullable columns, all default to SQLite's implicit
 * `NULL` (no placeholder-then-backfill two-step needed, unlike MIGRATION_12_13's NOT NULL
 * columns) — a manually-registered patient with no ABHA has none of them:
 *
 * - `abhaAddress` (`AbhaIdentity.abha_address`, e.g. `"sunita.devi@sbx"`).
 * - `kycVerified` (`AbhaIdentity.kyc_verified`, `INTEGER` boolean). Deliberately not a
 *   multi-state "status" column: `docs/requirements/abha-internal-contract.md`'s field-by-field
 *   diff against the real ABDM response confirms the pinned `AbhaIdentity` shape has no ABHA
 *   account-status field at all (the real `status`/`"ACTIVE"` value is confirmed dropped, never
 *   mapped) — an `abhaStatus` column here would sit permanently unpopulated. See PROGRESS.md.
 * - `verificationSource` (`AbhaIdentity.verification_source`, a backend-assigned workflow
 *   constant like `"ABDM_AADHAAR_OTP"`, not derived from any single ABDM field).
 * - `verifiedAt` (`AbhaIdentity.verified_at`, the SaMD backend's own verification-transaction
 *   timestamp, distinct from the ABHA account's own creation date).
 *
 * Registration STATE (session id, in-progress step) never lands here — it lives in the
 * backend's `abha_transactions` table; only the final verified provenance does.
 */
val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE `patients` ADD COLUMN `abhaAddress` TEXT")
        connection.execSQL("ALTER TABLE `patients` ADD COLUMN `kycVerified` INTEGER")
        connection.execSQL("ALTER TABLE `patients` ADD COLUMN `verificationSource` TEXT")
        connection.execSQL("ALTER TABLE `patients` ADD COLUMN `verifiedAt` INTEGER")
    }
}

/**
 * H-14 evaluate-failure signal (docs/quality/h-14-evaluate-failure-decision.md, Option 2): a
 * nullable local-only marker on `evaluate_reports`, non-null when `/api/v1/evaluate` was
 * attempted and failed. Every pre-existing row gets `NULL` (a real report), which is Room's
 * default `ADD COLUMN` behavior with no explicit default clause.
 *
 * Deliberately excluded from `EvaluateReportSyncPayloadDto`: this column must never cross the
 * sync wire, both because a failure carries nothing the backend's `evaluate_reports` schema has
 * a place for, and because the H-09-tail defect (migration 0006 on the backend) is the concrete
 * proof of what happens when a device-only enum/state value leaks onto the wire unannounced.
 * `EvaluateReportDao.getPendingForSync`'s `failureCode IS NULL` predicate is the actual
 * enforcement point — this column existing at all is what makes that predicate possible.
 */
val MIGRATION_14_15 = object : Migration(14, 15) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE `evaluate_reports` ADD COLUMN `failureCode` TEXT")
    }
}

/**
 * One current assessment per case: makes `caseRecordId` UNIQUE on `kernel_reports` and
 * `evaluate_reports`, after de-duping the rows already there.
 *
 * Both tables' KDoc has always claimed "one row per case, replaced wholesale on retry", and both
 * repositories rely on that claim (`getForCase` takes `.first()` of the match set). It was only
 * ever true for `evaluate_reports`, and only since the H-14 pass added
 * `EvaluateReportDao.getIdForCase`. On `kernel_reports` it was never true: `GenerateKernelReport-
 * UseCase` mints a fresh `UUID.randomUUID()` per attempt, the primary key is that `id`, and the
 * REPLACE-upsert therefore keyed on a value that never collided — so every re-assessment of a case
 * (a retry after an `InferenceSource.UNAVAILABLE` failure, most of all) inserted an ADDITIONAL row,
 * and the five `getForCase` readers picked between them with no `ORDER BY` at all. Clinically that
 * is the sharpest edge here: an unordered `.first()` can hand a reviewer the superseded failure
 * row while the successful retry sits in the same table unread.
 *
 * **De-dup rule: newest write wins.** A successful retry must beat the failure row it replaced, so
 * the surviving row per `caseRecordId` is the one with the greatest `localModifiedAt`. That column
 * is the right key rather than `inferenceEndedAt` because it is defined as "when this row's bytes
 * last changed on this device", whereas `inferenceEndedAt` is clinical inference timing and is a
 * placeholder on a failure row. It is safe to sort on: NOT NULL on both tables, and although
 * MIGRATION_12_13 added it as `NOT NULL DEFAULT 0` it backfilled every existing row in the same
 * step from `COALESCE(inferenceEndedAt, inferenceStartedAt)`, both NOT NULL — no row carries 0.
 * Ties (two writes for one case inside the same millisecond — a network round trip apart in
 * practice, but not provably impossible) break on `rowid DESC`, which is insertion order on these
 * rowid tables, so the outcome is deterministic rather than left to SQLite's choice. The
 * correlated subquery below exists for that tiebreak: the shorter `MAX()`-with-bare-columns form
 * SQLite allows would leave which row's `rowid` is returned implementation-defined.
 *
 * **Deleted duplicates are not recoverable and are not pushed anywhere.** The DELETE is
 * device-local. No foreign key anywhere references either table's `id` (checked against schema
 * 15), and the sync outbox is a query over `syncState` rather than a separate table
 * (`RoomSyncOutboxRepository`), so a discarded PENDING row simply leaves the outbox with no
 * orphan behind. A discarded row that was already SYNCED does still exist on the backend: this
 * migration cannot reach it, and enforcing the same invariant server-side is a stated Phase 4
 * sync-push requirement (`docs/backend/api-contract.md` §6.1), deliberately not mixed into this
 * commit.
 *
 * **Expected first-launch effect:** where the surviving row is PENDING and the discarded one had
 * been SYNCED, the case re-pushes under the surviving `id`. That is the intended outcome — newest
 * content should win on the server too — but it does mean a one-time push spike on the first sync
 * after upgrade. Recorded in PROGRESS.md's deploy notes so it is not misread as a sync fault.
 *
 * The index name is reused rather than suffixed: Room names an index `index_<table>_<column>`
 * whether or not it is unique, so the exported schema 16 expects exactly these names.
 */
val MIGRATION_15_16 = object : Migration(15, 16) {
    override fun migrate(connection: SQLiteConnection) {
        for (table in listOf("kernel_reports", "evaluate_reports")) {
            connection.execSQL(
                """
                DELETE FROM `$table`
                WHERE `rowid` NOT IN (
                    SELECT (
                        SELECT r.`rowid` FROM `$table` AS r
                        WHERE r.`caseRecordId` = g.`caseRecordId`
                        ORDER BY r.`localModifiedAt` DESC, r.`rowid` DESC
                        LIMIT 1
                    )
                    FROM `$table` AS g
                    GROUP BY g.`caseRecordId`
                )
                """.trimIndent(),
            )
            connection.execSQL("DROP INDEX IF EXISTS `index_${table}_caseRecordId`")
            connection.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_${table}_caseRecordId` " +
                    "ON `$table` (`caseRecordId`)",
            )
        }
    }
}

/**
 * ASR track PR 1 (`scratchpad/asr-field-audit-memo.md` Part B.2): adds
 * `impactOnDailyActivitiesProvenance` to `consultations`, the first (and so far only)
 * [com.example.samdapp.domain.model.FieldProvenance] column. No UI or ASR code writes a
 * non-`TYPED` value yet — see `FieldProvenance`'s KDoc.
 *
 * Backfill: every existing row's `impactOnDailyActivities` was typed (no voice capture path has
 * ever existed on a shipped build), so backfilling to `TYPED` is a statement of fact, not an
 * assumption — this is the memo's own reasoning for the `TYPED` default value, applied here to
 * every pre-existing row rather than leaving them `NULL`/"unknown".
 */
val MIGRATION_16_17 = object : Migration(16, 17) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "ALTER TABLE `consultations` ADD COLUMN `impactOnDailyActivitiesProvenance` TEXT",
        )
        connection.execSQL(
            "UPDATE `consultations` SET `impactOnDailyActivitiesProvenance` = 'TYPED'",
        )
    }
}

/**
 * H-18, Build 3a (consultation documents: storage, audit, retract, direct-file upload). Purely
 * additive — one new table, no existing table altered, mirroring [MIGRATION_2_3]'s new-table
 * shape. `departmentCode`/`recordTypeCode` are stored as their enum `.name` (TEXT), same
 * convention as every other enum column in this schema (see `Converters`), and deliberately carry
 * no CHECK constraint: [com.example.samdapp.domain.model.RecordTypeCode] is operator-signed
 * PROVISIONAL, and a future code-list change must not require a migration.
 */
val MIGRATION_17_18 = object : Migration(17, 18) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `consultation_documents` (`id` TEXT NOT NULL, " +
                "`consultationId` TEXT NOT NULL, `patientId` TEXT NOT NULL, `abhaNumber` TEXT, " +
                "`label` TEXT NOT NULL, `canonicalName` TEXT NOT NULL, `departmentCode` TEXT NOT NULL, " +
                "`recordTypeCode` TEXT NOT NULL, `storageKey` TEXT NOT NULL, `mimeType` TEXT NOT NULL, " +
                "`sizeBytes` INTEGER NOT NULL, `sha256` TEXT NOT NULL, `source` TEXT NOT NULL, " +
                "`uploadedAt` INTEGER NOT NULL, `uploaderUserId` TEXT NOT NULL, `uploaderRole` TEXT NOT NULL, " +
                "`retractedAt` INTEGER, `retractionReason` TEXT, `syncState` TEXT NOT NULL, " +
                "`serverVersion` INTEGER, `syncErrorCode` TEXT, `lastSyncAttemptAt` INTEGER, " +
                "`localModifiedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_consultation_documents_consultationId` " +
                "ON `consultation_documents` (`consultationId`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_consultation_documents_patientId` " +
                "ON `consultation_documents` (`patientId`)",
        )
    }
}

/**
 * H-18, Build 3b. Adds the `pageCount` column the Build 3a table shipped without.
 *
 * The operator-signed design memo (`scratchpad/consultation-documents-and-prescription-gate-memo.md`,
 * B3) listed `pageCount Int?` in the `consultation_documents` column table; `MIGRATION_17_18` and
 * the exported `18.json` omitted it. Build 3b needs it: `DocumentSource.CAMERA_ASSEMBLED` rows
 * record how many captured pages were consolidated into the assembled PDF, and the
 * `DOCUMENT_UPLOADED` audit payload carries that number at write time.
 *
 * Purely additive and nullable with no default: every existing row is `DIRECT_FILE`, for which a
 * page count was never measured, so NULL is the honest value and there is nothing to backfill.
 * `ALTER TABLE ... ADD COLUMN` on a nullable column is SQLite's one always-safe alter.
 */
val MIGRATION_18_19 = object : Migration(18, 19) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE `consultation_documents` ADD COLUMN `pageCount` INTEGER")
    }
}
