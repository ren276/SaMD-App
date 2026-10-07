# Backend truthfulness memo: model identity, calibration, derivation, contracts

Drafted 2026-09-30. Read-only design memo. Covers F6C-01 (served model version attributable to
nothing) and F6C-05 (calibration asserted, not true), plus the /evaluate identity gap, the
server-side derivation gap, classifier contract discipline, historical correction and the embedding
pin. Every claim is labelled **MEASURED** (file:line, or a command run) or **INFERRED**.
Nothing in `docs/` is edited by this memo; section 8 holds proposals only.

Sources: the Phase 0 and Phase 1 reviews of 2026-09-29 (in-session), `docs/design/perf-audit-2026-09-18.md`
(F6C-01 `:1004`, F6C-05 `:1148`), `docs/design/f6c-calibrated-fix-and-version-inventory.md` (commit
`7dfef02`), and the merged emergency fix #65.

## 0. State re-verified on master before relying on it

| Fact | Status |
|---|---|
| master is `7f716eb`, the merge of #65; working tree clean | MEASURED (`git log`, `git status`) |
| `DERIVATION_RULE_VERSION = "HAN-07/08-v2"` | MEASURED `backend/core/app/domain/kernel_derivation.py:74` |
| Shared fixtures and `expectations.json` exist in both dirs, all `classifier_commit: 63e85af` | MEASURED (`ls`, `grep`) |
| H-29 registered PROPOSED at `risk-management-file.md:57`; H-28 reservation note at `:61` | MEASURED |
| Version key mismatch unchanged: backend reads `model_metadata.model_version` (`services/kernel.py:335`), device DTO `@SerializedName("model_version")` (`KernelAssessmentResponseDto.kt:46`), fallback `"remote-kernel"` (`GenerateKernelReportUseCase.kt:291`, moved from `:289` by #65) | MEASURED |
| SaMDClassifier still at `63e85af`; the calibrated fix is still uncommitted (`M src/app.py`, `?? tests/test_calibrated_metadata.py`) alongside unrelated `M docker-compose.yml`, `M readme.md` and untracked category-model files | MEASURED (`git status` in SaMDClassifier) |
| Running image `samd-classifier:latest` created 2026-09-08T09:25:36Z, before the fix (app.py mtime 2026-09-19) | MEASURED (Phase 1, `docker image inspect --format '{{.Created}}'`) |
| Embedding model unpinned: `SentenceTransformer('sentence-transformers/all-MiniLM-L6-v2')` | MEASURED `SaMDClassifier/Dockerfile:27`; loaded by name again at `src/rag/retriever.py:25` |
| Next free hazard IDs across the WHOLE repo (tracked and untracked text, `graphify-out/` excluded, ASCII and Unicode hyphens): **H-30, H-31**. Highest used is H-29 | MEASURED (repo-wide scan) |

## 1. F6C-01: version attribution

**The defect (MEASURED).** The classifier has emitted `model_metadata: {"version": ..., "calibrated": True}`
in every commit of `src/app.py` (`9cb7939`, `c4f4934`, `63e85af`). The API contract
(`docs/backend/api-contract.md:825`), the backend (`services/kernel.py:335`), the device DTO
(`KernelAssessmentResponseDto.kt:46-47`) and the backend test fixtures (`tests/test_kernel.py:128`,
`tests/test_kernel_derivation.py:34`) all use `model_version`. So every real `/assess` has produced
`kernel_call_log.model_version = NULL`, `kernel_assessments.model_version = NULL`, and a device
`kernel_reports.modelVersion = "remote-kernel"` that syncs to the backend's NOT NULL column. No stored
value binds a version string to the bytes that were served: `model_meta.json` records no hash of
`model.json` (MEASURED, Phase 0), and nothing checks one at load.

**Recommendation.**

1. **Canonical key is `model_version`**, because the contract, both consumers and the backend tests
   already use it; only the classifier differs. The classifier emits
   `model_metadata: {"model_version", "model_sha256", "calibrated"}`. Consumers read `model_version`
   and accept the legacy `version` key as a **pinned alias only**, because the deployed image
   (2026-09-08) emits `version` and the classifier and SaMD-App deploy independently (INFERRED from
   separate repos and images). The alias is tested with the existing `63e85af` fixtures and removed
   in the PR that moves the pin past the key change (section 5).
2. **Bind the version to the bytes.** Training writes `artifact_sha256` into each metadata file:
   `model_meta.json` for `model.json`; `symptom_model_meta.json` for `symptom_model.json`, both
   vectorizers and the label encoder. The precedent already exists: `sha256_file` at
   `scripts/train_category_classifier.py:1021`, and `category_model_meta.json`'s `model_sha256` matches
   its file (MEASURED, Phase 0). For the currently served artifacts, the operator records the present
   hashes (`model.json` = `1621668c636e935d...`, MEASURED) without retraining. `app.py` hashes each
   artifact at import and **raises on mismatch**; the existing load block already raises
   `RuntimeError` on failure (`app.py:42-43`, MEASURED), so the container fails its healthcheck rather
   than serving an unbound model (fail closed, INFERRED from `Dockerfile` HEALTHCHECK).
3. **Drop `"remote-kernel"`.** A sentinel string in a `modelVersion` column is a fabricated
   attribution. Make the column nullable on both sides: device `kernel_reports.modelVersion`
   (Room 21 to 22, shared with the sync taxonomy work per D4; SQLite cannot relax NOT NULL in place,
   so this is a table-rebuild migration),
   `SyncPayloadDto.kt:190` to `String?`, backend `kernel_reports.model_version` to nullable (Alembic
   0009). NULL means "the kernel did not say", the same convention `kernel_derivation.py` already uses
   for absent values. When the kernel omits the version, the device records the existing typed
   `KERNEL_UNRECOGNISED_OUTPUT` event with field `model_version`. It does **not** force physician
   verification: attribution is a traceability fact, not a clinical signal.
4. The backend lifts `model_sha256` into `kernel_call_log` and `kernel_assessments` beside
   `model_version`. The device stores the version only; the hash lives server-side where the raw body
   already is.

## 2. F6C-05: calibration

**The defect (MEASURED).** `model_meta.json` records `calibration_used_for_evaluation: false`, the
training script says the served model is the raw, uncalibrated one (`scripts/train_model.py:94-100`),
and the committed `app.py` asserts `"calibrated": True` on both branches. Nothing in SaMD-App reads
the field, but workers see the raw score as a confidence: `KernelAssessmentScreen.kt:183`
("Confidence below 90%"), `:232` (the "N%" figure), `GenerateKernelReportUseCase.kt:278`
("at N% confidence"), and `KernelAssessmentViewModel.kt:89` (`/evaluate` "ICD (N%)", an
`adjusted_confidence` that is a heuristic re-weighting of an uncalibrated symptom model,
`refine_diagnosis.py:109-115`). The PDF shows no percentage (MEASURED, Phase 1).

**Recommendation.**

1. **Commit path for the pending classifier fix.** A SaMDClassifier PR containing only the three
   `src/app.py` hunks and `tests/test_calibrated_metadata.py`. Leave out `docker-compose.yml` and
   `readme.md` (unrelated edits) and the untracked category-model files (separate in-flight work).
   Fold in the section 1 key and hash changes so the image is rebuilt once. After merge: rebuild,
   confirm the image `Created` time is after the commit, then recapture the fixtures at the new pin.
   `expectations.json` should not change, since triage does not read the flag (INFERRED from
   `KernelTriageRules.kt`).
2. **Store the flag.** Device `kernel_reports.modelCalibrated: Boolean?` and backend
   `kernel_reports.model_calibrated`, both nullable, in the same Room 22 and Alembic 0009 migrations as
   section 1. The backend lifts it into `kernel_assessments` too. **NULL is treated as false for
   display** (fail closed): an absent claim of calibration is not calibration.
3. **Wording when `calibrated` is false or null**, which is every result today. Amended by D2
   (section 10): workers see no percentage at all on `/assess` or `/evaluate`; the "model score N%
   (uncalibrated)" wording below applies to physicians only. Hindi strings change in the same PR.
   - The figure is called a **model score**, never a confidence: "Model score 97%", and in the
     reasoning line "model score 97% (uncalibrated)".
   - The 0.90 rule **stays** as a risk control (H-02, REQ-HAN-08), but is labelled for what it is:
     "⚠ Model score below 0.90 (uncalibrated): physician verification is required before any
     diagnosis is finalized." The rule compares a raw score; the label must not call it a probability.
   - `/evaluate` differential lines read "ICD (score 42%)".
   - Only when `calibrated` is true may the UI say "Confidence N% (calibrated)". No build today
     reaches that branch.
   - The PDF stays without a percentage.
4. **No investment in calibrating the legacy model.** It is the toy vitals model being replaced by the
   rebuilt classifier; calibrating it would be spent effort on an artifact with a known end.

## 3. /evaluate identity

**The gap (MEASURED).** `/api/v1/evaluate` uses two models, `symptom-clf-v0.2-enriched-symptom-pool`
and the `toy-v0.6` vitals model (`refine_diagnosis.py:37-54`), plus a sentence-transformers embedding
for retrieval. Its response schema (`src/api_schemas.py:78-84`) carries no identity. The device
stores `gson.toJson(payload)` of the mapped domain object (`EvaluateReportRepositoryImpl.kt:58`),
so any field the DTO does not declare is lost before storage. The backend deliberately lifts nothing
for `/evaluate` (`services/kernel.py:332-335`).

**Recommendation.** Add a `model_metadata` object to the `/evaluate` response:
`{vitals_model_version, vitals_model_sha256, symptom_model_version, symptom_model_sha256,
calibrated: {vitals, symptom}, embedding_model: "<name>@<revision>"}`, every value read from the
artifacts as in section 1. Declare it in the device `EvaluateReportDto` and domain
`EvaluateReportOutput`, so it lands in `payloadJson` and syncs into backend
`evaluate_reports.payload_json` unchanged. Extend the backend lift at `services/kernel.py:335` to set
`kernel_assessments.model_version` for `/evaluate` to the symptom model version. No new
`evaluate_reports` columns: the payload is already the record there, and jsonb is queryable.

## 4. The server-side derivation gap

**The gap (MEASURED).** No production code calls `derive_assess`; only tests import it (docstring
corrected in #65, `kernel_derivation.py:9-16`). The DOCTOR-role encounter endpoint
(`api/v1/encounters.py:270-293`) serves `kernel_reports` rows as the device synced them
(`services/encounter.py:175-182`). That query takes `.first()` with **no ORDER BY** while
`kernel_reports` has no unique index per case and duplicates already exist (F6C-02), so the physician
can be shown an arbitrary one of several reports (MEASURED query, INFERRED effect).

**Options.**
- **A.** The DOCTOR endpoint derives at read time from `kernel_assessments.raw_response`. The server
  becomes authoritative, and a rule-version bump heals history for every row that has a
  `kernel_assessments` row.
- **B.** Change the documented rule to match reality: the device's derived values, as synced, are the
  stored clinical record; `derive_assess` is a mirror whose agreement with the device is proven by
  the shared `expectations.json`; historical errors are corrected by explicit correction records.

**Recommendation: B now, with A made possible later by a keyed link.** Amended by operator
decision D1 (section 10): B plus a per-row derivation rule version, a doctor-view flag for
superseded versions, and a sync-time re-derivation cross-check that records disagreement as a flag.
This reverses the previously recorded "derived at read time" rule.

Reasons:
1. A has **no deterministic pairing** today. `kernel_reports` carries no `request_id` or response
   hash (`models/kernel.py:46-72`, MEASURED), so a read-time derivation must guess which
   `kernel_assessments` row belongs to a report by case and time (Phase 1 recoverability table).
2. A would make the physician's view differ from the record the worker acted on for the same case,
   which is the divergence `kernel_derivation.py` itself warns against.
3. Rows from before the proxy existed have no `kernel_assessments` row at all, so A cannot heal them.

What B adds now:
- The device stores the proxy's `X-Request-ID` (minted at `middleware/request_id.py:26`, MEASURED;
  never read by the device, MEASURED) on each new `kernel_reports` row, which gives a keyed link
  going forward. Once every row in use carries it, A can be revisited without guessing.
- The DOCTOR query gets `ORDER BY` (latest `inference_ended_at`) as a one-line fix. This is a
  stopgap (PR 2). The real fix is a backend UNIQUE index on `case_record_id` plus dedup, per the
  one-current-X-per-case rule.
- Per D1 (section 10): every `kernel_reports` row records the device's `derivation_rule_version`,
  and rows derived under a superseded version are flagged on the DOCTOR view.

**Effect on H-29 remediation.** Under B, the version bump to v2 heals nothing, and the H-29 rows are
healed only by the section 6 correction records, which the DOCTOR endpoint overlays. Under A, the bump
would heal rows with a `kernel_assessments` match, but only through the heuristic pairing, and would
leave the device record and any pre-proxy rows uncorrected. B is the one that treats every affected
row the same way, with an auditable basis for each correction.

**Documentation that must change under B** (proposals, section 8): every place that says the report
layer derives at read time. MEASURED code-comment sites: `services/kernel.py:26`, `:327`, `:467`;
`models/sync.py:183`; `alembic/versions/0004_kernel_assessments.py:11`. The backend PRD's decision
entries D-9, D-10 and D-11 describe the same design (`docs/backend/backend-prd.md:820-822`,
MEASURED).

## 5. Contract discipline

**What went wrong (MEASURED).** Every consumer-side test of the kernel contract used hand-written
bodies, so the device, the backend and the contract document agreed with each other and not with
the classifier: the `version` key (section 1), `EMERGENCY_REFERRAL` (H-29), `inference_time_ms`
(never emitted), and `calibrated` (never read).

**Recommendation.**

1. **One pin file**, `contracts/classifier-pin.json` in SaMD-App: classifier commit, the SHA-256 of
   every served artifact, the capture date, and the capture script version.
2. **One capture script**, `scripts/capture_classifier_fixtures.py`: `git archive` the pinned commit
   into a temp dir, run it in the classifier's own venv, capture `/v1/assess`, `/api/v1/evaluate` and
   `/health` for a fixed input set, and write fixtures with `_provenance` into both fixture
   directories. This is the method used for #65, made repeatable. It refuses to run if the commit does
   not match the pin.
3. **Every contract element is asserted from those fixtures**, never from literals:
   - response keys (a key-presence test per endpoint)
   - urgency tokens and class names (`expectations.json` must cover every token the classifier
     source can emit; a test fails if a captured fixture holds a token the expectations do not list)
   - the `/evaluate` schema (fixtures parse through the device DTO and the backend unchanged)
4. **CI** asserts every fixture's `classifier_commit` equals the pin, and keeps the byte-identity
   mirror test. Live capture stays a developer step: CI has no checkout of SaMDClassifier (INFERRED).
5. **Drift between the pin and the deployed image is detected at runtime without an extra call.**
   After section 1, every response carries `model_sha256`. The backend proxy compares it and the
   version to the pin on each call, records `pin_match` on `kernel_call_log`, logs a warning on
   mismatch, and reports it on its own `/health` (which today does not fan out to the classifier,
   `api/v1/health.py:23`, MEASURED). It does **not** refuse to serve on mismatch: blocking all
   assessments on a version skew would stop care, and the fail-closed triage rules from #65 already
   contain a misparse (INFERRED). The classifier also reports its build commit on `/health`, baked in
   through a Docker build argument, so the pin-to-image comparison covers code as well as artifacts.

## 6. Historical rows: append-only correction

**Status: deferred by D3 (section 10).** PR 7 is built only if real patient data exists before
PR 4 lands. Pre-pilot data is declared non-clinical and databases are reset before the first real
deployment. The design below is kept for that contingency.

**Rule.** Stored rows are never updated, and `audit_events` and its hash chain are never touched.
`audit_events` is already protected by `BEFORE UPDATE` and `BEFORE DELETE` triggers
(`alembic/versions/0001_initial_auth_audit_abha.py:30-37`, `:183-196`, MEASURED); the correction
table gets the same.

**Design.** A backend table `kernel_attribution_corrections`:
- identity: `id`, `target_table`, `target_id`, `field`
- values: `stored_value`, `corrected_value`
- basis: `evidence_kind`, `evidence_ref` (for example a `request_id`), `correction_rule_version`
- provenance: `created_by` (job id or operator), `created_at`
- immutability: triggers rejecting UPDATE and DELETE
- one `audit_events` row per applied batch, action `KERNEL_ATTRIBUTION_CORRECTED`, with counts per
  evidence kind and the rule version
- the DOCTOR endpoint returns the stored value **and** the latest correction per field, flagged
  `corrected: true` with its basis, so a reader can always see both

**Evidence kinds, from the Phase 1 recoverability table:**

| Target | Field | Evidence kind | Recoverable |
|---|---|---|---|
| `kernel_assessments` (successful calls) | `model_version` | `RAW_RESPONSE_SAME_ROW`: `raw_response->'model_metadata'->>'version'` | Yes |
| `kernel_call_log` (successful calls) | `model_version` | `REQUEST_ID_KEYED`: join `kernel_assessments` on `request_id` | Yes |
| `kernel_call_log` (failed calls) | `model_version` | none (hashes only, no body) | No; no model output to attribute |
| `audit_events` `KERNEL_CALL_FORWARDED` | none | referenced by `request_id` from a correction, never corrected itself | Not applicable |
| `kernel_reports` (`"remote-kernel"`) | `model_version` | `CASE_TIME_UNIQUE` when the case has exactly one successful `/assess` row; `CASE_TIME_AMBIGUOUS` otherwise, corrected only if every candidate row agrees on the version | Approximately |
| `kernel_reports` (H-29 red-flag rows) | `urgency_level`, `risk_category`, `required_human_verification` | `SAME_ROW_RULE_V2`: re-derive from the row's own `predicted_condition` and `reasoning_summary` under HAN-07/08-v2 | Yes |
| `kernel_reports` (D-11 rows) | `risk_category` | `SAME_ROW_RULE_V2`: from the row's own `predicted_condition` | Yes |
| Pre-proxy `kernel_reports` | `model_version` | `DEPLOYMENT_TIMELINE`: operator-attested only, never automatic | Only by attestation |
| `evaluate_reports` | model identity | `DEPLOYMENT_TIMELINE`: operator-attested only | Only by attestation |

The job runs **dry-run first**, producing counts per evidence kind for operator approval before any
row is written. Device-side rows are not corrected: there is no server-to-device channel for derived
fields, and the device record is what the worker saw. That limitation belongs in the residual-risk
column of H-30.

## 7. Embedding model pin

**Recommendation.** Pin a revision in both places that load the model, `Dockerfile:27` and
`src/rag/retriever.py:25`, from one shared constant, and set `HF_HUB_OFFLINE=1` in the image so the
runtime cannot fetch a different revision. The revision value must be read from the currently built
image's cache: it cannot be determined statically (UNVERIFIABLE here). The Chroma vector store was
built with whatever revision ingest used (`src/rag/ingest.py:245`, MEASURED load site). A query-time
revision that differs from the ingest-time one changes retrieval without any signal (INFERRED), so
the pin should record the ingest revision too.

**Out of scope, noted only:** whether retrieval-based NLEM treatment belongs on the clinical path at
all, versus the owner's planned deterministic medicines database (H-27's open action).

## 8. Proposed hazard rows and proposed doc edits

Next free IDs are **H-30** and **H-31** (MEASURED, whole-repo scan, section 0). Both are
PROPOSED, AWAITING OPERATOR SIGN-OFF, and would go below H-29 in section 2.

**H-30 (PROPOSED).** *Model identity not attributable for stored assessments.*
- **Hazard:** every real `/assess` is stored with `model_version` NULL (backend) or `"remote-kernel"`
  (device and synced backend), `/evaluate` records no model identity, and no version is bound to
  artifact bytes. A field incident, recall or algorithm-change review cannot establish which model
  produced an assessment.
- **Harm:** inability to scope a model defect to affected patients; an unknown model served under a
  known name.
- **Sev:** High. **Prob:** not established (precedent of H-15, H-17, H-18, H-26).
- **Controls (proposed):** sections 1, 3 and 5: canonical key, hash-bound artifacts refusing to
  serve on mismatch, nullable version instead of a sentinel, `/evaluate` identity, pin file and
  runtime `pin_match`.
- **Residual:** history is corrected append-only (section 6) and approximately for synced
  `kernel_reports`; pre-proxy and `/evaluate` rows only by operator attestation; device rows are not
  corrected.

**H-31 (PROPOSED).** *Uncalibrated scores presented as confidence, with calibration asserted.*
- **Hazard:** the classifier asserts `calibrated: true` for a model whose metadata says calibration
  was not used, and the UI presents the raw score as "confidence" with a 0.90 gate labelled
  "Confidence below 90%". An uncalibrated boosted-tree score of 0.90 does not mean 90% of such cases
  are correct, and the model branch saturates at 1.0 (MEASURED, #65 fixture (e)). No reliability
  measurement exists (MEASURED, `train_model.py:94-100`).
- **Harm:** automation bias; skipped verification on a high score whose error rate is unknown.
  Related to H-02.
- **Sev:** High. **Prob:** not established.
- **Controls (proposed):** section 2: the flag read from the artifact, stored, and fail-closed on
  NULL; "model score" wording; the 0.90 gate relabelled, not removed.
- **Residual:** the gate's error rate stays unknown until the rebuilt, calibrated classifier replaces
  the legacy model.

**Proposed doc edits (not made):**

| Doc | Edit |
|---|---|
| `docs/backend/api-contract.md:825` | `model_metadata` keys become `model_version`, `model_sha256`, `calibrated`; `inference_time_ms` marked as not emitted |
| `docs/backend/api-contract.md` (`/evaluate` section) | add the `model_metadata` object from section 3 |
| `docs/backend/api-contract.md:837-838` | note that `model_version` is NULL for rows before the fix, with a pointer to the corrections table |
| `docs/backend/backend-prd.md` G-3 (`:65`) | status note: the audited model version was absent until the fix |
| `docs/backend/backend-prd.md:820-822` (D-9, D-10, D-11) | D-11 resolved by #65; D-9 and D-10 rewritten to Option B (section 4) |
| `docs/quality/soup-validation-record.md` | add the sentence-transformers model with its pinned revision as a SOUP item |
| `docs/quality/risk-management-file.md` | add H-30 and H-31 as PROPOSED rows |

## 9. PR breakdown

Table rows keep their original numbers. **Revised order (D-order, section 10): PR 2, PR 1, PR 3,
PR 4, PR 5, PR 6, PR 8. PR 7 deferred (D3).** PR 4 now also carries `derivation_rule_version` and
the sync cross-check (D1). File counts are estimates (INFERRED); every one is well under 100
reviewable files.

| # | Repo | Content | Est. files | Depends on |
|---|---|---|---|---|
| 1 | SaMD-App | Uncalibrated wording: "model score", relabelled 0.90 notice, reasoning line, `/evaluate` lines; UI test | ~5 | none |
| 2 | SaMD-App | DOCTOR endpoint `ORDER BY` on `kernel_reports` (and `evaluate_reports`), with a persisted-row test | ~3 | none |
| 3 | SaMDClassifier | Commit the pending calibrated fix; emit `model_version`, `model_sha256`; hash-bind artifacts and refuse on mismatch; `/evaluate` `model_metadata`; `/health` build commit; embedding revision pin and `HF_HUB_OFFLINE` | ~10 | owner merge and image rebuild |
| 4 | SaMD-App | Consumers read `model_version` (legacy `version` alias), drop `"remote-kernel"`, store `model_calibrated` and `model_sha256`, Room 22 and Alembic 0009, `X-Request-ID` stored on `kernel_reports`, fixtures recaptured at the new pin | ~30 | PR 3 |
| 5 | SaMD-App | `/evaluate` identity: DTO, domain object, backend lift, fixtures | ~12 | PR 3 |
| 6 | SaMD-App | Contract discipline: pin file, capture script, key and token coverage tests, CI pin check, proxy `pin_match` and `/health` | ~15 | PR 4 |
| 7 | SaMD-App | `kernel_attribution_corrections` table with triggers, dry-run job, DOCTOR overlay, persisted-row tests | ~15 | PR 4 |
| 8 | SaMD-App docs | H-30, H-31 and the section 8 doc edits, including the Option B rewrite | ~5 | operator sign-off |

PR 2 now goes first: a one-line query order fix on the physician's view, a stopgap until the
UNIQUE index and dedup. PR 1 changes what a worker reads today, touches no contract, and needs
nothing from the classifier; under D2 it removes worker percentages instead of relabelling
them. The legacy
`version` alias in PR 4 is removed in the first PR that moves the pin past PR 3.

## 10. Operator decisions (2026-09-30)

**D1, derivation: Option B, plus:**
- (a) Every `kernel_reports` row records the device's `derivation_rule_version`, and the backend
  flags rows derived under a superseded version on the DOCTOR view.
- (b) Once the `X-Request-ID` link exists, the backend re-derives on sync with current rules and
  records disagreement as a flag, never a replacement.
- (c) A minimum-app-version gate on sync for safety fixes is a recorded option, to be designed in PR 4.
- This reverses the recorded "derived at read time" rule by operator decision. The documentation
  sites listed in section 4 are rewritten accordingly in PR 8.

**D2, worker wording:** workers see NO percentage on `/assess` or `/evaluate`, only the prediction
plus the verification flag and its reason. Physicians see "model score N% (uncalibrated)". The PDF
stays without a percentage. Hindi strings are updated in the same PR.

**D3, corrections:** PR 7 is deferred. Pre-pilot data is declared non-clinical and databases are
reset before the first real deployment. Build PR 7 only if real patient data exists before PR 4 lands.

**D4, schema:** Room v22 is shared with the sync taxonomy work; whichever PR lands first takes v22
and the other rebases. The nullable `modelVersion` table-rebuild migration test must seed rows in
every affected table.

**Revised PR order:** PR 2, PR 1, PR 3, PR 4 (now with `derivation_rule_version` and the sync
cross-check), PR 5, PR 6, PR 8. PR 7 deferred.

**PR 2 note:** ORDER BY is a stopgap. The real fix is a backend UNIQUE index on `case_record_id`
plus dedup.
