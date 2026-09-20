# F6C-01 / F6C-05: calibrated-flag fix and model-version inventory

Date: 2026-09-19. Source: `scratchpad/perf-audit-2026-09-18.md`, findings F6C-01 and F6C-05.
Scope: `SaMDClassifier/src/app.py` only in this pass. `docs/quality/risk-management-file.md`
not touched (rule for step 1/2). Nothing committed.

## Step 1: calibrated flag (DONE, tested, not committed)

**Confirmed, MEASURED, two call sites (audit named only one):**
- `SaMDClassifier/src/app.py:71` (pre-edit) — critical-threshold-breach branch —
  `"model_metadata": {"version": meta["model_version"], "calibrated": True}`
- `SaMDClassifier/src/app.py:143-146` (pre-edit) — normal-inference branch — same literal,
  block form. The audit only flagged the first; both are the same defect and both are fixed.
- `SaMDClassifier/models/model_meta.json:58` — `"calibration_used_for_evaluation": false`

**Fix applied.** At import time, next to where `meta`/`model`/`explainer` are built
(`app.py:41`): `calibrated = meta.get("calibration_used_for_evaluation")`. Both response
sites now emit `"calibrated": calibrated` instead of the literal. If the key is ever absent
from the artifact, this reads as `None` (JSON `null`), not a silent default to `True` or
`False` — the honesty requirement from the brief.

**What `calibrated` means / who reads it, MEASURED.** Grepped `SaMDClassifier`,
`backend/core`, `app/src`, and `docs/` for the field. Nothing outside `app.py` itself reads
`model_metadata.calibrated` anywhere in this codebase — not the backend proxy, not the
Android app, not any documented contract example. Flipping the value has no known
downstream branch today. This is stated as absence-of-evidence, not absence-of-risk: a
future consumer that starts branching on it will now get the true value instead of an
always-`True` literal, which is the point of the fix.

**Tests added,** `SaMDClassifier/tests/test_calibrated_metadata.py` (unittest, `TestClient`,
matches repo's existing `sys.path`-into-`src` convention since there's no pytest/conftest
here). 4 tests, all passing against the real artifact:
1. emergency branch `calibrated` == artifact's `calibration_used_for_evaluation`
2. routine branch, same assertion
3. **the one that matters**: monkeypatches `app_module.calibrated = True` (reintroducing the
   exact old literal) against the real artifact (which says `False`), and asserts that the
   equality check from (1)/(2) then raises `AssertionError` — proving the guard has teeth,
   not just a vacuously-true comparison
4. simulates an absent key (`{}.get(...)` → `None`) and asserts the response reports `null`,
   not a defaulted `True`/`False`

What the tests caught that reading did not: nothing — the fix is a straight-line
substitution and all 4 passed on the first run. Recorded per the instruction anyway.

Run: `cd SaMDClassifier && source .venv/bin/activate && python3 tests/test_calibrated_metadata.py -v` → `Ran 4 tests ... OK`.

## Step 2: version-string inventory (STOP HERE, no strings changed)

**The perf audit undercounted and, in one place, mischaracterized.** It found "five strings,
no two the same" by treating every `model_version`-shaped string in the repo as if they all
named the same model. They don't — there are **three distinct model artifacts**, each
independently versioned, plus one doc-example string that has never matched a real one.
Re-stating the inventory correctly:

### A. The vitals/tier model — the one actually served on `/v1/assess` and `/health`

| location | string | DERIVED or ASSERTED | for |
| --- | --- | --- | --- |
| `SaMDClassifier/models/model_meta.json:2` | `toy-v0.6-observed-glucose-4tier` | source of truth (written by training) | training artifact |
| `SaMDClassifier/scripts/train_model.py:13` | `toy-v0.6-observed-glucose-4tier` (`MODEL_VERSION` const) | ASSERTED, matches meta today | the value training stamps into the artifact next run |
| `SaMDClassifier/src/app.py:218` `/health` | `meta.get("model_version", "unknown")` | DERIVED | runtime health-check consumer contract |
| `SaMDClassifier/src/app.py:75,148` `/v1/assess` | `meta["model_version"]` | DERIVED | clinical inference response |
| `SaMDApp/docs/backend/slm-service-contract.md:195` | `toy-v0.6-observed-glucose-4tier` | ASSERTED, **matches** runtime | doc describing observed `/health` behaviour |
| `SaMDApp/docs/backend/api-contract.md:825` | `xgb-2026-06-11` | ASSERTED, wire-example | documents the shape of the `/v1/assess` response's `model_metadata` |
| `SaMDApp/backend/core/tests/test_kernel.py`, `test_kernel_derivation.py` | `xgb-2026-06-11` | ASSERTED, test fixture | copied from the api-contract example; asserts pass-through storage, not a real model identity |
| `SaMDApp/docs/quality/risk-management-file.md` | **absent** | — | no hazard row anywhere in the file names this model's version |

**Correction to the audit's framing.** It said "the hazard analysis is written against
`v0.1-xgb-venn-abers` and the thing answering `/assess` is `toy-v0.6`." That is not what the
risk file says. The only model-version string in `risk-management-file.md` (line 46, hazard
row H-15.C1) names `category-clf-v0.1-xgb-venn-abers` **and explicitly states in the same
sentence that this model "is not loaded by `SaMDClassifier/src/app.py` and so is on no live
path."** The risk file is not confused about which model is live — it is silent about it.
**The real finding for the risk file is an absence (no hazard row keyed to
`toy-v0.6-observed-glucose-4tier`, the model actually answering `/assess`), not a
contradiction.** That distinction matters for what step 3 has to fix: there is no wrong
line to correct in the risk file for this model, there is a missing one.

The one genuine mismatch in this group is `api-contract.md:825`'s `xgb-2026-06-11`, an
example string that — as far as this repo's git history of artifacts shows — has never
corresponded to any shipped model. It reads as a placeholder that was never revisited, not a
record of a real past state.

### B. The symptom classifier (Classifier B, `refine_diagnosis.py`) — live, used by `/api/v1/evaluate`

| location | string | DERIVED or ASSERTED | for |
| --- | --- | --- | --- |
| `SaMDClassifier/models/symptom_model_meta.json:3` | `symptom-clf-v0.2-enriched-symptom-pool` | source of truth, the artifact actually loaded by `refine_diagnosis.py:47-51` | shipped artifact |
| `SaMDClassifier/scripts/train_symptom_classifier.py:17` | `symptom-clf-v0.1-tfidf-xgboost` (`MODEL_VERSION` const) | ASSERTED, **stale** | what the script would stamp on its *next* run |
| `SaMDApp/PROGRESS.md:4510,4514` | both strings, side by side | a progress note, already correctly diagnosing the drift | historical record — **this one is not a fresh finding**, PROGRESS.md already says re-running the script today would mislabel the v0.2 artifact as v0.1 |

Not exposed in any API response (`refine_diagnosis.py` uses `symptom_meta` internally only;
`api_schemas.py`'s response models carry no version field for this model). Not on the live
wire, but the training script is one accidental re-run away from silently relabelling a
shipped artifact. PROGRESS.md already flagged this — it is pre-existing, tracked, separate
from F6C-01, and out of scope for this change set. Named here only so the inventory is
complete.

### C. The category classifier (Venn-Abers conformal model) — not loaded, not live

| location | string | DERIVED or ASSERTED | for |
| --- | --- | --- | --- |
| `SaMDClassifier/scripts/train_category_classifier.py:54` | `category-clf-v0.1-xgb-venn-abers` | source | training script constant |
| `models/category_model_meta.json`, `category_feature_schema.json`, `logs/category_training_manifest.json` | same string, all agree | consistent | shipped-but-unwired artifacts (PR-2/PR-3 in-flight work) |
| `docs/quality/risk-management-file.md:46` | same string | ASSERTED, and **correctly scoped**, states its own not-live status | hazard row for a specific voice-input caveat, not a claim about `/assess` |

This group is internally consistent and the risk file's use of it is accurate. No action
needed here.

**Corrected count: not "five strings, no two the same."** Nine-plus locations across three
independently-versioned models. Within each model's own group the strings mostly agree
(`category-clf` group: 3/3 agree; symptom group: known, already-tracked drift; vitals group:
`model_meta.json`, `train_model.py`, `/health`, `/v1/assess`, and
`slm-service-contract.md` all agree at `toy-v0.6-observed-glucose-4tier`). The actual open
questions are narrower than the audit implied:

1. `api-contract.md:825`'s `xgb-2026-06-11` wire example, and the two backend test fixtures
   copied from it, versus the real served `toy-v0.6-observed-glucose-4tier`.
2. `risk-management-file.md` has no hazard row keyed to the model that is actually live on
   `/assess` at all (an absence, addressed in step 3 once the owner confirms scope — this is
   the sensitive edit the brief reserves for step 3).
3. The symptom-classifier script/artifact drift (PROGRESS.md-tracked, arguably a separate
   ticket, not this one).

**Recommendation, with reason.** Treat `toy-v0.6-observed-glucose-4tier` (from
`model_meta.json`, already what `/health`, `/v1/assess`, and
`docs/backend/slm-service-contract.md` agree on) as canonical for the vitals model, and
correct `api-contract.md:825`'s example plus the two backend test fixture literals to match
it or to an explicitly-labelled placeholder. Reason: it is the one string with the most
independent agreement today (training artifact, two runtime endpoints, and one doc), and it
is DERIVED at every runtime site already post-step-1 — there is nothing to change in code,
only in the three ASSERTED doc/test-fixture sites. The open item is whether the owner wants
a `risk-management-file.md` hazard row added for `toy-v0.6-observed-glucose-4tier` at all
(it is, by its own name, a toy/placeholder model, so the owner may prefer to gate that on
the real training-data decision rather than write a hazard row against a model that is
expected to be replaced).

## Owner's decisions (2026-09-19)

1. **Canonical string: `toy-v0.6-observed-glucose-4tier`** (the recommended option — already
   what the artifact, both runtime endpoints, and `slm-service-contract.md` agree on).
2. **No new risk-management-file.md hazard row this pass** — leave the toy-model traceability
   gap for separate scoping rather than write hazard content against a placeholder model
   expected to be replaced. `docs/quality/risk-management-file.md` was **not edited** in this
   change set — there was no wrong line in it to correct (see the correction above: it's an
   absence, not a contradiction), and the owner declined adding a new row now.

## Step 3: correction applied

- `docs/backend/api-contract.md:825` — wire-example `model_version` changed from
  `xgb-2026-06-11` to `toy-v0.6-observed-glucose-4tier` (matches every runtime and doc source
  that already agreed).
- **Not changed:** `backend/core/tests/test_kernel.py` and `test_kernel_derivation.py`'s
  `"xgb-2026-06-11"` fixture literals. These assert pass-through storage of *whatever* string
  arrives on the wire — they don't assert model identity — so the literal is arbitrary test
  data, not a documentation claim. Renaming it adds no protection and isn't a "document that
  disagrees." Left alone per minimal-diff.
- **Test added** (extends `tests/test_calibrated_metadata.py`, doesn't need a new file — it
  already has the `TestClient` + artifact-loading setup): `ModelVersionMatchesArtifact`, 3
  tests — served `/health` and `/v1/assess` `model_version` each equal the artifact's, plus a
  "the one that matters" test that reintroduces a stale literal and proves the comparison
  then fails. **7/7 tests pass** (4 from step 1 + 3 new):
  `cd SaMDClassifier && source .venv/bin/activate && python3 tests/test_calibrated_metadata.py -v`

## Recorded, not fixed: ABDM adapter KeyError (for sync failure-taxonomy work, not this change set)

During PR-2 work, the ABDM adapter was observed raising `KeyError: 'accessToken'` against a
live gateway response. An unexpected upstream response shape is becoming an opaque crash
rather than a classified error. Not touched here; flagging for whoever picks up the sync
failure taxonomy.
