# Build 3a developer README — consultation documents: storage, audit, retract, direct-file upload, safe viewer

**Branch:** `feat/consultation-documents-storage`. **Status:** built, tested, NOT committed —
awaiting operator authorization. Read `scratchpad/consultation-documents-and-prescription-gate-memo.md`
Feature 1 (B1, B3, B4, B5, B6, B8, B9) first, plus `scratchpad/document-vocab-audit.md` for where
the two controlled vocabularies came from. This doc is orientation on top of both, not a
replacement.

## Scope: what this build is, and isn't

**In scope (built):** encrypted local storage, the metadata row, retract, PATH A (an existing
PDF/JPEG/PNG the worker already has), the safe in-app viewer, three audit actions + backend
mirror, an interim (conservative) role gate.

**Out of scope, explicitly:**
- **PATH B, camera multi-page capture** — Build 3b.
- **The real cadre role gate** — Build 3c, needs the cadre-scope model from Build 2 first. This
  build ships a conservative interim gate instead (see below).
- **Backend sync of the document row or its bytes** — the metadata row carries the standard sync
  columns (`syncState` etc.) because the brief's schema asked for them, but nothing pushes them.
  No backend table or endpoint for `consultation_documents` exists. The design memo's B8 puts
  "push of document bytes to AWS/S3" as a PRE-PRODUCTION GATE, not build-now — this matches that.
  Only the three **audit actions** got a backend mirror (a much smaller, already-established
  pattern from Build 1), not the document table itself.

## Entity + migration

`ConsultationDocumentEntity` (`data/local/entity/ConsultationDocumentEntity.kt`) mirrors
`AttachmentEntity`'s linkage shape: `consultationId` mandatory and indexed, `patientId`
denormalised and indexed, derived at insert from the consultation and never updated after (a real
DB-round-trip test proves this — `MigrationTest17To18` plus
`ConsultationDocumentRepositoryImpl.upload` resolving `patientId` via a new
`ConsultationRepository.getById(consultationId)` lookup, never a caller-supplied value).

`MIGRATION_17_18`, DB version 17 → 18. Purely additive: one `CREATE TABLE IF NOT EXISTS
consultation_documents` plus two `CREATE INDEX IF NOT EXISTS` (consultationId, patientId). No
existing table touched. `app/schemas/.../18.json` committed alongside. Verified on-device against
a real SQLCipher database (`MigrationTest17To18`, `connectedDevDebugAndroidTest`, 2/2 passed on
`emulator-5554`) — both the migrated-from-17 path and the fresh-install-at-18 path.

## Naming: canonical name vs. storage key, and where ABHA does and doesn't appear

Two distinct name-shaped fields, built in `ConsultationDocumentRepositoryImpl.upload`:

- **`canonicalName`** (display/record name): `<UHID>_<DepartmentCode>_<YYYYMMDD>_<RecordTypeCode>.<ext>`.
  `UHID` is **always `Patient.id`** — the 12-char always-present local id, the same value the
  report pipeline already uses as `patientUid` (confirmed in the earlier vocab audit). `YYYYMMDD`
  is the evidentiary `uploadedAt` date (device zone), never a worker-entered date. `<ext>` comes
  from the **validated** MIME type, never the source filename.
- **`storageKey`** (the actual on-disk filename, under
  `filesDir/documents/<consultationId>/`): `<RecordTypeCode>_<epochMillis>_<uuid>.<ext>` —
  deliberately non-identifying. No UHID, no label, nothing worker-typed anywhere in it.

**ABHA number is deliberately never in either name.** It's stored on the metadata row
(`abhaNumber: String?`, nullable) for display/linkage priority only. This keeps the national
health identifier off the filesystem, off logs, and off any future sync manifest — a decision the
memo already leaned toward for other reasons, applied consistently here. Stated as a deliberate
decision in the H-18 risk entry, not left implicit.

Every component of both names is already system-generated (a UUID, an enum's `.name`, digits, the
12-char UID) — nothing free-text ever reaches either name. The `ReportPdfExporter` sanitiser
(`.replace(Regex("[^A-Za-z0-9_-]"), "_")`) is still applied to each component anyway, as
defense-in-depth per the brief's instruction, even though nothing it would actually catch can
occur under this naming spec. `label` (the worker's free text) never touches either name — it's a
metadata column only, rendered in the UI, same rule `Patient.fullName` already follows.

## The two controlled-vocabulary enums

`DepartmentCode` (17 values) and `RecordTypeCode` (6 values, **provisional**) both live in
`domain/model/ConsultationDocument.kt`, plain Kotlin enums (matching every other small controlled
enum in this schema — `.name` is the wire/storage value via `Converters`, no separate string field
the way `AuditAction` has). Both are **worker-selected from a dropdown**, never free text, at
upload (`ConsultationScreen.kt`'s "Upload reports, if any" section).

`RecordTypeCode` deliberately has **no DB CHECK constraint** — the brief's instruction, so a future
change to the provisional code list is a code-list change only, not a migration. Enforced in app
and (if it ever needs backend awareness) backend code, not schema.

## Storage: Keystore key, encryption, magic-byte validation

`DocumentEncryptionProvider` (`data/local/security/`) is `DatabasePassphraseProvider`'s Keystore
pattern copied exactly — same `KeyGenParameterSpec` shape (`BLOCK_MODE_GCM`,
`ENCRYPTION_PADDING_NONE`, 256-bit, non-exportable), different alias (`samd_document_key`,
separate from `samd_db_passphrase_key` so a document-key rotation or loss doesn't take the
database with it). Per-file random 12-byte IV, written as the first 12 bytes of the ciphertext
file. AES-GCM is authenticated: a corrupt/tampered file throws
`DocumentDecryptionFailedException` on decrypt rather than producing garbage —
`DocumentViewerViewModel` catches this and sets an explicit "this document cannot be opened on
this device" error state, never a blank view.

`DocumentTypeValidator` (`domain/document/`, pure Kotlin, no Android dependency) does the
magic-byte sniffing: PDF (`25 50 44 46 2D`), JPEG (`FF D8 FF`), PNG (`89 50 4E 47 0D 0A 1A 0A`),
nothing else. `ConsultationDocumentRepositoryImpl.upload` reads the file's header bytes,
detects the real type, and rejects (no bytes written, no row inserted) on either an unrecognised
signature or a claimed-vs-detected mismatch. **7 unit tests** in `DocumentTypeValidatorTest`,
including an explicit "a file with no matching magic bytes is rejected" case and a "detection
ignores the claim, only the content" case — the operator's "a mis-typed file rejected" verify item.

Size cap: 20 MB, enforced **while streaming** inside `DocumentEncryptionProvider.encryptToFile`
(a manual copy loop that throws `DocumentTooLargeException` the moment the running total exceeds
the cap, mid-copy — not just a pre-flight length check, which a lying content provider could
defeat).

## The safe viewer

`DocumentViewerScreen`/`DocumentViewerViewModel` (`presentation/documents/`). PDF via
`android.graphics.pdf.PdfRenderer` (first page rendered at upload, next/previous for multi-page),
images via `BitmapFactory` with `inSampleSize` downscaling (max 2048px on the long edge). Never
`Intent.ACTION_VIEW`, never a `FileProvider` grant for documents — `res/xml/file_paths.xml` was
**not** touched, per the brief.

**The one plaintext-on-disk window, stated plainly rather than buried:** `PdfRenderer` needs a
real seekable file, so viewing decrypts to a `cacheDir/document_viewer_temp/<documentId>.<ext>`
temp file. Deleted in `DocumentViewerViewModel.onCleared()` on a clean exit; a process death
mid-view would skip that, so `SaMDApplication.onCreate()` sweeps the whole temp subdirectory at
every app start (`sweepOrphanedViewerTempFiles`), regardless of how the process died.
`DocumentViewerRoute` is registered in `Routes.kt`'s `SECURED_ROUTE_TYPES`, so `FLAG_SECURE`
applies in staging/prod.

## Audit: three actions, backend mirror, real-insert tests

`DOCUMENT_UPLOADED`, `DOCUMENT_VIEWED`, `DOCUMENT_RETRACTED` — all landed on the Kotlin
`AuditAction` enum **and** `backend/core/app/domain/audit_actions_device.py` in this commit, set-
agreement test green (`test_audit_actions_device.py`), plus three new real-insert-and-persist
tests in `test_sync.py` (the "real insert, not set-membership" requirement — each pushes a record
through `/api/v1/sync/push` and asserts the persisted `AuditEvent` row from a fresh DB query).
None of the three payloads ever carries the worker's free-text `label`.

`DOCUMENT_VIEWED` fires only when content is actually decrypted and rendered
(`DocumentViewerViewModel.loadContent`, once, guarded by a `viewedAudited` flag), never when a
list row is drawn on `PatientSummaryScreen`.

**These are insert-only device rows with no on-device hash chain** — same C-2 finding from the
prescription-gate build. They become tamper-evident only once they sync into the server hash
chain. The risk entry says this explicitly.

## Retract

`RetractConsultationDocumentUseCase`: the metadata row is never deleted (`retractedAt` +
optional `retractionReason` set via `ConsultationDocumentDao.retract`, the DAO's only mutation
method — no generic update/delete). The `DOCUMENT_UPLOADED` row stays; a `DOCUMENT_RETRACTED` row
is added alongside it. Encrypted bytes are deleted (`File.delete()`) — **not physical erasure on
flash storage**, stated as such in the risk entry; adequate only because plaintext never touched
disk during upload (the encryption path never writes an intermediate plaintext file).

**Who can retract:** the uploader, or any `UserRole.DOCTOR` — checked against the live
`AuthSession`, not a caller-supplied actor. Same **H-06 caveat** as Build 1's decision-surface
gate: role is self-asserted at login, so this is an accountability/intent gate, not access
control. Stated in the use case's own KDoc and in the risk entry, not just assumed.

## The interim role gate (3a only — 3c replaces it)

`DocumentViewerViewModel`: raw decrypted content renders only for the uploader or a
`UserRole.DOCTOR`. Every other role sees the document's metadata (label, department, record type,
that it exists) via `DocumentMetadataOnly` in the viewer screen — never the decrypted bytes. The
document list on `PatientSummaryScreen` shows every document to every role (metadata only,
nothing gated at the list level); the gate is enforced once, inside the viewer, not duplicated.

This is explicitly conservative and explicitly temporary — the brief and the risk entry both say
so. It blocks a CHO (once that cadre exists in Build 2) from lab reports they're trained to read,
which is the "clinically obstructive" failure mode the domain scope document warns about for a
`DOCTOR`-only interim gate. Build 3c replaces this with the real cadre-scope model.

## Deferred-commit pattern (a real constraint, not a stylistic choice)

`ConsultationRoute` never carries a pre-existing `consultationId` — a fresh `Consultation` row is
always created on Send (`SaveConsultationUseCase`), so `consultation.id` genuinely does not exist
until that moment. Documents are queued in `ConsultationViewModel`'s `pendingDocuments` list
(worker picks a file + selects both dropdowns + optional label → queued, not yet touched on disk)
and only actually encrypted + inserted in the same post-save loop `pendingAttachments` already
uses, right after `consultation.id` exists. This mirrors an existing pattern in the codebase
exactly — it isn't a new design, it's the only design that works given how `ConsultationScreen`
is structured. `UploadConsultationDocumentUseCase.upload` takes a `sourceUri: String` (an opaque
content-URI string, resolved to bytes only inside the data-layer repository via
`ContentResolver.openInputStream`), not an open stream — keeping the ViewModel layer exactly as
URI-agnostic as it already is for `pendingAttachments`.

**A document-upload failure at Send time is non-fatal** (same leniency `pendingAttachments`
already has for `addAttachmentUseCase`) — surfaced via `errorMessage`, doesn't block the
consultation from sending.

## Where documents are reachable from

`PatientSummaryScreen` gained a "Documents" section (visible whenever
`PatientSummaryUiState.documents` is non-empty), resolved via a new reactive chain in
`PatientSummaryViewModel` (`caseRecord → encounterId → consultation → consultation.id →
documents`), each row navigating to `DocumentViewerRoute(documentId)`. This is a new UI section
the brief's UPLOAD UX wording didn't spell out explicitly (it only described the upload
affordance), but a safe viewer needs a real entry point to reach it from, and this is the
smallest one that fits the existing screen structure — flagging it here in case the operator wants
it placed somewhere else instead.

## Test count

`testDevDebugUnitTest`: 334 passed (322 baseline after Build 1 + 12 new: 7 in
`DocumentTypeValidatorTest`, 2 in `UploadConsultationDocumentUseCaseTest`, 3 in
`RetractConsultationDocumentUseCaseTest`), 0 failed.

`connectedDevDebugAndroidTest` (`emulator-5554`, `adb shell am kill-all` run first,
`ANDROID_SERIAL` pinned): `MigrationTest17To18`, 2/2 passed.

Backend `test_audit_actions_device.py` + `test_sync.py`: 31 passed (27 baseline after Build 1 + 4
new: 1 membership test, 3 real-insert tests), 0 failed.

## What's NOT unit-tested, and why (a pre-existing boundary, not a gap this build introduced)

`DocumentEncryptionProvider`, `ConsultationDocumentRepositoryImpl`, and `DocumentViewerViewModel`
all touch Android Keystore/Context directly and have no JVM-unit-test coverage — same boundary
`DatabasePassphraseProvider` already sits behind (no test file for it either, no Robolectric in
this project). The magic-byte logic itself (`DocumentTypeValidator`, pure Kotlin) is fully unit
tested; the encrypt/decrypt round-trip and the repository's rejection paths are exercised only by
reading the code and by the real-device migration test proving the schema/DAO half works. If the
operator wants an instrumented (`androidTest`) round-trip test for the crypto/rejection paths
specifically, that's a reasonable follow-up, not something this build silently skipped without
noting it.

## ID collision note (same situation as Build 1)

The design memo drafted this feature's risk entry as **H-17**; Build 1 already claimed H-17 for
the prescription-visibility-gate hazard. This build's entry is renumbered to **H-18** (the PHI-at-
rest hazard for uploaded documents), and the memo's second entry (existing unencrypted
image/video attachments) becomes **H-19**. Both noted inside their own entries for auditability,
same pattern as Build 1's H-16→H-17 renumber.
