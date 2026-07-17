# Data retention & deletion posture

Per-table record of how each Room entity is deleted (or not). The point: **nothing in this app
hard-deletes clinical or audit data today.** Before adding any `@Delete`, `DELETE FROM`, or a
destructive migration, check this file — several tables are append-only *by design* (regulatory /
audit reasons), not just by omission, and changing that is a deliberate decision, not a cleanup.

DB: `samd_app.db` (SQLCipher-encrypted), current schema **version 7**. Room entities live in
`data/local/entity/`, DAOs in `data/local/dao/`.

## Legend

- **Insert-only (locked):** no update and no delete path exists, and one must never be added —
  the append-only property is the feature.
- **Soft-delete:** rows are marked deleted (a timestamp column), never physically removed; the row
  and its history survive.
- **Mutable, no-delete:** rows can be inserted and updated, but there is no delete path today.
- **Reference/seed:** static-ish reference data seeded by the app, replaced wholesale if ever.

## Tables

| Table | Entity | Posture | Notes |
|-------|--------|---------|-------|
| `audit_log` | `AuditLogEntity` | **Insert-only (locked)** | `AuditLogDao` has only `@Insert` + read queries; its KDoc forbids adding `@Update`/`@Delete`. REQ-AUD-02. The per-worker read-side (`AuditLogRepository`) is intentionally retained for a future audit-export/review surface even though no screen renders it after the Profile "Recent activity" section was removed as clutter. |
| `ailments` | `AilmentEntity` | **Soft-delete** | `AilmentDao.markDeleted` stamps `deletedAt`; rows are never physically removed. Private-ailment audio is delete-only at the *file* level, but the DB row persists. |
| `patients` | `PatientEntity` | Mutable, no-delete | Registration + updates only. |
| `encounters` | `EncounterEntity` | Mutable, no-delete | Includes `followUpOfEncounterId` (self-referential follow-up link, no FK constraint). A closed encounter is never edited/deleted — a correction is a new encounter. |
| `consultations` | `ConsultationEntity` | Mutable, no-delete | Insert + transcription update. |
| `observations` | `ObservationEntity` | Insert + read | Vitals snapshots. |
| `case_records` | `CaseRecordEntity` | Mutable, no-delete | Status/doctor-assignment updates only. |
| `prescriptions` / `medication_lines` | `PrescriptionEntity` / `MedicationLineEntity` | Insert + read | Doctor-intake results. |
| `kernel_reports` | `KernelReportEntity` | Insert (upsert) + read | `@Insert(REPLACE)` upsert per case. |
| `referrals` | `ReferralEntity` | Insert + status update | Never advances past QUEUED in the mock. |
| `abha_profiles` | `AbhaProfileEntity` | Insert + read | Mock ABHA identities. |
| `medical_history_items`, `allergies`, `family_history_entries`, `social_histories`, `medication_entries` | (respective) | Insert / update, no-delete | Medical background. |
| `attachments` | `AttachmentEntity` | Insert + read | Consultation attachments (URIs). |
| `doctors` | `DoctorEntity` | **Reference/seed** | Seeded with 9 mock doctors on fresh install (`RoomDatabase.Callback.onCreate`) and by `MIGRATION_6_7` on upgrade. Real onboarding is out of scope; if replaced, replace wholesale. |

## Historical exception

`MIGRATION_3_4` dropped the `symptoms` table after copying its rows into `ailments` (the
Complaints→Ailments rename). That was a one-time schema migration, not a row-deletion path, and the
data was preserved in `ailments`.
