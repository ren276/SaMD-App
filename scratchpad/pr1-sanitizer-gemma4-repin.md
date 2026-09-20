# PR-1: model identity and the sanitizer fail-open

**Status:** code change, uncommitted. Four pre-existing uncommitted changes in this tree are
untouched, as are the two PR-0 lines in `docs/product-and-development-report.md`.
`docs/quality/risk-management-file.md` was not edited: H-11.C1, H-27 and the drafted H-28 remain
PROPOSED.

**Date:** 2026-09-18
**Tree:** SaMDApp @ `a219da9`, main machine, authoritative.
**Owner decisions applied:** topology (A); served artifact `google/gemma-4-E2B-it`; first build
WORKER tier only.

---

## 1. Where the numbers came from

**(MEASURED)** The tokenizer derivation ran on the GPU machine and arrived here as
`scratchpad/gpu-control-token-handoff.md`. Its pin:

| Item | Value |
|---|---|
| Artifact | `google/gemma-4-E2B-it` |
| Revision | `3e22461f65e89153144f8adb70e3b8c2cc9845a7` |
| `tokenizer.json` SHA-256 | `cc8d3a0ce36466ccc1278bf987df5f71db1719b9ca6b4118264f45cb627bfe0f` |

**(MEASURED)** Serving repo at `be6cc049fdb6de9a87d5adc8f8e0287fca269061` = `be6cc04`, unmoved from
the 2026-09-09 audit. Working tree carried three `.idea/` entries only.
`models/gemma-4-E2B-pharma-merged/` still absent. **Recorded as owner-attested per D4 for the
"unchanged since the audit" claim; the three checks themselves were run and reported.**

**(MEASURED)** The handoff's step-3 literal comparison was read from a non-authoritative clone. It
was re-derived here against the real `SlmStreamSanitizer.kt`, which is **319 lines, SHA-256
`d6dfdd93f37aa97f25c7f7834b4f65c3cea75ecd2ed3905e934a1246752f1ce0`**, byte-identical to the clone's.
The clone therefore read identical bytes and its counts stand:

| | count |
|---|---|
| Old set, distinct literals | 6,253 |
| Present in Gemma 4 | 6,233 |
| Absent from Gemma 4 | 20 |

The 20: `<unused6227>` through `<unused6241>` (15), plus `<start_of_turn>`, `<end_of_turn>`,
`<start_of_image>`, `<end_of_image>`, `<image_soft_token>` (5).

**(MEASURED) One gap in the input, stated because it shaped a decision.** The handoff says "24
added special tokens; full id table is in the handoff", but the file received is the handoff and
the table is not in it. Twenty of the 24 are recoverable by name from the handoff body plus the
audit's sections 2.2 and 2.3. The remaining four are the image and audio boundary markers
(begin/end of image, begin/end of audio). **Their exact spellings were not guessed.** Writing a
plausible-looking literal into a safety control's match set on the strength of a naming convention
is the failure this PR exists to prevent. They are covered by the fail-closed catch-all instead, and
completing the set is a one-line change once the spellings are read out of `tokenizer.json`.

---

## 2. What the real leak was

**(MEASURED)** The previous delimiters were `THOUGHT_OPEN = "<unused94>"` and
`THOUGHT_CLOSE = "<unused95>"`, verified by stage 3b-0 against `google/medgemma-1.5-4b-it`, where
they carry channel meaning at ids 100 and 101.

**In Gemma 4 those two literals still exist**, at ids 256006 and 256007, as ordinary reserved tokens
with **no channel meaning**. This is the whole finding, and it is worse than a simple mismatch:

- Membership **succeeded**. `<unused94>` is inside `(0..6241)`, so the set contained it.
- Nothing failed closed. There was no unknown token to trip the fail-closed rule.
- The live channel, `<|channel>` and `<channel|>` at ids 100 and 101, was **absent from the set**,
  so it was never matched and the whole span reached the visible stream: both delimiters, the
  plain-text channel name `thought`, and every character of reasoning between them.
- Every counter stayed at zero. `SlmSuppression` read `NONE`.

**(MEASURED, by running it.)** The old suite would have stayed green throughout. All 23 tests drove
hand-built streams containing `<unused94>`, and the one test that pinned the vocabulary,
`the reserved range covers both disjoint id blocks in full`, asserted the MedGemma range and size.
Nothing anywhere fed the suite Gemma 4 text. The memo's inference is **confirmed**.

**(MEASURED) One test did catch it, from a stage earlier and for a different reason.**
`SlmReadbackUseCaseTest.the seam strips the thought channel before the output gate sees the text`
threw `ClassCastException` on the first run after the re-pin. Its span names `ibuprofen`; with the
delimiters dropped as standalone tokens and the reasoning passed through, `ibuprofen` reached the
grounding gate, the readback was refused as `OUTPUT_NOT_GROUNDED`, and the cast to `Answer` failed.
That is the leak reproduced end to end by an assertion written to prove pipeline ordering.

**The sentence worth carrying forward:** a zero suppression count was previously ambiguous between
"nothing to strip" and "wrong vocabulary". Those two readings are now separated, by the unknown
counter and by the identity gate.

---

## 3. What changed in the parse

This was a grammar change, not a string swap.

| | Before (MedGemma) | After (Gemma 4) |
|---|---|---|
| Channel | `<unused94>` ... `<unused95>`, unnamed | `<|channel>thought\n` ... `<channel|>`, **named** |
| Channel name | not a thing | plain text after the opener, inside the span |
| Enable flag | none | `<|think|>` (98), **unpaired**, a plain literal |
| `<unused*>` range | `0..6241`, two id blocks | `0..6226`, six id blocks |
| Named literals | 11 | 21 |
| Set size | 6,253 | 6,248 |
| Longest literal | 12 (`<unused6241>`, `[multimodal]`) | 16 (`<|tool_response>`) |

**Why a two-string swap would not have been enough.** The channel name arrives as plain text
immediately after the opener. A fix that replaced only the two delimiter constants would have
stripped `<|channel>` and `<channel|>` and left `thought` and the reasoning after it visible: the
same leak through a smaller hole. The span rule suppresses everything between the delimiters
inclusive, which carries the name out without needing to know what names exist.

**`<|think|>` is not a delimiter.** Treating it as an opener would start a span that never closes,
and the fail-closed unterminated-span rule would then swallow the entire answer. It is an ordinary
literal, dropped like any other standalone control token, and a test asserts `spans == 0` for it.

**The 20 absent literals were removed, not left in.** They cost nothing at runtime, and that is
exactly the problem: a literal that cannot match implies a coverage the set does not have. A test
asserts the five named ones are absent, so re-adding one is a visible failure.

---

## 4. The two new controls, and what each protects against

### (a) The model identity gate. The durable one.

`SANITIZER_TARGET_MODEL_ID`, `SANITIZER_TOKENIZER_REVISION` and `SANITIZER_TOKENIZER_SHA256` are
declared next to the vocabulary. `servedModelMatchesSanitizer(servedModelId: String?)` compares the
pin against the `model_id` of the response envelope specified in
`scratchpad/slm-remote-inference-memo.md` §2.2. The seam refuses the whole readback on mismatch,
with a new `SlmRefusal.SERVED_MODEL_MISMATCH`.

- **A null or blank identity is a mismatch, not a free pass.** An envelope that has not implemented
  the field is the likeliest early-integration state and is indistinguishable from a server quietly
  pointed at a different artifact. Tested for `null`, `""` and `"   "`.
- **Comparison is exact.** Not prefix, not contains, not case-insensitive.
- **Placed before the grounding gate**, because grounding cannot compensate: an unrecognized control
  construct is neither a drug name nor a numeral, so it is "grounded" by construction and would be
  displayed.
- **Tier-blind**, like the sanitizer. A foreign control grammar is not an answer for a physician
  either. Asserted explicitly, since every other output-side control on this path is tier-scoped.
- `SlmEngine` gained `servedModelId(): String?`, read after collection completes. Under §4.1's
  non-streaming transport a binding does one call, holds the envelope, emits the body as one chunk,
  so there is no second round trip. Its KDoc forbids a binding from substituting the configured or
  requested id, which would make the comparison agree with itself.

**What (a) protects against:** the next model swap. Re-pinning a literal set fixes today's artifact;
comparing the pin against what the server actually served is what makes the next change loud.

### (c) The fail-closed catch-all. The one that covers what nobody anticipated.

Any run matching `isControlTokenShaped` that is **not** in `CONTROL_TOKENS` is suppressed and
counted in a new `SlmSuppression.unknownTokens`, separate from `droppedTokens`.

**What (c) protects against:** membership alone is fail-open by construction. A construct the set
does not name simply does not match and is rendered. That is precisely how `<|channel>` would have
reached a clinician. It also covers the four media-boundary tokens whose spellings are missing from
the handoff.

**Why clinical prose survives, and this was tested rather than assumed.** `BP <120/80` and
`sat <95%` have no closer at all. `eGFR <60 and >90` and `temp <38.5 and pulse >92` have whitespace
inside the run. Five such strings are asserted to pass through with `SlmSuppression.NONE`.

---

## 5. Two things the tests changed my mind about

Both were caught by a red test, not by review. Recording them because the reasoning is the useful
part.

**5.1 The catch-all had to be narrowed to angle brackets.** The first implementation probed `<` and
`[` alike, and turned `ordinary square brackets in clinical text are untouched` red: `See note [2]`
satisfies the shape rule exactly, so a footnote reference was deleted out of clinical prose. The
trade is asymmetric and the asymmetry is in the vocabulary. This artifact has **one**
square-bracket control token, `[multimodal]`, and it is known by name, while the entire generative
control grammar (`<|channel>`, `<|turn>`, `<|tool*>`, the media placeholders) is angle-bracketed. So
the unknown-construct risk lives behind `<` and the prose-collision risk lives behind `[`.
Suppressing on shape is only defensible where the shape is not also ordinary writing. **Residual:**
an unknown square-bracket control token would be rendered; the identity gate is the layer that
catches vocabulary drift.

**5.2 The catch-all has a length ceiling, and it cannot simply be raised.** The unknown scan is
bounded by `MAX_CONTROL_TOKEN_LENGTH`, currently 16. `<image_soft_token>` is 18 characters, so it is
past the bound and is rendered. The bound is the same number as the hold-back window on purpose: a
construct longer than the window can straddle a chunk boundary and be partly emitted before it is
recognised, which is the retraction leak the window exists to prevent. The two move together or not
at all. A test asserts the gap directly rather than leaving it implicit.

---

## 6. Tests

**(MEASURED)** Full suite `testDevDebugUnitTest --rerun-tasks`: **558 tests, 0 failures, 0 errors,
0 skipped.**

| Class | Before | After |
|---|---|---|
| `SlmStreamSanitizerTest` | 23 | 32 |
| `SlmReadbackUseCaseTest` | 28 | 32 |
| `ApprovedRecordReaderTest` | 10 | 10 |
| `SlmEngineIsUnreachableFromPresentationTest` | 3 | 3 |

Every pre-existing test was re-pointed, none deleted, each carrying a note naming the MedGemma-era
literal it used to drive and why the swap was not cosmetic.

New: the Gemma 4 channel span end to end; the plain-text channel name suppressed with its span; the
unpaired think flag not opening a span; an absent literal suppressed by the catch-all; known and
unknown counters kept separate; five clinical measurements surviving the catch-all; the catch-all's
length ceiling; the five removed literals asserted absent; the pin constants; the identity
comparison's truth table; mismatch, missing and blank identity refusals at the seam; the identity
gate applying to the physician tier; and the matching case, so the refusal tests are not vacuous.

**Mutation checks, five, all confirmed red (MEASURED):**

| # | Mutation | Result |
|---|---|---|
| M1 | Delimiters reverted to `<unused94>`/`<unused95>` | RED |
| M2 | A null served identity becomes a free pass | RED |
| M3 | Identity check removed from the seam | RED |
| M4 | The MedGemma `(0..6241)` range restored | RED |
| M5 | Fail-closed catch-all disabled | RED |

Both files were restored byte-identical after the run and the full suite re-run green.

---

## 7. Open items

1. **Four token spellings.** The image and audio boundary markers are not in the set. Read them out
   of `tokenizer.json` and add them. Until then a non-zero `unknownTokens` on a multimodal
   generation is expected and correct.
2. **The channel-name assumption.** Suppressing every named channel assumes the model's answer is
   not itself carried inside one. That holds for this artifact: the chat template emits the answer
   as plain content of the model turn and uses the channel only for thought. If a future export
   moved the answer into a named channel, this rule would suppress it, and the detector is `spans`
   non-zero while visible output is empty. Not currently asserted anywhere.
3. **The catch-all ceiling** (5.2). Upgrade path is to drive both the window and the scan bound from
   a declared maximum rather than from the longest known literal. Costs a larger hold-back on every
   chunk. Not worth it until a real vocabulary needs it.
4. **`SlmSuppression.unknownTokens` has no audit row yet.** It is computed and carried on the answer;
   the recording belongs to PR-2's audit work, with the backend enum mirror in the same commit.
5. **Bookkeeping, no downstream effect.** Two GPU sessions reported the SaMDApp clone at different
   revisions, `8fb5257` in the brief and `a219da9` in the handoff. The authoritative tree here is
   `a219da9`, the clone's `SlmStreamSanitizer.kt` was byte-identical to this one, and no line number
   from the clone is cited in this PR. Unresolved, and it changes nothing that was derived.
6. **H-22's mitigation column** still reads "Design-stage only, no code exists", which was accurate
   for the engine and is now slightly behind for the guardrail seam. A controlled-docs pass, not
   this PR's to make.

---

## 8. What is not in PR-1

No transport, no OkHttp client, no Retrofit interface, no endpoint, no backend route. No `SlmEngine`
implementation in any flavor, including a dev stub. No `SLM_READBACK_ENABLED` flag. No UI. No change
to `ApprovedRecordSnapshot`'s five-field whitelist. No change to `SlmScopeGate`. No risk-file edit.
Nothing touching the physician tier or the `openTier` branches: the identity gate sits outside them
and applies to every tier.
