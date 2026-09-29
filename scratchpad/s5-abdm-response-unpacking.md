# S-5: ABDM response unpacking in `verify_otp`

Tree at a219da9 plus the pre-existing uncommitted changes. Nothing committed. No ABDM gateway
contacted, no live tests run, no credential file opened, risk-management-file.md untouched.

Every claim below is tagged MEASURED (observed from a run in this session) or INFERRED (read from
source, not executed).

---

## 0. Environment finding, before anything else

MEASURED. `backend/core/tests/test_abha.py` at HEAD fails 13 of 19 in this working copy, and the
failures are not merely "missing credentials". `app.config.Settings` loads `env_file=".env"`
relative to the process CWD, and the local `.env` sets a non-stub `abdm_mode`. The suite therefore
makes **real outbound requests to `https://dev.abdm.gov.in/api/hiecm/gateway/v3/sessions`**, which
answer without an `accessToken`, producing `KeyError: 'accessToken'` and a 502 on every test that
walks the happy path.

Observed once, then stopped. Every run in this session after that used `ABDM_MODE=stub`, which is
what the suite's own module docstring says it assumes ("ABDM_MODE=stub is the default ... never
overridden here"). With it:

    cd backend/core && ABDM_MODE=stub ./.venv/bin/python -m pytest tests/test_abha.py -q
    19 passed        # baseline, before any change in this session

The `.env` was never opened. The mode was overridden by environment variable, which pydantic
ranks above the dotenv file. Worth raising separately: a suite that silently retargets itself at
a live government gateway when a developer's `.env` says live is a hazard independent of this PR.

---

## 1. STEP 1: what actually happened before the fix. MEASURED.

The diagnosis left open what happens to `txn.state = OTP_VERIFIED` (:355), its `session.flush()`
(:356) and the `ABHA_OTP_VERIFIED` audit row (:357) when the unpack at :359 raises. Measured with
a throwaway probe test (live mode, `httpx.MockTransport`, `enrol/byAadhaar` answering HTTP 200
with `tokens` present and `ABHAProfile` absent), catching the escaping exception and then reading
the committed row and the audit table on a fresh session. Probe deleted afterwards.

    PROBE raised:                        KeyError 'ABHAProfile'
    PROBE txn.state:                     OTP_REQUESTED
    PROBE txn.last_error_code:           None
    PROBE txn.abha_number:               None
    PROBE txn.external_token_encrypted:  None
    PROBE audit rows for this session:   abha_session_started, abha_identity_submitted
                                         (no abha_otp_verified, no abha_session_failed)

**Answer: both roll back.** `_audit` writes through the request session (`service.py` `_audit` ->
`audit_service.append(session, ...)`), not through `write_out_of_band`, unlike `_fail`. So
`session_scope`'s rollback takes the state change and the audit row together.

This is the second of the two wrong outcomes the brief named, and it is worse than "an audit row
asserting a verification that did not complete" in one specific way. There is no surviving
falsehood, but there is no surviving truth either:

- The transaction sits at `OTP_REQUESTED`, **byte-for-byte identical to a session where the worker
  never submitted an OTP at all.** Nothing distinguishes "not attempted" from "attempted, reached
  ABDM, ABDM answered with a body we could not use".
- `state` is not `FAILED`, so the one state every other failure in this module converges on is
  skipped. `last_error_code` and `last_error_detail` stay null.
- The row is still inside its 30-minute TTL and `OTP_REQUESTED -> OTP_VERIFIED` is still a legal
  transition, so the session remains live and re-submittable, with no record of the prior attempt.
- The client gets an unhandled 500 (`unhandled_exception` in the logs), not the typed 502
  `SAMD-ABHA-2006` envelope every other ABDM failure produces.

This is why it is an **audit-integrity** finding and not a failure-taxonomy one. The taxonomy work
is about classifying failures that are recorded. This one is not recorded at all: the audit chain,
which is the artefact a regulator reads, contains no evidence that an enrolment attempt against a
patient's Aadhaar ever happened. A gap in an append-only chain that is supposed to be the record
of who did what to whom is a different class of defect from a misclassified error code.

---

## 2. The load-bearing part reasoning missed. MEASURED.

The obvious reading is that the ordering is a preference and the real fix is "wrap the unpack in a
try and route it to `_fail`". That is wrong, and a test proved it rather than an argument.

With the guard added but left **below** the state change and flush, the same test does not fail.
It **hangs**. Killed at 70s, twice. Sampling `pg_stat_activity` during the hang, three times at
8s intervals, identical each time:

      pid   |        state        | wait_event_type | wait_event | query
    --------+---------------------+-----------------+------------+---------------------------
     236643 | idle in transaction | Client          | ClientRead | INSERT INTO audit_events...
     236644 | active              | Lock            | advisory   | SELECT pg_advisory_xact_lock($1)

- 236643 is the request session. Its last statement is the `ABHA_OTP_VERIFIED` insert, and it
  holds `pg_advisory_xact_lock(facility_key)`, taken inside `audit.append` and transaction scoped,
  so held until the request transaction ends.
- 236644 is `_fail`'s out-of-band session, blocked taking the **same** facility key, because it
  also calls `audit.append`, for its `ABHA_SESSION_FAILED` row.

The request transaction cannot end because the handler is awaiting `_fail`. Permanent deadlock,
one pooled connection consumed on each side, no statement timeout configured to break it.

**This is wider than the rule as currently written.** `write_out_of_band`'s docstring (rule 2,
`backend/core/app/db/session.py`) and `service.py`'s own module docstring both state the
constraint in terms of a lock on *that row*: "never call this with `work` touching a row the
CALLER's still-open transaction holds a lock on". The real constraint, measured, is the
facility-wide advisory lock that `audit.append` takes. **Any `_fail` reached after any `_audit` in
the same request deadlocks, whether or not the two touch the same row.**

Consequence for this PR: the reads had to move above the audit write. A guard added in place could
not have worked. Consequence beyond it: see section 6.

---

## 3. STEP 2: the fix

`backend/abdm-adapter/abdm_adapter/service.py`, two sites, no new error path, no Pydantic model,
no change to `_result_from_transport_error` and no widening of its catch.

**`verify_otp`.** Every read out of `result.body` moved above the first state change, flush and
audit row, into one `try` whose `except (AttributeError, TypeError, ValueError, KeyError)` routes
to `_result_from_transport_error` and `_fail`, exactly the shape `fetch_profile` (:513) already
uses for `mapping.profile_to_abha_identity`. The reads land in locals; the assignments to `txn`
happen afterwards, unchanged in content and order. `enrolled_masked_mobile`, previously read at
:373 well after both audit rows, moved into the same guarded block.

Covers: `ABHAProfile`, `tokens`, `tokens.token`, `tokens.expiresIn` (`TypeError` on a null,
`ValueError` on a non-numeric string), `ABHAProfile.ABHANumber`, and `AttributeError` if either
sub-object is not a mapping.

**`submit_identity` (:289), the site the brief asked me to confirm needs no change: it did need
one.** Its *ordering* was already correct and needed nothing, as the brief expected. But the read
itself was unguarded, so a 200 body missing `txnId` raised `KeyError` straight past `_fail`: rolled
back to `STARTED`, never `FAILED`, no audit row, unhandled 500. Same defect class, weaker form (no
false claim is flushed first, and no deadlock is possible because nothing has been audited yet).
Guarded with the same shape. Fixing one site and leaving its sibling is how this trap has already
shipped four times; this is the fifth and sixth instance of it in the same file.

`verify_mobile_otp` has no body unpack. `fetch_profile` was already guarded. `result.body.get(
"message", "")` at :294 is a `.get` and cannot raise. Those are all four `result.body` reads in
the module, so the file is now clean on this defect class.

Also written into the source, next to the code, because it cost real time to find: the deadlock
measurement from section 2, with the explicit note that it is wider than
`write_out_of_band`'s stated per-row rule.

## 4. STEP 3: tests

`backend/core/tests/test_abha.py`, all against `httpx.MockTransport`. No network.

- `test_verify_otp_malformed_enrol_body_fails_session_with_persisted_row`, parametrized over four
  bodies: no `ABHAProfile`, no `tokens`, `tokens` without `token`, `tokens` without `expiresIn`.
  One parametrized test rather than four near-identical copies. Each asserts the **persisted row**
  (`state == "FAILED"`, `last_error_code`, `abha_number is None`,
  `external_token_encrypted is None`) and the **audit table** (no `abha_otp_verified`, no
  `abha_enrolled`, `abha_session_failed` present), not only the 502 envelope. Per this repo's rule
  that a request-scoped rollback can make a handler's return value lie.
- Same test is the ordering regression guard, asserted on outcomes and not on line order: `state
  == "FAILED"` and the `ABHA_SESSION_FAILED` row are both written by `_fail`, which the reverted
  ordering never reaches. Verified negatively by actually reverting the ordering and re-running
  (section 2): it does not merely fail, it hangs, which is a louder signal than intended but an
  unambiguous one.
- `test_malformed_enrol_body_is_classified_retryable`: asserts `error_code ==
  ABHA_UPSTREAM_ERROR` and `retry_class == RetryClass.RETRYABLE`. Separate from the integration
  test because `retry_class` is internal to `AbdmResult`, neither persisted nor placed in the
  error envelope, so it has no observable at the HTTP boundary. The integration test's proxy for
  it is `last_error_detail == "The ABDM gateway did not respond as expected."`, the fixed
  transport-error string, which distinguishes this path from `_result_from_local_error`'s.
- `test_submit_identity_missing_txn_id_fails_session_with_persisted_row`, for the :289 site.
- Happy path unchanged: no existing test was edited.

Results, all with `ABDM_MODE=stub`:

    backend/core     tests/test_abha.py            25 passed   (19 before, +6 new)
    backend/core     tests/                       280 passed
    backend/abdm-adapter tests/                    64 passed   (0 requests to abdm.gov.in)

    ruff check    service.py, test_abha.py         clean
    ruff format --check                            clean
    mypy          service.py                       Success: no issues found

mypy over `tests/test_abha.py` reports 171 errors; the unmodified file at HEAD reports 137. The
+34 are the `def patched_init(self, *args: object, **kwargs: object)` idiom, copied verbatim from
the eight pre-existing live-mode tests in the same file, at the same ~17 errors each. Not a new
error class, and mypy is evidently not run over `tests/` in CI given the 137-error baseline. Left
alone rather than restyling eight existing tests inside this PR.

## 5. What remains open

- **Full envelope validation.** Fixed here at the point of use, per key. A schema over ABDM
  responses would be a change to every response site in `client.py` and `service.py`, not this
  PR's scope. Noted in the source comment too.
- **`_extract_masked_mobile`** is still a regex over ABDM free text (D4, pre-existing).
- The environment finding in section 0.

## 6. For the S-1 session. Recorded, not acted on.

Two things.

**(a) The question S-1 asked.** Whether a row the device marked `FAILED` is genuinely absent
server-side. If any reject path can leave a record partially applied, the one-time re-push
migration is unsafe.

This session's measurement is direct evidence on that question, and it points the wrong way. The
bug fixed here is precisely a reject path that left the server in a state the device could not
infer: the request returned 500, the device would reasonably mark the attempt failed, and the
server row was left at `OTP_REQUESTED`, not absent, not `FAILED`, but live and re-submittable.
Partial application is not hypothetical in this codebase; it is the fourth-through-sixth instance
of the same trap. S-1 should not assume "device says FAILED" implies "server has nothing", and
should check every server write path reachable from a device-visible failure before treating the
re-push as idempotent.

**(b) A wider constraint S-1 will need.** The deadlock rule in `write_out_of_band`'s docstring
(`backend/core/app/db/session.py`, rule 2) is narrower than reality: it says "a row the caller's
transaction holds a lock on", but the measurement in section 2 shows the binding constraint is the
per-facility `pg_advisory_xact_lock` that `audit.append` takes. Any `write_out_of_band` call made
after any audit write in the same request will hang, same row or not. That docstring is the
canonical statement of the rule and is where the next person will look. Deliberately **not** edited
here, because `session.py` is core and outside this PR's stated scope. Flagged for the operator
to place, since sync push's own failure paths are exactly the shape that would hit it.
