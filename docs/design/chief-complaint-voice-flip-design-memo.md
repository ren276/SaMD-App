# `VOICE_INPUT_ENABLED` flip: chiefComplaint + the High-severity path — design memo

**Status: PROPOSED. Read-only. No code written, no flag changed, no risk-file row committed.**
Date: 2026-09-08. Branch surveyed: `feat/voice-field-expansion` @ `473f611`.
Model: Opus (safety-control reversal; standing rule routes these through an Opus memo before build).

**Verdict up front: NOT-YET.** The decisive blocker is not chiefComplaint. It is that
`VOICE_INPUT_ENABLED` gates *two* affordances, and the second one — the audio attachment — feeds a
raw, ungated ASR transcript into the same `/api/v1/evaluate` input string, through a path the
`VOICE_UNCONFIRMED` repository refusal explicitly does not cover. Detail in §3 and §7.

---

## 0. What was actually surveyed

Everything below is read from the working tree, not from PROGRESS.md or from prior memos. Where a
prior description and the code disagree, the code is quoted.

Files read: `config/FeatureFlags.kt`, `presentation/consultation/ConsultationScreen.kt`,
`presentation/consultation/ConsultationViewModel.kt`, `presentation/navigation/AppNavHost.kt`,
`presentation/transcription/TranscriptionViewModel.kt`, `domain/usecase/TranscribeAudioUseCase.kt`,
`domain/usecase/AssessmentRunner.kt`, `domain/usecase/GenerateEvaluateReportUseCase.kt`,
`domain/model/KernelPayload.kt`, `domain/model/FieldProvenance.kt`,
`domain/model/EvaluateReportOutput.kt`, `data/remote/RetrofitEvaluateSource.kt`,
`data/repository/ConsultationRepositoryImpl.kt`, `data/local/entity/ConsultationEntity.kt`,
`data/local/AppDatabase.kt`, `data/sync/SyncRecordMappers.kt`,
`backend/core/app/api/v1/encounters.py`, `backend/core/app/models/encounter.py`,
`docs/quality/risk-management-file.md`, and — in the sibling repo — `SaMDClassifier/src/app.py`,
`src/refine_diagnosis.py`, `src/thresholds.py`, `scripts/train_category_classifier.py`,
`models/category_model_meta.json`, `models/category_feature_schema.json`.

---

## 1. Is chiefComplaint still free text?

**Yes. Unchanged. Plain `OutlinedTextField`, no structure of any kind.**

`ConsultationScreen.kt` ~L273-281:

```kotlin
item {
    OutlinedTextField(
        value = uiState.chiefComplaint,
        onValueChange = actions::onChiefComplaintChange,
        label = { Text("Main concern *") },
        modifier = Modifier.fillMaxWidth(),
    )
}
```

The branching tree did **not** land on this field. What landed on `feat/consultation-constrained-fields`
(`5a3a9f6`) is constrained input on the *neighbouring* fields only — `ONSET_OPTIONS` dropdown,
`DURATION_BUCKETS` chips, a severity `Slider`, `HISTORY_CHIPS` append-chips on `relevantHistory`.
`chiefComplaint` sits above that section and was not touched.

Corroborating negative: `grep -rl "categoryId\|CategoryId\|reasonForEncounter" app/src/main/java`
returns **nothing**. The reason-for-encounter category system and the questionnaire tree
(`scratchpad/reason-for-encounter-category-system-memo.md`, `questionnaire-tree-design-memo.md`,
`pr7a-structured-capture-design-memo.md`) are all PROPOSED. `pr7a-...-memo.md:734` says so in its own
words: "H-15. Untouched. `FeatureFlags.VOICE_INPUT_ENABLED` stays `false`. 7a is not the voice PR."

**So the flag question does not dissolve.** Dictation-into-field is still the applicable tool, and
free-text chiefComplaint is still literally `symptom_string` (§2). But see §8: it is about to
dissolve, and that changes the sequencing recommendation, not the verdict.

---

## 2. The mis-transcription failure mode — the core risk

### 2.1 What chiefComplaint actually feeds today

`RetrofitEvaluateSource.kt:45-48`:

```kotlin
val symptomString = listOfNotNull(
    payload.chiefComplaint.takeIf { it.isNotBlank() },
    payload.transcription?.takeIf { it.isNotBlank() },
).joinToString(". ")
```

That string is the entire text input to the model. It reaches `POST /api/v1/evaluate` →
`SaMDClassifier/src/app.py:194` → `refine_diagnosis.refine(payload.symptom_string, vitals)`.

Inside `refine_diagnosis.py`:

- `get_symptom_ranked()` (L76-84) vectorises the string with a word TF-IDF (1,2) **and** a
  `char_wb` (3,5) char vectoriser, hstacks them, and runs Classifier B (XGBoost, 18 ICD classes).
- `refine()` (L93-146) multiplies each class probability by `0.5 + alignment`, where `alignment` is
  the dot product of that ICD's empirical risk-tier profile with Classifier A's vitals-tier
  probabilities, then **renormalises**: `adjusted_confidence = {c: v / total}`.
- Its own docstring: *"Always returns at least 3 candidates, never a single winner."*
  `top_k = max(top_k, 3)`.

### 2.2 Does the abstention chain contain a bad voice-derived complaint?

**No. And not for the reason one would expect — the chain is not weak here, it is not present here.**

Two separate findings, both decisive:

**(a) The abstention chain is not on the live path.** `REFER_EMERGENCY` rule-abstain,
`other_not_in_list`, and conformal `tau` all belong to `category-clf-v0.1-xgb-venn-abers`
(`SaMDClassifier/scripts/train_category_classifier.py:627-662`, `models/category_model_meta.json`:
`tau = 0.12069287387508021`, `epsilon = 0.05`, 27 classes, `other_not_in_list_idx: 19`). That model
is served by nothing. `src/app.py` and `src/refine_diagnosis.py` load exactly two artifacts —
`models/model.json` (Classifier A, vitals→risk tier) and `models/symptom_model.json` +
the two vectorisers + label encoder (Classifier B, text→ICD). The category model is a trained
artifact awaiting an integration that does not exist.

**(b) Even once wired, that chain cannot see a mis-transcription, because it consumes no text.**
`models/category_feature_schema.json` — 466 features, all structured: `sex`, `facility_tier`, eight
vitals plus their eight `*_provenance` flags, and ~440 questionnaire `__status` answers
(`danger_signs__status`, `fever_pattern__status`, `pain_radiation__status`, …). `category_id` is the
**label**, not an input. And `EXCLUDED_COLS` (train script L96-118) explicitly drops
`disposition_floor` and `tree_disposition_floor` as rule outputs — the emergency floor is a *rule*
evaluated over structured answers, upstream of the model, never over complaint prose.

So the abstention architecture guards the *structured* input representation. A free-text complaint
does not enter it. Wiring the category classifier tomorrow would not contain this hazard by one
percent.

**(c) What the live path does instead of abstaining.** Nothing. There is no confidence floor, no
OOV detector, no abstain branch anywhere in `refine_diagnosis.py`. The renormalisation guarantees
the returned confidences sum to 1 over the top-k, so *every* input — including a fluent
mis-transcription of a complaint the patient never made — yields a ranked differential with a
plausible-looking top-1 percentage. This is the same mechanism already measured and registered as
**H-20**: an *empty* symptom string produced `E66 Obesity` at **74.95 %**. Empty input and wrong
input differ only in which prior dominates; neither triggers a guard.

**(d) The one guard that does exist does not fire.** `GenerateEvaluateReportUseCase.kt:40-41`:

```kotlin
private fun KernelPayload.hasNoSymptomText(): Boolean =
    chiefComplaint.none(Char::isLetterOrDigit) && transcription.orEmpty().none(Char::isLetterOrDigit)
```

`EMPTY_SYMPTOM_INPUT` catches blank and all-punctuation only. A mis-transcription is by construction
fluent, letter-bearing text. It sails through.

### 2.3 Why *this* model is unusually exposed to *this* error mode

Not a generic ASR complaint — a specific interaction:

- **Negation is not represented.** Classifier B is bag-of-ngrams TF-IDF. There is no negation
  handling of any kind. A dropped or hallucinated "no" — the single most common clinically
  significant ASR substitution — changes the meaning completely and the feature vector barely at
  all. "no fever, cough two weeks" and "fever, cough two weeks" are near-neighbours in this space
  and opposite in clinic.
- **`char_wb` (3,5) rewards phonetic near-misses.** Character n-grams are precisely what makes a
  phonetically-plausible substitution ("chest pain" → "chest sprain", "loose motions" → "lose
  motions") land *near* a trained class rather than far from everything. The feature design that
  makes the model robust to spelling variation is the same design that makes a wrong-but-similar
  transcription land confidently on a wrong class.
- **A 300:1 class imbalance supplies the fallback prior.** Per `classifier-dataset-nlem-audit-memo.md`
  as summarised in PROGRESS: E66 41.82 % down to B50 0.14 %; the three weakest classes are the three
  most dangerous (J22 F1 0.3636, A91 0.3810, B50 0.4000). A degraded input does not land nowhere; it
  lands on the majority class, and the classes it is *least* likely to reach are dengue, malaria and
  acute lower respiratory infection.
- **The residual H-15 already names the two transcript shapes** that survive the confirmation gate:
  semantic substitution, and silent truncation at `TRAILING_SILENCE_MS = 1500` for a hesitant or
  elderly speaker. Both produce grammatical, short, confident-looking text. Against a human reader
  that is a Medium hazard. Against a bag-of-ngrams classifier with no abstention it is the *ideal*
  adversarial input: maximally plausible, minimally detectable.

### 2.4 Answer to the question as posed

> Does the abstention chain contain a bad voice-derived complaint, or can a plausible-but-wrong
> transcription produce a confident wrong category?

**A plausible-but-wrong transcription produces a confident wrong differential, and nothing in the
current system detects, flags, or abstains on it.** The abstention chain that would be the natural
mitigation is (i) unwired and (ii) structurally incapable of reading the field in question. The
containment that *does* exist is entirely downstream and entirely human: `PRESCRIPTION_APPROVAL_GATE_ENABLED
= true` hides the treatment block and brand suggestion until a physician commits a decision. That is
a doctor-in-the-loop gate, not an abstention — and §6 shows what it rests on.

---

## 3. The High-severity path specifically

Two readings of "High-severity path" were checked. Both are answered; the first is the one that
governs the verdict.

### 3.1 Reading A — the H-15 High-severity paths (what the flag actually gates)

`VOICE_INPUT_ENABLED` gates **two** affordances, not one. Its own KDoc says "these are the **High**-severity
H-15 paths", plural. Confirmed in code:

**(i) chiefComplaint mic** — `ConsultationScreen.kt:259-271`, `onRecordChiefComplaintVoice`
(`ConsultationViewModel.kt:1280-1311`). Today that handler, past the flag check, calls
`captureAudioAttachmentUseCase()`, logs `AUDIO_CAPTURED` with `"purpose" to "chief_complaint"`, and
**discards the transcript**. Its comment says so: *"until it lands this handler intentionally drops
the transcript rather than committing an unconfirmed value."* There is no suggestion surface, no
three-button gate, no provenance, no `canSend` participation for this field.

> **Consequence: a bare flag flip does not ship a feature.** It exposes a "Record main concern"
> button that records, logs an audit event, and throws the result away. The flip and the gate build
> are not separable. Anyone treating this as a one-line flag change ships a button that lies.

**(ii) Audio attachment** — `ConsultationScreen.kt:508-517`, `onRecordAudioAttachment`
(`ConsultationViewModel.kt:1313-1338`), which appends `PendingAttachment(AttachmentType.AUDIO, uri)`.
Follow that attachment:

```
ConsultationScreen "Record audio"  →  PendingAttachment(AUDIO)
  → AppNavHost.kt:318-329: KernelAssessmentRoute.onContinue → if (key.audioUri != null) TranscriptionRoute
  → TranscriptionViewModel.init { transcribeAudioUseCase(consultationId, audioUri) }   // auto-runs
  → TranscribeAudioUseCase.kt:22  consultationRepository.updateTranscription(consultationId, text)
  → ConsultationRepositoryImpl.kt:64-67  consultationDao.updateTranscription(...)      // writes
  → RetrofitEvaluateSource.kt:45-48  symptom_string = chiefComplaint + ". " + transcription
```

**No confirmation. No provenance. No refusal.** And the repository KDoc states the exemption in
so many words (`ConsultationRepositoryImpl.kt:61-63`):

> *"No provenance refusal here, deliberately: this path writes only `transcription`, `updatedAt`/`localModifiedAt`
> and `syncState`, never a provenance column, so it cannot carry `VOICE_UNCONFIRMED`."*

That reasoning was correct when written and is now the hole. The field cannot carry
`VOICE_UNCONFIRMED` because it has no provenance column — which is not the same as the value being
confirmed. `TranscriptionScreen` shows the transcript to the worker *after* it has already been
persisted, on a screen whose only affordance is Continue.

**This is a complete, silent, ungated voice→model write path.** It is dark today for exactly one
reason: nothing constructs an `AttachmentType.AUDIO` while the flag is off. Flipping the flag lights
it up in the same commit as the chiefComplaint mic. **A perfect chiefComplaint confirmation gate does
not close it.**

**Additional finding — a timing nondeterminism, worth registering on its own.** `AssessmentRunner`
runs from `AssessmentWorker` (the async submission queue), and re-resolves the consultation from the
repository on every run *including every retry* (`AssessmentRunner.resolve`, L114+). The transcription
write happens on a later screen. So whether the ungated transcript is inside `symptom_string` depends
on whether the assessment ran before or after that write — first attempt versus retry. **Two
assessments of the same case can be produced from different input text, and nothing persisted
distinguishes them:** `EvaluateRequestDto` carries no provenance, and per the classifier audit the
report tables replace one row per case on retry. This is unfalsifiable after the fact.

### 3.2 Reading B — a clinically high-acuity patient

Checked, and the honest answer is that the structural difference people expect is not there:

- **`severityScore` never reaches the evaluate model.** The 0-10 slider goes into `KernelPayload`
  and `Consultation`, and `EvaluateRequestDto` has no severity field
  (`RetrofitEvaluateSource.kt:50-68`: caseToken, symptomString, age, sex, four vitals, glucose, spo2,
  RR, temperature). Nobody should assume high severity gives the model a floor. It gives it nothing.
- **There is no complaint-text-driven emergency floor.** Device-side triage is
  `EvaluateSafetyAndTriage.requiresHumanReview` / `pediatricReferralFlag`, backend-computed from
  vitals via `thresholds.py` (`classify_bp`, `classify_spo2`, `classify_temperature`, …).
  Vitals-driven escalation is the one safety signal a mis-transcribed complaint **cannot** corrupt —
  a genuinely hypoxic or hypertensive patient still trips those thresholds regardless of what the
  ASR heard. That is real, and it is the reason this is a NOT-YET rather than an emergency.
- **What a wrong complaint *does* corrupt on a high-acuity patient** is the ICD ranking, and through
  it the NLEM treatment retrieval (ChromaDB, 0.6 cosine gate) and the brand suggestion — i.e. the
  drug. The mitigation is `PRESCRIPTION_APPROVAL_GATE_ENABLED = true`, physician-first. See §6 for
  what that physician actually is.
- The `REFER_EMERGENCY` disposition floor that *would* be the right control lives only in the
  drishti_v2 corpus and the unwired category training script, as an **excluded** rule-output column.
  It is not a control that exists.

**Missing mitigations for this path, stated plainly:** mandatory human confirmation on chiefComplaint —
absent (never built). Provenance guard on chiefComplaint — absent (no column). Provenance on the
`transcription` field — absent, and explicitly exempted. Disposition floor over complaint text —
does not exist anywhere. Abstention over the text classifier — does not exist anywhere.

---

## 4. Provenance requirement

**Yes, required, and required more strictly than for `impactOnDailyActivities`.**

The asymmetry that PR5 accepted for `aggravatingFactors` / `relievingFactors` / `relevantHistory` —
audit-log breadcrumbs only, no persisted column, per `FeatureFlags.kt` KDoc — was justified because
those fields are read by a human. `chiefComplaint` is read by a **model**, and its value is the
model's entire text input. The justification does not transfer. `FieldProvenance`'s own KDoc says
`impactOnDailyActivities` was chosen as the first column precisely because *"a bug in this column
cannot reach the classifier"*. chiefComplaint is the exact complement of that sentence.

### Scope (survey-accurate; the build is a separate session — see §9)

**Device, Room.** `AppDatabase.kt:75` is at `version = 19`. Add
`ConsultationEntity.chiefComplaintProvenance: FieldProvenance?` + `MIGRATION_19_20`, backfilling
existing rows to `TYPED` (honest: they were typed), mirroring `MIGRATION_16_17`'s pattern.

**Device, domain + repository.** `Consultation`, `SaveConsultationUseCase` parameter, and — the part
that must not be copy-pasted — `ConsultationRepositoryImpl.saveConsultation` currently refuses on one
literal field (`ConsultationRepositoryImpl.kt:45`). It should become a check over *every*
provenance-bearing field on the entity, so that the next voice field inherits the backstop instead of
re-earning it. That is the root-cause shape; a second literal `if` is the symptom shape.

**Device, sync.** `SyncRecordMappers.kt:81` + `SyncPayloadDto`.

**Backend.** Alembic migration + `models/encounter.py` + `_CONSULTATION_FIELDS` in
`api/v1/encounters.py:68-83`. The pattern already exists there —
`impact_on_daily_activities_provenance` is present in that tuple — so this is a mirror, not an
invention. Per CLAUDE.md, the migration test must assert the persisted row, not the HTTP response.

**The extra requirement chiefComplaint has and impact does not.** Provenance must be recoverable
*alongside the inference*, not only on the consultation row. Today `EvaluateRequestDto` carries no
provenance and the evaluate report table replaces its row on retry, so a differential cannot be
traced to whether its input was spoken. Minimum acceptable: stamp provenance into the
`EVALUATE_RESPONSE_RECEIVED` audit payload and/or the persisted report row, at the same commit as
the column. Without it the column records how the field was filled but not what the model was fed —
which is the question that will be asked after an incident.

**Also in scope, and non-optional:** the `transcription` field needs the same treatment or the same
prohibition (§3.1(ii)). Adding provenance to `chiefComplaint` while `transcription` stays exempt
leaves the wire string half-traced, and `symptom_string` concatenates the two.

---

## 5. Recommendation on the flag itself

`VOICE_INPUT_ENABLED` is one flag meaning two things with different risk profiles and different
missing controls. That conflation is itself the hazard: there is no way to enable the gated field
without enabling the ungated attachment path.

**Cheapest correct move — split it, and do that before anything else is decided:**

- `VOICE_FIELD_CHIEF_COMPLAINT_ENABLED` — the mic on the field, same shape as the four existing
  `VOICE_FIELD_*` flags, default `false`.
- `VOICE_AUDIO_ATTACHMENT_ENABLED` — the attachment + the auto-transcribe route, default `false`,
  and blocked behind its own separate memo.
- Retire `VOICE_INPUT_ENABLED`.

This costs one small refactor, ships no behaviour change (both stay `false`), and converts an
all-or-nothing safety control into two controls that can be reasoned about separately. It is a
precondition of any future flip, so it may as well be the first thing built.

---

## 6. H-06 interaction — self-asserted role

`docs/quality/risk-management-file.md` H-06, verbatim: *"`signIn` has no credential check, so anyone
typing an existing worker's name+role gets that worker's `userId`; it only makes accountability
consistent once someone is in, not verified."* Status: **Open**.

It changes the calculus in three specific ways, all of which cut against flipping:

**(a) The confirmation gate's only real control is an unverified assertion.** Every control in the
H-15 residual reduces to "a human read this and accepted it". H-06 means the trail records *a
claimed identity*, not a person. On `impactOnDailyActivities` — human-read, no model path — that
weakens accountability. On `chiefComplaint` it weakens the sole barrier between an ASR artefact and a
model's diagnostic input.

**(b) It compounds an already-unstaffed detection control.** H-15 records the open action: *"no one
reads the `dwellMs` breadcrumbs."* Rubber-stamp detection is therefore (i) not performed and (ii)
attributable only to a self-asserted identity if it ever were. Two independent failures of the same
control, on the higher-stakes field.

**(c) The chain nobody has written down.** The one mitigation that actually contains a wrong
voice-derived differential is `PRESCRIPTION_APPROVAL_GATE_ENABLED`: no treatment block until a
`UserRole.DOCTOR` commits a decision. That role is self-asserted (H-06), and community-tier scope is
exactly what `docs/domain/phc-workforce-scope.md` and H-17 exist to police. So the full path is:

> ASR mis-transcription (H-15) → no abstention over the text classifier (H-20 mechanism) →
> confident wrong ICD → NLEM treatment + brand → gate requiring a "physician" who is
> self-asserted (H-06) → out-of-scope administration (H-17).

Every link is registered. **The chain is not.** Each row reads as tolerable in isolation because each
assumes the next control holds. Registering it is §7 below.

---

## 7. Hazards / hazard-caveats to register

Drafted here at full text, **not written into `docs/quality/risk-management-file.md`** — this session
is read-only, and the file's convention is that new rows land PROPOSED in a separate docs commit
awaiting operator sign-off (the precedent is H-20 on 2026-09-06). Transcribe verbatim when that
commit is made.

**H-15 caveat 1 — the abstention chain does not cover this hazard.**
> The conformal/`other_not_in_list`/`REFER_EMERGENCY` abstention chain is a property of
> `category-clf-v0.1-xgb-venn-abers`, which (i) is not loaded by `SaMDClassifier/src/app.py` and so
> is on no live path, and (ii) consumes 466 structured features — vitals, vitals provenance, and
> questionnaire `__status` answers — and no free text at all (`models/category_feature_schema.json`).
> `disposition_floor` is an **excluded** rule-output column in training. The live evaluate path
> (`refine_diagnosis.refine`) renormalises over the top-k and returns at least 3 candidates with no
> confidence floor and no abstain branch. **A voice-derived `chiefComplaint` is therefore contained
> by no abstention control, wired or planned.** The only device-side guard, `EMPTY_SYMPTOM_INPUT`,
> tests for absence of any letter or digit and cannot fire on a fluent mis-transcription.

**H-15 caveat 2 — the flag gates a second, ungated path (proposed severity: High).**
> `VOICE_INPUT_ENABLED` also gates the audio attachment. An `AttachmentType.AUDIO` routes
> `KernelAssessmentRoute → TranscriptionRoute`, where `TranscriptionViewModel.init` auto-runs
> `TranscribeAudioUseCase`, which writes the raw transcript into `Consultation.transcription` via
> `updateTranscription` — a path with no confirmation gate, no provenance column, and an explicit
> exemption from the `VOICE_UNCONFIRMED` repository refusal
> (`ConsultationRepositoryImpl.kt:61-63`). `transcription` is concatenated into `symptom_string`
> (`RetrofitEvaluateSource.kt:45-48`) and reaches the model. The screen displays the transcript only
> after it has been persisted, with Continue as its only affordance. **Flipping `VOICE_INPUT_ENABLED`
> enables this path in the same commit as the chiefComplaint mic; a confirmation gate on
> chiefComplaint does not mitigate it.**

**H-15 caveat 3 — retry nondeterminism in the assessed input (proposed severity: Medium).**
> `AssessmentRunner` re-resolves the consultation on every run including retries, and the
> transcription write occurs on a later screen than the assessment enqueue. Whether an ungated
> transcript is present in `symptom_string` therefore depends on retry timing. Nothing persisted
> distinguishes the two cases: `EvaluateRequestDto` carries no provenance, and the report table
> replaces its row per case on retry. The exact input to a given differential is not reconstructible
> after the fact.

**New hazard H-21 (proposed) — the safety chain rests on an unauthenticated role claim.**
> An ASR mis-transcription (H-15) reaches an ungated text classifier (H-20 mechanism), producing a
> confident wrong differential, whose NLEM treatment and brand suggestion are withheld only until a
> `UserRole.DOCTOR` commits a decision (`PRESCRIPTION_APPROVAL_GATE_ENABLED`). That role is
> self-asserted with no credential check (H-06, Open), and cadre scope of practice is the concern
> H-17 registers. Each control is rated tolerable on the assumption that the next one holds; the
> composed path is registered nowhere. Proposed severity High, probability not established.
> Mitigation is not additive controls but closing H-06 (REQ-SEC-03).

---

## 8. Sequencing note the operator should weigh (does not change the verdict)

The reason-for-encounter / questionnaire-tree track would replace free-text `chiefComplaint` with a
selected category plus structured branching answers — which is *exactly* the input representation
`category-clf-v0.1` already consumes, and which the abstention chain *does* cover. If that lands,
"voice on chiefComplaint" largely becomes "voice fills a category picker": a smaller problem, a
different control, and a field whose wrong values the conformal gate can actually abstain on.

Spending a two-repo migration on `chiefComplaintProvenance` now therefore risks spending it on a
column for a field that is about to change shape. This is an argument about *when*, not *whether* —
and it is the operator's call, not this memo's. Note that §5 (splitting the flag) and the
`transcription` exemption in §3.1(ii) are worth doing on their own merits **regardless** of how that
sequencing goes: both are live gaps in shipped code, not properties of a future feature.

---

## 9. Verdict and gated path

### VERDICT: **NOT-YET.**

**Primary blocker, stated singly as required:** `VOICE_INPUT_ENABLED` cannot be flipped because it
also enables the audio-attachment → `TranscriptionScreen` path, which writes a raw, unconfirmed ASR
transcript into `Consultation.transcription`, from where it is concatenated into `symptom_string` and
sent to `/api/v1/evaluate` — with no confirmation gate, no provenance, and an explicit exemption
from the `VOICE_UNCONFIRMED` repository refusal. This path is uncontrolled today and is not
mitigated by any work proposed for `chiefComplaint`.

**Supporting blockers, each independently sufficient:**

- **B2.** No confirmation gate exists on `chiefComplaint` at all. `onRecordChiefComplaintVoice`
  discards the transcript. A bare flip ships a button that records and throws the result away.
- **B3.** No data-layer provenance for `chiefComplaint`: a Room migration (19→20), a backend Alembic
  migration, and the sync contract between them. Two repositories.
- **B4.** No abstention over the text classifier on the live path, and the abstention chain that
  exists is neither wired nor text-consuming (§2.2). A voice-derived complaint would be the first
  ungated model input in the system with no downstream detector.
- **B5.** The accented-speech accuracy of the shipped int8 Parakeet artifact for the deployment
  population is still unmeasured — already an OPEN ACTION under H-15. It bites harder here than it
  did at the PR 4b flip, because the consumer of the value is a model with no abstention rather than
  a human reader.

### Conditions under which this becomes SAFE-UNDER-CONDITIONS

Not a wish list — the specific set that, if all met, would make a subsequent memo able to say yes:

- **X.** `VOICE_INPUT_ENABLED` is split per §5, and `chiefComplaint` voice can be enabled without
  enabling the audio attachment. The attachment path is either gated behind its own flag with its
  own memo, or `TranscribeAudioUseCase` is brought behind the same confirm-before-persist discipline
  as the field gate. **Non-negotiable; nothing else can be evaluated until this holds.**
- **Y.** `chiefComplaint` carries persisted `FieldProvenance` end to end (Room column + migration,
  domain, generalised repository refusal, sync DTO, backend column + migration), **and** the
  provenance of the value that produced a given differential is recoverable from the evaluate audit
  trail or the report row. Migration tests assert the persisted row per CLAUDE.md.
- **Z.** The confirmation gate is built on `chiefComplaint` to the `impactOnDailyActivities` pattern —
  transcript to a separate suggestion state, never into the field; three equal-weight buttons;
  `canSend` blocked while a suggestion is pending; all four `VOICE_FIELD_*` breadcrumbs — **plus**
  one thing that field did not need: the value that reaches `symptom_string` must be one a human
  affirmatively committed, with no path by which an unconfirmed value can be assessed.

**And two conditions that are gates on clinical deployment rather than on a dev flip, but which
must be stated because this field feeds a model:** the accented-speech evaluation of the shipped int8
artifact (B5), and a decision on whether a text-side abstention or OOV guard is required before any
voice-derived value reaches Classifier B at all. My reading is that Z plus the physician gate is
sufficient for a *dev-only* flip and not sufficient for clinical deployment — but the operator should
rule on that explicitly rather than inherit it from this sentence.

### If the operator authorises the follow-up build

Not a flag flip. A build with a **Room migration (19→20), a backend Alembic migration, a sync
contract change across two repositories, and a new confirmation-gate state machine on the
highest-stakes field in the form.** Per the standing convention it is **a separate Opus session with
its own checkpoint**, and it should be split at minimum into: (1) the flag split, §5, no behaviour
change; (2) the provenance migration, device + backend, tests asserting persisted rows; (3) the
chiefComplaint gate; (4) the flag flip itself, last, behind its own evidence. Folding any of these
into a one-line flag change is exactly the shape this memo exists to prevent.

---

*PROPOSED. No code written, no flag changed, no risk-file row committed in this session.*
