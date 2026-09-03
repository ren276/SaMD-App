package com.example.samdapp.domain.model

import java.time.Instant

/**
 * Clinical service-line vocabulary for the `<DepartmentCode>` slot of a consultation document's
 * canonical name (H-18, Build 3a). Operator-signed, Option 1 (specialty/service-line), 17 codes,
 * derived from the union of [com.example.samdapp.domain.usecase.ResolveDoctorAssignmentUseCase]'s
 * `mapConditionToSpecialty` routing targets and the seeded `doctors` table
 * (`DatabaseModule.seedDoctorsOnCreate`, `MIGRATION_6_7`/`MIGRATION_11_12`) — see
 * `scratchpad/document-vocab-audit.md` for the full sourcing and the one taxonomy conflict found
 * ([PEDS]/[INFECT_DIS] are seeded but no routing keyword ever assigns a case to them).
 *
 * The worker SELECTS one of these from a dropdown at upload; this is never free text.
 */
enum class DepartmentCode {
    CRIT_CARE, CARDIO, NEURO, PULMO, ORTHO, GASTRO, URO, ENDO, PSYCH, GYNE, DERM, ENT, OPHTHAL,
    INT_MED, GEN_PHYS, PEDS, INFECT_DIS,
}

/**
 * Document-content-category vocabulary for the `<RecordTypeCode>` slot (H-18, Build 3a).
 * **Operator-signed PROVISIONAL** — unlike [DepartmentCode], no repo source exists for this
 * vocabulary (`scratchpad/document-vocab-audit.md` section B: zero authoritative source for a
 * clinical document-category taxonomy anywhere in this repo or its sibling projects). These 6
 * values were operator-picked, not repo-derived, and are marked provisional pending clinical
 * review. Adding/removing a code later is a code-list change only — this enum has deliberately
 * no backing DB CHECK constraint, so the set can change without a migration.
 *
 * The worker SELECTS one of these from a dropdown at upload; this is never free text.
 */
enum class RecordTypeCode {
    LAB_REPORT, IMAGING, DISCHARGE_SUMMARY, EXT_PRESCRIPTION, REFERRAL, OTHER,
}

/**
 * How a [ConsultationDocument]'s bytes were produced. Only [DIRECT_FILE] is emitted by Build 3a
 * (PATH A, an existing PDF/JPEG/PNG the worker already has). [CAMERA_ASSEMBLED] is Build 3b's
 * multi-page camera capture, pre-declared here (same reasoning as `AuditAction.KERNEL_EMPTY_DIFFERENTIAL`
 * being added ahead of its caller) so 3b needs no enum/Converter/schema change, only a new caller.
 */
enum class DocumentSource { DIRECT_FILE, CAMERA_ASSEMBLED }

/**
 * A worker-uploaded clinical document (lab report, discharge summary, external prescription,
 * etc.) attached to a consultation (H-18, Build 3a) — consultation-primary, mirroring
 * [Attachment]'s linkage shape (`consultationId` mandatory and indexed; [patientId] denormalised
 * from the parent consultation at insert, never updated afterwards).
 *
 * Two distinct name-shaped fields, deliberately kept apart (same reasoning as
 * [com.example.samdapp.domain.model.Patient.fullName] never touching a storage key):
 * - [canonicalName] is the display/record name, `<UHID>_<departmentCode>_<uploadedAt date>_<recordTypeCode>.<ext>`
 *   ([uhid] is always [Patient.id], never [abhaNumber] — the national identifier is deliberately
 *   kept off the filename; see the H-18 risk entry).
 * - [storageKey] is the on-disk filename under `filesDir/documents/<consultationId>/`,
 *   `<recordTypeCode>_<epochMillis>_<uuid>.<ext>` — non-identifying, carries no UHID and no
 *   worker-typed text, so it is safe as a raw filesystem path component.
 *
 * [label] is the worker's free-text name for the document (e.g. "Blood test 12 Aug") — metadata
 * only, rendered in the UI, never touches [storageKey] or any path/SQL/log line, same rule
 * [Patient.fullName] follows.
 *
 * [abhaNumber] is carried for display/linkage priority only (REQ-REG-02-adjacent) — deliberately
 * never in [canonicalName] or [storageKey]. See the H-18 risk entry.
 */
data class ConsultationDocument(
    val id: String,
    val consultationId: String,
    val patientId: String,
    val abhaNumber: String?,
    val label: String,
    val canonicalName: String,
    val departmentCode: DepartmentCode,
    val recordTypeCode: RecordTypeCode,
    val storageKey: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256: String,
    val source: DocumentSource,
    val uploadedAt: Instant,
    val uploaderUserId: String,
    val uploaderRole: String,
    val retractedAt: Instant?,
    val retractionReason: String?,
)
