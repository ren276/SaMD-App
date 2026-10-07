# PR-4: the backend/core SLM readback proxy

**Status:** code change, committed on `feat/slm-backend-proxy`. The four pre-existing uncommitted
changes are untouched and still dirty. No credential file was opened. `docs/quality/risk-management-file.md`
was not edited; H-28 stays drafted in `docs/design/slm-remote-inference-memo.md` section 1.6.

**Date:** 2026-09-20.
**Tree:** SaMDApp on `feat/slm-backend-proxy`, off `feat/audit-remediation` (10 commits) off `a219da9`.
**Settled and applied, not redesigned:** topology (A), device to backend/core to the SLM container,
permanently. Stock `google/gemma-4-E2B-it`, no fine-tune. WORKER tier only. Built to
`docs/backend/slm-service-contract.md`.

Every backend test run in this session used `ABDM_MODE=stub` on the command line. MEASURED =
observed from a run in this session. INFERRED = read from source, not executed.

---

## 1. The PHI guard, and what it cannot do

### 1.1 Does the existing denylist cover this payload? No. MEASURED.

`assert_no_identity_fields` (`app/adapters/kernel/phi_guard.py:64`) is `DENYLIST & payload.keys()`:
a set intersection against the TOP-LEVEL KEYS of a parsed body, 28 denied names, no recursion into
nested values and no inspection of any value at all.

The readback outbound body's keys are `prompt`, `model_id`, `prompt_template_version`,
`max_tokens`, `temperature`, `do_sample`, `seed`, `stop`. **None of the eight is on the denylist,
so on this payload shape the guard cannot fire, ever.** MEASURED by reading both artifacts and
confirmed by the fact that every green test in `tests/test_slm.py` passes through it untouched.

It is still called, on the INBOUND body, for the kernel's own stated reason: `extra="forbid"`
rejects a field that is not declared, and the denylist is what catches a future edit that declares
a denied name AS a field. It guards the next edit, not this payload. That is written into
`app/adapters/slm/phi_guard.py`'s module docstring so the next reader does not mistake its
presence for coverage.

### 1.2 What actually crosses, and what a guard can and cannot catch in free text

One string. The prompt, assembled on the device from the five whitelisted `ApprovedRecordSnapshot`
fields plus the worker's question (memo section 1.2, MEASURED there): a physician's free-text
working diagnosis, the prescription lines (generic, brand, strength, route, frequency, duration,
quantity), the three-valued `kernelDecision`, the referral bit, the question.

| Control | Catches | Cannot catch |
|---|---|---|
| `extra="forbid"` | an undeclared field | anything inside the prompt |
| the 28-name denylist | a future edit declaring a denied name | anything on today's payload (1.1) |
| case-id absence check | the real `case_record_id` in the prompt text | an id the device rewrote or split |
| identifier digit-run check | Aadhaar (12), ABHA (14), mobile (10), contiguous or in the groups they are written in | a name, a village, a relative's name, a landmark, a spelled-out number |

**Stated plainly, because a denied key list is not sufficient reasoning on this hop: no server-side
guard can keep a patient's name out of a physician's free-text diagnosis.** The diagnosis field is
`prescription?.diagnosis` verbatim (memo 1.2), nothing strips or validates it at any layer, and a
physician can type anything into it. The same is true of the worker's question, which is bounded
at 500 characters and filtered for scope on the device, not for content.

**Residual risk.** This is H-28's subject and PR-4 does not close it. What PR-4 does provide: the
narrative crosses exactly one hop, the hop is authenticated, the hop is bounded by a timeout and a
breaker, and every crossing leaves a row naming who, which facility, which case, which template and
which model, with no text. Two things would reduce the residual further and are NOT built: a
device-side content check before assembly, and a physician-facing warning on the diagnosis field.
Neither is in this PR's scope and neither is implied by it.

### 1.3 The digit-run rule, and why it is narrow

A maximal chain of digit groups of three or more, joined by single spaces or hyphens, totalling ten
or more digits. `1234 5678 9012` is one chain of twelve; `2026-09-12 38.5` is not a chain at all,
because `09`, `12` and `38` are two digits and break it.

The narrowness is the design. A broader heuristic over clinical text refuses legitimate readbacks,
and a guard a worker learns to route around is worse than no guard. Four ordinary clinical strings
(a date beside a temperature, a dose line, a vitals line, a quantity) are pinned as MUST PASS, and
mutation M13 proves that pin has teeth: loosening the rule to two-digit groups, the naive version
anyone would write first, turns `Fever since 2026-09-12 38.5 C` into a refusal.

### 1.4 No pseudonym substitution, and why that is the contract's doing

The kernel proxy substitutes `HMAC(case_record_id)` for the outbound `case_token`. **This hop has
no such step, because the service's request schema has no identifier field and rejects unknown
fields** (contract section 2.1). There is nothing to substitute into. The correct state is the
ABSENCE of the id from the wire, which is stronger than a replacement, and step 2 enforces the
absence rather than trusting the device's promise not to interpolate it (memo 1.2 measured that it
does not; the guard means a future device change cannot quietly start).

The HMAC token is still computed and stored on the log row, so an SLM row and a kernel row for the
same case correlate by a token an operator can recompute.

---

## 2. The deadlock. MEASURED, for every reachable path.

S-5 measured the hang: `audit.append` takes `pg_advisory_xact_lock(facility)`, transaction scoped;
a request session holding it plus a later `write_out_of_band` audit append for the same facility
blocks forever. Not an error, a hang, with no statement timeout to break it. S-5b corrected the
docstring and swept the backend for other sites, finding zero.

**This proxy does not become the second one. Every reachable path, and what it does on the caller's
session:**

| # | Path | In-request DB work | Audit write |
|---|---|---|---|
| 1 | hop not configured (no shared secret) | none | out of band |
| 2 | case not found, or in another facility | one SELECT on `case_records` | out of band |
| 3 | PHI guard trips (denied key, case id in prompt, digit run) | that SELECT | out of band |
| 4 | circuit open | that SELECT | out of band |
| 5 | connect error / connect timeout | that SELECT | out of band |
| 6 | read timeout | that SELECT | out of band |
| 7 | other `httpx.HTTPError` | that SELECT | out of band |
| 8 | `503` NOT_LOADED | that SELECT | out of band |
| 9 | `503` QUEUE_FULL | that SELECT | out of band |
| 10 | `503` unrecognised code | that SELECT | out of band |
| 11 | `500` / `504` | that SELECT | out of band |
| 12 | `4xx` | that SELECT | out of band |
| 13 | unparseable body | that SELECT | out of band |
| 14 | envelope missing a required field | that SELECT | out of band |
| 15 | `200`, complete | that SELECT | out of band |
| 16 | `200`, truncated | that SELECT | out of band |
| 17 | a defect in the proxy itself | that SELECT | out of band |

**Zero in-request audit appends on any of the seventeen.** The only thing the service does with the
caller's session is the `SELECT` on `case_records`, which takes no advisory lock. The route sets
`request.state.audit_action`, which suppresses the audit middleware's fallback row; that write is a
separate session and in any case runs after the handler returns.

**Proved three ways, not by reading:**

1. **End to end, on a failure path, with a timeout.** `test_failure_path_records_survive_the_request_rollback_without_hanging`
   drives a `500` through the real route and the real database. Every request in the suite goes
   through a helper that wraps it in `asyncio.wait_for(..., timeout=30)`, so the advisory-lock
   deadlock would FAIL the test rather than hang the suite: a hung test that never finishes is not
   a red test. The rows are then read back on a FRESH session, after the request's own transaction
   has rolled back, which is only true if they were committed out of band.
2. **Structurally, against the parsed artifact.** `test_audit_appends_only_out_of_band` parses
   `app/services/slm.py` with `ast` and asserts that every `audit_service.append` call site's
   enclosing function is `_write`, the closure `write_out_of_band` runs. Not a grep: a grep for
   "write_out_of_band" would match the module docstring. It also asserts at least one append
   exists, so a rename cannot make it pass vacuously.
3. **By mutation.** M7 (write on a session that never commits, exactly what a request rollback
   does) and M8 (an append outside the closure) are both RED.

---

## 3. QUEUE_FULL, and the mirror

Added to `SlmCallOutcome` (`app/models/enums.py`), to the `slm_call_log` CHECK constraint
(migration `0008`), and the device-side `SlmRefusal.ENGINE_UNAVAILABLE` KDoc was widened to name
saturation, **both sides in this commit**. The mirror coupling has caught this project twice and
neither half is worth landing alone.

The device gains no new refusal value, and that is deliberate: circuit open, model not loaded and
queue full are three server states with three different operator responses and ONE worker action,
"retryable, but not immediately". Splitting one action across three refusals would be the mirror of
the defect this taxonomy exists to remove.

**One decision the contract does not make, made here and recorded:** a `QUEUE_FULL` does NOT count
toward the circuit breaker. A loaded, healthy, saturated service answering immediately with a
`Retry-After` is backpressure, not an outage, and opening the circuit on two of them would convert
a one-second retry into a thirty-second outage for every other worker in the PHC. Same reasoning as
the kernel proxy's 4xx rule, which is cited next to the code. Both exemptions are pinned by tests
and both mutations (M1, M2) are RED.

---

## 4. What the contract got wrong, or did not say

Four findings. None of them blocked the build, so none triggered the "say so and stop" clause, but
PR-5 needs all four.

1. **The generation endpoint has no path.** Contract section 2 specifies the request, section 3 the
   response, section 5.1 specifies `GET /health`, and **nothing anywhere names the URL the
   generation request is posted to.** MEASURED by reading all 460 lines. PR-4 chose
   `POST /v1/generate` (`app/adapters/slm/client.py:GENERATE_PATH`, named as a constant precisely
   so the two sides cannot drift silently). **PR-5 must serve that path or this changes.**
2. **Sections 3.1 and 4.2 disagree about an absent `finish_reason`.** 3.1: "Every field is required
   on a `200`", which makes an absent one a malformed envelope. 4.2: "`200`, `finish_reason`
   `length` or `error`, **or absent** -> `TRUNCATED`". Resolved toward 4.2's explicit mapping row,
   because it is the table that exists to be exhaustive, and because both readings refuse the text
   downstream either way. Pinned by `test_absent_finish_reason_is_truncated_not_success`, whose
   docstring records the tension. Every OTHER missing required field is `MALFORMED_RESPONSE`.
3. **Section 4.2's vocabulary has no value for "rejected before a call, for a reason that is not
   PHI".** Two such paths exist here: the hop is not configured, and the case does not resolve.
   Rather than add enum values the contract's own totality rule would then have to absorb,
   `slm_call_log.outcome` is NULLABLE and NULL means "there was no call; `error_code` says why".
   `WHERE outcome IS NULL` is the operator's query for requests this backend refused to send, which
   is also the cross-facility probe query. Every non-NULL value is still held to the vocabulary by
   the CHECK, verified by inserting a junk value against the real database (rejected,
   `CheckViolationError`) and a NULL (accepted).
4. **Section 4.4 defers the auth mechanism to the service, which is the wrong side to defer to.**
   It says the mechanism "belongs with the service that has to enforce it". The caller has to pick a
   header and a setting before the callee can check anything, so PR-4 picked: header
   `X-SLM-Service-Token`, setting `SLM_SERVICE_TOKEN`. The value was never read or printed. PR-5
   implements the matching check.

**Not a contract error, but worth recording:** the contract's section 4.3 timeout table is the
backend row this PR implements (connect 5 s, read 50 s), and its own correction of the device's
read bound to 55 s is carried in section 11.5 of `api-contract.md` rather than in device code,
because no device transport exists yet (PR-6).

---

## 5. Mutation checks. 20 mutations, 20 RED, all restored byte-identical.

Snapshot and restore, never `git checkout`, with a sha256 comparison after each restore. An empty
test selection is treated as RED, never as a pass. Harness:
`scratchpad/mutate.py` equivalent kept in the session scratchpad, not committed.

| # | Mutation | Test that caught it | Result |
|---|---|---|---|
| M1 | QUEUE_FULL counts toward the breaker | `...does_not_open_on_a_rejected_payload_or_on_backpressure[queue_full]` | RED |
| M2 | a 4xx counts toward the breaker | same test, `[payload_rejected]` | RED |
| M3 | the client retries a 5xx once | `test_no_retry_on_failure` | RED |
| M4 | the outbound hop loses its shared-secret header | `..._is_authenticated_and_carries_the_fixed_decode_parameters` | RED |
| M5 | an unconfigured hop proceeds | `test_an_unconfigured_hop_fails_closed` | RED |
| M6 | a resolve failure raises without recording (the kernel's 404 gap, reintroduced) | `test_unknown_case_...still_leaves_an_audit_row`, `..._another_facility_...` | RED (2 failed) |
| M7 | rows written on a session that never commits | `test_failure_path_records_survive_the_request_rollback_without_hanging` | RED |
| M8 | an audit append outside the out-of-band closure | `test_audit_appends_only_out_of_band` | RED |
| M9 | `finish_reason: length` treated as complete | `test_truncated_is_relayed_...` | RED |
| M10 | `model_sha256` no longer required | `...map_and_write_exactly_one_row_pair[blank_model_sha256]` | RED |
| M11 | the case-id check is removed from the guard | `test_case_identifier_in_the_prompt_is_rejected_...` | RED |
| M12 | digit threshold raised to 15 | `test_identifier_shaped_digit_runs_...` | RED (4 failed) |
| M13 | digit chain accepts two-digit groups (the naive version) | `test_ordinary_clinical_numbers_are_not_refused` | RED |
| M14 | the resolve-failure row claims a call outcome | `test_unknown_case_...` | RED |
| M15 | `QUEUE_FULL` removed from the vocabulary | `test_every_outcome_in_the_vocabulary_is_exercised` (collection error) | RED |
| M16 | the prompt guard is not called at all | both prompt tests | RED (5 failed) |
| M17 | the audit payload carries the generated text | `test_no_row_and_no_audit_payload_carries_the_text` | RED |
| M18 | the bug path is not recorded | `test_an_unexpected_exception_still_leaves_both_rows` | RED |
| M19 | the audit row is written twice | `test_success_returns_the_envelope_and_writes_one_row_pair` | RED |
| M20 | the read budget widens to 60 s, equal to the device's whole-call bound | `test_timeout_budget_matches_the_contract` | RED |

**The two shapes watched for specifically.** The S-2 shape (asserting against a fake that
re-implements the policy): `ScriptedSlm` answers canned bytes and makes no decisions, and every
behavioural assertion reads the real `slm_call_log` row, the real `audit_events` row or the real
outbound `httpx.Request`. The test client is built by the REAL `build_slm_client`, through a new
`transport=` parameter, so M4 (header removed) is red; a suite that assembled its own client could
not notice that. The S-3 shape (a needle that is not unique): the one artifact-reading guard parses
with `ast` and checks the enclosing function, and asserts non-emptiness so it cannot pass
vacuously.

---

## 6. What a test caught that reasoning did not

**The audit middleware does not cover the bug path, and my first design assumed it did.** The route
originally set `request.state.audit_action` AFTER the service call, reasoning that an unexpected
exception would then leave the flag unset and let `AuditMiddleware`'s fallback row record that the
request happened. The test written to prove that failed immediately. MEASURED from the traceback:
Starlette's `ServerErrorMiddleware` sits OUTSIDE `AuditMiddleware`, so an unhandled exception
propagates through the audit middleware's `await self.app(...)` and its post-response write never
runs at all. The fallback row does not exist on that path, for this route or any other.

Fixed by making the service record the bug path itself: `readback` wraps the real work, re-raises a
`SamdError` (already recorded by `_fail`), and on any other exception writes the pair with
`outcome` NULL and `SAMD-SYS-9005` before re-raising. The route went back to the kernel's simpler
ordering. M18 pins it.

**A near miss, caught while writing rather than by a test, recorded because it is the same family:**
the first version of the `ast` guard built its enclosing-function map with `setdefault`. `ast.walk`
is breadth first, so the OUTER function is visited first and `setdefault` records `_record` rather
than `_write` for every node. The guard would have asserted the wrong thing and still passed. It
uses plain assignment now, with the reason in a comment.

---

## 7. What is NOT in PR-4

No `SlmEngine` implementation, no device-side transport (PR-6). No `SLM_READBACK_ENABLED` flag and
no UI (PR-7). No SLM service (PR-5). No change to `ApprovedRecordSnapshot`'s five-field whitelist,
none to `SlmScopeGate`, nothing touching the physician tier or the `openTier` branches, no change to
the sync path. The kernel proxy's own 404 audit-row gap is NOT fixed here; it stays standalone.

**One thing deliberately not built, recorded rather than assumed away:** the route carries no role
guard, matching the kernel routes. The WORKER-tier-only decision lives on the device in
`SlmScopeGate`, and the server cannot tell a worker-tier prompt from a physician-tier one because
both are one assembled string. Every row records the caller's role, so a call from any role is
attributable after the fact, but attribution is not prevention. A server-side cadre gate is an
owner decision, not an omission to be filled in silently.

---

## 8. Verification

- **Backend suite, `ABDM_MODE=stub` on the command line: 330 passed, 0 failed.** Baseline before
  this PR, same command: 287 passed, 0 failed. The 43 new tests are `tests/test_slm.py`. No
  pre-existing failures existed and none was introduced.
- **Kotlin `testDevDebugUnitTest`: 647 tests, 0 failures, 0 errors, 98 result XML files.** The
  count of result files is checked because an empty results directory reads as success otherwise.
- `ruff check app tests`: clean except one pre-existing E501 at `app/db/session.py:103`, which came
  in with the S-5b docstring and is untouched by this PR (`git diff HEAD -- app/db/session.py` is
  empty). `ruff format --check`: clean. `mypy app`: clean, 72 files.
- **Alembic from an empty database:** `0001` through `0008` applied, then `alembic check` reported
  "No new upgrade operations detected", so the model and the migration agree. Against that database:
  a row with `outcome` NULL inserts, a row with a junk outcome raises `CheckViolationError`.

## 9. Carry forward, not acted on

Two Room migrations unrun for want of a device, and they are a merge gate. No corrective edit path
exists for a rejected record. Five hazard items pending sign-off. The kernel proxy's 404 audit-row
gap, standalone. The `strings.xml` fork. The one-time re-push of pre-existing FAILED rows,
owner-gated and unblocked. Whether Gemma 4 E2B can run on the target phone beside 692 MB of
resident ASR weights is unmeasured and no plan should assume it. PR-5 owes the service at
`POST /v1/generate` with the `X-SLM-Service-Token` check, and the first latency measurement that
confirms 12.8 tokens per second is achievable inside the 40 s cap.
