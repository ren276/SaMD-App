# STEP 1 memo: `get_profile` gateway Bearer

Branch: `fix/abha-get-profile-gateway-bearer` off `master` (`27f7a94`, post PR #19).
Design only. No code in this step. No live ABDM calls were made producing this memo.

Note on finding numbers: "finding #5" and "finding #6" below refer to the reviewer's own numbered
list from the PR #18 / #19 review cycle. No document in this repo carries those numbers, so this
memo restates what each one means before acting on it, rather than assuming the numbering is
self-explanatory to a later reader.

---

## 0. What is actually broken

`client.get_profile` sends `X-token: Bearer <per-transaction token>` plus `REQUEST-ID` and
`TIMESTAMP`, and no `Authorization` header. PR #19 proved that the WSO2 gateway in front of
`abhasbx.abdm.gov.in` rejects `enrollment/request/otp` with 401 "Missing Credentials" (WSO2 900902)
when `Authorization: Bearer <gateway session token>` is absent. The M1 tracker's live activation
risk list already records the prediction that `profile/account` behaves the same way, because the
rejection is a gateway policy rejection rather than an application response.

That prediction is still a prediction. It has not been measured, because no live run has yet reached
Call 4. This PR acts on it anyway, for the reason given in section 4 of the PR #19 memo: the token
is cheap to obtain, the header is ignored by endpoints that do not require it, and the alternative
is a second failed watched run.

The `get_profile` docstring already pre authorised exactly this change:

> If that run returns 401, add `gateway_token=` here (abdm_headers already supports it) rather than
> reworking this call.

This PR is the cash-in of that instruction, with one important difference from what that sentence
implies: the header is additive. `X-token` does not go away, and it is not replaced. The two are
different headers carrying structurally different tokens, and after this change `profile/account`
carries both.

---

## 1. Exact call path, entry point down to the HTTP layer

```
HTTP GET /api/v1/abha/registration-sessions/{session_id}/profile
  router.get_profile                       router.py:123
    service.load_transaction               (no ABDM call, row load only)
    service.fetch_profile                  service.py:457   <-- token must be acquired here
      client.get_profile                   client.py:354    <-- must accept gateway_token
        abdm_headers(x_token=...)          request_context.py:33  <-- must also take gateway_token
          httpx.AsyncClient.get            client.py:378
```

Functions that must change to accept and forward the token: exactly two.

| Function | File | Change |
|---|---|---|
| `service.fetch_profile` | `service.py:457` | acquires the token (see section 2) and passes it down |
| `client.get_profile` | `client.py:354` | new required keyword-only `gateway_token: str`, forwarded to `abdm_headers` |

`abdm_headers` needs no signature change. It already accepts both `gateway_token` and `x_token`
and already emits both headers independently (`request_context.py:40-45`). Only its docstring is
wrong, see section 1a.

`router.get_profile` needs no change. It already has `settings` in scope and passes it to
`fetch_profile`, which is where acquisition belongs. Nothing above `fetch_profile` learns about the
gateway token, which is correct: the token is an ABDM transport concern, not a routing concern.

### 1a. `abdm_headers` docstring is now false

`request_context.py:34-38` currently says:

> At most one of `gateway_token`/`x_token` should be set: the gateway session token (cert fetch
> only, in this P0 slice) and the per-transaction X-token (profile fetch only) are never the same
> header on the same call.

After this PR, `profile/account` sets both on the same call. The sentence must change. This was
flagged as a follow-up in the PR #19 memo (section 2d) and deliberately left alone there because it
was still true at the time. It stops being true here, so it changes here. The corrected wording
should keep the part that still holds, which is the substantive claim: the two tokens are
structurally different and must never be substituted for one another, even though a single call can
now legitimately carry both.

---

## 2. Where the gateway token comes from, and whether the existing path is reusable

### Current acquisition sites

Three call sites in `service.py` acquire a gateway token today, all with an identical block:

| Caller | Line | Acquires for |
|---|---|---|
| `submit_identity` | `service.py:247` | cert fetch, then `send_otp` (added in PR #19) |
| `verify_otp` | `service.py:310` | cert fetch, then `enrol_by_aadhaar` (added in PR #19) |
| `verify_mobile_otp` | `service.py:412` | cert fetch, before the `NotImplementedError` branch |

All three sit inside a `try` whose `except` clauses route through `_fail`:

```python
try:
    gateway_token = await client.fetch_gateway_session_token(
        mode=settings.abdm_mode,
        session_url=settings.abdm_session_url,
        client_id=settings.abdm_client_id,
        client_secret=settings.abdm_client_secret,
        timeout_seconds=settings.abdm_timeout_seconds,
    )
    cert_pem = await fetch_public_key_pem(...)
except SamdError as exc:
    await _fail(session, worker, txn, _result_from_samd_error(exc))
except (httpx.HTTPError, ValueError, KeyError) as exc:
    await _fail(session, worker, txn, _result_from_transport_error(exc))
```

### Is it reusable for `fetch_profile`?

**Yes for the token itself, no for the block.** Two distinctions matter.

**The token is shared, and this is the important part.** `fetch_gateway_session_token` is
process-wide cached (`client.py:38-40`, `_cached_token` plus `_session_lock` single flight). In a
real run, Call 4 happens seconds after Call 3 on the same process, and the token from Call 3 is
still inside its `expiresIn` minus 60 second skew window. So the practical cost of acquisition in
`fetch_profile` is normally zero network calls: it is a cache read behind a lock. This is the single
most important fact for judging the size of this change. The PR #19 memo called adding acquisition
to `fetch_profile` "architectural, not one line" and deferred it partly on that basis. That
characterisation was correct about the code shape and slightly overstated about the runtime cost.
Both things are true: the diff is real, and the runtime cost is near zero.

**The block cannot be copied verbatim, because `fetch_profile` must not fetch a cert.**
`fetch_profile` does no encryption. It has no `encrypt_oaep_sha1` call and needs no
`cert_pem`. So the new block is the token half only:

```python
try:
    gateway_token = await client.fetch_gateway_session_token(...)
except SamdError as exc:
    await _fail(session, worker, txn, _result_from_samd_error(exc))
except (httpx.HTTPError, ValueError, KeyError) as exc:
    await _fail(session, worker, txn, _result_from_transport_error(exc))
```

**Decision: give `fetch_profile` its own acquisition block, placed after the
`external_token_encrypted` guard and before the existing `client.get_profile` try block.**

Reasoning for that placement, which is not arbitrary:

1. **After the `external_token_encrypted` guard** (`service.py:463-466`). That guard raises
   `SamdError(ABHA_INVALID_STATE)` directly without going through `_fail`, because a caller asking
   for a profile before enrolment is a client sequencing error, not an ABDM failure. Fetching a
   gateway token before that check would spend a network call (on a cold cache) to serve a request
   that was always going to be rejected locally.
2. **Before the `get_profile` try block, not merged into it.** Merging them would widen one `try`
   over two ABDM calls with different failure meanings. Keeping them separate preserves the existing
   block's tight scope, and matches the shape the other three call sites already use, where token
   acquisition and the endpoint call sit in separate `try` statements.
3. **No new flush ordering risk.** `_fail` writes out of band and its contract requires the request
   session to hold no prior flush on the row. `fetch_profile` performs its first flush at
   `service.py:500`, well after both blocks. Adding a block above that line does not move any flush,
   so the deadlock rule that this module's docstring documents is not disturbed. This is worth
   stating explicitly because it is the one property most likely to be broken by a careless edit to
   this function, and it has already shipped broken three times in this codebase's history.

**Rejected alternative: caching the token on the transaction row.** No. The token is process wide
and already cached in memory by design ("never DB, never disk" per the live wiring brief). Putting a
gateway credential on a database row would be a security regression for zero benefit.

---

## 3. Failure classification for a missing `gateway_token`

**Confirmed: it becomes a `TypeError` at the call site, not a silent 401, provided the parameter is
declared required keyword-only with no default.**

`client.get_profile` is declared `async def get_profile(*, mode: str, x_token: str, ...)`. Adding
`gateway_token: str` with no default to that keyword-only group means any call that omits it raises
`TypeError: get_profile() missing 1 required keyword-only argument: 'gateway_token'` at call time.
This is the same discipline PR #19 applied to `send_otp` and `enrol_by_aadhaar`, and for the same
reason: the failure mode being defended against is a future edit silently dropping the argument and
reintroducing a 401 that only shows up on a live run.

Do **not** use `gateway_token: str | None = None`. An optional parameter reintroduces exactly the
bug class this PR exists to close.

Two consequences worth naming now so STEP 2 does not discover them as surprises:

1. **Every existing caller must be updated in the same commit or the suite fails loudly.** That is
   the desired behaviour. `client.get_profile` has one production caller
   (`service.fetch_profile`, `service.py:474`) and several test callers
   (`test_client_live.py`, two tests). All are enumerated in section 6.
2. **`TypeError` is already caught by `fetch_profile`'s mapping guard, but not by its call guard.**
   Look carefully at the existing code: the `client.get_profile` try block catches
   `(httpx.HTTPError, ValueError, KeyError)`, which does **not** include `TypeError`. The
   `profile_to_abha_identity` block below it catches `(AttributeError, TypeError, ValueError,
   KeyError)`, which does. So a missing `gateway_token` at the `client.get_profile` call site
   propagates as an unhandled `TypeError` and surfaces as a 500, not as a `_fail`-routed
   `ABHA_UPSTREAM_ERROR`. That is the correct outcome for a programming error: it is loud, it is
   not classified as an upstream problem, and it does not mark a citizen's transaction FAILED
   because of our own bug. **Do not add `TypeError` to the call block's except clause.** Noting this
   explicitly because a reviewer optimising for "catch everything" would be tempted to, and that
   would convert a loud developer error into a silent data-integrity event.

---

## 4. Finding #6: state-machine risk on post-enrolment failure

Restating the risk: if `get_profile` fails after enrolment already succeeded, the transaction lands
in `FAILED`, and a downstream reader could mistake that for "ABHA creation failed" when in fact the
ABHA exists at ABDM and only the profile read failed.

### What the code actually does today

This is not hypothetical, and it is not introduced by this PR. It is the current behaviour on
`master`, reachable since PR #17 made the live `get_profile` branch real. Tracing it:

- `verify_otp` persists the enrolment outcome **before** any profile call. `service.py:361-364` sets
  `txn.abha_number`, `abha_address`, `abha_status`, `abha_type`, then transitions to `ENROLLED` and
  flushes at `service.py:368`, then writes the `ABHA_ENROLLED` audit row at `service.py:369`.
- `fetch_profile` failing later calls `_fail`, which sets only `state`, `last_error_code`, and
  `last_error_detail`, and appends one `ABHA_SESSION_FAILED` audit row. It does not clear
  `abha_number` and does not touch any other column.

So the resulting row is: `state = 'FAILED'`, `abha_number = '91...'` (non null),
`last_error_code = 'SAMD-ABHA-2006'`, and the audit chain contains both `ABHA_ENROLLED` and
`ABHA_SESSION_FAILED` for that `session_id`.

**The discriminator already exists in the persisted data.** `abha_number IS NOT NULL AND state =
'FAILED'` means precisely "ABHA was created, a later step failed". `abha_number IS NULL AND state =
'FAILED'` means "creation itself failed". The audit trail says the same thing independently, and
the audit trail is append only and chain hashed, so it cannot be retroactively confused.

What is missing is not the data. What is missing is that nothing **names** this distinction: there
is no state value, no doc line, and no reader-facing contract that tells a downstream consumer to
make that check. The risk is real but it is a semantics and documentation gap, not a data loss
gap.

### Recommendation: do NOT touch state in this PR. Defer to the design ticket. Document the
### discriminator here.

Reasoning:

1. **Fixing it properly means adding a state value or a column**, for example a
   `PROFILE_FETCH_FAILED` terminal state or an `enrolment_succeeded` boolean. Either one is a schema
   change plus an Alembic migration plus a change to `ALLOWED_TRANSITIONS` (`transaction.py:27-43`)
   plus a change to the API contract's documented state list plus a device-side change to whatever
   reads that state. That is a design ticket, and the constraint on this branch says as much.
2. **It is orthogonal to the header bug.** This PR's entire purpose is to stop `get_profile` from
   401ing. Bundling a state-machine redesign into it produces exactly the incoherent PR narrative
   that the PR #19 review correctly pushed back on, and it does so in a regulated-software audit
   trail where small single-purpose commits are the point.
3. **This PR makes the risk less likely to fire, not more.** If the WSO2 inference holds, the
   dominant cause of post-enrolment `get_profile` failure right now is the missing header, and this
   PR removes it. Shipping the header fix first strictly reduces exposure to the ambiguity while the
   design ticket is worked.
4. **The cheap half of the mitigation is free and belongs here.** Writing down that
   `abha_number IS NOT NULL AND state = 'FAILED'` is the discriminator costs one paragraph in the
   M1 tracker and closes the "a reader would be confused" gap immediately, without a migration.

So: no schema change, no `ALLOWED_TRANSITIONS` change, no new state value, no new column in this
PR. One documentation paragraph, and one test that pins the discriminator so a future change cannot
silently clear `abha_number` on failure without breaking a test (see section 5, test T5).

**Out of scope for this branch, explicitly:** any change to `AbhaTransactionState`,
`ALLOWED_TRANSITIONS`, `abha_transactions` columns, or the API contract's documented state list.
Those belong to the design ticket. If review decides otherwise, that is a scope change to be made
deliberately, not absorbed.

---

## 5. Test plan

Governing rule, carried forward from PR #18 and restated in PR #19: a mock that fabricates the wire
shape is not a test of the wire shape. Finding #5, as this memo understands it, is the demand that
no fixture be able to encode the missing-header bug back into the suite. Every test below is chosen
so the wrong wire shape fails.

### T1 (modify) `test_get_profile_live_mode_matches_postman_request_shape`, `test_client_live.py:78`

This test currently asserts `assert "Authorization" not in request.headers`, with a docstring
explaining that sending X-token only and letting a real 401 be the signal is "the locked decision".
That decision is now superseded. Both the assertion and the docstring paragraph defending it must
change, or the suite keeps asserting the bug is correct.

- pass `gateway_token="gw-token-abc"` to the call
- replace the absence assertion with `assert request.headers["Authorization"] == "Bearer gw-token-abc"`
- **keep** `assert request.headers["X-token"] == "Bearer real-x-token"`. This is the anti-collapse
  assertion: it proves the new header was added alongside the existing one and did not replace it.
  A change that mistakenly routed the gateway token into `X-token` would pass a presence-only check
  and fail this one.
- rewrite the docstring paragraph to record why the locked decision changed, citing the PR #19
  finding, and to note that the header for this specific endpoint is inferred rather than measured
  until a live run says otherwise.

### T2 (modify) `test_get_profile_live_mode_x_token_expired`, `test_client_live.py:114`

Mechanical: add `gateway_token=` to the call so it still constructs. No new assertions. Its subject
is the 401 body classifier, not headers.

### T3 (new) `test_get_profile_live_mode_sends_both_tokens_without_conflating_them`

The anti-fabrication test finding #5 asks for. Distinct from T1 because it uses two deliberately
different token values and asserts each lands in its own header:

- call with `gateway_token="gateway-AAA"` and `x_token="transaction-BBB"`
- `assert request.headers["Authorization"] == "Bearer gateway-AAA"`
- `assert request.headers["X-token"] == "Bearer transaction-BBB"`
- `assert "gateway-AAA" not in request.headers["X-token"]`
- `assert "transaction-BBB" not in request.headers["Authorization"]`

The two negative assertions are the point. With a single shared token value, a bug that swaps the
two headers is invisible. With distinct values, it fails. This is the test that makes it impossible
for a later fixture to quietly re-encode either the missing header or a crossed one.

### T4 (new) end-to-end threading test, `backend/core/tests/test_abha.py`

Same role as PR #19's `test_abha.py` assertion, and the same reasoning: T1 to T3 pass the token in
explicitly, so all of them still pass if `service.fetch_profile` stops acquiring or forwarding it.
Only a test through the real service call graph proves the plumbing.

Shape: walk to `ENROLLED` in stub mode (as the existing profile tests do), flip to live, install a
path-aware mock transport that answers `/sessions` with `{"accessToken": "gw-token", "expiresIn":
1800}` and `/profile/account` with a valid profile body while capturing the profile request, then
assert `captured.headers["Authorization"] == "Bearer gw-token"`. The asserted value is the one the
mocked `/sessions` endpoint returned, so the assertion proves the token travelled
`fetch_gateway_session_token` to `fetch_profile` to `client.get_profile` to the wire.

**Cache caveat that STEP 2 must handle.** `_cached_token` is module-level process-wide state.
`test_client_live.py` has an autouse fixture resetting it between tests; `test_abha.py` does not.
An earlier test in the same process that populated the cache would make the `/sessions` branch of
this mock never fire, and the assertion would then compare against a stale token and fail
confusingly, or worse, pass for the wrong reason. STEP 2 must reset
`client_module._cached_token` and `_cached_token_expires_at` in this test (or add an autouse fixture
to `test_abha.py` doing so). Flagging it here because it is the single most likely source of a
mysterious failure during implementation.

### T5 (new) discriminator pin, `backend/core/tests/test_abha.py`

The documentation-only half of the section 4 mitigation, given teeth. Reach `ENROLLED`, force a
profile-fetch failure, then assert on the persisted row:

- `txn.state == "FAILED"`
- `txn.abha_number is not None`
- and that both `ABHA_ENROLLED` and `ABHA_SESSION_FAILED` audit rows exist scoped to this
  `session_id`

This pins the invariant that a post-enrolment failure preserves the evidence that enrolment
succeeded. It does not add a state value, so it does not pre-empt the design ticket; it just makes
sure a future change cannot silently destroy the discriminator the design ticket will build on.
Audit assertions must be scoped to this session's own `session_id` in the payload, per the PR #17
review finding about unscoped existence checks passing on another test's row.

### T6 (modify, mandatory) the three existing live-mode profile tests in `test_abha.py`

**This is a correctness issue, not a housekeeping one, and it is the highest-risk item in this
memo.** All three of these tests install a **path-blind** mock transport, one that returns the same
response for every request regardless of URL:

| Test | Line | Current handler behaviour |
|---|---|---|
| `test_live_mode_profile_fetch_failure_leaves_transaction_failed_with_persisted_row` | `test_abha.py:336` | raises `ConnectTimeout` for **every** request |
| `test_live_mode_malformed_profile_body_fails_session_with_persisted_row` | `test_abha.py:408` | returns the malformed profile body for **every** request |
| `test_d5_no_phi_in_persisted_row_or_logs_live_mode` | `test_abha.py:479` | returns the PHI-bearing profile body for **every** request |

Once `fetch_profile` calls `/sessions` first, each of these handlers answers the sessions call too.
Consequences, per test:

- The timeout test: the `ConnectTimeout` now fires on the **sessions** call, not the profile call.
  The test still ends at `state == FAILED` with `ABHA_UPSTREAM_ERROR`, so **it still passes while no
  longer testing what its own name and docstring claim it tests**. This is precisely the
  silent-drift class finding #5 is about, arriving from the opposite direction: not a fixture
  encoding a bug, but a fixture quietly changing which code path it covers.
- The malformed-body and PHI tests: the sessions call receives a profile body, and
  `fetch_gateway_session_token` does `body["accessToken"]`, raising `KeyError`. That is caught by
  `fetch_profile`'s new acquisition block and routed to `_fail`, so these tests will **fail
  outright** with the wrong error path. Loud, at least.

Required fix for all three: make each handler path-aware, answering `/sessions` with a valid token
body and keeping the existing behaviour for `/profile/account`. The timeout test in particular must
raise its `ConnectTimeout` **only** on the profile path, or it stops being a profile-boundary test.

---

## 6. Files to touch in STEP 2

| File | Change |
|---|---|
| `backend/abdm-adapter/abdm_adapter/client.py` | `get_profile`: add required keyword-only `gateway_token: str`, pass to `abdm_headers(gateway_token=..., x_token=...)`. Rewrite the docstring paragraph that currently defends sending X-token only, preserving the PR #19 finding as the reason and marking this endpoint's header as inferred rather than measured. |
| `backend/abdm-adapter/abdm_adapter/request_context.py` | `abdm_headers` docstring only. Correct the "at most one of" claim (section 1a). No signature or behaviour change. |
| `backend/abdm-adapter/abdm_adapter/service.py` | `fetch_profile`: add the token acquisition block after the `external_token_encrypted` guard and before the existing `get_profile` try block, with the same two `except` clauses the other three call sites use. Pass `gateway_token=gateway_token` to `client.get_profile`. |
| `backend/abdm-adapter/tests/test_client_live.py` | T1 (modify), T2 (modify), T3 (new). |
| `backend/core/tests/test_abha.py` | T4 (new), T5 (new), T6 (modify three existing tests to be path-aware). Plus the `_cached_token` reset noted under T4. |
| `docs/abdm/M1-tracker.md` | Move `profile/account` out of "deferred" in the live-activation-risks list and into "header applied by inference, unverified", matching how `enrol/byAadhaar` is already recorded. Update the watched-run retry sequence, where Call 4 currently says "expect 401 or success" and a 401 is described as the known-deferred outcome; after this PR a 401 at Call 4 is no longer expected and is a real finding. Add the section 4 discriminator paragraph and name the state-semantics design ticket as the owner of the remaining gap. |

**Not touched:** `crypto.py`. `verify_mobile_otp` stays `NotImplementedError`.
`backend/docker-compose.yml` is never committed, and currently carries `ABDM_MODE: live` locally.
No schema, no migration, no `ALLOWED_TRANSITIONS`, no `AbhaTransactionState` change (section 4).
No change to `backend/core/` production code; the `test_abha.py` edits are test-only and are
justified by T4, T5, and T6.

**Known adjacent issue, still deferred:** `backend/core/tests/` auto-loads `backend/core/.env`, so a
local `.env` with `ABDM_MODE=live` makes stub-labelled tests hit the real sandbox. Recorded in the
M1 tracker by PR #19. STEP 2 must run the suites with `ABDM_MODE=stub` forced on the invocation, and
must not attempt the conftest fix here.

---

## 7. STEP 2 gate

Same shape as PR #18 and #19. Before committing, report: full file list with any correction to
section 6 called out explicitly, test-diff list, exact test names and intents, `backend/core` and
`backend/abdm-adapter` counts before and after, `ruff check` plus `ruff format --check` plus
`mypy app` clean in both directories, and the specific assertion that proves the fix (T4). Then
stop.

Commit message should state plainly that the header is applied by inference from the PR #19
measurement and is not itself live verified for `profile/account`, so the commit does not overclaim
in the same way the original Postman-derived comment did.

---

STEP 1 memo complete. Human review required before STEP 2.
Recommended model for STEP 2: Claude Sonnet (implementation).
Awaiting authorization to switch or stay.
