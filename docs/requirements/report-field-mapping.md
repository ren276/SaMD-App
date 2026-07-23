# Report field-mapping table (REQ-RPT-03)

> Binds **every** element rendered on the clinical report (preview + PDF) to its exact source in
> the Phase 0 Room schema / domain model. This is the guard against placeholder creep: if a row
> here has no real source column, it doesn't get drawn. Renderer: `presentation/report/
> ReportCanvasRenderer` (one `android.graphics.Canvas` layout for both the Compose preview and the
> `PdfDocument` export). Assembly: `domain/report/ReportFormatter` ← `domain/usecase/
> AssembleReportUseCase`.

Layout emulates a standard AIIMS outpatient card. Preliminary report = the pre-kernel artifact
(`kernelOutput` null, `prescription` empty, `signature` null). The final report is the **same**
object once Phases 4/5 populate those sections.

## 1. Header metadata block

| Rendered element | Source |
|---|---|
| Logo slot (top-left) | `res/drawable-nodpi/logo.png` (real institutional logo, same asset as Home) via `decodeReportLogo()`; falls back to a bordered "LOGO" placeholder only on decode failure |
| System title "PRIMARY HEALTH CENTER DIGITAL HEALTH SYSTEM" | Fixed system label |
| PHC centre name | `Patient.primaryCareClinicName` (fallback "Primary Health Centre") |
| CR No | `CaseRecord.id` → `ReportHeader.consultationRecordNo` |
| Barcode (top-right) | `Patient.id` via `Code128.encodeB` |
| UID text under barcode | `Patient.id` |

## 2. Two-column patient demographic matrix

| Rendered element | Source |
|---|---|
| Patient (name) | `Patient.fullName` |
| Guardian + relation (pediatric only) | `Patient.guardianOrSpouseName` + `Patient.guardianRelation`, shown only when age < 18 (`Patient.age` or derived from `Patient.dateOfBirth`) |
| Address | `Patient.village/block/district/state/pincode` joined |
| Mobile | `Patient.mobileNumber` |
| Category | `Patient.category` |
| Age / Sex | `Patient.age` (or from `dateOfBirth`) + `Patient.biologicalSex` |
| Visit date/time (DD-MMM-YYYY HH:MM) | `Encounter.startedAt` (fallback `CaseRecord.createdAt`) |
| ABHA Number (XX-XXXX-XXXX-XXXX) | `Patient.abhaNumber` via `formatAbhaId` |
| ABHA Address | `AbhaProfile.abhaAddress` (looked up by `Patient.abhaNumber`) |
| "✓ Verified via ABHA" tag | `AbhaProfile.kycVerified` |

## 3. Clinical summary & advice block

| Rendered element | Source |
|---|---|
| Section header "Primary Ailments & Clinical Findings" | Fixed label |
| Main concern (verbatim, quoted) | `Consultation.chiefComplaint` (DB/code field name unchanged; UI label is "Main concern") |
| Measurable ailment lines (◆) | `AilmentEntry` where `measurementType == MEASURABLE`: `description` + `measuredValue`/`measuredUnit` |
| Non-measurable ailment lines (•) | `AilmentEntry` where `NON_MEASURABLE`: `description` + `severity`/`duration`/`onset`/`qualifiers` |
| Redacted private line ("🔒 Private entry") | `AilmentEntry.visibility == PRIVATE` under `ReportAudience.WORKER` — text intentionally absent |
| Vitals table | `VitalsSnapshot` (reassembled from `Observation` rows): pulse/BP/SpO₂/temp/RR/weight/height/BMI/glucose/pain/urinalysis |
| "Attachments" section — photo/affected-area photo thumbnails | `Consultation.attachments` (`AttachmentType.IMAGE`/`AFFECTED_AREA_PHOTO`) → `ReportAttachmentEntry`, decoded via `decodeAttachmentBitmap()`; unmodified pass-through, same posture as `KernelPayload.attachments` |
| "Attachments" section — audio/video lines | `Consultation.attachments` (`AttachmentType.AUDIO`/`VIDEO`) → labeled text line (no playback on a static page) |
| "Rx / Advice" marker | Fixed label (rendered only when a prescription/diagnosis exists) |
| Diagnosis | `Prescription.diagnosis` |
| "Doctor's review of AI assessment: …" | `Prescription.kernelDecision` (`KernelDecision.AGREE/MODIFY/REJECT`) — set by `SubmitDoctorDecisionUseCase` from the reviewer's real in-app pick, REQ-RX-03 (2026-07: no longer a mock intake) |
| Numbered medication lines | `MedicationLine` → `[genericName] ([brandName]) - [strength] \| [route] \| [frequency] \| [duration] \| [quantity]` |

## 3a. AI Clinical Evaluation block (`/api/v1/evaluate`, REQ-EVL-03 — 2026-07)

> Renders only when `ClinicalReport.evaluateOutput` is non-null (the `/api/v1/evaluate` call
> succeeded for this case — no mock fallback exists for this leg, see REQ-HAN-08/EVL-01). Supersedes
> the removed "Kernel AI Assessment" block (old `/v1/assess`-sourced `KernelReportOutput` display) —
> that field/model still exists and still feeds the AI Assessment Panel's fallback path, but is no
> longer drawn on the printed report/prescription. Section header: "AI Clinical Evaluation".
> Rendered by `ReportCanvasRenderer.evaluateBlock`, in this fixed order:

| Rendered element | Source |
|---|---|
| Vitals triage line (BP/pulse/SpO₂/temp/resp/BMI[/glucose] grades) | `EvaluateReportOutput.safetyAndTriage.vitalsTriage` (`EvaluateVitalsTriage`) — omitted if null |
| Diagnosis (AI) — top candidate only, bold | `EvaluateReportOutput.diagnosticSummary.primaryAilmentName` + `.primaryIcdCandidate` (NOT the full differential list — that's a physician-review-screen detail, not a prescription one) |
| Recommended treatment — drug + dosage forms, bold | `EvaluateReportOutput.nlemTreatment.recommendedDrug` + `.dosageForms` |
| Brand (India, AI-suggested), bold | `EvaluateReportOutput.topIndianBrand.displayName` (`IndianBrandSuggestion.brandName`/`.companyName`, via Gemini, REQ-EVL-02) — "Not available" if null |
| "⚠ Requires physician verification…" (bold red, conditional) | `EvaluateReportOutput.safetyAndTriage.requiresHumanReview` |
| "⚠ Pediatric referral required." (bold red, conditional) | `EvaluateReportOutput.safetyAndTriage.pediatricReferralFlag` |
| "Vitals rule-check urgency: …" (bold; bold red if `urgent-review`) | `EvaluateReportOutput.safetyAndTriage.vitalsTriage.overallUrgency` — deterministic vitals-threshold bucketing, independent of ML classifier's `triage_urgency` (see reasoning summary) |
| Inference time (small line, bottom, reference only) | `Duration.between(EvaluateReportOutput.inferenceStartedAt, .inferenceEndedAt)` |

## 4. Legal footer & consent segment

| Rendered element | Source |
|---|---|
| Consent statement (fine print) | `ReportFormatter.CONSENT_STATEMENT` — "Patient has given explicit consent to create, link, and share digital health records asynchronously under ABDM guidelines." |
| Signature line (double-underlined, bottom-right) | "Physician Verification Node / Reg No: " + `Doctor.registrationNumber` (via `Prescription.doctorId`); "pending" on the preliminary report |
| Disclaimer | `ReportFormatter.DISCLAIMER` — "AI-Assisted, Physician-Verified" |
| Page x-of-n | Renderer pagination |

## Referral action (Phase 6, not printed on the canvas)

`ClinicalReport.suggestsReferral`/`referralReasonSuggestion` are UI-only computed fields consumed
by `ReportScreen`'s "Refer to Higher Facility" button/sheet — they do not appear on the rendered
report/PDF itself, so they have no canvas row above. Source: `ReportFormatter` computes them from
`AilmentEntry.severity` (max, non-deleted) and `Prescription.kernelDecision`.

## Notes

- **Full-text frequency (REQ-RX-02):** `ReportFormatter.formatMedicationLine` throws on OD/BD/TDS/
  QID/SOS/HS/etc. — the report is the last line of defence, not just the doctor entry form.
- **Privacy propagation (REQ-AIL-02/04):** redaction of PRIVATE ailments happens in the formatter
  per `ReportAudience`, so a WORKER report's data model has no private text at all. The PHYSICIAN
  report (final, doctor-facing) shows everything — the private entries were always meant for the
  clinician.
- **No unmapped placeholders:** the only non-data elements are the logo box and the fixed section
  labels/system title. Every patient/clinical value binds to a real column above.
