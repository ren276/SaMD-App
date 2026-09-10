# STEP 1 design memo: empty-input safety guard + safe structured-field dropdowns (read-only)

Run date: 2026-09-06. SaMDApp branch `master`, HEAD `cab78ae`.

**Read-only guarantee.** Nothing was written, edited or deleted outside this one memo. No source,
test, config, dataset or model file touched. Nothing staged, nothing committed. No `.env`,
`local.properties` or any credential file opened at any point. No build run, no network call.

**Inputs treated as established fact, not re-derived**: `scratchpad/classifier-dataset-nlem-audit-memo.md`
and `scratchpad/production-classifier-architecture-memo.md`. Also read for prior-art continuity:
`scratchpad/pr7a-structured-capture-design-memo.md` (2026-08-31, HEAD `a1f9176` — its line
citations have drifted; every citation below was re-verified against disk at `cab78ae`).

Working tree at start (pre-existing, none of it mine): `.idea/deploymentTargetSelector.xml` and
`PROGRESS.md` modified; `dashboard.html` and 25 `scratchpad/` files untracked.

---

## 0. STOP-AND-REPORT — five places where disk contradicts the brief

The brief says repo and disk win over any planning doc. Five contradictions were found. None is
blocking; each changes the shape of one piece of the design, and the design below is written on the
corrected picture.

### C-1. The evaluate path has no `InferenceSource` at all. There is nothing to "return `UNAVAILABLE`" from.

The brief asks the guard to "return `InferenceSource.UNAVAILABLE`". On disk, `InferenceSource` is a
property of the **`/v1/assess` kernel path only**:

- `app/src/main/java/com/example/samdapp/domain/model/InferenceSource.kt:12`
  `enum class InferenceSource { REAL_INFERENCE, MOCK_FALLBACK, UNAVAILABLE }`, whose KDoc opens
  *"Which path produced a given [KernelReportOutput]"*.
- It is stamped in exactly two places, both in `GenerateKernelReportUseCase.kt` — `:246`
  (`REAL_INFERENCE`) and `:297` (`UNAVAILABLE`, inside `buildUnavailableOutput`).
- `EvaluateReportOutput` (`app/src/main/java/com/example/samdapp/domain/model/EvaluateReportOutput.kt:12-26`)
  has **no `inferenceSource` field**. Neither does `EvaluateResult`
  (`domain/kernel/EvaluateKernelSource.kt:34-39`).

And the E66 hazard is exclusively on the evaluate path. `RetrofitKernelSource.assess`
(`data/remote/RetrofitKernelSource.kt:41-53`) builds `KernelAssessmentRequestDto` from vitals, age
and sex only — **it sends no symptom text whatsoever**. The only place a symptom string is
constructed is `RetrofitEvaluateSource.kt:45-48`, and the only wire field carrying it is
`EvaluateRequestDto.symptomString` (`data/remote/dto/EvaluateRequestDto.kt:18`). So the hazard the
audit measured cannot be expressed as an `InferenceSource` value, because the endpoint that
produces the E66 differential does not carry one.

**What the evaluate path has instead, and what the guard must therefore use.** The H-14 failure
marker, already built, already persisted, already rendered:

- `domain/repository/EvaluateReportRepository.kt:22`
  `suspend fun saveFailure(caseRecordId: String, failureCode: String): Result<Unit>` — KDoc:
  *"persists that `/api/v1/evaluate` was attempted for [caseRecordId] and failed, so the failure is
  readable back instead of looking identical to 'hasn't run yet'"*.
- `data/repository/EvaluateReportRepositoryImpl.kt:68-95` upserts the same per-case row `save`
  writes, so a later success clears the marker; `:101` makes `getForCase` return null while a
  failure is on record, so a failure row can never be read as a report.
- `presentation/report/ReportCanvasRenderer.kt:61-62` renders it to the clinician:
  `"EVALUATION FAILED — no AI treatment recommendation was generated"`.
- `domain/usecase/AssembleReportUseCase.kt:56` and `presentation/patientsummary/PatientSummaryViewModel.kt:219`
  both already read it.

**Resolution taken in this design.** The guard produces the evaluate path's *existing* honest
failure state — a persisted `saveFailure` marker plus `Result.failure` — which is the exact
functional equivalent of `InferenceSource.UNAVAILABLE` on the path that owns `InferenceSource`:
no fabricated clinical content, distinguishable from "never ran", readable back, and already
visible on the report. Adding an `inferenceSource` column to `EvaluateReportEntity` to satisfy the
brief's wording literally would be a Room migration plus a domain-model change on a
hazard-registered surface — an architecture change, which the brief explicitly forbids for this
guard. **Flagged for the operator: the memo delivers the brief's intent, not its literal wording.
If the operator wants the literal enum, that is a separate migration PR.**

### C-2. Two of the five Part B fields are already constrained controls. Converting them is not the work.

- **duration** is already a `FilterChip` row over a fixed vocabulary —
  `presentation/consultation/ConsultationScreen.kt:87`
  `private val DURATION_BUCKETS = listOf("today", "few_days", "week_plus", "chronic")`, rendered at
  `:242-251`. It is not free text and never was.
- **severity** is already a `Slider` with `valueRange = 0f..10f, steps = 9` —
  `ConsultationScreen.kt:253-259`. The brief names sliders as an acceptable constrained control, so
  this field is already in its target state.

The real defects on these two are different from "they are free text", and are handled in §2.

### C-3. `impactOnDailyActivities` carries a **live** voice confirmation gate. A pure dropdown would break it.

`config/FeatureFlags.kt:71` — `const val VOICE_FIELD_IMPACT_ENABLED = true`, flipped on and
authorized (commit 5). The field is the sole target of the PR 3 confirmation gate:
`ConsultationUiState.impactVoiceSuggestion` / `.isCapturingImpactVoice` / `.impactProvenance` /
`.impactVoicePendingEdit` (`ConsultationViewModel.kt:111-149`), the
`ImpactVoiceSuggestionSurface` (`ConsultationScreen.kt:451-479`), the `FieldProvenance` column and
its `MIGRATION_16_17` backfill, the `VOICE_UNCONFIRMED` write refusal at
`data/repository/ConsultationRepositoryImpl.kt:44-54`, and the `canSend` clause at
`ConsultationViewModel.kt:186-188`.

That whole mechanism assumes a **free-text field** that an arbitrary ASR transcript can be written
into. Replacing it with a pick-list means `onUseImpactSuggestion` would commit a transcript the
control cannot represent, and `VOICE_EDITED` ("voice-seeded, then hand-corrected") would lose its
meaning. **`impactOnDailyActivities` therefore does not become a dropdown.** It gets a constrained
*assist* that preserves the free-text field exactly — see §2.

### C-4. `relevantHistory` is not a fixed set anywhere in this codebase.

`Consultation.relevantHistory: String?` (`domain/model/Consultation.kt:21`), backend
`relevant_history: Mapped[str | None] = mapped_column(Text)` (`backend/core/app/models/encounter.py:83`).
No enum, no constant, no CHECK constraint. The repo's own demo values are irreducible narrative —
`data/mock/DemoPatientProfile.kt:378`: *"No known bleeding disorder; two other cases reported in
same village this week"*, which is an epidemiological signal no comorbidity pick-list can hold.
The brief's own qualifier ("where it is a fixed set") applies: it is not one, and it gets the same
constrained-assist treatment as impact rather than a replacement pick-list.

### C-5. The E66 hazard is currently **latent, not live**. One guard already exists, one layer up.

`domain/usecase/ConsultationUseCases.kt:33-35`:

    if (chiefComplaint.isBlank()) {
        return Result.failure(IllegalArgumentException("Chief complaint is required"))
    }

plus the UI gate `ConsultationViewModel.kt:186` (`canSend` requires `chiefComplaint.isNotBlank()`).
So no *current* path produces an empty `symptom_string`.

That does not make the guard optional, and three facts say why:

1. The check is on **one use case**, not on the repository and not at the seam. `ConsultationRepositoryImpl.saveConsultation`
   (`:44-55`) refuses `VOICE_UNCONFIRMED` provenance but accepts a blank `chiefComplaint` from any
   caller. `SendToKernelUseCase` (`domain/usecase/SendToKernelUseCase.kt:28-37`) copies the field
   through with no check. `RetrofitEvaluateSource:45-48` builds `""` from two blanks without
   complaint. Everything downstream of `SaveConsultationUseCase` is unguarded.
2. The audit memo states the trigger explicitly (§ "The moment a pick-list can resolve to an empty
   token list, the unregistered E66-at-74.95% hazard goes live in production"). Part B of this very
   memo adds pick-lists to the same screen. The guard must land before or with them.
3. The audit's L-8 finding is that the hazard is **unregistered** in
   `docs/quality/risk-management-file.md` (H-01…H-19, none covering it). Registration is required
   regardless of current reachability — a latent hazard with a known mechanism is exactly what ISO
   14971 §5 asks to be recorded.

**Stated plainly for the gate: this build closes a latent hazard and registers it, and is a
precondition for Part B rather than a response to a live field failure.**

---

## 1. Part A — the empty-input safety guard

### 1.1 The measured hazard, restated (not re-derived)

From `classifier-dataset-nlem-audit-memo.md` §C.1, transcribing finding X-3 of
`classifier-wire-format-investigation.md` (2026-08-31):

    empty symptom_string ->  E66 0.7495 · I10 0.0709 · M17 0.0397 · A90 0.0337 · B54 0.0308

An empty input yields a **confident Obesity differential at 74.95%**, not an abstention. The
backend does no inference-time preprocessing at all (`SaMDClassifier/src/refine_diagnosis.py:76-82`,
`src/app.py:194`), so the model receives the empty string verbatim and answers from its prior —
E66 being 41.82% of labelled training rows (audit §B).

### 1.2 The seam, quoted

`app/src/main/java/com/example/samdapp/data/remote/RetrofitEvaluateSource.kt:45-48`:

    val symptomString = listOfNotNull(
        payload.chiefComplaint.takeIf { it.isNotBlank() },
        payload.transcription?.takeIf { it.isNotBlank() },
    ).joinToString(". ")

This yields `""` **if and only if both `chiefComplaint` and `transcription` are blank**. There is no
other producer of `symptom_string`; `EvaluateRequestDto` (`:16-29`) carries exactly one text field
and it is this one.

The single caller of the evaluate boundary is
`domain/usecase/GenerateEvaluateReportUseCase.kt:39-43`:

    val result = evaluateKernelSource.evaluate(
        payload = payload,
        patientAge = patientAge ?: 30,
        patientSex = patientSex ?: "U",
    )

reached from `domain/usecase/AssessmentRunner.kt:81-86`, the sole orchestrator for both a case's
first assessment and every retry.

### 1.3 Where the guard sits, and why there

**Decision: `GenerateEvaluateReportUseCase.invoke`, immediately before the `try` block at `:38`.**

Rationale, in the order that decided it:

- It is the layer that already owns the honest-failure outcome. `saveFailure` is injected here
  (`:23`) and already called on the failure path (`:66`). The guard reuses that machinery with zero
  new plumbing — no new repository method, no new UI state, no new audit action.
- It guards the **interface**, not one implementation. `EvaluateKernelSource` is the domain boundary;
  a future second implementation (the on-device classifier the architecture memo designs for) would
  bypass a guard placed inside `RetrofitEvaluateSource` but not this one. Ponytail's root-cause rule:
  one guard where all callers route through, not one per caller.
- The predicate is expressible in domain terms without importing wire detail — see 1.4, it is the
  exact logical complement of the `listOfNotNull` at the seam.
- `AssessmentRunner:95-102` already converts the resulting `Result.failure` into an
  `AuditAction.EVALUATE_RESPONSE_FAILED` breadcrumb. Nothing to add there either.

**Rejected: inside `RetrofitEvaluateSource.evaluate`.** It is where the string is built, which is
why the audit points there, but it is implementation-scoped and would have to signal failure by
throwing (the interface contract at `EvaluateKernelSource.kt:21-24` is "throws on any failure"),
reporting a deliberate policy refusal as an exception. The same objection
`ConsultationRepositoryImpl:38-42` already records for the `VOICE_UNCONFIRMED` refusal.

**Rejected: blocking at the capture screen only.** `canSend` already does that
(`ConsultationViewModel.kt:186`) and it is exactly the guard C-5 shows is insufficient — a UI gate
does not protect a second caller.

### 1.4 The exact condition

    private fun KernelPayload.hasNoSymptomText(): Boolean =
        chiefComplaint.isBlank() && transcription.isNullOrBlank()

This is the precise logical complement of `RetrofitEvaluateSource:45-48`: those two blanks are the
only way `symptomString` becomes `""`.

**Recommended widening, flagged honestly.** The measured hazard is the *empty* string. A non-blank
but token-free string (`"-"`, `"???"`, `"…"`) is **not** the measured case — the char n-gram
vectorizer does produce features for punctuation, so its output is unmeasured, and claiming it
yields the same E66 prior would be overreach. It is nonetheless nonsense input reaching a clinical
model, and excluding it costs one predicate:

    private fun KernelPayload.hasNoSymptomText(): Boolean =
        chiefComplaint.none(Char::isLetterOrDigit) && transcription.orEmpty().none(Char::isLetterOrDigit)

(`none { it.isLetterOrDigit() }` is true for the empty string, so this strictly contains the
blank case.) Recommended: **take the widened form.** It is a trust boundary into a clinical model,
the only input it newly refuses is an all-punctuation chief complaint, and there is no legitimate
clinical text field whose entire content is punctuation. The build memo should record that the
blank case is measured and the punctuation case is a precaution.

### 1.5 The exact behavior

    suspend operator fun invoke(...): Result<EvaluateReportOutput> {
        val inferenceStartedAt = Instant.now()

        if (payload.hasNoSymptomText()) {
            // The classifier answers an empty symptom_string with a confident E66 (Obesity)
            // differential at 74.95% — its training prior, not an assessment of this patient
            // (risk-management-file.md H-20). Never call it with nothing.
            logger.warning("Empty symptom input for case $caseRecordId — evaluate not called.")
            evaluateReportRepository.saveFailure(caseRecordId, EMPTY_SYMPTOM_INPUT)
            return Result.failure(IllegalStateException(EMPTY_SYMPTOM_INPUT))
        }

        return try { ... }   // unchanged from here down
    }

with `const val EMPTY_SYMPTOM_INPUT = "EMPTY_SYMPTOM_INPUT"` in the existing `companion object`
(`:27-29`), so the failure code is one named constant rather than a bare string.

Properties this gives, each traceable to something already on disk:

| Property | Mechanism already present |
|---|---|
| The classifier is not called | `evaluateKernelSource.evaluate` is never reached |
| No fabricated differential is persisted | no `EvaluateReportOutput` is constructed |
| The state is readable back, distinct from "never ran" | `saveFailure` upsert, `EvaluateReportRepositoryImpl:68-95` |
| A retry that succeeds clears it | same upsert row, `failureCode = null` at `:63` |
| The clinician sees it | `ReportCanvasRenderer.kt:61-62` failure-marker label |
| The audit trail records it | `AssessmentRunner.kt:95-102`, `EVALUATE_RESPONSE_FAILED` |
| The `/v1/assess` kernel path is unaffected | it sends no symptom text at all (C-1) |

`saveFailure`'s own `Result` is ignored, matching the existing best-effort call at `:66`.

### 1.6 The one runnable check

`app/src/test/java/com/example/samdapp/domain/usecase/GenerateEvaluateReportUseCaseTest.kt`
already exists with a fake repository and three tests asserting the *persisted* marker (per
CLAUDE.md's assert-the-persisted-row rule). One test is added there:

> `empty symptom input never reaches the classifier and persists a failure marker` — build a
> `KernelPayload` with `chiefComplaint = ""` and `transcription = null`, invoke, then assert
> **(a)** the fake `EvaluateKernelSource`'s call count is `0`, **(b)** the fake repository's
> persisted `failureCode` for the case is `EMPTY_SYMPTOM_INPUT`, **(c)** `getForCase` returns null.

Assertion (a) is the load-bearing one: it is the difference between "the model was called and we
discarded the answer" and "the model was never called".

### 1.7 The risk-file entry (PROPOSED, docs-only, separate commit)

`docs/quality/risk-management-file.md` §2 currently runs H-01…H-19. Next free ID is **H-20**. The
row follows the exact prefix convention H-16/H-17/H-18/H-19 already use (bold PROPOSED banner
inside the ID cell). Proposed row, to be appended after H-19:

| ID | Hazard / hazardous situation | Potential harm | Sev* | Prob* | Risk controls implemented | Residual / open work |
|----|------------------------------|----------------|------|-------|---------------------------|----------------------|
| **PROPOSED, AWAITING OPERATOR SIGN-OFF. NOT APPROVED. Drafted 2026-09-06, Part A of the demo constrained-capture track** H-20 | An empty or token-free symptom input produces a **confident** differential from the model's training prior rather than an abstention | A clinician is shown `E66 Obesity` at **74.95%** confidence for a patient about whom nothing was recorded, stamped as a real inference and indistinguishable from an assessment of that patient. Measured 2026-08-31 (`scratchpad/classifier-wire-format-investigation.md` finding X-3, transcribed in `scratchpad/classifier-dataset-nlem-audit-memo.md` §C.1): `E66 0.7495 · I10 0.0709 · M17 0.0397 · A90 0.0337 · B54 0.0308`. Root cause is the label space, not the wire: E66 is 41.82% of labelled training rows. Automation-bias amplified — cf. H-02 | High | Low | Chief complaint required at capture (`SaveConsultationUseCase` blank check) and at send (`ConsultationUiState.canSend`); **new**: defensive gate in `GenerateEvaluateReportUseCase` — an empty or token-free symptom input never reaches `/api/v1/evaluate` and is recorded as an H-14 failure marker (`EMPTY_SYMPTOM_INPUT`), surfaced on the report as "EVALUATION FAILED" | **Open.** The gate refuses the input; it does not make the model abstain. Any future change that can resolve the chief complaint to an empty token list (a collapsible pick-list, a normalization layer that drops all terms, an on-device port) re-opens this at the new seam. Out-of-vocabulary input remains **unmitigated and separate**: measured confidently-wrong single-term answers (`'Toothache' → B54 malaria 0.437`; `'Diarrhoea / loose motions' → M17 knee osteoarthritis 0.644`) are not covered by any control here and need their own row once the label space is reworked. Real resolution is the reason-for-encounter relabel (`scratchpad/production-classifier-architecture-memo.md`) |

Plus one sentence appended to the explanatory paragraph that already follows the table:

> **H-20 is a proposed addition from the 2026-09-06 read-only design work
> (`scratchpad/demo-dropdowns-and-empty-input-guard-memo.md`). The hazard was measured on
> 2026-08-31 and carried in a scratchpad memo only until now; the row registers it. The control
> lands in code as Part A of the same track. The row stays open: the guard prevents the empty-input
> path, not the confidently-wrong-answer class it belongs to.**

Docs-only. **Separate commit from any code**, per the brief and per the IEC 62304 controlled-docs
practice H-16…H-19 already follow.

---

## 2. Part B — constrained inputs for the five structured fields

### 2.0 The safety precondition, proven before any control is chosen

Every field in this part must be shown *not* to reach the classifier. The proof is a single
enumeration, and it is complete because there is exactly one outbound evaluate DTO:

`data/remote/dto/EvaluateRequestDto.kt:16-29` carries **twelve** fields:
`case_token`, `symptom_string`, `age`, `sex`, `systolic_bp`, `diastolic_bp`, `bmi`, `heart_rate`,
`random_glucose`, `spo2`, `respiratory_rate`, `temperature`.

`symptom_string` is built only from `chiefComplaint` + `transcription` (`RetrofitEvaluateSource:45-48`).
Everything else is vitals, age and sex. **There is no DTO field for `onset`, `durationBucket`,
`severityScore`, `relevantHistory`, `aggravatingFactors`, `relievingFactors` or
`impactOnDailyActivities`.** This matches the architecture memo §1.2 verbatim — *"`durationBucket`,
`severityScore` and `relevantHistory` are captured, carried across the kernel boundary — and then
dropped. `EvaluateRequestDto.kt` has **no field for any of them**"* — and §1.1's statement that the
`symptomString` expression *"is the entire input contract"*.

The `/v1/assess` path is narrower still: `KernelAssessmentRequestDto`
(`RetrofitKernelSource.kt:41-53`) carries only case token, age, sex and five vitals. No text.

Two of the five do cross the app's *internal* kernel boundary and stop at the DTO —
`KernelPayload.durationBucket` / `.severityScore` / `.relevantHistory`
(`domain/model/KernelPayload.kt:28-30`). They are consumed by exactly one thing:
`GenerateKernelReportUseCase.dataQualityScore` (`:82-91`), a presence-count over five optional
signals. Presence, never value. Constraining a field's vocabulary cannot change its presence, so
even that consumer is unaffected. `onset` and `impactOnDailyActivities` do not cross the boundary
at all — `KernelPayload`'s own KDoc (`:15-18`) records their deliberate exclusion.

**Conclusion: none of the five fields reaches any classifier, on either endpoint. Part B is
UI-and-capture only.**

### 2.1 No wire-contract, schema or model impact

Confirmed on disk, all four homes of each field:

| Layer | Evidence | Change? |
|---|---|---|
| Device domain | `Consultation.kt:9-25` — `onset: String?`, `durationBucket: String?`, `severityScore: Int?`, `impactOnDailyActivities: String?`, `relevantHistory: String?` | none |
| Device Room | `ConsultationEntity.kt` — same nullable shapes | none, **no migration** |
| Sync wire | `SyncPayloadDto.kt:57-72` `ConsultationSyncPayloadDto` — `onset`, `duration_bucket`, `severity_score`, `impact_on_daily_activities`, `relevant_history` all present and nullable | none |
| Backend Postgres | `backend/core/app/models/encounter.py:71-83` — `onset String(120)`, `duration_bucket String(40)`, `severity_score Integer`, `impact_on_daily_activities Text`, `relevant_history Text`; **no CHECK constraint on any of them** | none, **no Alembic migration** |
| Classifier / dataset | not reached (§2.0) | none |

The backend push applier is a strict field allowlist that rejects unknown keys
(`backend/core/app/api/v1/encounters.py` `_CONSULTATION_FIELDS`, enforced at
`backend/core/app/services/sync.py:281` and `:383`) — which is precisely why **no new column is
proposed**. Every control below writes a `String` into the column that already exists. The only
hard constraints are width: `onset` must fit **120 chars**, `duration_bucket` **40**. Every option
below is well inside both.

This also means the design is device-only and migration-free, the same property PR 7a's memo
identified (its P-1) as the thing that keeps a capture change to one PR.

### 2.2 Per-field design

Reuse before building — `presentation/common/DropdownField.kt:24-53` already exists
(`ExposedDropdownMenuBox`, `readOnly` anchor), and is already used six times, twice on this very
screen (`ConsultationScreen.kt:353` Department, `:362` Record type). Nothing new is written.

---

**① `durationBucket` — already constrained. Widen the vocabulary, fix the demo data.**

Current: `FilterChip` row (`ConsultationScreen.kt:242-251`) over
`private val DURATION_BUCKETS = listOf("today", "few_days", "week_plus", "chronic")` (`:87`).

Real defect (PR 7a P-2, re-verified on disk at `cab78ae`): the app's own demo data writes values
outside the set — `DemoPatientProfile.kt:160` `durationBucket = "months"` and `:228`
`durationBucket = "weeks"`. Neither matches a chip, so tapping "Fill demo patient data" leaves the
duration row with **nothing selected** while a non-vocabulary value silently persists and syncs.
On a demo screen this reads as a broken control.

Change:
- Widen to `listOf("today", "few_days", "week_plus", "month_plus", "chronic")` — five chips, longest
  is 11 chars against a 40-char column.
- Remap `DemoPatientProfile.kt:160` `"months"` → `"month_plus"`, `:228` `"weeks"` → `"week_plus"`.
  `:304`, `:373`, `:443` already say `"few_days"` and are untouched.

Control type: unchanged (`FilterChip` row). Options above.

*Not done, deliberately:* the chips have no deselect — once tapped the value cannot be cleared,
because `ConsultationActions.onDurationBucketChange(value: String)` is non-null
(`ConsultationViewModel.kt:311`). Making it nullable is an interface change plus a fake-actions
change in `ConsultationVoiceGateUiTest.kt`. Out of scope; noted for a later pass.

---

**② `onset` — free text → `DropdownField`.** The one true conversion in this build.

Current: `item { OutlinedTextField(uiState.onset, actions::onOnsetChange, label = { Text("Symptom onset") }, ...) }`
(`ConsultationScreen.kt:240`).

Safe to convert, and cheaper than it looks: `Consultation.onset` is read in exactly four places —
the field itself (`:240`), the review dialog (`:423`), the DB mappers
(`ConsultationRepositoryImpl.kt:92,111`) and the sync mapper (`SyncRecordMappers.kt:78`). **It does
not reach the report** — `ReportFormatter.kt:191`'s `entry.onset` is `AilmentEntry.onset`, a
different column on a different table. No clinician-facing surface loses anything.

Control: `DropdownField(label = "Symptom onset", value = uiState.onset, options = ONSET_OPTIONS,
onValueChange = actions::onOnsetChange, modifier = Modifier.fillMaxWidth())`. No ViewModel change —
`onOnsetChange(value: String)` (`ConsultationViewModel.kt:310`) already takes exactly this.

Options (`private val ONSET_OPTIONS`, longest 27 chars vs the 120-char column):

    "Sudden (minutes to hours)"
    "Acute (1-3 days)"
    "Gradual (days to weeks)"
    "Insidious (weeks to months)"
    "Intermittent / episodic"
    "Not known"

Demo remap in `DemoPatientProfile.kt`:

| Line | Current | New |
|---|---|---|
| `:159` | "Gradual onset over the past 6 months, worsening" | "Insidious (weeks to months)" |
| `:227` | "3 weeks ago, gradually worsening, worse in the mornings" | "Gradual (days to weeks)" |
| `:303` | "4 days ago — started with low-grade fever, now stepwise rising each evening" | "Gradual (days to weeks)" |
| `:372` | "5 days ago — high fever from day 1, bleeding gums and breathlessness from day 4" | "Sudden (minutes to hours)" |
| `:442` | "2 days ago — noticed crying while urinating, mild fever since yesterday" | "Acute (1-3 days)" |

**Narrative loss, stated rather than glossed.** Four of those five strings carry clinical detail no
onset option can hold — *"worse in the mornings"* (diurnal variation), *"stepwise rising each
evening"* (a fever pattern the training corpus has a whole column for, `fever_pattern_flag`, audit
§D.3), *"bleeding gums and breathlessness from day 4"* (warning signs). This detail must not
vanish from the demo. The build relocates it into `aggravatingFactors`, which stays free text and
is untouched by this work — a constants-only edit in the same file. No field is removed and no
clinical content is dropped; it moves one row down the form.

---

**③ `severityScore` — already constrained. No change.**

`Slider(valueRange = 0f..10f, steps = 9)` (`ConsultationScreen.kt:253-259`) is a constrained
control and the brief names sliders as acceptable. Converting an 11-point slider into an 11-item
dropdown would be worse to use and is not asked for.

*Known issue, out of scope and named:* `severityScore` defaults to `0`
(`ConsultationUiState`, `ConsultationViewModel.kt:107`) and is saved unconditionally
(`:828` `severityScore = current.severityScore`), so **"not asked" and "0 out of 10" are the same
persisted value**. Fixing it means `severityScore: Int?` through the UI state, the actions
interface and the test fakes. Same class of honesty gap as the vitals fabrication, not the same
bug, and not this build.

---

**④ `relevantHistory` — constrained assist over a retained free-text field.**

Not a fixed set (C-4), so it does not become a pick-list. It gains a `FilterChip` row **above** the
existing `OutlinedTextField` (`ConsultationScreen.kt:308`); tapping a chip appends its label to the
field through the existing `actions::onRelevantHistoryChange`. Faster to fill, standard vocabulary
for the common cases, and anything the chips cannot express is still typed.

Options (`private val HISTORY_CHIPS`, `Text` column — no width limit):

    "Diabetes"  ·  "Hypertension"  ·  "TB (past or current)"  ·  "Asthma / COPD"
    "Heart disease"  ·  "Thyroid disorder"  ·  "Pregnancy"  ·  "Known drug allergy"
    "Tobacco / alcohol use"  ·  "No known history"

Demo values unchanged — they remain valid free text.

---

**⑤ `impactOnDailyActivities` — constrained assist, free-text field preserved exactly.**

Same treatment as ④, and for the reason in C-3: this field is the sole target of the live voice
confirmation gate, and the gate requires a field an arbitrary transcript can be written into. The
`OutlinedTextField` at `ConsultationScreen.kt:291-298` — including its `micTrailingIcon` and the
`ImpactVoiceSuggestionSurface` below it — is **untouched**. A `FilterChip` row is added above it.

Provenance is correct for free, with no new logic: a chip tap routes through
`actions::onImpactChange`, which is the manual-edit path
(`ConsultationViewModel.kt:323-334`) and already carries the A.3 transitions — a
`VOICE_CONFIRMED` value that a chip appends to becomes `VOICE_EDITED`, which is that value's own
definition ("voice-seeded, then hand-corrected"). Nothing new to design.

One conservative constraint: the chips are `enabled = !uiState.isCapturingImpactVoice`, mirroring
the mic button's own `enabled` at `:276` and the state's *"The field is never mutated while this is
true"* KDoc (`:125`).

Options (`private val IMPACT_CHIPS`, `Text` column):

    "Unable to work / farm"  ·  "Missing school"  ·  "Cannot do household chores"
    "Bedridden"  ·  "Sleep disturbed"  ·  "Reduced appetite"  ·  "No impact on daily activity"

Demo values unchanged.

### 2.3 The one shared helper, and its one runnable check

Both ④ and ⑤ need the same append. One internal top-level function in `ConsultationScreen.kt`,
plain Kotlin, no Compose types, so a plain JVM unit test reaches it:

    internal fun appendClause(current: String, clause: String): String = when {
        current.isBlank() -> clause
        current.split(",").any { it.trim().equals(clause, ignoreCase = true) } -> current
        else -> "$current, $clause"
    }

Idempotent on a repeat tap, which is the only branch worth a test. One new test file with three
assertions (empty → clause; repeat tap → unchanged; second distinct clause → comma-joined). No
Compose test, no fixtures.

### 2.4 What Part B does not touch

`aggravatingFactors` and `relievingFactors` (`ConsultationScreen.kt:261-262`) stay free text —
they are not in the brief's list, and ② uses `aggravatingFactors` as the home for relocated demo
narrative.

---

## 3. Explicitly out of scope, and why

| Item | Why it is out |
|---|---|
| **`chiefComplaint` capture — above all** | It **is** the classifier input (`RetrofitEvaluateSource:45-48`). Deferred to the foundation rebuild: its option set and the model's label space have to be designed together, and a pick-list that can collapse to no selection re-creates the Part A hazard at a new seam — the audit memo's own trigger condition. Untouched by both parts. |
| **`transcription`** | The second half of `symptom_string`. Same reason, same deferral. |
| **Vitals fabrication** | `RetrofitEvaluateSource.kt:38-43` and `:59-66` default `bmi=22.0`, `120/80`, `hr=72.0`, `spo2=98.0`, and nothing persisted distinguishes a measured 120/80 from a defaulted one (audit §C.3). Known, separate, adjacent to but not covered by H-13. **Not touched here.** |
| **`EvaluateRequestDto` / `ConsultationSyncPayloadDto` / backend columns** | No wire change in either part. |
| **Dataset, model, `canonical_dataset.csv`, `symptom_model.json`** | Untouched. The E66 prior is a label-space problem; the guard refuses the input, it does not retrain anything. |
| **Structured fusion** (sending duration/severity to the model) | Architecture memo §5 — blocked on the corpus having duration and severity columns at all. Not this build. |
| **`AilmentEntry.onset` / `.duration`** (Compounder screen) | A second table, a second sync contract, wider columns, and it *does* reach the report (`ReportFormatter.kt:191`). Doubling the diff for a demo change. Named and deferred. |
| **Adding `inferenceSource` to `EvaluateReportOutput`** | Room migration + domain change on a hazard-registered surface = architecture change. See C-1. |
| **Deselectable duration chips; nullable `severityScore`** | Both are interface + test-fake changes for an honesty gap the brief did not ask about. Named in §2.2. |

**If any part of the build turns out to touch the diagnosis path, stop and re-gate.** The
falsifiable test is one line: if a diff adds or changes a field in `EvaluateRequestDto`, or changes
how `symptomString` is built in `RetrofitEvaluateSource:45-48`, it is out of scope.

---

## 4. Build scope

### Branch 1 — `fix/evaluate-empty-symptom-guard` (Part A), two commits

**Commit 1 (code):**
- `app/src/main/java/com/example/samdapp/domain/usecase/GenerateEvaluateReportUseCase.kt` — add the
  `EMPTY_SYMPTOM_INPUT` constant to the existing companion object, the private
  `KernelPayload.hasNoSymptomText()` extension (widened form, §1.4), and the guard block before the
  `try` at `:38` (§1.5).
- `app/src/test/java/com/example/samdapp/domain/usecase/GenerateEvaluateReportUseCaseTest.kt` —
  one test asserting call-count 0 **and** the persisted `EMPTY_SYMPTOM_INPUT` marker (§1.6).

Two files. No new file, no new class, no new interface, no DI change, no migration.

**Commit 2 (IEC 62304-controlled docs, no code):**
- `docs/quality/risk-management-file.md` — append the H-20 row and the explanatory sentence, both
  verbatim from §1.7, carrying the PROPOSED-pending-operator banner.
- `PROGRESS.md` — session entry, matching the existing inline-with-the-code convention.

### Branch 2 — `feat/consultation-constrained-fields` (Part B), one commit

- `app/src/main/java/com/example/samdapp/presentation/consultation/ConsultationScreen.kt` — widen
  `DURATION_BUCKETS`; add `ONSET_OPTIONS`, `HISTORY_CHIPS`, `IMPACT_CHIPS` and `appendClause`;
  replace the onset `OutlinedTextField` at `:240` with `DropdownField`; add two `FilterChip` rows.
- `app/src/main/java/com/example/samdapp/data/mock/DemoPatientProfile.kt` — remap two
  `durationBucket` values and five `symptomOnset` values; relocate the displaced onset narrative
  into `aggravatingFactors`. Constants only.
- One new plain JVM test for `appendClause`.

Three files. No ViewModel change, no `ConsultationActions` change (so
`ConsultationVoiceGateUiTest.kt`'s fake still compiles), no DTO change, no entity change, no
migration, no backend change.

### Build model

**The build runs on Sonnet 5.** Both parts are bounded mechanical changes against a design settled
here: the seam is located and quoted, the guard predicate and its placement are decided, every
option list is fixed, every file and line is named, and the two out-of-scope boundaries
(`chiefComplaint` / `symptomString`, and the vitals defaults) are stated as a one-line falsifiable
test. Nothing left in the build requires a judgment call about the classifier's diagnosis path,
which is the only reason this memo needed Opus.

### Commit and branch discipline

Two branches, three commits total. Part A's code and Part A's risk-file entry are **separate
commits** (the brief's requirement, and the practice H-16…H-19 already follow). Part B is its own
branch so a demo-facing UI change can be reverted without taking the safety guard with it. No
push without explicit per-turn consent.

---

## 5. Repo state at end of this memo

Nothing written outside `scratchpad/demo-dropdowns-and-empty-input-guard-memo.md`. Nothing staged,
nothing committed. `git status --short` reported in the session output.
