# ASR Field-Reduction Audit + Design Memo (STEP 1, read-only)

Run date: 2026-08-30. Branch `master`, HEAD `a69101c`
("Merge pull request #25 from ren276/fix/resumable-draft-exclusion").
Working tree: no tracked modifications. Two untracked paths only, `dashboard.html` and
`scratchpad/`. `git diff` and `git diff --cached` both empty. Nothing was staged, committed,
or pushed by this session. No file outside `scratchpad/` was written. `.env`,
`local.properties`, `BuildConfig` and every credential file untouched.

Docs read: `CLAUDE.md`, `PROGRESS.md` (3429 lines, structure plus the last four session
entries), `docs/backend/backend-prd.md`, `docs/backend/api-contract.md`,
`docs/requirements/abha-internal-contract.md`, `docs/requirements/software-requirements.md`,
`docs/requirements/intended-use-statement.md`, `docs/quality/risk-management-file.md`,
`docs/quality/qms-overview.md`, `docs/regulatory-foundation.md`, `docs/sbom/README.md`.

---

## 0. STOP-AND-REPORT: four places where the repo contradicts this task's premises

The brief instructed that repo state wins over the research memo and over any assumption in the
brief, and that a contradiction is to be reported rather than designed around. Four were found.
Everything after section 0 is written on top of the corrected picture, not the assumed one.

### C-1 (blocking, safety). ASR is not greenfield. It already ships, and it already fills a field that reaches the backend, with no confirmation gate and no provenance marker.

The brief frames ASR as new work. It is not. A complete voice-to-field path exists today:

- `app/src/main/java/com/example/samdapp/domain/transcription/TranscriptionService.kt`: the
  domain seam, `captureAudioAttachment()` plus `transcribe(audioUri)`.
- `app/src/main/java/com/example/samdapp/data/transcription/AndroidSpeechRecognizerService.kt`:
  the real implementation, Android platform `SpeechRecognizer`,
  `RecognizerIntent.ACTION_RECOGNIZE_SPEECH`, `LANGUAGE_MODEL_FREE_FORM`, language from
  `Locale.getDefault()`.
- Bound as a singleton in `app/src/main/java/com/example/samdapp/di/MockBoundaryModule.kt:35-36`.
- `app/src/main/java/com/example/samdapp/domain/usecase/TranscribeAudioUseCase.kt`: two use
  cases, `CaptureAudioAttachmentUseCase` and `TranscribeAudioUseCase`.
- `app/src/main/java/com/example/samdapp/presentation/transcription/TranscriptionScreen.kt` and
  `TranscriptionViewModel.kt`, routed in
  `app/src/main/java/com/example/samdapp/presentation/navigation/AppNavHost.kt:320-326`.
- `Consultation.transcription` persisted at
  `app/src/main/java/com/example/samdapp/domain/model/Consultation.kt:17`, written by
  `ConsultationDao.updateTranscription` (`app/src/main/java/com/example/samdapp/data/local/dao/ConsultationDao.kt:38-41`).
- `AuditAction.TRANSCRIPTION_COMPLETED` and `AuditAction.AUDIO_CAPTURED` already in the enum
  (`app/src/main/java/com/example/samdapp/domain/audit/AuditLogger.kt:55` and `:32`).

The unsafe part is narrower and worse than "ASR exists":
`ConsultationViewModel.onRecordChiefComplaintVoice()`
(`app/src/main/java/com/example/samdapp/presentation/consultation/ConsultationViewModel.kt:130-137`)
writes the raw transcript straight into UI state:

    _uiState.update { it.copy(isRecordingVoice = false, chiefComplaint = captured.transcript) }

There is no unconfirmed state, no provenance stamp, no diff view, no affirmative tap. The field
it lands in is `Consultation.chiefComplaint`, and that field is assembled into
`KernelPayload.chiefComplaint` (`SendToKernelUseCase.kt:31`) and then joined into the request
body sent to the backend at
`app/src/main/java/com/example/samdapp/data/remote/RetrofitEvaluateSource.kt:45-48`:

    val symptomString = listOfNotNull(
        payload.chiefComplaint.takeIf { it.isNotBlank() },
        payload.transcription?.takeIf { it.isNotBlank() },
    ).joinToString(". ")

So a mis-transcription today can reach `/api/v1/evaluate`, which is the NLEM treatment
recommendation and vitals-triage leg, with nothing between the microphone and the wire. This is
an existing, unregistered hazard. It is not in `docs/quality/risk-management-file.md`. It should
be H-15 before any new ASR work is scheduled.

### C-2 (correction to a stated safety assumption). "The classifier input path" is two different paths, and free text reaches only one of them.

The brief treats "on the classifier input path" as one column. In this repo there are two
backend legs with different input shapes:

- `/v1/assess` (Classifier A, XGBoost differential). Request built at
  `app/src/main/java/com/example/samdapp/data/remote/RetrofitKernelSource.kt:41-53`. It carries
  `caseToken`, `age`, `sex`, `systolicBp`, `diastolicBp`, `bmi`, `heartRate`, `randomGlucose`,
  `spo2`. **Zero free-text fields.** Chief complaint and transcription do not reach this leg at
  all.
- `/api/v1/evaluate` (NLEM treatment plus triage). Request built at
  `RetrofitEvaluateSource.kt:45-68`. It carries `symptomString` (chief complaint plus
  transcription, joined), plus the same numerics.

Practical consequence for the audit: a voice-filled numeric field is dangerous because it
becomes a *measurement* that reaches both legs. A voice-filled free-text field is dangerous
because it reaches the `/api/v1/evaluate` leg. The two need different controls, and the single
yes/no column the brief asked for would have flattened that. The table in Part A therefore
splits the column into `assess` and `evaluate`.

### C-3 (correction). There is no SHA-256 hash chain on the device audit log. It is backend-only.

The brief lists "insert-only audit with SHA-256 hash chain" as a locked device-side property.
On the device, `AuditLogEntity`
(`app/src/main/java/com/example/samdapp/data/local/entity/AuditLogEntity.kt`) has no `prevHash`,
no `entryHash`, no chain column of any kind, and `RoomAuditLogger`
(`app/src/main/java/com/example/samdapp/data/local/audit/RoomAuditLogger.kt`) computes no digest.
`docs/quality/risk-management-file.md` H-07 states this explicitly: control implemented is
"Insert-only audit DAO (no update/delete at interface level)", residual open work is
"Tamper-evidence (hash chain) + export for review".

The chain is real, but it lives on the server:
`backend/core/app/services/audit.py:42-71` (`sha256_hex`, `compute_entry_hash`, genesis hash and
`previous_hash` linkage), verified at `:221-226`. So "keep the chain honest" is a statement about
what the device *emits into sync*, not about a device-side digest. Part B is written accordingly.

### C-4 (correction). `drishti_pipeline` does not exist in this repository.

Part D asks to tie dataset discipline to "the existing drishti_pipeline synthetic-data
discipline if relevant". A case-insensitive search across the working tree (excluding
`graphify-out/`) returns nothing. There is no such directory, module, or doc. If it exists it is
in another repo. Part D is written against what is actually here: `DemoPatientProfile`
(`app/src/main/java/com/example/samdapp/data/mock/`), the dev-flavor-only mock discipline in
`app/src/dev/java/com/example/samdapp/di/DevClinicalMockModule.kt`, and the no-PHI-in-logs
proof pattern in `backend/core/tests/test_abha.py::test_d5_no_phi_in_persisted_row_or_logs`.

### Secondary observations, non-blocking but worth recording

- **Clinical free text is already written into audit payloads.**
  `ConsultationViewModel.onSend()` logs `AuditAction.CONSULTATION_SAVED` with
  `auditPayload("consultationId" to ..., "chiefComplaint" to current.chiefComplaint)`
  (`ConsultationViewModel.kt:203-207`). That payload row syncs to the backend. Any rule of the
  form "no PHI transcript in audit logs" for voice is already violated by the typed path for the
  same field. Flagging, not fixing; it is out of this memo's scope but it constrains what a new
  voice-audit rule can honestly claim.
- **`AndroidSpeechRecognizerService` may make a network call outside the backend.** Platform
  `SpeechRecognizer` routes to the device's recognition service (typically Google's), which is
  cloud-backed unless `RecognizerIntent.EXTRA_PREFER_OFFLINE` is set. It is not set anywhere in
  `AndroidSpeechRecognizerService.kt:59-63`. `AndroidManifest.xml:8` grants `INTERNET`. This
  potentially contradicts the locked principle that all external calls route through the backend,
  and it is a DPDP exposure of patient narrative to a third party that is not covered by H-11
  (which covers only the Gemini brand lookup and explicitly notes only a generic drug name is
  sent). Needs a deliberate check on a real device, not an assumption either way.
- **`transcribe()` is memory-only.** `AndroidSpeechRecognizerService` keeps transcripts in a
  `ConcurrentHashMap` keyed by a synthetic `speech-session://<uuid>` URI (`:33`, `:41-43`). After
  a process death the map is empty and `transcribe()` returns a failure
  ("No transcription for $audioUri", `:52-53`). The `speech-session://` URI is also stored as an
  `AttachmentType.AUDIO` attachment URI, so an attachment can exist whose bytes never existed.
- **Timing nondeterminism on `transcription` reaching the wire.** `AssessmentRunner.resolve()`
  reads the consultation at worker-run time
  (`app/src/main/java/com/example/samdapp/domain/usecase/AssessmentRunner.kt:113-119`), but the
  nav order is Consultation, Sending, KernelAssessment, Transcription
  (`AppNavHost.kt:308-326`), so `Consultation.transcription` is normally still null when the
  queued assessment runs. Whether the transcript reaches `symptomString` therefore depends on
  WorkManager scheduling versus how fast the worker taps through two screens. That is a real
  race, not a designed ordering.

---

## Part A: full textual-field audit of the encounter flow

### A.0 Files that collect operator input for a patient encounter

Every Composable screen plus ViewModel in the encounter flow, complete list, real paths:

| Screen | Path (under `app/src/main/java/com/example/samdapp/presentation/`) | Lines |
|---|---|---|
| Register | `register/RegisterScreen.kt` + `RegisterViewModel.kt` | 330 + 252 |
| Medical background | `medicalbackground/MedicalBackgroundScreen.kt` + `MedicalBackgroundViewModel.kt` | 341 + 181 |
| Compounder (ailments + vitals) | `compounder/CompounderScreen.kt` + `CompounderViewModel.kt` | 411 + 466 |
| Consultation | `consultation/ConsultationScreen.kt` + `ConsultationViewModel.kt` | 272 + 221 |
| Transcription | `transcription/TranscriptionScreen.kt` + `TranscriptionViewModel.kt` | 53 + 60 |
| Kernel assessment | `kernelassessment/KernelAssessmentScreen.kt` + `KernelAssessmentViewModel.kt` | 222 + 230 |
| Patient summary (doctor review) | `patientsummary/PatientSummaryScreen.kt` | doctor-role input |
| Consent | `consent/` | checkbox gate only |
| Doctor assignment confirm | `doctorassignment/DoctorAssignmentConfirmScreen.kt` | selection only |

No text input in: `sending/`, `acknowledgement/`, `emergency/`, `referrals/`, `patients/`,
`home/`, `profile/`, `consultationchain/`, `doctorlist/`, `connectivity/`. Verified by grep for
`OutlinedTextField` across `presentation/`.

Shared input primitives, worth naming because reduction verdicts reuse them:
`presentation/common/NumericInputFilters.kt` (`filterDigitsOnly`, `filterDecimal`),
`presentation/common/DropdownField.kt`, `presentation/common/PermissionAction.kt`
(`rememberPermissionAction`).

### A.1 The inventory

`assess` = reaches `/v1/assess` (Classifier A). `evaluate` = reaches `/api/v1/evaluate`.
"KernelPayload only" = present in `KernelPayload` but dropped before either wire DTO, so it is
carried but not currently consumed by a model.

#### Register screen (`register/RegisterScreen.kt`)

| Field | Type | Current input | assess | evaluate | Verdict | Justification |
|---|---|---|---|---|---|---|
| Full name (`FULL_NAME`, L86) | free text | keyboard | no | no | KEEP-AS-TYPED | Identity. Excluded from `KernelPayload` by construction (`KernelPayload.kt` KDoc, H-10). A mis-transcribed name is a wrong-patient hazard (H-03). Never voice. |
| Age (`AGE`, L87) | numeric | `filterDigitsOnly(max 3)` | **yes** | **yes** | KEEP-AS-TYPED | Reaches both classifiers as `age`. Three keystrokes. Voice buys nothing and risks everything. |
| Date of birth (L273-278) | date | picker | derived to age | derived | PICK-LIST+SKIP-LOGIC | Already a picker. If DOB present, DERIVE age and hide the age field. |
| Mobile (`MOBILE_NUMBER`, L88) | numeric | `filterDigitsOnly(max 10)` | no | no | KEEP-AS-TYPED | Digit string. Voice digit errors are silent. |
| Guardian/spouse name (L89) | free text | keyboard | no | no | CARRY-FORWARD | Stable per patient. Prefill from prior visit. |
| Emergency contact (L90) | numeric | `filterDigitsOnly(max 10)` | no | no | CARRY-FORWARD | Same. |
| Village / Block / District (L94-96) | free text | keyboard | no | no | PICK-LIST+SKIP-LOGIC | One PHC serves a bounded catchment. A per-deployment village list plus block/district defaults removes three free-text fields outright. Highest structured-capture win in the whole flow. Also relevant to RR-01 (these three are plaintext quasi-identifiers). |
| Pincode (L97) | numeric max 6 | keyboard | no | no | DERIVE | Derive from the village pick above. |
| State (L183) | single-select | `DropdownField`, 33 options | no | no | DEFAULT | Default to the PHC's own state; a PHC does not change state. |
| Category (L194) | single-select | `DropdownField` | no | no | KEEP-AS-TYPED | Already one tap. |
| Marital status (L202) | single-select | `DropdownField` | no | no | KEEP-AS-TYPED | Already one tap. |
| Blood group (L210) | single-select | `DropdownField` | no | no | CARRY-FORWARD | Immutable per patient. |
| Biological sex (L326) | single-select | `FilterChip` | **yes** | **yes** | KEEP-AS-TYPED | Reaches both as `sex`. One tap. |
| Aadhaar (L101) | numeric max 12 | keyboard | no | no | OCR-OR-SCAN | 12 digits typed by hand is the single worst keystroke cost in registration. Aadhaar cards carry a scannable QR/barcode. Never voice: a digit slip is unverifiable. |
| ABHA number (L102) | numeric max 14 | keyboard | no | no | OCR-OR-SCAN | Same, 14 digits. Also autofillable from the ABHA flow already in `presentation/abha/`. |
| Primary care clinic (L103) | free text | keyboard | no | no | DEFAULT | Almost always this PHC. |
| Referring physician (L104) | free text | keyboard | no | no | PICK-LIST+SKIP-LOGIC | Bounded local list. |

#### Medical background screen (`medicalbackground/MedicalBackgroundScreen.kt`)

None of these fields appear in `KernelPayload` (`domain/model/KernelPayload.kt:26-34`), so none
is on either classifier path today.

| Field | Type | Current input | assess | evaluate | Verdict | Justification |
|---|---|---|---|---|---|---|
| History category (L200) | single-select | `FilterChip` | no | no | KEEP-AS-TYPED | One tap. |
| History description (L203-207) | free text | keyboard | no | no | PICK-LIST+SKIP-LOGIC then VOICE | Most entries are a small set of chronic conditions. Give a condition pick-list first, voice only for the "other" tail. |
| History year (L209-213) | numeric-ish free text | keyboard | no | no | PICK-LIST+SKIP-LOGIC | Year picker or relative bucket. |
| Medication kind (L240) | single-select | `FilterChip` | no | no | KEEP-AS-TYPED | One tap. |
| Medication name (L243) | free text | keyboard | no | no | PICK-LIST+SKIP-LOGIC | NLEM list is deterministic and already the app's drug vocabulary. Type-ahead against it, not voice. |
| Medication dosage (L244) | free text | keyboard | no | no | PICK-LIST+SKIP-LOGIC | Dose forms are enumerable per drug. |
| Medication frequency (L245) | free text | keyboard | no | no | PICK-LIST+SKIP-LOGIC | OD/BD/TDS/QID/SOS is a closed set. `ReportFormatter` already spells frequencies out (REQ-RPT-01). |
| Allergy category (L269) | single-select | `FilterChip` | no | no | KEEP-AS-TYPED | One tap. |
| Allergen (L272) | free text | keyboard | no | no | PICK-LIST+SKIP-LOGIC then VOICE | Common allergens are a short list; long tail is genuine free text. |
| Reaction type (L273) | free text | keyboard | no | no | PICK-LIST+SKIP-LOGIC | Rash / swelling / breathlessness / anaphylaxis is a closed clinical set. |
| Family condition (L294) | free text | keyboard | no | no | PICK-LIST+SKIP-LOGIC | Same list as history description. |
| Family relation (L295) | free text | keyboard | no | no | PICK-LIST+SKIP-LOGIC | Closed set: mother, father, sibling, child, grandparent. |
| Occupation (L321) | free text | keyboard | no | no | PICK-LIST+SKIP-LOGIC | NCO-style short list covers rural PHC catchment. |
| Tobacco use (L322) | free text | keyboard | no | no | PICK-LIST+SKIP-LOGIC | Never/former/current plus amount bucket. Free text here is a data-quality bug, not a feature. |
| Alcohol use (L323) | free text | keyboard | no | no | PICK-LIST+SKIP-LOGIC | Same. |
| Recreational drug use (L324) | free text | keyboard | no | no | PICK-LIST+SKIP-LOGIC | Same. |
| Environmental exposure (L325) | free text | keyboard | no | no | **VOICE** | Genuinely open-ended (biomass smoke, pesticide, dust, occupational chemical). Structured capture cannot enumerate it. |
| Recent travel (L326) | free text | keyboard | no | no | **VOICE** | Open-ended place plus date narrative. |

#### Compounder screen (`compounder/CompounderScreen.kt`), ailments

Ailments are **not** in `KernelPayload` today. REQ-AIL-04
(`docs/requirements/software-requirements.md:190-194`) states the future kernel path will read
`AilmentRepository.observeForEncounter` directly, bypassing the worker projection. So these
fields are off the classifier path now and on it later. Treat them as on-path for design.

| Field | Type | Current input | assess | evaluate | Verdict | Justification |
|---|---|---|---|---|---|---|
| Main concern (L122-128) | free text, required | keyboard | no | **yes** (via `CompounderEffect.Continue` into `Consultation.chiefComplaint`) | **VOICE**, already is, ungated | Carried to Consultation at `CompounderViewModel.kt:457`. See C-1. |
| Ailment description (L266) | free text | keyboard | not yet | not yet | **VOICE** | The core narrative field. Highest frequency free text in the encounter. |
| Measurement type (L272-282) | single-select | `FilterChip` | no | no | KEEP-AS-TYPED | One tap. |
| Measured value (L285-291) | numeric | `filterDecimal` | not yet | not yet | **EXCLUDE FROM VOICE** | See A.3. A mis-transcribed value here is a fabricated measurement. |
| Measured unit (L292-297) | free text | keyboard | not yet | not yet | PICK-LIST+SKIP-LOGIC | Unit set is closed and depends on the ailment. Free text here lets "°F" and "F" and "farenheit" coexist. |
| Severity 0-10 (L299-305) | numeric | `filterDigitsOnly(max 2)` | not yet | not yet | **EXCLUDE FROM VOICE** | Ordinal that reads as a measurement. Use the same `Slider` the Consultation screen already uses (`ConsultationScreen.kt:172-177`) rather than a text field. Inconsistency worth fixing independent of ASR. |
| Duration (L306-311) | free text | keyboard | not yet | not yet | PICK-LIST+SKIP-LOGIC | Consultation already has `DURATION_BUCKETS` chips (`ConsultationScreen.kt:161-168`). Reuse them. Two screens, two shapes, same clinical concept. |
| Onset (L312-317) | free text | keyboard | not yet | not yet | PICK-LIST+SKIP-LOGIC | Sudden/gradual plus a time bucket. |
| Qualifiers (L318-323) | free text | keyboard | not yet | not yet | **VOICE** | "sharp, dull, burning, radiating" is genuinely descriptive language. |
| Visibility toggle (L327-336) | boolean | `Switch` | no | no | KEEP-AS-TYPED | Safety control (REQ-AIL-02). Never voice-settable: a spoken "private" that mis-transcribes silently changes a disclosure boundary. |
| Private voice note (L340-352) | audio | `MediaRecorder` | no | no | KEEP-AS-IS | REQ-AIL-03. Never uploaded, no playback path. Explicitly out of ASR scope: transcribing it would defeat the control. |

#### Compounder screen, vitals

| Field | Type | Current input | assess | evaluate | Verdict | Justification |
|---|---|---|---|---|---|---|
| Capture method (L139-150) | single-select | `DropdownField` | no | no | KEEP-AS-TYPED | REQ-TRS-05 risk control. One tap. |
| Pulse (L152) | numeric | `filterDigitsOnly(3)` | **yes** (`heartRate`) | **yes** | **EXCLUDE FROM VOICE** | Measurement. |
| BP systolic (L156) | numeric | `filterDigitsOnly(3)` | **yes** | **yes** | **EXCLUDE FROM VOICE** | Measurement, and drives the emergency override (REQ-TRS-02, 90-180 mmHg). |
| BP diastolic (L157) | numeric | `filterDigitsOnly(3)` | **yes** | **yes** | **EXCLUDE FROM VOICE** | Same, plus the >=120 emergency rule. |
| SpO2 (L161) | numeric | `filterDigitsOnly(3)` | **yes** | **yes** | **EXCLUDE FROM VOICE** | Drives the <90% emergency override. |
| Temperature (L164) | decimal | `filterDecimal` | no | **yes** | **EXCLUDE FROM VOICE** | Measurement. Also the unit-confusion field (C vs F). |
| Respiratory rate (L167) | numeric | `filterDigitsOnly(3)` | no | **yes** | **EXCLUDE FROM VOICE** | Measurement. |
| Weight (L171) | decimal | `filterDecimal` | via `bmi` | via `bmi` | **EXCLUDE FROM VOICE** | Feeds BMI which reaches both legs. |
| Height (L172) | decimal | `filterDecimal` | via `bmi` | via `bmi` | **EXCLUDE FROM VOICE** | Same. Also CARRY-FORWARD for adults: height does not change between visits. |
| BMI (L176) | derived | computed | **yes** | **yes** | DERIVE (already is) | `VitalsSnapshot.bmi` (`VitalsSnapshot.kt:52-61`). Good pattern, cited as the model for other derivations. |
| Pain score (L178) | numeric | `filterDigitsOnly(2)` | no | no | **EXCLUDE FROM VOICE** | Stripped at the payload boundary (`toVitalsReading`, `VitalsSnapshot.kt:64-76`), so off both wires, but it is an ordinal that reads as a measurement. Use a `Slider`. |
| Blood glucose (L196) | numeric | `filterDigitsOnly(3)` | **yes** | **yes** | **EXCLUDE FROM VOICE** | Measurement. |
| Urinalysis result (L199) | free text | keyboard | no | no | PICK-LIST+SKIP-LOGIC | Dipstick results are a fixed grid (protein/glucose/ketones/blood, each with a fixed scale). Free text here is a data-quality bug. |

#### Consultation screen (`consultation/ConsultationScreen.kt`)

| Field | Type | Current input | assess | evaluate | Verdict | Justification |
|---|---|---|---|---|---|---|
| Text/Voice mode chips (L136-137) | single-select | `FilterChip` | no | no | KEEP-AS-TYPED | Existing control surface for the voice path. |
| Main concern (L148-153) | free text, required | keyboard **or voice, ungated** | no | **yes** | **VOICE**, needs gate retrofit | The C-1 field. |
| Symptom onset (L158) | free text | keyboard | no | no | PICK-LIST+SKIP-LOGIC | Excluded from `KernelPayload` by KDoc. Sudden/gradual plus time bucket. |
| Duration bucket (L161-168) | single-select | `FilterChip` | KernelPayload only | KernelPayload only | KEEP-AS-TYPED | Already structured. Note it never reaches either wire DTO. |
| Severity (L172-177) | ordinal | `Slider` | KernelPayload only | KernelPayload only | KEEP-AS-TYPED | Already zero-keystroke. The pattern the two numeric severity/pain fields should copy. |
| Aggravating factors (L179) | free text | keyboard | no (excluded by KDoc) | no | **VOICE** | Genuinely narrative; provably off both wires. |
| Relieving factors (L180) | free text | keyboard | no (excluded by KDoc) | no | **VOICE** | Same. |
| Impact on daily activities (L181) | free text | keyboard | no (excluded by KDoc) | no | **VOICE** | Same. Recommended first slice, see Part E. |
| Other relevant history (L182) | free text | keyboard | KernelPayload only | KernelPayload only | **VOICE** | In `KernelPayload.relevantHistory` but absent from both wire DTOs. Contributes to `dataQualityScore` (`GenerateKernelReportUseCase.kt:85-90`), so it is not inert. |
| Image/video attach (L190-196) | media | picker | no | no | KEEP-AS-IS | Not typing. |
| Affected-area photo (L197-202) | media | camera | no | no | KEEP-AS-IS | Not typing. |
| Record audio attachment (L203-208) | audio + ASR | `SpeechRecognizer` | no | timing-dependent | See C-1 / secondary obs | Produces a `speech-session://` URI that is memory-only. |

#### Patient summary, doctor role (`patientsummary/PatientSummaryScreen.kt`)

| Field | Type | Current input | assess | evaluate | Verdict | Justification |
|---|---|---|---|---|---|---|
| AGREE/MODIFY/REJECT | single-select | buttons | no | no | KEEP-AS-TYPED | The H-02 human-in-the-loop control. Never voice. |
| Corrected diagnosis (L308-317) | single-select | `DropdownField` over `TRAINED_ICD_CANDIDATES` | no | no | KEEP-AS-TYPED | Deliberately a fixed 18-class list, not free text (H-02). Voice would reintroduce exactly the free-text path that was closed. |
| Clinical note (L318-323) | free text | keyboard | no | no | **VOICE** | Labelled "audit only, not used for retraining". Genuine narrative, structurally off every model path. Strong later candidate. |
| Drug name (L345-350) | free text | keyboard | no | no | PICK-LIST+SKIP-LOGIC | NLEM lookup is deterministic and already exists. |
| Dosage (L351-356) | free text | keyboard | no | no | PICK-LIST+SKIP-LOGIC | Enumerable per drug. |
| Brand name (L358-364) | free text | keyboard + `Get brand` | no | no | KEEP-AS-TYPED | Already assisted by `GeminiBrandLookupSource` (H-11). |

### A.2 VOICE candidates ranked by frequency x typing cost

Frequency is per encounter unless noted. Typing cost is a rough keystroke estimate for the field
as it is actually filled today.

| Rank | Field | Freq | Typing cost | Structured slot it maps to | Validation rule |
|---|---|---|---|---|---|
| 1 | Ailment description (`AilmentEntry.description`) | 1-4 per encounter | high | `AilmentEntry.description: String` | Non-blank; length cap; must NOT parse as a bare number (a numeric-only transcript is rejected, not accepted, because that means the worker dictated a measurement into a narrative field) |
| 2 | Main concern (`Consultation.chiefComplaint`) | 1 per encounter, required | high | `Consultation.chiefComplaint: String` | Non-blank; **on the evaluate wire, so strictest gate**: confirmation tap required before `canSend` |
| 3 | Ailment qualifiers (`AilmentEntry.qualifiers`) | 1-4 per encounter | medium | `AilmentEntry.qualifiers: String?` | Free text, length cap, no numeric-only |
| 4 | Other relevant history (`Consultation.relevantHistory`) | 1 per encounter | high | `Consultation.relevantHistory: String?` | Free text; feeds `dataQualityScore` so an empty confirm must clear the field, not leave a suggestion |
| 5 | Aggravating factors (`Consultation.aggravatingFactors`) | 1 | medium | `Consultation.aggravatingFactors: String?` | Free text |
| 6 | Relieving factors (`Consultation.relievingFactors`) | 1 | medium | `Consultation.relievingFactors: String?` | Free text |
| 7 | Impact on daily activities (`Consultation.impactOnDailyActivities`) | 1 | medium | `Consultation.impactOnDailyActivities: String?` | Free text. Provably excluded from `KernelPayload` by its own KDoc, which is why Part E picks it first |
| 8 | Clinical note, doctor (`PatientSummaryUiState.clinicalNoteText`) | 1 per reviewed case | high | doctor note, audit-only | Free text |
| 9 | Environmental exposure (`SocialHistory`) | 1 per patient lifetime | medium | `SocialHistory` exposure field | Free text |
| 10 | Recent travel (`SocialHistory`) | 1 per patient lifetime | medium | `SocialHistory` travel field | Free text |

Ranks 9 and 10 are once-per-patient, so despite being genuine narrative they are not worth an
ASR slice on frequency grounds. Ranks 1 to 4 are where the doctor's actual complaint lives.

Everything not in this list gets a non-voice verdict. That is the point: of roughly 60 input
fields in the encounter flow, 10 are voice candidates and 4 are worth building for. The largest
single typing reduction available in this app is not ASR at all, it is the
PICK-LIST+SKIP-LOGIC and CARRY-FORWARD column, which covers about 30 fields including all six
social-history fields, all four medication fields, both duration/onset pairs, urinalysis, and the
village/block/district/pincode block.

### A.3 Fields where a mis-transcription could reach a model looking like a measurement

Hard exclusion from voice, no confirmation strength is sufficient:

`pulseBpm`, `bpSystolic`, `bpDiastolic`, `spo2Percent`, `temperatureCelsius`,
`respiratoryRate`, `weightKg`, `heightCm`, `bloodGlucoseMgDl`, `painScore`,
`AilmentEntry.measuredValue`, `AilmentEntry.severity`, `Consultation.severityScore`, `Patient` age,
`Patient` biological sex, and every digit-string identity field (Aadhaar, ABHA, mobile,
emergency contact, pincode).

The reasoning is specific, not generic caution. Three of these values
(`spo2Percent`, `bpSystolic`, `bpDiastolic`) drive `CheckEmergencyThresholdsUseCase`, the
REQ-TRS-02 emergency override that short-circuits the entire consultation path and is a terminal
screen with no route onward. Seven reach `/v1/assess` and `/api/v1/evaluate` as typed numerics.
A wrong number in this set is indistinguishable from a correct one downstream: it carries no
uncertainty, it has a plausible range, and `VitalsCaptureMethod` will still say
`DIGITAL_MONITOR`. A wrong word in a narrative field degrades a text blob a doctor reads. Those
are not the same failure and must not get the same control.

The strictest-confirmation tier (voice allowed, but confirmation is blocking, not dismissible)
is exactly one field today: `Consultation.chiefComplaint`, because it is the only narrative
field that reaches a wire DTO.

---

## Part B: codebase-grounded ASR integration assessment

### B.1 Where an ASR module belongs

The repo already has the correct seam and the correct placement convention. Follow it rather
than inventing a parallel structure.

Existing convention, verifiable: a domain interface in
`app/src/main/java/com/example/samdapp/domain/<capability>/`, an Android implementation in
`app/src/main/java/com/example/samdapp/data/<capability>/`, bound in
`app/src/main/java/com/example/samdapp/di/MockBoundaryModule.kt`, with clinically-sensitive
bindings pushed into flavor-specific modules (`app/src/dev/java/.../di/DevClinicalMockModule.kt`
versus `app/src/staging|prod/java/.../di/ProductionClinicalModule.kt`). `domain/transcription/`
and `data/transcription/` already exist and hold exactly this shape.

Proposed placement, minimal and additive:

- `domain/transcription/TranscriptionService.kt`: **keep the interface, do not replace it.** It
  is already the swappable seam its own KDoc claims to be, and the KDoc already anticipates
  "a future cloud STT backend that can transcribe from a file would only change the
  implementation, not either call site". A sherpa-onnx implementation is that swap.
- `domain/transcription/VoiceFieldSlot.kt` (new): the enum of which field a capture is for, so
  a transcript is never a bare `String` floating free of its destination.
- `domain/transcription/VoiceCapture.kt` (new): the result type carrying transcript, slot,
  model id, model version, and an ASR confidence if the runtime exposes one. Replaces the current
  `CapturedAudio(uri, transcript)` for the field-fill path, without disturbing the attachment path.
- `data/transcription/SherpaOnnxTranscriptionService.kt` (new): the on-device implementation.
- `data/transcription/AndroidSpeechRecognizerService.kt`: kept as the fallback binding, or
  deleted once sherpa-onnx ships. Decision deferred to the gate.
- Binding: sherpa-onnx bound in `MockBoundaryModule` for all flavors, since it is not a clinical
  mock. Model asset selection and any dev-only "canned transcript" test double go in the
  flavor modules, following the `MockVitalsSource` / `UnavailableVitalsSource` precedent
  (H-13, `docs/quality/risk-management-file.md`).
- Model assets: `app/src/main/assets/asr/<model-id>-<version>/`. Not `res/raw`, because the
  model is multi-file (encoder, decoder, joiner, tokens) and version-pinned as a SOUP unit.

No new Gradle module. The app is single-module (`settings.gradle.kts`, one `:app`), and adding a
module for one interface plus one implementation buys nothing.

### B.2 Provenance stamping, mirroring the vitals-provenance property

The repo has three separate provenance concepts already, and they are consistent enough to copy:

- `ObservationSource { MANUAL, DEVICE }`: `domain/model/Observation.kt:17`. Per-snapshot, not
  per-vital, deliberately (REQ-TRS-05 scoping decision).
- `VitalsCaptureMethod { MANUAL_CUFF, DIGITAL_MONITOR, PULSE_OXIMETER, THERMOMETER, OTHER }`:
  same file, `:23`.
- `InferenceSource { REAL_INFERENCE, MOCK_FALLBACK, UNAVAILABLE }`:
  `domain/model/InferenceSource.kt`. Its KDoc states the load-bearing property: "Stamped once, at
  the exact point that branch is decided, so it can never drift out of sync with which path
  actually ran."

The brief's premise that "vitals carry a MEASURED-versus-mock provenance concept" is close but
not exact. What actually exists is H-13's flavor-gating: `MockVitalsSource` lives only in
`app/src/dev/`, and staging/prod bind `UnavailableVitalsSource`
(`app/src/main/java/com/example/samdapp/data/vitalssource/UnavailableVitalsSource.kt`) which
returns an all-null `VitalsReading()`. The safety property is not a runtime marker on vitals; it
is that fake vitals cannot be compiled into a non-dev build at all. There is no
per-vitals-field MEASURED marker. Recording the correction here so a later session does not
build against a marker that does not exist.

Proposed, following `InferenceSource`'s stamped-once discipline:

    // domain/model/FieldProvenance.kt
    enum class FieldProvenance { TYPED, VOICE_UNCONFIRMED, VOICE_CONFIRMED, VOICE_EDITED }

Semantics, and the reason each value must exist:

- `TYPED`: the default for every existing row. A migration backfills every existing value to
  `TYPED`, which is honest: they were typed.
- `VOICE_UNCONFIRMED`: an ASR suggestion sits in UI state. **Never persisted, never sent.** The
  save path refuses it. This is the value that makes "a transcript can never be silently trusted"
  a structural property rather than a UI convention: if the repository rejects the write, a
  future screen that forgets to render a gate still cannot get an unconfirmed transcript into the
  database.
- `VOICE_CONFIRMED`: the worker read it and tapped confirm. Persistable.
- `VOICE_EDITED`: voice-seeded, then hand-corrected before confirming. Distinct from
  `VOICE_CONFIRMED` because it is the ASR quality signal that Part D's threshold gate needs,
  measured in production rather than only on a test set.

Storage shape, deliberately narrow for the first slice: one nullable `TEXT` column per
voice-enabled field, named `<field>Provenance`, on the owning entity. Not a separate provenance
table, and not a provenance column on every field in the schema. The precedent is REQ-TRS-05's
own scoping decision, which chose one capture-method value per snapshot over "eight pickers" and
noted the same reasoning. One column per actually-voice-enabled field means the first slice adds
exactly one column.

Enforcement point, stated concretely so it is testable: the check belongs in the repository
implementation (for the first slice, `data/repository/ConsultationRepositoryImpl.kt`), not in the
ViewModel, so every caller routes through it. The test asserts the persisted row, not the
function's return value, per this repo's own backend convention in `CLAUDE.md` and the
`EvaluateReportFailureSyncSafetyTest` precedent named in H-14.

### B.3 Confirmation-gate UI, as a state model

Existing Compose patterns this must fit: screens expose a single `data class *UiState` plus a
`@Stable interface *Actions` that the ViewModel implements, and the composable takes
`(uiState, actions)` (`ConsultationScreen.kt`, `CompounderScreen.kt`, `PatientSummaryScreen.kt`
all follow this). Permissions go through `rememberPermissionAction`
(`presentation/common/PermissionAction.kt`), already used for `RECORD_AUDIO` at
`ConsultationScreen.kt:110` and `CompounderScreen.kt:260`. Reuse both; add no new pattern.

State model for one voice-fillable field, four states, one per provenance value plus a
transient capture state:

1. **Idle.** Field holds committed text with provenance `TYPED`, `VOICE_CONFIRMED`, or
   `VOICE_EDITED`. A microphone affordance is present. Nothing pending.
2. **Capturing.** Mic is live. The field itself is not mutated. The existing screens already
   express this with `isRecordingVoice` and a label swap to "Listening..."
   (`ConsultationScreen.kt:143`); keep that, but the field must stay untouched, which is exactly
   what today's code gets wrong.
3. **Suggested (the new state).** A `voiceSuggestion: String?` sits **beside** the committed
   value in UI state, not in it. The screen renders the committed value in the text field and the
   suggestion in an adjacent surface with two affirmative actions and one negative:
   Use it (commits, provenance `VOICE_CONFIRMED`), Edit (moves the suggestion into the editable
   field, provenance `VOICE_EDITED`, still requires a confirm), Discard (drops it, field
   unchanged, provenance unchanged).
   Three properties make this a gate rather than decoration:
   - There is no timeout and no auto-accept. The suggestion persists across recomposition and
     across process death (`rememberSaveable` or `SavedStateHandle`), so it cannot be dismissed
     by accident.
   - `canSend` / `canContinue` is **false** while any suggestion is outstanding. Both screens
     already compute exactly this kind of derived guard (`ConsultationUiState.canSend`,
     `CompounderUiState.canContinue`), so this is one clause, not a new mechanism.
   - The suggestion is never the field's value, so no code path can read it as if it were.
4. **Rejected.** Terminal for that capture. Emits an audit breadcrumb, then returns to Idle.

For `Consultation.chiefComplaint` specifically, the field on the evaluate wire, add one more
constraint: the confirm action must present the transcript as text the worker reads, in the
worker's own language, at body-large or larger. The existing screens already use
`heightIn(min = 56.dp)` touch targets throughout, which is the accessibility floor to match.

What this deliberately does not do: no separate confirmation screen, no dialog. The repo's
review-before-submit dialogs (H-08) exist at the *screen* boundary and are a different control.
A per-field dialog would train dismissal, which is the failure mode H-02 already calls out for
AGREE ("no enforcement stopping a reviewer from picking AGREE without actually reading").

### B.4 Audit breadcrumbs

The chain lives on the backend (C-3), so "keeping it honest" means the device emits complete,
non-PHI rows that survive sync. Two hard constraints found in the code:

1. `AuditLogger.log()` accepts only an `AuditAction` enum member
   (`domain/audit/AuditLogger.kt:6-7`), by design, so a free-text action cannot be introduced.
   New values must be added to that enum.
2. **The backend rejects unknown device actions.** `backend/core/app/services/sync.py:386`
   checks `if action not in _DEVICE_AUDIT_ACTION_VALUES`, sourced from
   `backend/core/app/domain/audit_actions_device.py`. That mirror file's own docstring says it
   "must be regenerated by hand whenever the Android enum changes: add a value here, add the
   matching value there, in the same commit", and `tests/test_sync.py` parses `AuditLogger.kt`
   directly and asserts the two match. So adding a voice audit action is a **coordinated
   device-plus-backend change in one commit**, and skipping the backend half breaks a test rather
   than silently dropping rows. This is the single most important build constraint in the whole
   ASR plan and it is easy to miss.

Proposed actions, four, mirroring the state model one-to-one:

| Enum member | Wire value | Emitted when |
|---|---|---|
| `VOICE_FIELD_SUGGESTED` | `voice_field_suggested` | ASR returns a transcript for a slot |
| `VOICE_FIELD_CONFIRMED` | `voice_field_confirmed` | Worker taps Use it |
| `VOICE_FIELD_EDITED` | `voice_field_edited` | Worker edits then confirms |
| `VOICE_FIELD_REJECTED` | `voice_field_rejected` | Worker discards, or capture fails |

Payload, field-level provenance only, no transcript. Built with the existing
`auditPayload(vararg Pair<String, String?>)` helper:

    auditPayload(
      "slot" to slot.name,                    // WHICH field, e.g. IMPACT_ON_DAILY_ACTIVITIES
      "provenance" to provenance.name,        // VOICE_CONFIRMED / VOICE_EDITED / ...
      "asrModelId" to modelId,                // SOUP traceability, see Part C
      "asrModelVersion" to modelVersion,
      "charCount" to text.length.toString(),  // a measured length, not content
      "editDistance" to distance?.toString(), // only on VOICE_FIELD_EDITED
    )

`charCount` and `editDistance` are the two signals Part D's production-quality gate needs, and
neither carries content. `editDistance` in particular gives a live, per-field ASR quality metric
from real use without ever logging what was said.

What must NOT be in the payload: the transcript, the corrected text, the audio URI, any patient
name. Note that the audio URI *is* currently logged by the existing code
(`ConsultationViewModel.kt:135` and `:160`, `"uri" to captured.uri`) and the chief complaint text
*is* currently logged (`:206`). Those are pre-existing and are called out in section 0; the new
actions must not extend the pattern.

---

## Part C: regulatory and risk framing

### C.1 Intended-use framing that keeps ASR non-diagnostic

`docs/requirements/intended-use-statement.md` §g already lists "consultation text and audio" as
a device input, so ASR is inside the declared device boundary and cannot be argued as a
non-device accessory. The framing that holds the risk class down is therefore not "ASR is outside
the device", it is:

> The speech-to-text function is a **documentation aid**. It proposes text for a human to read
> and affirmatively accept into a field. It performs no clinical interpretation, produces no
> diagnostic or triage output, and no value it proposes can be persisted or transmitted without an
> explicit human confirmation recorded in the audit trail. Its failure mode is a wrong word in a
> narrative field that a clinician reads, not a wrong clinical conclusion.

Three properties make that claim defensible rather than aspirational, and each is a code
obligation, not a documentation one:

1. **No generative model anywhere in the path.** A transducer emits a transcription of what was
   said. If the SLM slot-parsing option from the research memo is adopted later, it must be
   constrained decoding into a fixed slot schema with a closed value set, and its output goes
   through the same confirmation gate. It never authors clinical content.
2. **The `VOICE_UNCONFIRMED` write refusal** (B.2). This is what turns "mandatory human
   confirmation" from a UI claim into a structural one, and it is the sentence that will be
   quoted in the risk file.
3. **Voice is excluded from every measurement field** (A.3). This is what keeps the hazard's
   severity at "degraded narrative" rather than "fabricated measurement". If voice is ever
   extended to a numeric vital, this whole framing collapses and the classification argument in
   `docs/regulatory-foundation.md` §2.3 has to be reopened. That is the line to defend.

The class stays where it is (B or C, unresolved, per `docs/quality/risk-management-file.md` §1
and the DHF entry of 2026-08-13). ASR adds no new *output* that drives clinical management, so
the Table 2 position does not move.

### C.2 The new hazard and its risk controls

Proposed register entry for `docs/quality/risk-management-file.md`, to be added under change
control, not written by this session:

**H-15. ASR mis-transcription silently changes a clinical field value.**
Potential harm: a clinician or the `/api/v1/evaluate` model acts on a symptom description the
patient did not give. Severity: provisionally High for `chiefComplaint` (reaches the evaluate
wire), Medium for the other narrative fields (read by a human only). Probability: Medium in
Indian-accented English on rural PHC audio, and this is the number that must be measured rather
than assumed, see Part D.

Risk controls, each traced to where it will live:

| Control | Where it lives |
|---|---|
| Voice excluded from every measurement and identity field | `VoiceFieldSlot` enum is the allowlist; a field not in it has no microphone affordance. `domain/transcription/VoiceFieldSlot.kt` |
| Mandatory affirmative confirmation before commit | Screen state model, B.3; `canSend`/`canContinue` guard clause |
| `VOICE_UNCONFIRMED` cannot be persisted | Repository implementation write refusal, B.2, proven by a persisted-row test |
| Field-level provenance retained on the record | `<field>Provenance` column, B.2 |
| Audit breadcrumb for every suggest/confirm/edit/reject | Four `AuditAction` values plus the backend mirror, B.4 |
| Entity-level accuracy gate before a field may be voice-enabled in production | Part D thresholds; enforced as a per-slot release decision |
| Honest failure, never a silent empty commit | Follows the `InferenceSource.UNAVAILABLE` precedent: an ASR failure shows an error and leaves the field untouched. It must never commit an empty string, because an empty confirmed value is indistinguishable from a deliberate blank |

The last row deserves emphasis because this repo has fought this exact battle twice already: the
empty-differential fabrication fix (`AuditAction.KERNEL_EMPTY_DIFFERENTIAL`,
`GenerateKernelReportUseCase` KDoc) and H-14's evaluate-failure marker. Both landed on the same
principle, that an absence must be distinguishable from a failure. ASR gets the same treatment
for free by reusing the pattern.

### C.3 SOUP obligations

`docs/sbom/README.md` and `docs/regulatory-foundation.md:198` establish that SOUP validation and
SBOM are closed items with a checked-in SBOM (`docs/sbom/sbom-2026-07-20-v1.0.json`). Anything
added here must land in that SBOM or the control regresses.

Must be version-pinned and validated as SOUP:

- The sherpa-onnx runtime AAR, exact version, plus its ONNX Runtime dependency and that
  dependency's own version. Two components, not one.
- **The model weights, as a separate SOUP item from the runtime.** This is the part usually
  missed. Pin: model family, exact release tag or commit, the SHA-256 of each artifact file
  (encoder, decoder, joiner, tokens), the training corpus and its license, and the model's own
  license.
- The tokenizer / vocabulary file, since a mismatched tokens file against the right encoder
  produces plausible wrong text rather than an error.
- Any LoRA adapter, each adapter as its own pinned item with its own base-model pin.
- The current `AndroidSpeechRecognizerService` path, if retained as a fallback, is SOUP the app
  does not ship and cannot pin: the platform recognition service version is whatever the device
  has. That is an argument for removing it once sherpa-onnx works, and it should be recorded as
  such rather than left implicit.

Validation, minimum: the entity-level accuracy report from Part D run against the pinned
artifact hashes, recorded in the DHF with the artifact hashes in the record, so a later
"same model" claim is checkable rather than asserted.

**ACP.** `docs/quality/qms-overview.md:45` lists the Algorithm Change Protocol as TODO, required
before any post-deployment update to the kernel model, and notes no version-gating exists yet
(`ai_kernel_version` gap). An ASR model is a second model in the device and inherits the same
obligation the moment it can change post-deployment. Concretely:

- A fine-tuned or LoRA-adapted ASR model **requires an ACP entry** before it can ship an update.
  A frozen, version-pinned model that only changes with a full app release is a design change
  under normal change control, which is a lower bar. That distinction is a real architectural
  choice with regulatory cost attached, and it should be made deliberately at the gate rather
  than drifted into.
- Recommendation for the MVP: **no post-deployment model update mechanism at all.** The model
  ships in the APK, changes only with an app version, and the app records the model id and
  version on every voice-derived field (B.4). This defers the ACP obligation honestly rather than
  building an update path the QMS cannot yet cover. Revisit when the Indic LoRA path is real.
- `docs/quality/risk-management-file.md` H-12 established the principle that a rule change and a
  model change must be separable and separately versioned (`derivation_rule_version`). The same
  applies here: the ASR model version and the slot-validation rule version are two independent
  pins.

Nothing in this section has been written into `docs/`. It is a proposal for what goes there under
change control.

---

## Part D: dataset and fine-tuning plan, scoped only

### D.1 What a domain and accent test set for THIS app needs

Scoped to the Part A vocabulary, not to generic Indian English:

- **The voice-fillable field vocabulary, ranks 1 to 4 of A.2.** Symptom descriptions, pain and
  sensation qualifiers ("sharp", "dull", "burning", "radiating", "throbbing"), duration phrases
  ("three days", "since yesterday", "two weeks"), onset phrases, aggravating and relieving
  factor phrases, and past-history narrative.
- **The adjacent numeric vocabulary, as negative test material.** The test set must include
  utterances where a worker dictates a measurement into a narrative field ("BP one forty over
  ninety"). Not to transcribe it well, but to prove the A.3 exclusion and the numeric-only
  rejection rule from A.2 actually fire. This is a test-set requirement that a generic ASR
  benchmark will never give you.
- **Vitals units and drug names as distractors**, not as targets: mg/dL, mmHg, bpm, degrees
  Celsius, plus the NLEM drug list. These will appear inside narrative even though the fields
  they belong to are voice-excluded.
- **Accent and channel coverage.** Indian-accented English across at least the language
  backgrounds of the deployment region's PHC workers, not "Indian English" as one bucket. Plus
  the real channel: mid-range Android microphone, ambient PHC noise, a patient talking in the
  background, a fan.
- **Code-mixing, recorded but not yet targeted.** PHC workers will say the English sentence with
  Hindi or regional clinical words in it. Capture it in the test set from day one so the English
  baseline's degradation is measured rather than discovered in the field, even though the
  English-first model is not expected to handle it.

**Metrics: entity-level, not WER.** WER on a narrative field is nearly meaningless for this
decision, because the failure that matters is not "how many words wrong" but "did a clinically
load-bearing token survive". Track:

- **Clinical entity error rate** per entity class: symptom term, body site, laterality,
  duration, severity qualifier, negation. Negation is the highest-consequence class in the whole
  set: "no chest pain" transcribed as "chest pain" inverts the clinical meaning while scoring
  well on WER.
- **Numeric-leak rate.** The fraction of measurement-shaped utterances that produce a
  numeric-parseable transcript in a narrative slot. Target zero after the rejection rule.
- **Confirmation-burden proxy.** Mean edit distance between suggestion and confirmed text,
  which is the same `editDistance` the audit payload emits (B.4), so the lab metric and the
  production metric are the same number. That is deliberate.

### D.2 Ethical sourcing

- **No real PHI, no exceptions.** This repo already has the discipline and the proof pattern:
  `backend/core/tests/test_abha.py::test_d5_no_phi_in_persisted_row_or_logs`, and the
  2026-08-28 live-run entry in `PROGRESS.md` where structure-only instrumentation was added,
  verified to contain zero patient values, then removed in full so the file was byte-identical.
  That is the standard for any recording pipeline here.
- **Synthetic-first.** Scripted utterances built from the Part A field vocabulary, read by
  consented speakers who are not patients. `DemoPatientProfile`
  (`app/src/main/java/com/example/samdapp/data/mock/`) is the existing precedent for a curated
  non-real clinical persona and is the natural seed for the script content.
- **Pilot audio only under explicit, separate, written consent** for recording and model
  development, distinct from the clinical consent the app already captures
  (`presentation/consent/`, REQ-TRS-01). Reusing the clinical consent for model training would be
  a DPDP problem and a research-ethics problem, and it is the obvious shortcut to refuse now.
- **Store the corpus outside this repo**, outside the app, and outside any path the app can
  read. Reference it by hash in the SBOM and the DHF, never by inclusion.
- Note again: **`drishti_pipeline` does not exist in this repository** (C-4). If it exists
  elsewhere and has a synthetic-data discipline worth inheriting, point this memo at it and this
  section gets rewritten against it.

### D.3 The LoRA path for Indic and code-mixed, and why it does not disturb English

A LoRA adapter trains a small low-rank delta over frozen base weights. The base encoder,
decoder, joiner, and tokens files are unchanged bytes on disk, so:

- The English base model keeps its exact SOUP pin and its validated accuracy report. It does not
  need revalidation because an adapter shipped.
- An adapter is a separate SOUP item with its own pin, its own test set, and its own accuracy
  gate, and it can be validated and enabled per language without touching the English evidence.
- Adapters are loadable per session, so language selection is a runtime routing decision, not a
  rebuild.

Two honest caveats: for a streaming transducer, adapter support depends on the runtime actually
exposing it, which needs verification against the chosen sherpa-onnx version rather than
assumption; and if the tokenizer has to change for Indic script, the "base is untouched" claim is
no longer true and it becomes a second model, with a second full validation. Check the tokenizer
question before committing to the LoRA framing.

### D.4 Good enough to ship

Per-slot gates, not one global number, because A.2's fields carry different consequences. A field
becomes voice-enabled in production only when its own gate passes.

| Gate | Threshold | Why this number |
|---|---|---|
| Clinical entity accuracy, narrative slots | >= 95% on the domain test set | Below this the confirmation burden exceeds the typing it saves, and the feature is a net loss even when it is safe |
| **Negation accuracy** | **100% on the negation subset, no exceptions** | A flipped negation is a clinically wrong record that reads as correct. There is no acceptable non-zero rate |
| Numeric-leak rate into narrative slots | 0% | A.3's whole argument depends on this |
| `chiefComplaint` slot specifically | entity accuracy >= 98% **and** a passing rate on the evaluate-wire review | It is the only narrative field on a wire DTO |
| Production edit distance, first 4 weeks | median 0, p90 below 20% of field length | Measured from the `VOICE_FIELD_EDITED` audit payload. If p90 is high the model is not helping regardless of what the lab set says |
| Latency, mid-range Android, end of speech to suggestion | under 1.5 s | Above this the worker types faster than they wait, and adoption fails for reasons unrelated to accuracy |

A field failing its gate is not shipped with a warning label. Its microphone affordance is
absent, which is the same posture `UnavailableVitalsSource` takes: no affordance is better than a
degraded one.

---

## Part E: proposed build sequence

### E.1 What is already on the board and stays there

Read from `PROGRESS.md` and not restarted. These are unchanged and are not substituted by ASR:

- Mobile masking recheck. `PROGRESS.md:3424-3429`, the 2026-08-28 entry: D4 is CONTRADICTED, not
  resolved, and the explicit instruction is "Do not build Android-side mobile handling assuming
  either shape until that check lands." Still open.
- Real authentication plus RBAC enforcement, REQ-SEC-03. `PROGRESS.md:1697`. This is the closest
  thing on the board to the brief's "role-visibility gating"; no item using that exact phrase
  exists in `PROGRESS.md`. Recording the discrepancy rather than renaming someone else's ticket.
  H-06 in the risk file carries the same gap.
- Compose UI tests for the Register form and review dialogs, plus instrumented SEC-01/AUD-02
  coverage. `PROGRESS.md:58`.
- Instrumented tests into CI once the suite is larger, needs an emulator action. `PROGRESS.md:59`.
- The hard gate at `PROGRESS.md:2845`: instrumented tests never executed, run before ANY
  deployment.
- Pre-production process blockers, `PROGRESS.md:1695`.

ASR is appended below these, not ahead of them.

### E.2 Proposed ASR sequence

Each PR is independently shippable and independently revertible. STEP-1 means a read-only design
memo on Opus before the build; STEP-2 means the mechanical build. The routing follows the
precedent set by the resumable-draft session (`PROGRESS.md:3340-3376`): design memo on Opus,
build on Sonnet, and the split is decided by whether the PR contains a real design decision.

| PR | Scope, one line | Routing |
|---|---|---|
| **PR 0** | Close C-1: `ConsultationViewModel.onRecordChiefComplaintVoice` stops writing the transcript into `chiefComplaint`; the transcript lands in a `voiceSuggestion` field and `canSend` is false while one is outstanding. No new model, no new column, no new audit action. | STEP-2 Sonnet, this memo is the design |
| **PR 0b** | Register H-15 in `docs/quality/risk-management-file.md` and the ASR intended-use paragraph, under change control. Docs only, no code. | STEP-1 Opus, operator-reviewed |
| **PR 1** | `FieldProvenance` enum plus one nullable provenance column on `consultations` for the first slice field, with the migration and a `TYPED` backfill. Schema only, no UI, no ASR. | STEP-2 Sonnet |
| **PR 2** | The four `VOICE_FIELD_*` values in `AuditAction` **and** the matching entries in `backend/core/app/domain/audit_actions_device.py`, same commit. Nothing emits them yet. Verified by the existing `tests/test_sync.py` mirror assertions. | STEP-2 Sonnet, cross-repo-half, needs care |
| **PR 3** | **The first vertical slice.** One field, `Consultation.impactOnDailyActivities`: microphone affordance, suggest state, confirm/edit/discard, provenance stamped, `VOICE_UNCONFIRMED` write refusal in `ConsultationRepositoryImpl` proven by a persisted-row test, all four audit breadcrumbs emitted, English only, behind the existing `TranscriptionService` interface with today's `AndroidSpeechRecognizerService` still the binding. **No new ASR model in this PR.** | STEP-1 Opus for the state model, STEP-2 Sonnet for the build |
| **PR 4** | Swap the implementation: `SherpaOnnxTranscriptionService` bound behind the unchanged interface, model assets pinned, SBOM updated. Behavior identical to PR 3, only the engine changes, which is what makes the swap reviewable. | STEP-1 Opus for the model pick and SOUP entries, STEP-2 Sonnet for the build |
| **PR 5** | Extend voice to A.2 ranks 3 to 6 (`qualifiers`, `relevantHistory`, `aggravatingFactors`, `relievingFactors`). Pure repetition of PR 3's pattern across four fields. | STEP-2 Sonnet |
| **PR 6** | `chiefComplaint` under the strict gate. Separate PR because it is the only narrative field on a wire DTO and deserves its own review. Requires PR 4's model and its passing gate. | STEP-1 Opus, STEP-2 Sonnet |
| **PR 7** | The structured-capture work from Part A that is not ASR at all: village/block/district/pincode pick-lists, the six social-history fields, the four medication fields, urinalysis, duration and onset buckets on the Compounder screen. **This is the larger typing-reduction win.** Splittable into three PRs by screen. | STEP-2 Sonnet |
| **PR 8** | Dataset and evaluation harness for Part D. Separate from the app. | STEP-1 Opus |

Why `impactOnDailyActivities` for PR 3 rather than the highest-ranked candidate: it is the only
narrative field that `KernelPayload`'s own KDoc names as deliberately excluded, so it is provably
off both model paths, and the first slice's job is to prove the safety architecture, not to
deliver the typing win. The typing win arrives at PR 5 and PR 7. Being explicit about that
tradeoff so it does not read as a weak first PR chosen by accident: it is a deliberately boring
field chosen so that a bug in the new gate cannot reach a model.

Why PR 0 comes before everything: the unguarded path in C-1 is live on `master` today. Building
new gated fields while an ungated one ships would be indefensible in a risk file.

---

## DECISION GATE

STEP 2 does not begin until the operator (Sandesh) rules on these. Items 5 and 6 were not in the
brief and are here because the audit found them.

**1. MVP English model.**
Recommendation: **streaming Zipformer transducer on sherpa-onnx**, Apache-2.0 lineage.
Reasoning tied to Part A's actual fields: eight of the ten voice candidates are short phrases of
five to fifteen words, not long narrative, so the streaming transducer's strength (low latency,
partial hypotheses as the worker speaks, small CPU and memory footprint on mid-range Android) is
exactly what this field set needs, and it feeds the confirmation gate's live preview naturally.
- **NeMo Parakeet / Nemotron**: strong accuracy, but **CC-BY-4.0 is a decision item, not a
  detail.** Permissive licensing is a locked principle here and the SBOM discipline is already
  established (`docs/sbom/`). CC-BY-4.0 is permissive but carries an attribution obligation that
  must be discharged in the shipped app and in the SBOM, and its "no additional restrictions"
  clause needs a real read against a medical-device distribution before it is accepted. Not a
  blocker, but not a shrug either.
- **Whisper-small**: recommend against for the MVP. Non-streaming, so no partial hypotheses for
  the gate; slow on the target hardware; and its documented tendency to hallucinate fluent text
  on silence or noise is the single worst failure mode for a field that a worker is about to
  confirm, because a hallucination is confident and grammatical and therefore easy to accept.
- **AI4Bharat IndicConformer, MIT**: correct choice for the Indic swap-in later, behind the same
  interface. Not the MVP.

Confirm: Zipformer, Parakeet with the license accepted, or something else.

**2. The single first voice field.**
Recommendation: `Consultation.impactOnDailyActivities`, for the provable-off-path reason above.
Alternative if you want the first slice to also deliver visible value:
`AilmentEntry.description`, rank 1, much higher typing saving, but it becomes a classifier input
under REQ-AIL-04's future kernel path, so the gate has to be right the first time.
Confirm: the safe one or the valuable one.

**3. Provenance enum values.**
Recommendation: `FieldProvenance { TYPED, VOICE_UNCONFIRMED, VOICE_CONFIRMED, VOICE_EDITED }`,
stored as one nullable `TEXT` column per voice-enabled field, `VOICE_UNCONFIRMED` refused at the
repository write boundary. The alternative three-value form drops `VOICE_EDITED`, which is
simpler but throws away the production quality signal Part D's gate depends on.
Confirm the four values and the per-field column shape, since PR 1 writes a migration against it
and the column name enters the sync wire contract.

**4. Part D dataset work: parallel or after.**
Recommendation: **after PR 3, parallel with PR 4.** PR 0 through PR 3 need no model and no
dataset. Starting dataset collection before the first slice exists means collecting against a
field set that may still change. Starting it after PR 4 means the model ships unmeasured.
Confirm.

**5. New, not in the brief: does PR 0 ship immediately, ahead of the rest?**
The ungated voice-to-`chiefComplaint` path (C-1) is live on `master`. It can be fixed in a small
PR today using nothing this memo proposes to build. Recommendation: yes, ship PR 0 and PR 0b
first, independent of every ASR decision above.

**6. New, not in the brief: is `AndroidSpeechRecognizerService` making an off-backend network
call?**
`EXTRA_PREFER_OFFLINE` is not set and `INTERNET` is granted. If the platform recognizer is
cloud-backed on the target devices, patient narrative is leaving the device to a third party
outside the backend, which contradicts a locked principle and is not covered by H-11. This needs
a deliberate on-device check. Recommendation: check before PR 0 ships, since the answer may make
PR 0 more urgent than a UX fix.
