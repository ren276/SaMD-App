# Gemini brand-lookup hazard entry, drafted for operator review

Read-only memo. Nothing in `docs/quality/risk-management-file.md` or any source file was edited.
This writes the record that the amended audit (`docs/design/perf-audit-2026-09-18.md`, item 0,
amendment A1) called for. No `.env`, `.env.*`, `local.properties`, or credential file was opened;
`GEMINI_API_KEY`'s storage mechanism is named below, its value never is.

**Owner decision this memo assumes and does not re-argue:** the Gemini brand-lookup path stays. It
supplies brand name and manufacturer for the medicine the evaluate line names; the medicines
themselves come from the NLEM database PDF. Removal is deferred until a deterministic medicines
database pipeline exists. This memo does not propose removal or a replacement feature.

---

## 1. What already exists in the register, checked before drafting anything new

`docs/quality/risk-management-file.md` already carries **H-11** ("External third-party network call
(Gemini API) for brand-name lookup"), an original seeded row, not a `PROPOSED` one. It already covers
part of defect (a) below: third-party egress, sent-data scope, and API-key source-control hygiene.
It does **not** cover defect (b): nothing in H-11 speaks to whether the text Gemini returns is
verified before it reaches the prescription. Two consequences for how this memo is structured:

- Defect (a) is drafted as a **caveat row, H-11.C1**, not a new hazard id and not an in-place edit
  of H-11. In-place editing a non-`PROPOSED` seeded row is against this file's own convention,
  established at H-15.C1–C3 ("H-15 is operator-signed-off and this file's convention is that a
  signed row is not edited in place by design work that is itself still PROPOSED"). H-11 predates
  that convention but the same reasoning applies: it reads as an accepted row today.
- Defect (b) has no existing row anywhere in the register. It is drafted as a genuinely new hazard,
  **H-27**, the next id after the highest currently used (**H-26**, navigation back-stack, MEASURED
  from the file).

---

## 2. Drafted entries (PROPOSED, AWAITING OPERATOR SIGN-OFF — not written to the risk file)

### H-11.C1 — Caveat on H-11: egress containment is narrower than H-11's wording states, and a build-time fix is planned

**Status: PROPOSED, AWAITING OPERATOR SIGN-OFF.** Sits directly beside H-11 in §2, same shape as the
H-15.C1–C3 caveat rows: it corrects a stated assumption on an existing, already-accepted row rather
than reporting an unrelated new defect.

**What H-11 says today, and what the 2026-09-18 audit found that it doesn't account for.** H-11's
control cell reads "API key stored in git-ignored `local.properties`, never committed," which is true
of source control but says nothing about which **build flavors** the key compiles into. MEASURED,
`app/build.gradle.kts:74`: `buildConfigField("String", "GEMINI_API_KEY", ...)` sits inside
`defaultConfig` (the block spanning lines 56–76), which every product flavor inherits unless a flavor
overrides it. None of the three `productFlavors` blocks (`dev`, `staging`, `prod`) overrides or
removes it. **So the key compiles into staging and prod alike, not only into demo/dev builds**,
contradicted by H-11's residual note, which still frames the feature as at "demo status." Containment
today is a **runtime blank-key check** (`GeminiBrandLookupSource.kt:33`,
`if (BuildConfig.GEMINI_API_KEY.isBlank()) return null`), not a structural, build-time absence. Any
build — including a staging or prod build — whose `local.properties` happens to carry a key makes the
call live outside dev.

| | |
|---|---|
| **Current control** | Blank-key runtime check (`GeminiBrandLookupSource.kt:33`) plus H-11's existing controls: never throws/blocks the evaluate pipeline; only the generic drug name is sent, never patient identity/vitals/symptom text; key stored in git-ignored `local.properties`. |
| **Residual** | The runtime check is the *only* thing standing between "inert" and "live" in staging/prod. It depends on the properties file being empty in that build, which is an operational discipline, not a build-time guarantee — the ASR ban gets a structural, source-absent guarantee (`NoPlatformRecognizerSourceScanTest`, `TranscriptionPathHasNoNetworkDependencyTest`) and this path does not. |
| **Planned control** | Move `buildConfigField("String", "GEMINI_API_KEY", ...)` out of `defaultConfig` into the `dev` flavor block only, so the field does not exist in a staging/prod `BuildConfig` at all — structural absence rather than a runtime check. Add a test asserting the field is blank under staging and prod, in the shape of `RoutesSecurityTest.kt` (`app/src/test/java/com/example/samdapp/presentation/navigation/RoutesSecurityTest.kt`): a flavor-aware JUnit test reading `BuildConfig` directly, run via `testDevDebugUnitTest`/`testStagingDebugUnitTest`/`testProdDebugUnitTest`. **Trigger: lands in the housekeeping PR named in the amended perf audit's item 0. No date committed.** |
| **Severity / probability** | Unchanged from H-11 (Low / Med). This caveat corrects and updates the control text; it does not re-rate the hazard, following the H-15.C-series precedent of declining to invent a new figure on a caveat row. |

### H-27 — Unverified generative text rendered on a clinical artifact, no post-generation entity verifier

**Status: PROPOSED, AWAITING OPERATOR SIGN-OFF.** New id, next after H-26. Sits beside two existing
rows rather than standing alone: **H-17** (a PHC worker acts on an AI-generated drug and brand
recommendation no physician has reviewed) and **H-22/H-23** (the on-device SLM guardrail memo's
precedent that model-generated text needs a deterministic verification gate before it reaches a
clinical surface, and that zero controls may live inside the model itself). H-27 is the gap those two
rows leave uncovered: H-17 gates *when* an already-generated recommendation becomes visible to a
worker (physician review), not *whether the recommendation itself is true*; H-22/H-23 write the
verification-gate requirement for a different, not-yet-shipped model (the on-device SLM) and do not
reach a shipped path. Gemini brand lookup is a shipped generative path with neither kind of gate.

**Hazard / hazardous situation.** `GeminiBrandLookupSource.lookupTopIndianBrand` returns whatever two
non-blank, pipe-delimited strings the model produces, rendered next to
`EvaluateNlemTreatment.recommendedDrug` on the prescription surface
(`EvaluateReportOutput.topIndianBrand`, documented in the source's own KDoc as "for display next to
... **on the prescription**"). The only validation is a shape check:

```kotlin
val parts = rawText.split("|").map { it.trim() }
if (parts.size < 2 || parts[0].isBlank() || parts[1].isBlank()) { ... return null }
IndianBrandSuggestion(brandName = parts[0], companyName = parts[1])
```

Nothing confirms the brand exists, nothing confirms the manufacturer exists, nothing cross-checks the
pair against NLEM or any formulary, nothing checks that the returned brand actually contains the
generic drug that was asked about. This is a **false-attestation-shaped** hazard, not an
availability one: the harm is a confident, well-formatted, wrong brand/manufacturer pair presented to
a patient as if it were sourced fact.

**Potential harm.** A patient is dispensed, or seeks out, a brand that does not correspond to the
generic drug prescribed (hallucinated or mismatched brand/manufacturer), with no marker on the report
distinguishing this text as model-generated-and-unverified rather than formulary-sourced. Distinct
from H-17's harm (an unreviewed AI *diagnosis or treatment* reaching a worker): H-27's harm survives
physician review, because nothing in the review surface tells the reviewing doctor that this specific
field carries no verification at all — it reads exactly like the NLEM-sourced `recommendedDrug` next
to it.

| Sev | Prob | | |
|---|---|---|---|
| High | Not established | *(following H-15/H-17/H-18/H-26's precedent of declining to assert probability with no field data behind it)* | |

| | |
|---|---|
| **Current control** | None beyond the shape check quoted above. No entity verifier, no formulary cross-check, no drug-name cross-check. |
| **Residual** | Every call that returns two non-blank strings is rendered as fact. The architecture already has a precedent for the right shape of control — `SlmStreamSanitizer` (Stage 3a of the SLM track, `app/src/test/.../SlmStreamSanitizerTest.kt`) exists specifically to sit between generated text and what a worker sees — but this path is not routed through it and predates it (see §3 below). |
| **Planned control** | **Open action, no target date**, per the owner's stated position: removal is deferred until a deterministic medicines database pipeline exists, and that pipeline is what would replace the unverified lookup with a sourced one. This memo does not schedule it and does not invent a date. |

---

## 3. Deferral rationale, in the owner's own terms, recorded with a date

**2026-09-18 (audit close-out, amendment A1).** The owner reversed the removal decision the prior
draft of the audit recorded. The path stays because it supplies brand name and manufacturer for the
medicine the evaluate line names; the medicines themselves come from the NLEM database PDF, not from
Gemini. Removal is deferred until a deterministic medicines database pipeline exists to replace the
brand/manufacturer lookup; that pipeline is future work and carries no target date. This is recorded
here, with its date, so the retention reads as a considered acceptance made on 2026-09-18, not as a
gap nobody noticed.

---

## 4. Does any controlled document assert no generative model reaches the clinical path?

**No such assertion was found in `docs/`.** Checked specifically:

- `docs/regulatory-foundation.md` **discloses Gemini candidly**, three times (lines 185, 214, 245),
  including in its "mockup vs. production" boundary table (line 214) and its gap list (line 185: "the
  clinical kernel ... is now real HTTP inference ... **plus a Gemini API call for brand lookup**").
  Nothing there claims the clinical path is generative-model-free.
- `docs/requirements/intended-use-statement.md:78` states "no network call on the transcription
  path" — this claim is explicitly **scoped to the ASR/transcription path only** ("Recognition is
  **on-device**: ... a vendored sherpa-onnx / Parakeet int8 model runs locally, with no network call
  on the transcription path"), a different feature and a different path from brand lookup. It does
  not generalize to "no generative model anywhere in the app" and is not contradicted by Gemini's
  existence.
- Broader searches across `docs/` and `agent_docs/` for "no external AI," "fully offline," "no
  generative," "no cloud model," and similar absolute framing returned no hits that assert an
  app-wide or clinical-path-wide absence of generative or third-party AI.

**That is itself an answer:** the existing controlled documentation is already honest about Gemini's
presence. The gap this memo closes is that the *risk file* doesn't fully carry what those other
documents already disclose, not that some other document falsely claims Gemini doesn't exist.

---

## 5. Git archaeology: does the Gemini path predate the locked architecture rule?

```
GeminiBrandLookupSource.kt, first landed:
  036642e  2026-07-24  feat: /api/v1/evaluate endpoint integration + urgency label clarity

The two "generated text needs a verification gate before a clinical surface" precedents
in this repo, both landed after Gemini:

  NoPlatformRecognizerSourceScanTest (ASR structural-absence egress proof):
    9a33d51  2026-09-02  test(asr): source-level absence scan for the platform recognizer

  TranscriptionPathHasNoNetworkDependencyTest (ASR egress proof, network half):
    3f349a1  2026-09-02  test(asr): assert no network dependency reaches the transcription path

  SlmStreamSanitizer (the concrete embodiment of "verify generated text before a clinical
  surface renders it," SLM track, H-22/H-23's design-stage precedent):
    eab3d01  2026-09-09  feat(slm): the stream sanitizer, control-token stripping on the
                          token stream
```

**Gemini's brand-lookup path landed 2026-07-24. Every instance in this repository of the pattern
this memo is now asking Gemini to be held to — structural egress absence, or a deterministic
verification gate on generated text before it reaches a clinical surface — landed 5 to 7 weeks
later, on 2026-09-02 and 2026-09-09.**

**Reading of that gap.** This is a pre-existing item the rule was written to catch, not a violation
of a rule that existed when the code landed. Nothing in the 2026-07-24 commit or its surrounding
history suggests the ASR/SLM verification pattern existed yet in any form for Gemini's authors to
have skipped. The corrective posture that follows is the one already reflected in H-11.C1 and H-27
above: bring the pre-existing path under the pattern now that it exists, not treat its original
landing as a process failure.

---

STOP: hazard entry drafted, nothing changed in the risk file or in source. Needs owner review and an
id assignment.
