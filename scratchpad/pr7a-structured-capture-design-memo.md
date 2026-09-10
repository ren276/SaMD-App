# PR 7a STEP 1: Structured Symptom Capture Design Memo (read-only)

Run date: 2026-08-31. Branch `docs/asr-vocab-and-h16`, HEAD `a1f9176`
("docs(asr): accept H-16 control (symptom multi-select), hazard open pending validation").

Working tree at start: **no tracked modifications**. `git status --short` returned 16 lines, all
`??` untracked: `dashboard.html` and 15 files under `scratchpad/`. Nothing staged, nothing
modified, nothing deleted. The brief's "STOP if dirty" instruction was read as covering tracked
modifications; untracked scratchpad files are the memos this task was told to read, so this memo
proceeds and records the state rather than blocking on it. Nothing was staged, committed, or
pushed by this session. No file outside `scratchpad/` was written. `.env`, `local.properties`,
`BuildConfig` and every credential file untouched. No migration written, no `docs/` file modified.

Read in full: `scratchpad/asr-field-audit-memo.md` (864 lines),
`scratchpad/asr-symptom-vocabulary-research.md` (270 lines),
`scratchpad/asr-usecase-research-memo.md` (sections 0, TASK 3, TASK 4, RECOMMENDATIONS, FORMATTING
BOUNDARY STATEMENT read verbatim; TASK 0/1/2/5 read by outline and targeted section).
Also read: `CLAUDE.md`, `PROGRESS.md` (last four session entries),
`docs/backend/api-contract.md` section 5.4 (the evaluate leg),
`docs/quality/risk-management-file.md` (H-12, H-15, H-16 and the PROPOSED preamble),
`docs/requirements/software-requirements.md` (CON, HAN, AIL, TRS, RPT blocks).

Code read: `ConsultationScreen.kt`, `ConsultationViewModel.kt`, `CompounderScreen.kt`,
`CompounderViewModel.kt` (targeted), `domain/model/Consultation.kt`, `domain/model/KernelPayload.kt`,
`domain/model/AilmentEntry.kt`, `domain/model/VitalsSnapshot.kt`,
`domain/model/TrainedIcdCandidate.kt`, `data/remote/RetrofitEvaluateSource.kt`,
`data/remote/dto/SyncPayloadDto.kt`, `data/local/entity/ConsultationEntity.kt`,
`data/local/entity/AilmentEntity.kt`, `data/local/dao/ConsultationDao.kt`,
`data/local/Migrations.kt` (idiom + MIGRATION_14_15/15_16), `data/mock/DemoPatientProfile.kt`
(targeted), `presentation/common/DropdownField.kt`, `config/FeatureFlags.kt`,
`domain/usecase/GenerateKernelReportUseCase.kt` (targeted),
`presentation/report/ReportCanvasRenderer.kt` (targeted),
`backend/core/app/models/encounter.py`, `backend/core/app/models/clinical.py` (targeted),
`backend/core/app/api/v1/encounters.py` (TableSpec field tuples),
`backend/core/app/services/sync.py` (targeted).

---

## 0. STOP-AND-REPORT: six places where the repo contradicts this task's premises

The brief instructed that repo state wins over the research and over any assumption in the brief,
and that a contradiction is reported rather than designed around. Six were found. Two are blocking
for the build shape. Everything after section 0 is written on top of the corrected picture.

### P-1 (BLOCKING, changes the shape of the PR). `chiefComplaint` is not a device-only field. Changing its persisted shape by adding a column is a coordinated device-plus-backend change, or the row is rejected at sync.

The brief asks whether `chiefComplaint` becomes "a list of selected item codes? A join structure?"
as if the persisted shape were a device-local choice. It is not. The field has four homes:

- device domain, `app/src/main/java/com/example/samdapp/domain/model/Consultation.kt:9`,
  `val chiefComplaint: String` (non-null)
- device Room, `app/src/main/java/com/example/samdapp/data/local/entity/ConsultationEntity.kt:14`,
  `val chiefComplaint: String` (non-null)
- the sync wire, `app/src/main/java/com/example/samdapp/data/remote/dto/SyncPayloadDto.kt:60`,
  `@SerializedName("chief_complaint") val chiefComplaint: String`
- backend Postgres, `backend/core/app/models/encounter.py:70`,
  `chief_complaint: Mapped[str] = mapped_column(Text, nullable=False)`

And the backend's push applier is a strict allowlist. `_CONSULTATION_FIELDS`
(`backend/core/app/api/v1/encounters.py:68-82`) enumerates exactly the eleven clinical columns plus
timestamps, and `backend/core/app/services/sync.py:281` and `:383` both reject anything else:

    return _reject(table, record_id, ErrorCode.SYNC_RECORD_INVALID, f"{bad}: unexpected field.")

So adding `chiefComplaintCodes` (or `vocabularyVersion`, or `patientNarrative`) to the device entity
means: a Room migration, a backend Alembic migration, an addition to `_CONSULTATION_FIELDS`, and the
device-side sync mapper change, **in one commit**. This is the same class of constraint the
field-audit memo identified for `AuditAction` and its `audit_actions_device.py` mirror (B.4), and it
is the single most consequential build constraint in PR 7a. It is not in the brief.

Consequence, and this drives Part B: the recommended design adds **no column at all**. It keeps one
`chiefComplaint: String` and changes what that string contains. That is what makes 7a-i a
device-only, migration-free PR.

### P-2 (BLOCKING for Part D's "reuse DURATION_BUCKETS"). `DURATION_BUCKETS` is file-private to a Compose screen, its values are enforced nowhere, and the repo's own demo data already violates them.

`app/src/main/java/com/example/samdapp/presentation/consultation/ConsultationScreen.kt:77`:

    private val DURATION_BUCKETS = listOf("today", "few_days", "week_plus", "chronic")

Three problems, all real:

1. `private val` in a Compose screen file. `CompounderScreen.kt` cannot see it. "Reuse" requires
   promoting it to a shared location first; it is not a zero-cost import.
2. Nothing enforces membership. `Consultation.durationBucket` is `String?`
   (`Consultation.kt:11`), `ConsultationEntity.durationBucket` is `String?`
   (`ConsultationEntity.kt:16`), backend is `duration_bucket: Mapped[str | None] =
   mapped_column(String(40))` (`models/encounter.py:72`) with no CHECK constraint.
3. The repo already writes out-of-set values. `DemoPatientProfile.kt:160` writes
   `durationBucket = "months"` and `:228` writes `durationBucket = "weeks"`. Neither is in
   `DURATION_BUCKETS`. Tapping "Fill demo patient data" on the Consultation screen therefore
   produces a state where no duration chip renders as selected, and the value still persists and
   still reaches `KernelPayload.durationBucket` (`KernelPayload.kt:28`).

So Part D's "reuse `DURATION_BUCKETS`" is not pure reuse. It is: promote the constant out of the
screen file, then fix the demo data, then reuse. If the demo data is not fixed, the Compounder
screen inherits a picker whose vocabulary the app's own showcase path cannot express.

### P-3 (correction). "Symptom onset" is two different columns on two different tables, and "duration" likewise.

The brief lists "symptom onset" and "duration" once each, as if each were one field appearing on
two screens. They are four distinct persisted columns:

| Concept | Consultation home | Ailment home |
|---|---|---|
| onset | `Consultation.onset: String?` (`Consultation.kt:10`), backend `String(120)` (`encounter.py:71`) | `AilmentEntry.onset: String?` (`AilmentEntry.kt:44`), backend `String(120)` (`clinical.py:107`) |
| duration | `Consultation.durationBucket: String?` (`Consultation.kt:11`), backend `String(40)` (`encounter.py:72`) | `AilmentEntry.duration: String?` (`AilmentEntry.kt:45`), backend `String(120)` (`clinical.py:108`) |

The Consultation pair is already half-structured (duration is chips, onset is free text). The
Ailment pair is both free text. They share no vocabulary and no constant today. Converting both to
the same pick-list is the right call, but it is a change to two tables and it means one shared
constant must own the vocabulary for both, which is what P-2 already forces. Also note that the
column widths differ (`String(40)` vs `String(120)`), so a shared vocabulary must fit inside 40
characters to be safe on both, which every proposed bucket does.

### P-4 (correction). The brief's "STANDING DOCS RULE" is not a named artifact anywhere in this repo.

`grep -rn "STANDING DOCS RULE\|STANDING RULE" PROGRESS.md docs/ CLAUDE.md` returns nothing. The
practice the brief describes is real and evidenced (`PROGRESS.md` entries are written inline in the
same session as the code, and `docs/quality/risk-management-file.md:44-47` carries an explicit
PROPOSED-and-awaiting-sign-off preamble for H-15/H-16), so this memo honours the intent in Part F.
But there is no rule text to cite, and a later session should not go looking for one. If it is meant
to be durable it belongs in `CLAUDE.md`, which currently holds only the graphify rules and the
backend persisted-row test convention.

### P-5 (correction, stale citations). PR 0 landed. Both prior memos' line numbers for the Consultation screen are now wrong, and the field they describe as unguarded is already disabled.

`FeatureFlags.VOICE_INPUT_ENABLED = false` exists at
`app/src/main/java/com/example/samdapp/config/FeatureFlags.kt:45`. Every voice affordance on the
Consultation screen is behind `if (FeatureFlags.VOICE_INPUT_ENABLED)`
(`ConsultationScreen.kt:139-153` and `:212-219`), and both handlers return before invoking the
capture use case (`ConsultationViewModel.kt:140` and `:168`). The field-audit memo's C-1 is closed
as an interim control, recorded in `docs/quality/risk-management-file.md:39` (H-15) and in the
`PROGRESS.md` entry for `fix/asr-offdevice-exposure`.

Line drift, current values, for anything the build will edit:

| Memo citation | Current |
|---|---|
| Consultation main concern `ConsultationScreen.kt:148-153` | `:154-161` |
| Consultation symptom onset `:158` | `:165` |
| Consultation duration chips `:161-168` | `:166-176` |
| Consultation severity slider `:172-177` | `:177-185` |
| Compounder main concern `CompounderScreen.kt:122-128` | `:122-129` |
| Compounder measured unit `:292-297` | `:292-297` (unchanged) |
| Compounder severity `:299-305` | `:300-306` |
| Compounder duration `:306-311` | `:307-312` |
| Compounder onset `:312-317` | `:313-318` |
| Compounder urinalysis `:199` | `:197-199` |
| Compounder pain score `:178` | `:177-179` |

### P-6 (correction, and a regression this PR would otherwise ship). `chiefComplaint` is rendered as the patient's quoted verbatim words in the printed report and as a human-readable one-liner in three list screens.

`app/src/main/java/com/example/samdapp/presentation/report/ReportCanvasRenderer.kt:295-296`:

    private fun complaintBlock(report: ClinicalReport): Block {
        val text = "“${report.chiefComplaintVerbatim.ifBlank { "—" }}”"

That value arrives via `AssembleReportUseCase.kt:65` and `ReportFormatter.kt:130` into
`ClinicalReport.chiefComplaintVerbatim` (`ClinicalReport.kt:89`). REQ-RPT-01
(`docs/requirements/software-requirements.md:238-240`) names it as the "verbatim chief complaint".
Three more sites render it as a human label:
`PatientSummaryScreen.kt:425` and `:486`, and `DoctorListScreen.kt:70`.

If `chiefComplaint` becomes a token list and nothing else changes, the printed clinical record shows
`"fever|cough_cold|weakness"` inside typographic quote marks, presented as what the patient said.
That is a real defect in a regulated record, not a cosmetic one. Part B designs the resolver that
prevents it, and it is scoped into 7a-i rather than left as a follow-up.

---

## Part A: the vocabulary as a versioned repo artifact

### A.1 Storage form

**A Kotlin `val` list of a data class, in `app/src/main/java/com/example/samdapp/domain/model/`.**

The repo has an exact precedent for a versioned, model-facing, fixed clinical vocabulary:
`domain/model/TrainedIcdCandidate.kt`, which holds `TRAINED_ICD_CANDIDATES`, an 18-item
`List<TrainedIcdCandidate>`, whose KDoc pins it to a named upstream artifact
(`symptom_model_meta.json`'s labels list) and explains why free text was closed off. That file is
the shape to copy. It is the repo's own answer to this exact question, made once already for the
model's output side, and the usecase memo (3.4) makes the case that the identical argument applies
to the input side and has simply never been made there.

Rejected alternatives, with reasons rather than reflex:

- **`app/src/main/assets/`.** The directory exists but holds only images
  (`doctor-checking-patient.png`, `medicine.svg`, `prescription.svg`, `sign.png`, `sleep.svg`).
  There is no JSON asset, no asset-loading code, and no parser anywhere in the app. A JSON
  vocabulary means adding a reader, an IO error path, a parse error path, a test for a malformed
  asset, and a runtime failure mode for a list that cannot change without a rebuild anyway. The
  compiler already gives all of that for free on a Kotlin `val`.
- **`res/raw/`.** Does not exist in this project.
- **A Room table.** A static vocabulary is not user data. A table means a migration on every
  vocabulary edit, which is precisely what a version constant is supposed to make cheap, plus a DAO,
  plus seeding, plus a "what if the seed did not run" state.
- **A remote-fetched vocabulary.** Would be a post-deployment change to a clinical decision surface
  with no Algorithm Change Protocol in place (`docs/quality/qms-overview.md:45` lists the ACP as
  TODO). Out of scope and would be a regulatory regression.

### A.2 The data class

Proposed, `app/src/main/java/com/example/samdapp/domain/model/PresentingComplaint.kt`:

    /** Bumped in the same commit as any change to PRESENTING_COMPLAINT_VOCABULARY. Mirrors the
     *  derivation_rule_version precedent (H-12): a vocabulary change must be separable and
     *  traceable from a model change. Also the persisted prefix, see Consultation.chiefComplaint. */
    const val VOCABULARY_VERSION = "PC-v1"

    enum class ComplaintGroup { ADULT, MATERNAL, PAEDIATRIC, DANGER_SIGN }
    enum class FrequencyTier { VERY_COMMON, COMMON, LESS_COMMON_IMPORTANT, RED_FLAG }
    enum class ProgrammeMap { CBAC, IMNCI, ANC, IDSP }

    data class PresentingComplaint(
        /** Stable slug. THE persisted and wire-stable token. [a-z0-9_] only, never contains
         *  '|' or ':' so the persisted delimiter and version prefix can never collide with it.
         *  Never renamed: idiomLabel and clinicalTerm are expected to change after register
         *  validation, this is not. */
        val id: String,
        /** Vernacular idiom, the primary on-screen label. Devanagari where the research gives it. */
        val idiomLabel: String,
        /** English clinical term. Doctor-facing, dataset-facing, and the value resolved onto the
         *  evaluate wire. Mandatory first-class column per the vocabulary research. */
        val clinicalTerm: String,
        /** One or more ICPC-2 codes. A LIST, not a String: the research maps several items to
         *  more than one code ("D01 / D06", "K02 / A11", "T91 / T05") and collapsing that at the
         *  schema level is the exact collapse the hard gate forbids. Empty for other_describe. */
        val icpc2: List<String>,
        val tier: FrequencyTier,
        val group: ComplaintGroup,
        val programme: Set<ProgrammeMap>,
        /** Null means unambiguous. Non-null is the ambiguity note shown to the worker verbatim. */
        val ambiguity: String?,
        /** All candidate clinical mappings for an ambiguous idiom, preserved as data. Non-empty
         *  if and only if [ambiguity] is non-null. Never resolved to one entry by the app. */
        val candidateMappings: List<String>,
    )

    val PRESENTING_COMPLAINT_VOCABULARY: List<PresentingComplaint> = listOf(/* ~41 items */)

Two shape decisions that carry the safety argument, called out because a simpler shape is the
tempting mistake:

- **`ambiguity: String?` plus `candidateMappings: List<String>`, not `isAmbiguous: Boolean`.** The
  research is explicit (`asr-symptom-vocabulary-research.md`, "Preserve ambiguity as signal, not
  noise"): for one-to-many idioms, "store all candidate mappings with the idiom and label them
  'requires clinician disambiguation'". A boolean stores that ambiguity exists and throws away what
  it is. The seven flagged items (kamzori, chakkar, gas/jalan, garmi, ghabrahat, tension, safed
  pani) each have a documented candidate set; the data class must be able to hold it.
- **`icpc2: List<String>`.** A single-String field would force a human to pick one code at
  vocabulary-authoring time for items the research deliberately left multi-coded. That is the silent
  collapse, moved one layer earlier and made invisible.

### A.3 Where `VOCABULARY_VERSION` lives, and how it is kept honest

Top of `domain/model/PresentingComplaint.kt`, in the same file as the list.

The precedent is H-12 (`docs/quality/risk-management-file.md:33`): `derivation_rule_version =
"HAN-07/08-v1"` lives inside `app/domain/kernel_derivation.py`, the module it versions, and H-12's
stated principle is that bumping it is mandatory for any rule change so that the Algorithm Change
Protocol can separate a rule change from a model change. The identical argument holds here: after
register validation the vocabulary will change, and an auditor must be able to tell which vocabulary
a stored consultation was captured under.

H-12 also establishes that the control must not decay: it is backed by a test that fails if a
derived column is ever added. The equivalent here, and it is cheap:

- one unit test asserting `PRESENTING_COMPLAINT_VOCABULARY.size` and a hash of the sorted `id` list
  against constants declared next to `VOCABULARY_VERSION`. Any list edit without a version bump
  fails the build.
- one unit test asserting the invariants: ids unique, ids match `^[a-z0-9_]+$`,
  `candidateMappings.isNotEmpty() == (ambiguity != null)`, and every id fits inside the
  40-character backend `duration_bucket`-class width budget when joined (see Part B).

### A.4 Ambiguity preserved as data, and what the UI does with it

The rule, from the research hard gate: an ambiguous item is **never** auto-resolved to a single code,
in the field or in any export.

In the field: selecting an ambiguous chip stores its `id`, nothing else. There is no
"which one did you mean" step anywhere. The chip renders with a small neutral marker (an
information affordance, not a warning icon, because ambiguity is not an error) that expands to show
`ambiguity` and `candidateMappings` as read-only text. The worker reads it and may not act on it.
This is deliberate: the research's whole argument is that mapping `kamzori` onto anaemia is a
clinical-salience judgment a non-diagnostician should not silently make, so the app must not offer
the affordance to make it.

On the wire: the resolved `clinicalTerm` goes out, which for `kamzori` is "Weakness / tiredness",
itself a presenting complaint and not a diagnosis. The ICPC codes and candidate mappings do not go
on the evaluate wire at all (see Part B). So the wire cannot carry a collapsed resolution because it
never carries a resolution.

In any export: an export must emit `id`, the full `icpc2` list, and `ambiguity`. An export that
emits one ICPC-2 code per selection for an item where `icpc2.size > 1`, or that drops `ambiguity`,
is a defect against this design, and that sentence belongs in the export's own KDoc when it is
built. No export exists in 7a and none is proposed here.

### A.5 On-screen grouping

Three groups, per research recommendation 2: **Adult general**, **Women's / maternal**,
**Paediatric**. Rendered as three labelled sections in the existing `LazyColumn`, adult first,
all three always present.

The **danger-sign set** (`ComplaintGroup.DANGER_SIGN`, `tier = RED_FLAG`) renders in its own section
**above** the three groups and is always visible regardless of which group the worker is looking at,
per the research ("the danger-sign set always visible"). This is why `DANGER_SIGN` is a group value
rather than only a tier: it changes placement, not just styling.

**No patient-based filtering in 7a.** The tempting move is to hide the maternal group for male
patients and the paediatric group above age 5. Two reasons not to:

1. `ConsultationScreen`'s route carries `patientId`, `encounterId`, `caseRecordId`,
   `initialChiefComplaint` (`presentation/navigation/Routes.kt:72` and `ConsultationScreen.kt:52-60`).
   `ConsultationViewModel` does not load `Patient` at all. Filtering means a new repository
   dependency on the ViewModel purely to hide chips.
2. A hard filter is itself a clinical-salience judgment made silently by the app. A paediatric
   danger sign hidden because a date of birth was mistyped is a failure with no visible symptom.

Ordering by relevance would be acceptable later; hiding is not. Recorded as gate item 9.

`other_describe` renders last in every group, as a first-class item (Part C), never as a fallback
that appears only when nothing matched.

---

## Part B: the chiefComplaint field redesign

### B.1 What the field is today, completely

| Layer | Path | Shape |
|---|---|---|
| UI (Consultation) | `ConsultationScreen.kt:154-161` | `OutlinedTextField`, label "Main concern *" |
| UI (Compounder) | `CompounderScreen.kt:122-129` | `OutlinedTextField`, label "Main concern *" |
| Carry-forward | `CompounderViewModel.kt:457` | `CompounderEffect.Continue(..., current.chiefComplaint)` into the Consultation route |
| UI state | `ConsultationViewModel.kt:30`, `:44` | `val chiefComplaint: String`; `canSend` requires `isNotBlank()` |
| Save validation | `domain/usecase/ConsultationUseCases.kt:26` | rejects blank |
| Domain | `domain/model/Consultation.kt:9` | `String`, non-null |
| Room | `data/local/entity/ConsultationEntity.kt:14` | `String`, non-null |
| Sync wire | `data/remote/dto/SyncPayloadDto.kt:60` | `chief_complaint` |
| Backend | `backend/core/app/models/encounter.py:70` | `Text`, `nullable=False` |
| Kernel payload | `domain/usecase/SendToKernelUseCase.kt:31` | `KernelPayload.chiefComplaint` (`KernelPayload.kt:27`) |
| Evaluate wire | `data/remote/RetrofitEvaluateSource.kt:45-48` | joined into `symptomString` |
| Report | `AssembleReportUseCase.kt:65`, `ReportFormatter.kt:130`, `ReportCanvasRenderer.kt:295-296` | quoted verbatim |
| Lists | `PatientSummaryScreen.kt:425`, `:486`, `DoctorListScreen.kt:70` | one-line human label |
| Audit | `ConsultationViewModel.kt:227` | logged verbatim in `CONSULTATION_SAVED` payload |

Note the `/v1/assess` leg carries no free text at all, so `chiefComplaint` reaches exactly one model
input: `symptom_string` on `/api/v1/evaluate`. The field-audit memo's C-2 established this and it
still holds.

### B.2 Persisted shape: one column, a version-prefixed id list

**Recommendation: keep `Consultation.chiefComplaint: String`. Change what the string contains, not
the schema.**

Persisted value format:

    PC-v1:fever|cough_cold|weakness

That is: `VOCABULARY_VERSION`, a colon, then the selected `id`s joined by `|` with no surrounding
whitespace, in vocabulary declaration order (deterministic, so the same selection always produces
the same bytes and a sync diff means a real change).

Why this rather than a list column or a join table:

1. **It is the only shape that keeps 7a-i inside one repo.** P-1: any new column is a backend
   Alembic migration plus a `_CONSULTATION_FIELDS` edit plus a sync allowlist change, or the push is
   rejected SAMD-SYNC-6003.
2. **`chief_complaint` is `nullable=False` on the backend.** A join table leaves the column either
   empty (destroying a required clinical value) or duplicated (two sources of truth for one fact).
3. **Room has no list type.** A join table means a new entity, a new DAO, a new backend table, a new
   `TableSpec`, a new outbox drain, ordering semantics, and cascade-on-consultation-delete
   semantics, for a selection whose realistic maximum is about six items on a row that already
   exists.
4. **The version prefix is self-describing.** It is the same trick the repo already uses for
   `speech-session://<uuid>` URIs: a scheme prefix that tells the reader how to read the rest. It
   solves the "which vocabulary was this captured under" problem with zero schema change, which is
   otherwise a second new column and a second backend coordination.

Invariants, each with a one-line test:
- `id` matches `^[a-z0-9_]+$`, so `|` and `:` can never appear inside a token.
- the version prefix matches `^PC-v[0-9]+:`.
- the joined value fits the field's realistic budget (backend `chief_complaint` is unbounded `Text`,
  so this is a UI concern only).

### B.3 Ids persisted, clinical terms derived

Two candidates for what the ids resolve to at rest:

(a) persist ids, resolve to `clinicalTerm` at each boundary
(b) persist `clinicalTerm` strings directly, no resolution needed

**Recommend (a).** The vocabulary ships as `PC-v1`, an explicit hypothesis that register validation
is expected to change (research hard gate, and recommendation 3 of the MP supplement: "if
inter-rater agreement is low on any item, the wording is wrong, fix the vernacular label"). Under
(b), a wording fix rewrites the meaning-bearing content of the clinical record inconsistently: rows
captured before the fix carry the old wording, rows after carry the new, and nothing on the row says
which. Under (a) the id is stable, the persisted version prefix says which table to resolve against,
and a wording fix is a display change with no record impact. This is the same reason
`TrainedIcdCandidate` stores `icdCode` and derives `label`.

The cost of (a), stated plainly: `chiefComplaint` stops being human-readable at rest, which breaks
the four render sites in P-6. The fix is one pure function in the same file:

    /** Resolves a persisted chiefComplaint into human-readable clinical terms. A legacy free-text
     *  value (no version prefix) is returned unchanged, which is what it always was. */
    fun displayChiefComplaint(persisted: String): String

Four call sites: `ReportFormatter.kt:130` (so the report renders resolved terms, closing P-6),
`PatientSummaryScreen.kt:425` and `:486`, `DoctorListScreen.kt:70`. No new state, no new type.

### B.4 THE WIRE MAPPING

Today, `data/remote/RetrofitEvaluateSource.kt:45-48`:

    val symptomString = listOfNotNull(
        payload.chiefComplaint.takeIf { it.isNotBlank() },
        payload.transcription?.takeIf { it.isNotBlank() },
    ).joinToString(". ")

Proposed:

    val symptomString = resolveWireTerms(payload.chiefComplaint).joinToString(", ")

where `resolveWireTerms` maps each id to its `clinicalTerm`, lowercased, drops `other_describe`,
drops any unresolvable id, and returns the free-text value unchanged (lowercased, untouched
otherwise) for a legacy non-prefixed value.

Four decisions, each argued from a specific in-repo artifact:

**Term form: the English `clinicalTerm`. Not the ICPC-2 code, not the idiom.**
- `docs/backend/api-contract.md:902`, the contract's own request example:
  `"symptom_string": "fever, body ache, dry cough"`. English clinical words.
- `DemoPatientProfile.kt:143`, `:211`, `:287`, `:356`, `:426`: every persona's `mainConcern` is
  English clinical terms.
- `docs/quality/design-history-file.md:45` records a direct read of the training code: Classifier B
  trains `symptom_string` to `icd_candidate`. No in-repo artifact touching that model contains an
  ICPC-2 code anywhere; `TrainedIcdCandidate` is ICD-10, a different classification, and it is the
  model's output side. Sending ICPC-2 codes would be a guaranteed out-of-distribution input.
  Sending Hindi transliterations likewise, with no evidence of any Devanagari or transliterated
  token in the model's training description.

**Delimiter: `", "` (comma-space).**
The two in-repo shapes disagree. `api-contract.md:902` is comma-space; `DemoPatientProfile` is
` | ` pipe. The contract wins because it is the only artifact that documents the wire itself;
`DemoPatientProfile` is device-side mock display data one layer removed from it, and today's
`joinToString(". ")` at `:48` period-joins that pipe blob with a second blob anyway, so the pipe
form has never actually been what cleanly reached the wire. Recording the disagreement rather than
pretending it does not exist.

**Casing: lowercase.**
`api-contract.md:902` and the response `why` string at `:928` ("fever with dry cough and normal
spo2") are both lowercase. This is the lowest-confidence of the four decisions: whether the upstream
vectorizer lowercases is unknowable from this repository. Recommend lowercasing and recording it as
part of the wire-format decision so a later change is traceable rather than accidental.

**Drop `payload.transcription` from the join.**
This is the load-bearing line of the entire PR and it is one edit. If transcription stays in the
join, patient speech still reaches `symptom_string` the moment the voice track re-enables, and H-16
is not closed by 7a at all. `KernelPayload.transcription` stays on the payload (it feeds
`dataQualityScore`, `GenerateKernelReportUseCase.kt:83`, and dropping it would silently change that
score); it simply stops reaching `EvaluateRequestDto`.

**State plainly: this is a phase-zero-classifier-facing choice.** The term strings, the delimiter and
the casing are chosen to match the only four in-repo artifacts that describe that model's input
distribution. None of them is the model. When the model is retrained the mapping changes. That is
exactly why the persisted form is ids and the wire form is derived at one boundary in one function:
a future retrain makes this a single-function change with no migration and no record rewrite.

**And state plainly that this makes the input more in-distribution, not less.** Today any worker can
type any sentence into a field that becomes `symptom_string`. After 7a-i every value is a member of
a fixed English clinical term set shaped like the contract's own example. It also **eliminates R-2 /
H-16 for this field by construction**: a pick-list value cannot carry a patient's name or phone
number, so the key-name-only PHI guard at
`backend/core/app/adapters/kernel/phi_guard.py` stays sufficient and needs no value-level scanner
(and none of that scanner's own false-positive hazard).

### B.5 Migration and the existing free text

**Recommendation: no migration. No backfill. Leave every existing `chiefComplaint` value
byte-identical.**

New rows written after 7a-i carry `PC-v1:` prefixed id lists. Old rows carry whatever the worker
typed. The two are distinguished at read time by one predicate:

    fun isVocabularyValue(persisted: String): Boolean  // matches ^PC-v[0-9]+:

Everything that reads the field routes through `displayChiefComplaint` (B.3) or `resolveWireTerms`
(B.4), both of which pass a legacy value through unchanged.

Why this is the right answer and not merely the cheap one:

- **Zero data loss, and zero fabrication.** A backfill that reinterpreted old free text as vocabulary
  items would be inventing structure no human ever selected. That is the same class of error as the
  empty-differential fabrication the repo already fixed (`AuditAction.KERNEL_EMPTY_DIFFERENTIAL`,
  and `docs/backend/api-contract.md` section 5.3's account of it), where invented clinical content
  was put in front of a clinician attributed to the model.
- **Zero backend coordination.** No new column, so no Alembic, no `_CONSULTATION_FIELDS` edit, no
  sync allowlist change (P-1).
- **It is honest.** The record says what the worker actually entered at the time. REQ-RPT-01's
  "verbatim" promise stays true for every row where it was ever true.

The cost, stated: the field permanently holds two shapes, and every reader must handle both. That is
one predicate and two call paths, and the shape converges naturally as old encounters age out of
active view. There is no forced convergence date and none is needed.

If the operator prefers a hard cutover, the alternative is a coordinated device Room migration
(v16 to v17) plus a backend Alembic migration plus `_CONSULTATION_FIELDS` plus the sync allowlist,
in one commit, with a persisted-row assertion per `CLAUDE.md`'s backend convention. That is a
separate PR (7a-iii, Part F) and is recommended against for 7a.

### B.6 The narrative stays narrative, and where it lives

The voice track (PR 3 onward) must fill a narrative field, never `chiefComplaint`. That field must be
one that `KernelPayload` cannot reach, or the separation is a convention rather than a structure.

Three candidates, and the two obvious ones are both wrong:

- **`relevantHistory` is not it.** It IS in `KernelPayload` (`KernelPayload.kt:30`) and it feeds
  `dataQualityScore` (`GenerateKernelReportUseCase.kt:83`). It does not reach either wire DTO today,
  but it is not excluded by construction and it is one line from being included.
- **`impactOnDailyActivities` is excluded by construction** (named in the `KernelPayload` KDoc
  exclusion list, `KernelPayload.kt:15-18`) but it is a semantically wrong home. Storing "what the
  patient said" in a field labelled "impact on daily activities" corrupts that field for its own
  purpose and mislabels the clinical record.
- **Recommend a new field, `Consultation.patientNarrative: String?`**, whose KDoc states that it is
  excluded from `KernelPayload` by construction, which is the enforcement mechanism the repo already
  relies on for the other four exclusions.

But a new field is a new column, which is P-1 again. So:

**7a-i does not add it.** 7a-i closes H-16's code half by converting `chiefComplaint` and dropping
transcription from the wire. That is complete on its own.

`patientNarrative` is scoped to the voice track, where a coordinated device-plus-backend column
change is already unavoidable: the field-audit memo's PR 1 adds a `FieldProvenance` column, which has
the identical `_CONSULTATION_FIELDS` constraint. Adding `patientNarrative` and its provenance column
in that one coordinated PR is strictly cheaper than doing two coordinated PRs.

Interim home for 7a-i's "Other, describe" text: **`relevantHistory`**, which already exists, already
syncs, and is already read by the doctor on `PatientSummaryScreen` and in the report. Be exact about
what that means for H-16: `relevantHistory` reaches `KernelPayload.relevantHistory`, but
`EvaluateRequestDto` has no field for it (`RetrofitEvaluateSource.kt:50-69` constructs the DTO and
never references it) and `RetrofitKernelSource`'s `/v1/assess` request carries numerics only
(field-audit memo C-2). So the "Other" text reaches neither wire, and H-16 stays closed. That claim
is exact and was checked against the DTO construction, not assumed.

---

## Part C: the "Other, describe" long-tail path

A first-class vocabulary item, not a fallback:

    PresentingComplaint(
        id = "other_describe",
        idiomLabel = "Kuch aur / Something else",
        clinicalTerm = "Other, described by worker",
        icpc2 = emptyList(),
        tier = FrequencyTier.LESS_COMMON_IMPORTANT,
        group = /* rendered last in every group, see A.5 */,
        programme = emptySet(),
        ambiguity = "Unmapped long tail. Never a positive label for any existing class.",
        candidateMappings = emptyList(),
    )

`candidateMappings` is empty here while `ambiguity` is non-null, which breaks the invariant in A.3.
Resolve it by making `other_describe` the one declared exception in the invariant test, or by giving
`ambiguity` its own dedicated sentinel. Recommend the former, one line, explicitly named in the test
rather than a general loophole.

**Behaviour.**

1. Selecting it reveals an `OutlinedTextField` labelled in the worker's own terms ("Describe what the
   patient said"), placed directly under the chip so the connection is visible.
2. The typed text routes to `relevantHistory` (interim, B.6). It never enters `chiefComplaint`, so it
   never enters `symptomString`.
3. `other_describe` is **dropped by `resolveWireTerms`** (B.4). It has no `clinicalTerm` the model has
   ever seen and sending "other" as a symptom would be a novel token in the model's input.
4. It is **never a positive label for any existing class**. Nothing in the app maps it to an ICPC-2
   code, an ICD candidate, or any other vocabulary item. `icpc2` is deliberately empty rather than
   holding an "unspecified" code, because an unspecified code is a label and this must not have one.

**How a future export finds every "Other" entry.** The marker is already in the data: the id is
inside the persisted `chiefComplaint`. A future export finds them with

    WHERE chiefComplaint LIKE '%other_describe%'

joined to the same row's `relevantHistory` for the text. That costs nothing to build now, requires no
extra column, and survives the `patientNarrative` move later (the marker is on `chiefComplaint`, not
on the narrative field). This is the mining path the research's recommendation 5 calls for.

**The empty-`symptom_string` case, and this is a new decision the brief did not anticipate.** If
`other_describe` is the only selection, `resolveWireTerms` returns an empty list and `symptomString`
is empty. `canSend` (`ConsultationViewModel.kt:44`) is satisfied because the persisted id string is
non-blank, so the evaluate call would fire with `"symptom_string": ""`. The backend schema
(`backend/core/app/schemas/kernel.py`, `symptom_string: str`) has no minimum length and the upstream
behaviour on an empty string is unknown from this repository.

Recommend: **do not call evaluate when the resolved term list is empty. Stamp
`InferenceSource.UNAVAILABLE` through the existing path.** The repo already has correct, reviewed
machinery for "the model has no answer for this case" (the empty-differential handling described at
`docs/backend/api-contract.md` section 5.3, `AuditAction.KERNEL_EMPTY_DIFFERENTIAL`, and
`GenerateKernelReportUseCase`'s `UNAVAILABLE_PREDICTED_CONDITION`). Reusing it is honest and
requires no new state. The alternative, forcing the worker to tick a vocabulary item they do not
believe in so the form will proceed, is exactly the "never force a tick" failure the research warns
about, and it would corrupt the training labels this vocabulary exists to produce. This is gate
item 6 because it changes assessment-path behaviour.

**Audit.** The save breadcrumb records that an "Other" was used, never the text:

    auditPayload("consultationId" to consultation.id, "chiefComplaint" to current.chiefComplaint)

That existing call (`ConsultationViewModel.kt:227`) already logs the field verbatim. After 7a-i the
logged value is an id list, so the "Other" flag is present in the audit trail for free and the audit
row carries **less** free text than it does today. Do not add the described text to any audit
payload. Note this as an incidental privacy improvement, not as the PR's purpose.

**Growth threshold, operator-owned, not app code.** Research recommendation 5: if more than 10 to 15
percent of encounters carry `other_describe`, mine the text and promote recurring idioms into the
pick-list, as a `VOCABULARY_VERSION` bump. No dashboard, no counter, and no in-app metric ships in
7a.

---

## Part D: the other Consultation and Compounder pick-list conversions

Wire reach for all seven, established first because it is the whole reason this half is low-risk:

- **None of these seven fields reaches `EvaluateRequestDto`.** Verified against its construction at
  `RetrofitEvaluateSource.kt:50-69`, which references only `caseToken`, the resolved symptom string,
  age, sex, and the vitals numerics.
- **None reaches `/v1/assess`**, which carries numerics only (field-audit memo C-2).
- **None is being newly added to `KernelPayload`.** `Consultation.durationBucket` and
  `severityScore` are already in it (`KernelPayload.kt:28-29`) and are already structured; this PR
  does not change them. `Consultation.onset` is in the `KernelPayload` KDoc exclusion list
  (`KernelPayload.kt:15-16`). `AilmentEntry` has no `KernelPayload` representation at all today
  (REQ-AIL-04, `software-requirements.md:197-200`, describes the future path). `painScore` and
  `urinalysisResult` are stripped at the payload boundary by `VitalsSnapshot.toVitalsReading`
  (`VitalsSnapshot.kt:69-80`).

So Part D is entirely display-and-doctor-read and carries no model-input risk. That is why it can
ship without a model conversation, and why it belongs in a separate sub-PR from B.

**Migration count for the whole of Part D: zero.** Every column is already TEXT or INTEGER of
adequate width on both device and backend.

| # | Field | Current | Component to reuse | Persisted type change |
|---|---|---|---|---|
| D1 | Consultation symptom onset | `ConsultationScreen.kt:165` `OutlinedTextField`; `Consultation.onset: String?` (`Consultation.kt:10`), backend `String(120)` (`encounter.py:71`) | `FilterChip` row, the pattern already three lines below it at `:166-176` | None. String stays String |
| D2 | Ailment duration | `CompounderScreen.kt:307-312` `OutlinedTextField`; `AilmentEntry.duration: String?` (`AilmentEntry.kt:45`), backend `String(120)` (`clinical.py:108`) | shared `DURATION_BUCKETS`, see P-2 | None |
| D3 | Ailment onset | `CompounderScreen.kt:313-318` `OutlinedTextField`; `AilmentEntry.onset: String?` (`AilmentEntry.kt:44`), backend `String(120)` (`clinical.py:107`) | same chip row as D1, same shared constant | None |
| D4 | Measured unit | `CompounderScreen.kt:292-297` `OutlinedTextField`; `AilmentEntry.measuredUnit: String?` (`AilmentEntry.kt:42`), backend `String(20)` (`clinical.py:105`) | `DropdownField` (`presentation/common/DropdownField.kt`), already imported at `CompounderScreen.kt:48` | None |
| D5 | Ailment severity | `CompounderScreen.kt:300-306` `OutlinedTextField` + `filterDigitsOnly(max 2)`; `AilmentEntry.severity: Int?` (`AilmentEntry.kt:43`) | `Slider`, exact copy of `ConsultationScreen.kt:177-185` (`valueRange = 0f..10f, steps = 9`) | None. `Int?` stays `Int?` |
| D6 | Pain score | `CompounderScreen.kt:177-179` `OutlinedTextField` + `filterDigitsOnly(max 2)`; `VitalsSnapshot.painScore: Int?` (`VitalsSnapshot.kt:34`), persisted as an `Observation` numeric row | same `Slider` | None |
| D7 | Urinalysis | `CompounderScreen.kt:197-199` `OutlinedTextField`; `VitalsSnapshot.urinalysisResult: String?` (`VitalsSnapshot.kt:35`), persisted as one `Observation` row of `ObservationType.URINALYSIS` in `valueText` (`VitalsRepositoryImpl.kt:39`, `:80-97`), rendered at `ReportFormatter.kt:178` | four `FilterChip` rows in a grid | None. Still one `valueText` string |

Notes that matter for the build, kept short:

- **D1/D2/D3 vocabulary.** Onset: `sudden` / `gradual`, two chips, nothing more; anything richer is
  the per-ailment-type expansion REQ-TRS-04 still lists as PLANNED. Duration: the existing four
  values, after P-2's promotion and demo-data fix. Both vocabularies must fit inside 40 characters
  when persisted, which the backend's narrowest relevant column (`duration_bucket String(40)`)
  requires and every proposed value satisfies.
- **D1/D2/D3 legacy values.** Existing rows hold sentences: `DemoPatientProfile.symptomOnset` at
  `:159` is "Gradual onset over the past 6 months, worsening". Same two-shape read as B.5, but
  simpler here because nothing consumes these structurally. Render legacy values verbatim, do not
  migrate.
- **D4 unit list.** Flat, not per-ailment-type, because there is no ailment-type field in
  `AilmentEntry` to key it on and inventing one is out of scope. Ship °C, °F, mmHg, bpm, mg/dL, %,
  kg, cm, breaths/min. All fit `String(20)`. The conditional-by-type list is REQ-TRS-04's still
  PLANNED half and belongs there.
- **D5 fixes a real input-validation hole, not just keystrokes.** `filterDigitsOnly(maxLength = 2)`
  accepts "99" for a field the label calls 0-10, and `onAilmentSeverityChange` feeds
  `toIntOrNull()` (`CompounderViewModel.kt:358`) with no range check anywhere. The slider closes
  that by construction.
- **D5/D6 unset semantics.** `AilmentEntry.severity` and `VitalsSnapshot.painScore` are both `Int?`.
  Do not copy the Consultation slider's non-null `severityScore: Int = 0`
  (`ConsultationUiState`, `ConsultationViewModel.kt:35`), which makes "not asked" and "zero
  severity" indistinguishable. Keep `Int?`, render the slider as unset until first touch. The
  Consultation-side wart is pre-existing and out of 7a's scope; flagging it, not fixing it.
- **D7 encoding.** Four analytes (protein, glucose, ketones, blood), each over the dipstick scale
  (negative, trace, 1+, 2+, 3+, 4+). Persist as one canonical string in the same `valueText` column:
  `protein:1+;glucose:neg;ketones:trace;blood:neg`. No new `ObservationType`, no new column, no
  migration. Legacy free-text values render verbatim. `ReportFormatter.kt:178` needs the same
  resolve-or-passthrough treatment as B.3's display function.

---

## Part E: regulatory and safety

### E.1 REQ ids touched

| REQ | Location | Effect of 7a |
|---|---|---|
| REQ-CON-01 (DONE) | `software-requirements.md:37-38` | **Amend.** Chief complaint becomes a multi-select over a versioned vocabulary with an "Other, describe" long tail; onset becomes a pick-list. The "(text or voice)" clause should also note that voice is currently disabled per H-15 and that when it returns it fills the narrative field, not chief complaint |
| REQ-RPT-01 (DONE) | `:238-240` | **Amend.** "Verbatim chief complaint" is no longer literally accurate for a vocabulary-captured row; the report renders resolved clinical terms for those and verbatim text for legacy rows. See P-6 |
| REQ-TRS-04 (PARTIAL) | `:223-226` | **Advanced, not closed.** 7a-ii converts severity/duration/onset to structured controls, but the per-ailment-type dynamic field set stays PLANNED because no ailment-type field exists. Do not over-claim this to DONE |
| REQ-AIL-01 (DONE) | `:185-186` | Unchanged in intent; its capture components change |
| REQ-HAN-06 (DONE) / H-10 | `:49-52`, `KernelPayload.kt` | Unchanged and strengthened. The boundary is untouched; its content becomes structurally narrower |
| REQ-CON-02, REQ-TRS-05, REQ-TRS-06 | | Untouched |

**One new REQ is needed. Proposed, not written into `docs/`:**

> **REQ-CON-03** (PROPOSED) The presenting-complaint vocabulary is a versioned repository artifact
> carrying, per item, a vernacular idiom label, an English clinical term, one or more ICPC-2 codes, a
> frequency tier, an India-programme map, and an ambiguity flag with its candidate clinical mappings.
> `VOCABULARY_VERSION` is bumped in the same commit as any change to the list, and is persisted with
> every captured value so a stored record resolves against the vocabulary it was captured under. An
> item flagged ambiguous is never collapsed to a single clinical code in the field, on any wire, or
> in any export.

Nothing in `docs/` covers this today, and H-16's accepted control is not traceable without it.
`docs/requirements/traceability-matrix.md` needs the corresponding row. Flagged, not written.

### E.2 H-16: what 7a closes and what it does not

H-16 (`docs/quality/risk-management-file.md:40`), control option (a) accepted, hazard OPEN, residual
recorded as (1) the multi-select control ships and (2) register validation passes.

**Closes: residual item (1), completely.** After 7a-i, no code path populates `symptom_string` from
patient narrative or from operator free text. `chiefComplaint` resolves to a fixed clinical term set,
and `payload.transcription` is removed from the join at `RetrofitEvaluateSource.kt:45-48`. This
eliminates the value-level PHI path rather than mitigating it.

**Does not close:**

- **Residual item (2), register validation.** The vocabulary is a v1 hypothesis. Operator-owned, 4 to
  8 weeks, 3 to 5 sites. No amount of code closes it, and 7a must not be read as closing it.
- **The guard itself.** `assert_no_identity_fields` in
  `backend/core/app/adapters/kernel/phi_guard.py` is unchanged and still key-name-only. 7a removes the
  input that would have exposed it; it does not harden it. If any later change routes narrative to
  `symptom_string`, the hazard reopens at full severity. That sentence belongs in the residual column.
- **H-15.** Untouched. `FeatureFlags.VOICE_INPUT_ENABLED` stays `false`. 7a is not the voice PR.

**PROPOSED risk-file text for H-16's control and residual columns, drafted here, NOT written into
`docs/`, pending operator sign-off:**

> **Control implemented (PR 7a-i).** `chiefComplaint` is captured as a multi-select over
> `PRESENTING_COMPLAINT_VOCABULARY`
> (`app/src/main/java/com/example/samdapp/domain/model/PresentingComplaint.kt`,
> `VOCABULARY_VERSION = "PC-v1"`), persisted as a version-prefixed list of stable vocabulary ids, and
> resolved to lowercase English clinical terms at a single wire boundary in
> `RetrofitEvaluateSource`. `payload.transcription` is removed from the `symptomString` join in the
> same commit. No code path therefore populates `symptom_string` from patient speech or from operator
> free text. An ambiguous item carries all of its candidate clinical mappings as data and is never
> collapsed to a single code in the field, on the wire, or in an export. The "Other, describe"
> long-tail routes to a human-read narrative field that reaches neither wire DTO, and is never used
> as a positive label for any existing class.
> **Residual.** (a) Local PHC register validation of the vocabulary (4 to 8 weeks, 3 to 5 sites) is
> outstanding and gates production deployment; the shipped list is an evidence-grounded hypothesis,
> not a validated vocabulary. (b) `assert_no_identity_fields` remains key-name-only and is unchanged
> by this PR: the hazard reopens at full severity if any future change routes narrative onto
> `symptom_string`. **Hazard stays OPEN pending (a).**

### E.3 This PR reduces risk. Plainly.

1. **Eliminates the H-16 / R-2 value-level PHI path for `chiefComplaint`, by construction.** A
   pick-list value cannot carry a name or a phone number, so the value-blind guard stays sufficient
   and no backend value-scanner (with its own false-positive hazard) is needed.
2. **Makes the model input more in-distribution than it is today, not less.** Today a worker can type
   any sentence into a field that becomes `symptom_string`. After 7a-i every value is drawn from a
   fixed English clinical term set shaped like the contract's own example at
   `api-contract.md:902`.
3. **Removes free-text data-quality rot** on five further fields (two onsets, ailment duration,
   measured unit, urinalysis) and closes an unvalidated numeric input (ailment severity currently
   accepts 0-99 for a documented 0-10 scale).
4. **Reduces PHI in the device audit log.** `CONSULTATION_SAVED` currently logs `chiefComplaint`
   verbatim (`ConsultationViewModel.kt:227`), a pre-existing wart the field-audit memo flagged. After
   7a-i that payload is an id list.

**One honest counter, stated rather than buried.** 7a changes the input format of a trained model
without a measured accuracy comparison against the real kernel. The usecase memo (3.4, item 3) named
that comparison as a prerequisite for the *narrative* option. It is a materially smaller concern here
because the new format moves toward the documented distribution rather than away from it, but it is
not zero, and `docs/quality/qms-overview.md:45` lists the Algorithm Change Protocol as TODO precisely
for changes of this kind. Recommendation: run one side-by-side pass (the same cases, free text versus
resolved terms) against the kernel at `backend/core/app/config.py`'s `kernel_base_url` before 7a-i
reaches staging, and record it in the DHF. It is cheap and it is the only evidence that the change
helped. Gate item 7.

### E.4 The register-validation gate

The vocabulary ships as `PC-v1`, an explicitly labelled hypothesis. Production deployment is gated on
local PHC register validation, which is **operator-owned** and has weeks of lead time; the
2026-08-30/31 `PROGRESS.md` entry already records that it should start as soon as site access can be
arranged, independent of code work. Thresholds, from the research and not invented here:

- 4 to 8 weeks of OPD, ANC and child-health register data from 3 to 5 sites in the deployment
  districts, tallied against the shipped list.
- Add any complaint appearing in more than 5% of local visits that is missing.
- Demote any `VERY_COMMON` item appearing in under 2% locally.
- Worker-comprehension test in Malvi and Hindi: 50 real patient utterances mapped by ASHAs and
  nurses; low inter-rater agreement on an item means the vernacular wording is wrong and the wording
  is fixed, not the worker.
- If a promoted idiom's clinician-confirmed mapping disagrees with the seed mapping in more than 20%
  of cases, flag it for re-labelling.

Every one of those outcomes is a `VOCABULARY_VERSION` bump. That is what the version constant is for,
and it is why A.3 pins it with a test rather than trusting a convention.

---

## Part F: build sequence and docs

### F.1 Split into two sub-PRs

7a as one PR is too big, and not because of line count. 7a-i touches a model input path, the printed
clinical record, three list screens, and a wire format. 7a-ii touches six form fields and no model
path at all. Merging them buries the one change to the only narrative field on a wire DTO inside a
form-refactor diff, which is the specific thing that must not happen to that field.

**PR 7a-i: presenting-complaint vocabulary artifact, `chiefComplaint` multi-select, wire mapping.**

Scope, one line: add `domain/model/PresentingComplaint.kt` (data class, the ~41-item
`PRESENTING_COMPLAINT_VOCABULARY`, `VOCABULARY_VERSION = "PC-v1"`, `displayChiefComplaint`,
`resolveWireTerms`, `isVocabularyValue`, plus the list-integrity and invariant tests); replace the
`chiefComplaint` `OutlinedTextField` on both Consultation (`ConsultationScreen.kt:154-161`) and
Compounder (`CompounderScreen.kt:122-129`) with the grouped multi-select plus "Other, describe"
routing to `relevantHistory`; resolve ids to lowercase clinical terms comma-joined in
`RetrofitEvaluateSource` and drop `payload.transcription` from the join; skip the evaluate call and
stamp `UNAVAILABLE` when the resolved list is empty; render resolved terms at
`ReportFormatter.kt:130`, `PatientSummaryScreen.kt:425`/`:486`, `DoctorListScreen.kt:70` with legacy
free text passed through verbatim; **no migration, no new column, no backend change**; **update
`PROGRESS.md` inline in the same commit**, and draft the REQ-CON-01 / REQ-CON-03 / REQ-RPT-01 and
H-16 changes as PROPOSED-pending-operator, uncommitted to `docs/` approval.

Routing: **STEP-1 Opus is this memo. STEP-2 Sonnet for the build**, with the
`RetrofitEvaluateSource` change and the legacy-value predicate reviewed as their own pass rather than
inside the bulk diff.

**PR 7a-ii: Consultation and Compounder pick-list conversions.**

Scope, one line: promote `DURATION_BUCKETS` out of `ConsultationScreen.kt:77` into `domain/model/`
and fix `DemoPatientProfile`'s out-of-set `"months"` (`:160`) and `"weeks"` (`:228`); onset chips on
both screens (D1, D3); `DropdownField` measured-unit list (D4); `Slider` for ailment severity and
pain score, both `Int?` and unset until touched (D5, D6); urinalysis four-analyte chip grid persisted
as one canonical `valueText` string (D7); **zero migrations, no backend change**; **update
`PROGRESS.md` inline in the same commit**, and draft the REQ-TRS-04 status note as
PROPOSED-pending-operator.

Routing: **STEP-2 Sonnet only.** No design decision remains after this memo. Every component already
exists in the repo, and no field on this list reaches a model.

**PR 7a-iii (only if the operator overrides gate items 1 or 5): coordinated schema change.**

Scope, one line: device Room migration v16 to v17 adding whichever columns the override requires
(`chiefComplaintCodes` / `vocabularyVersion` / `patientNarrative`), plus the backend Alembic
migration, plus the `_CONSULTATION_FIELDS` addition in `backend/core/app/api/v1/encounters.py`, plus
the sync mapper change, in one commit, with a persisted-row assertion per `CLAUDE.md`'s backend
convention; update `PROGRESS.md` inline in the same commit.

Routing: **STEP-1 Opus for the migration design, STEP-2 Sonnet for the build.** Recommended against
for 7a; recorded so the cost of the override is visible before it is chosen.

### F.2 Docs discipline

Per P-4 the "STANDING DOCS RULE" is not a named artifact in this repo, but the practice is
established and this memo follows it:

- Each build PR **updates `PROGRESS.md` inline in the same commit** as the code. This is written into
  each sub-PR's scope line above, not left as a follow-up task.
- Every controlled `docs/` change (the REQ-CON-01 / REQ-CON-03 / REQ-RPT-01 / REQ-TRS-04 amendments
  and the H-16 control-and-residual text) ships as **PROPOSED-pending-operator**, following the
  existing marker convention at `docs/quality/risk-management-file.md:44-47`, and is not treated as
  settled until the operator confirms.
- `docs/requirements/traceability-matrix.md` needs the REQ-CON-03 row when that REQ is accepted.

---

## DECISION GATE

STEP 2 does not begin until the operator rules on these. Items 6 through 9 were not in the brief and
are here because the design found them.

**1. Persisted shape of `chiefComplaint` and the migration of existing free text.**
Recommendation: keep the single `chiefComplaint: String` column; the value becomes
`PC-v1:fever|cough_cold|weakness`. **No Room migration, no backfill, existing free-text values left
byte-identical**, distinguished at read time by the `^PC-v[0-9]+:` prefix predicate. Alternative: a
coordinated new column plus backend Alembic plus `_CONSULTATION_FIELDS` plus the sync allowlist
(PR 7a-iii). The recommendation is what keeps 7a-i inside one repo (P-1).
Confirm: the single-column version-prefixed id list, and no backfill.

**2. The exact `symptomString` wire format for the phase-zero classifier.**
Recommendation, four parts, confirm all four:
(a) term form = the English `clinicalTerm`, not the ICPC-2 code, not the idiom;
(b) delimiter = `", "` comma-space, per `api-contract.md:902`, overriding `DemoPatientProfile`'s pipe;
(c) casing = lowercase (lowest-confidence of the four; the upstream vectorizer is unreadable from
this repo);
(d) `payload.transcription` is **removed** from the join, and `other_describe` is dropped from the
resolved terms.
This is a phase-zero-classifier-facing choice that changes when the model is retrained, which is why
it lives in one function at one boundary.

**3. The vocabulary data-class shape and where `VOCABULARY_VERSION` lives.**
Recommendation: `PresentingComplaint` with `icpc2: List<String>` and `candidateMappings:
List<String>` (not an `isAmbiguous: Boolean`, which would discard the thing the research hard gate
says must be preserved), `const val VOCABULARY_VERSION = "PC-v1"` at the top of
`app/src/main/java/com/example/samdapp/domain/model/PresentingComplaint.kt` beside the list, pinned
by a size-and-id-hash integrity test so a list edit without a version bump fails the build.

**4. One PR or a split.**
Recommendation: **split.** 7a-i (vocabulary + multi-select + wire mapping, STEP-2 Sonnet with a
separate review pass on the wire change) then 7a-ii (pick-list conversions, STEP-2 Sonnet). Reason:
the only narrative field on a wire DTO must not be reviewed inside a form-refactor diff.

**5. Where the narrative field lives.**
Recommendation: **neither `relevantHistory` nor `impactOnDailyActivities`.** `relevantHistory` is in
`KernelPayload` and one line from the wire; `impactOnDailyActivities` is excluded by construction but
is a semantically wrong home. A new `Consultation.patientNarrative: String?`, deferred to the voice
track's already-necessary coordinated column PR (which must add a `FieldProvenance` column anyway).
7a-i uses `relevantHistory` as the interim home for "Other, describe" text, which reaches neither
wire DTO, so H-16 stays closed in the interim.

**6. NEW. What happens when "Other, describe" is the only selection and `symptom_string` resolves
empty.**
Recommendation: **do not call evaluate. Stamp `InferenceSource.UNAVAILABLE` through the existing
empty-differential machinery.** Confirm explicitly, because it changes assessment-path behaviour and
the alternative (send an empty string and see what the upstream model does) is unmeasured.

**7. NEW. The terse-versus-current accuracy comparison against the real kernel.**
Recommendation: run one side-by-side pass on the same cases, free text versus resolved terms, against
the kernel host in `backend/core/app/config.py`, before 7a-i reaches staging, and record it in the
DHF. It is cheap, and it is the only evidence that the wire-format change helped rather than merely
looked safer.

**8. NEW. `DemoPatientProfile` writes duration values outside `DURATION_BUCKETS`.**
`"months"` (`:160`) and `"weeks"` (`:228`) are not in the four-value list at
`ConsultationScreen.kt:77` (P-2). Recommendation: fix to in-set values in 7a-ii, at the same time
`DURATION_BUCKETS` is promoted out of the screen file. Confirm, because it edits demo data an
investor demo path depends on.

**9. NEW. Whether the maternal and paediatric groups are filtered by patient sex and age.**
Recommendation: **no filtering in 7a.** All three groups always visible, adult first, danger signs
always above. Reasons: `ConsultationViewModel` does not load `Patient` today, so filtering adds a
repository dependency purely to hide chips; and a hard filter is a clinical-salience judgment made
silently by the app, whose failure mode (a paediatric danger sign hidden by a mistyped date of birth)
has no visible symptom. Relevance ordering would be acceptable later; hiding is not.

---

## Appendix: what this memo did NOT do

No production code written. No migration written. No `docs/` file modified. No new REQ or hazard row
written into `docs/`; the REQ-CON-03 text and the H-16 control-and-residual text in Part E are
drafts for the operator, not entries. `.env`, `local.properties`, `BuildConfig` and every credential
file untouched. Nothing staged, committed, or pushed. The only file written by this session is this
one, at `scratchpad/pr7a-structured-capture-design-memo.md`.

The ~41 vocabulary items themselves are NOT transcribed into this memo. They live in
`scratchpad/asr-symptom-vocabulary-research.md` (30 adult general, 4 women's/maternal, 6 paediatric,
plus the always-present danger-sign set and the mandatory "Other, describe", with the MP-tribal-belt
supplement adding the malaria/sickle-cell/anaemia/malnutrition/TB items). That file is the ground
truth for the list content and must be the source the build reads from; duplicating it here would
create a second copy that can drift from the one the risk file cites.
