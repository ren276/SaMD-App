# STEP 1 memo — `send_otp` Bearer auth + sibling-endpoint audit

Branch: `fix/abha-send-otp-bearer-header` off `master`. Design only; no code in this step.

---

## 0. The finding (preserve verbatim, everywhere it is cited)

> On 2026-08-24 against abhasbx.abdm.gov.in sandbox, POST /abha/api/v3/enrollment/request/otp
> returned 401 "Missing Credentials" (WSO2 error 900902) when called without an
> Authorization: Bearer <gateway-session-token> header, and returned 400 "Invalid LoginId" when
> the same call was made with the header, proving auth was the missing piece. This contradicts the
> client.py docstring claim (Phase A finding) that "the four enrollment/* calls carry no
> Authorization header in any recorded example." The recorded examples were either incomplete or
> ABDM's sandbox gateway config has changed. Verified via /tmp/probe_otp.py in-container, using the
> same fetch_gateway_session_token path the adapter uses.

### Two sub-findings the probe also proves, which the memo relies on

1. **`X-CM-ID` is still not required.** The probe's successful (400 Invalid LoginId, i.e.
   gateway-accepted) attempt used `abdm_headers(gateway_token=tok)` — REQUEST-ID + TIMESTAMP +
   Authorization, and **no `X-CM-ID`**. So the half of the old docstring claim that says "no
   `X-CM-ID`" survives intact. Only the `Authorization` half is falsified. Do not add `X-CM-ID`.

2. **Error 900902 is a WSO2 API-Manager gateway rejection, not an ABDM application response.**
   It is emitted by the gateway before the request ever reaches ABDM's enrollment service. WSO2
   applies that check per *API product / resource policy*, not per handler. This is the single
   most important inference in this memo, because it is what makes the sibling-endpoint decision
   in §2 an evidence-based extrapolation rather than a guess. It is still an inference, and §2
   says so where it matters.

---

## 1. DECISION — `send_otp`

**Signature.** Add one *required* keyword-only parameter:

```python
async def send_otp(
    *,
    mode: str,
    gateway_token: str,      # NEW, required
    txn_id: str,
    ...
) -> AbdmResult:
```

**Required, not `str | None = None`.** This is deliberate and is the strongest regression guard in
the whole change: a future edit that drops `gateway_token=` at the call site becomes a `TypeError`
at call time, not a silent 401 against a live citizen's enrollment. An optional parameter with a
`None` default would let the exact bug we are fixing reappear invisibly. Cost is zero — see below.

**Where the header is added: through `abdm_headers()`, unchanged.** `request_context.abdm_headers`
already accepts `gateway_token` and already emits `Authorization: Bearer <token>` (line 41-42). No
new mechanism, no second header helper. The call becomes:

```python
headers={"Content-Type": "application/json", **abdm_headers(gateway_token=gateway_token)},
```

This is the identical shape `crypto.fetch_public_key_pem` already uses for the cert fetch — an
endpoint on the same host that is *already proven to accept* a gateway Bearer. We are not inventing
an auth path; we are applying the one already working next door.

**Body unchanged.** The 400 "Invalid LoginId" proves the body reached the application layer and was
parsed. `txnId`/`scope`/`loginHint`/`loginId`/`otpSystem` stay exactly as they are.

**Call-site cost: zero extra network calls.** `service.submit_identity` (service.py:247) *already*
fetches `gateway_token` for the cert fetch, in the same `try` block, before it calls `send_otp` at
line 271. The change at the call site is one line: `gateway_token=gateway_token,`. No new fetch, no
new token lifetime to reason about, no change to the three `try/except` blocks or to `_fail`
routing. `fetch_gateway_session_token` returns `"stub-gateway-session-token"` in stub mode, so the
required parameter is satisfiable in stub mode too and no stub test needs restructuring.

**Sole caller confirmed:** `service.py:271` is the only call site of `client.send_otp` in the repo.

---

## 2. DECISION — sibling endpoints, each on its own

### 2a. `enrollment/enrol/byAadhaar` — **ADD the Bearer preemptively. Option (i).**

Reasoning, in the order that decided it:

1. **Same WSO2 gateway, same API product, same path prefix** (`/abha/api/v3/enrollment/*`). The
   900902 rejection is a gateway-policy rejection (§0.2). A gateway policy that secures
   `enrollment/request/otp` and not `enrollment/enrol/byAadhaar` would be an unusual per-resource
   carve-out, and the *only* evidence for that carve-out is the same Postman recording set that has
   now been proven wrong for the neighbouring endpoint. Evidence that failed once at the exact
   point under test is not evidence.

2. **The failure asymmetry is decisive.** `enrol/byAadhaar` is step 2 of a real enrollment. If it
   401s on the first watched run, a real citizen's real Aadhaar OTP is burned, the transaction
   dies, and the run has to be restarted with a fresh OTP — with the operator watching. Whereas the
   cost of adding a header that turns out not to be needed is: nothing. ABDM's gateway does not
   reject a *valid* credential presented to a resource that does not require one; the cert fetch
   proves valid gateway tokens are accepted on this host. Downside of acting ≈ 0; downside of
   waiting = a burned OTP mid-demo.

3. **The token is already in hand.** `service.verify_otp` (service.py:310) already fetches
   `gateway_token` for its cert fetch before calling `enrol_by_aadhaar` at line 335. Same one-line
   call-site change, same zero extra network cost. There is no "wait and see" that is cheaper than
   just doing it.

Same treatment as `send_otp`: required keyword-only `gateway_token: str`, threaded through
`abdm_headers(gateway_token=...)`. Sole caller: `service.py:335`.

**Honesty requirement for the code comment:** this one is *extrapolated*, not live-verified. The
docstring must say so explicitly and must not borrow `request/otp`'s verification as if it were its
own. See §3.

### 2b. `enrollment/verify_otp` (`client.verify_mobile_otp`, `POST enrollment/auth/byAbdm`) — **decide the header, implement nothing.**

`verify_mobile_otp`'s live branch stays `raise NotImplementedError(...)`. Explicit out-of-scope per
the brief. But the brief is right that the header answer decides the shape of the future
implementation, so lock it now while the reasoning is fresh:

**Decision: when it is implemented, it carries `Authorization: Bearer <gateway session token>`,
via `abdm_headers(gateway_token=...)`, with the same required keyword-only parameter.** Same
`/abha/api/v3/enrollment/*` prefix, same gateway product, same §0.2 inference. And
`service.verify_mobile_otp` (service.py:412) *already fetches a gateway token* before reaching the
client call, so the future implementation inherits the same zero-cost call-site shape.

Recorded as a one-line note in the `verify_mobile_otp` docstring. No signature change, no parameter
added to a function that raises — adding a parameter to an unimplemented function is scaffolding for
later, and later can scaffold for itself.

### 2c. `profile/account` (`get_profile`) — **genuinely different. Do not change. Flag loudly.**

Confirmed from the code, not from memory:

| | gateway Bearer | per-transaction X-token |
|---|---|---|
| Header name | `Authorization` | `X-token` |
| Value | `Bearer <accessToken>` from `POST gateway/v3/sessions` | `Bearer <tokens.token>` from the `enrol/byAadhaar` **response body** |
| Source | client_id/client_secret, process-wide cache (`_cached_token`) | per-transaction, persisted on `AbhaTransaction.external_token_encrypted` |
| Emitted by | `abdm_headers(gateway_token=...)` → line 41-42 | `abdm_headers(x_token=...)` → line 43-44 |

They are **two different headers carrying two structurally different tokens**, emitted by two
different branches of `abdm_headers`. They do **not** collapse to the same thing. The contract doc
(`abha-internal-contract.md:84, 90-93`) says the same. So the brief's exclusion (f) applies as
written: the per-transaction Bearer mechanism is untouched.

**But the honest read is not "get_profile is fine."** Two facts have to be stated together:

- `get_profile`'s live path has **never made a real ABDM call**. PR #17 tested it against
  `httpx.MockTransport` only. No live run has ever reached `profile/account`, because every live
  run so far died at step 1 on the 401 this branch fixes.
- If §0.2's WSO2-product inference is right, `profile/account` needs `Authorization` **in
  addition to** `X-token`, and will 401 the same way on the first watched run that gets that far.

`get_profile`'s own docstring already predicted exactly this and pre-authorised the fix
("If that run returns 401, add `gateway_token=` here (abdm_headers already supports it) rather than
reworking this call"). That instruction remains correct and stays in place.

**Why defer it rather than fix it now, when we are fixing the same class of bug next door:**

1. `profile/*` is a *different path prefix* from `enrollment/*` and may well be a different WSO2 API
   product with a different policy. The §0.2 inference is strong within `enrollment/*` (same prefix,
   one endpoint directly measured); it is materially weaker across the prefix boundary.
2. Unlike 2a, the fix is **not** one line. `service.fetch_profile` (service.py:455) is the one
   outbound call in the module that fetches **no gateway token at all** — adding one means a new
   `fetch_gateway_session_token` call, a new failure edge, and new `_fail` routing in a function
   whose failure path was itself only just hardened in PR #17. That is a real diff in a freshly
   stabilised code path.
3. Unlike 2a, **the failure is cheap.** By the time `profile/account` fires, the ABHA already
   exists and no OTP is at stake. A 401 there yields a FAILED transaction on an already-created
   ABHA, and the profile fetch is re-attemptable. There is no burned-credential asymmetry forcing
   our hand.
4. The same watched run that verifies 2a verifies this, for free, in the same sitting.

**Deliverable instead: a live-verification checklist item in the M1 tracker (§5) that says plainly
that a 401 here is expected-if-the-inference-holds, and names the exact fix.** Not a vague "verify
headers" line — the next operator should be able to act in one minute without re-deriving anything.

If Sandesh would rather close this now and eat the service.py diff, say so at review and it moves
into scope; the reasoning above is a recommendation, not a constraint.

### 2d. Follow-up noted, not acted on

`abdm_headers`' docstring says "At most one of `gateway_token`/`x_token` should be set." If 2c is
ever resolved as "`profile/account` needs both", that sentence becomes false and must change with
it. It is true today and stays as-is this branch. Noted here so it is not forgotten later.

---

## 3. DOCSTRING correction plan

**Correction to the brief's file map:** the brief says "client.py's `send_otp` docstring and the
top-of-module comment both make the 'no auth' claim." The module docstring (lines 1-14) does **not**
make it. The second site is `fetch_gateway_session_token`'s docstring, **client.py:94-95**:

> "Only needed for the cert fetch in this P0 slice (Phase A finding: the four enrollment/* calls
> carry no Authorization header in any recorded example)."

That is the sentence quoted in the finding, and it is now false in two ways: the claim itself, and
"only needed for the cert fetch". Three sites total need edits, not two:

| # | Site | Change |
|---|---|---|
| 1 | `client.py:94-95`, `fetch_gateway_session_token` docstring | Delete the "no Authorization header" parenthetical. Replace with: this token authenticates the cert fetch **and** the `enrollment/*` calls. Carry the verbatim finding as the reason. |
| 2 | `client.py:159-161`, `send_otp` docstring | Delete the "carries no `Authorization`/`X-CM-ID`" paragraph. Replace with the verbatim finding + "`X-CM-ID` is still absent, and that half is confirmed by the same probe" (§0.1). |
| 3 | `client.py:199-201`, `enrol_by_aadhaar` docstring | **New** paragraph. Must state the header is applied by *extrapolation* from the `request/otp` finding (same gateway, same prefix), is **not itself live-verified**, and names the watched run as the confirmation step. Do not let it read as verified. |
| 4 | `client.py:265-270`, `verify_mobile_otp` docstring | One line: when live mode is implemented, it carries the gateway Bearer for the same reason as 3. `NotImplementedError` stays. |
| 5 | `client.py:5-6`, module docstring | No claim to correct, but the "live is implemented for…" sentence should stay accurate; verify Sonnet does not leave it stale. |

**Verbatim rule:** the finding block in §0 goes into sites 1 and 2 word-for-word, including the date,
the host, the two status codes, both error strings, and the sentence naming what it contradicts.
Not paraphrased, not summarised to "auth is required". The next person to hit a contradicting
Postman export needs the exact evidence and its provenance, which is the whole reason the previous
version of this comment failed us.

**Also update:** `test_client_live.py:64`'s comment carries the same false claim and dies with the
assertion under it (§4).

---

## 4. TEST STRATEGY

Governing rule, carried from the last PR: *a mock that fabricates the wire shape is not a test of
the wire shape.* A test that mocks without the Bearer and asserts success would freeze the 401 into
the suite as correct behaviour. Every assertion below is chosen so that the wrong wire shape fails
the suite.

### 4a. Change — `test_send_otp_live_mode_matches_postman_request_shape` (test_client_live.py:38)

- Pass `gateway_token="gw-token-abc"` to the `send_otp` call.
- **Delete** line 64's comment and line 65's `assert "Authorization" not in request.headers`.
  This assertion is the exact encoding of the bug and must not survive in any form.
- **Add** `assert request.headers["Authorization"] == "Bearer gw-token-abc"` — equality against the
  passed token, not a `in request.headers` presence check. Presence would pass on a hardcoded or
  empty-string token.
- **Keep** `assert "X-CM-ID" not in request.headers` — add it if absent. §0.1 proved the negative
  half of the old claim, and losing it invites someone to "fix" the 401 a second time by adding
  X-CM-ID too.
- Body assertion unchanged.

### 4b. Change — `test_enrol_by_aadhaar_live_mode_never_sends_plaintext_otp` (test_client_live.py:134)

- Pass `gateway_token="gw-token-abc"`.
- Add `assert request.headers["Authorization"] == "Bearer gw-token-abc"`.
- All existing plaintext-OTP assertions untouched — that test's primary job does not change.

### 4c. New — `test_send_otp_live_mode_carries_gateway_bearer_from_session_token`

Adapter-level regression guard, in `test_client_live.py`. Distinct from 4a: 4a proves the parameter
reaches the header; this proves a *distinct* token value is not being shadowed, hardcoded, or
crossed with `X-token`. Asserts, on the captured request:

- `request.headers["Authorization"] == "Bearer distinct-token-xyz"`
- `"X-token" not in request.headers` (the two Bearers must not be conflated — §2c's table is the
  contract this asserts)
- `"REQUEST-ID" in request.headers` and `"TIMESTAMP" in request.headers` still hold alongside it

### 4d. New — end-to-end pass-through assertion, `backend/core/tests/test_abha.py`

**This is the assertion that actually proves the fix**, and the one thing 4a-4c cannot cover.
4a-4c pass the token in explicitly, so they still pass if `service.py` stops passing the real one.
A silent revert at the call site is exactly the regression class we are defending against.

`test_submit_identity_end_to_end_with_base64_der_cert_produces_ciphertext` (test_abha.py:255) is
already the harness: it mocks `/sessions` → `{"accessToken": "gw-token", ...}`, then `/certificate`,
then captures the `/request/otp` request. **One line added** to it:

```python
assert otp_request.headers["Authorization"] == "Bearer gw-token"
```

`"gw-token"` is the value the mocked `/sessions` endpoint returned at line 280. So the assertion
proves the token travelled `fetch_gateway_session_token` → `submit_identity` → `send_otp` → wire.
If Sonnet finds the existing test's docstring no longer covers what it asserts, extend the docstring
rather than adding a near-duplicate test.

**Justification for touching `backend/core/` (the brief requires one):** test-only, one line, in an
existing live-mode test that already mocks the whole chain. No production code under `backend/core/`
is touched. Without it, the service-layer plumbing has no coverage at all and the fix can be
reverted silently.

### 4e. Not doing

No test asserting a live 401. Mocking a 401 and asserting we handle it tests `errors.py`'s
classifier, which is already tested, and proves nothing about whether we send the header.

---

## 5. BACKPORT — `docs/abdm/M1-tracker.md`

**Line 22** (backend wiring table, Send Aadhaar OTP row) — currently reads "Matches Postman
body/headers exactly (no Authorization/X-CM-ID, per the Phase A finding)". That parenthetical is the
false claim in doc form. Rewrite to: body matches Postman; headers **corrected 2026-08-24** to carry
the gateway Bearer, with the verbatim finding, and `X-CM-ID` still absent and confirmed absent.

**Line 23** (Enrol by Aadhaar row) — add: gateway Bearer applied by extrapolation from the
`request/otp` finding, **not yet live-verified**, confirmation pending the next watched run.

**"Resolved this session" section** — add a D-numbered entry, in the same shape as the existing D6
correction entry (which is a good model: it states what was believed, what was measured, when, how,
and what changed in code as a result). It must record that the Phase A "no Authorization on
enrollment/*" finding is **falsified for `request/otp` by direct measurement** and **presumed
falsified for the siblings by inference**, and keep the two epistemically separate. Include the
verbatim finding.

**"Live-activation risks, verify on first watched enrollment" section** — this is the section the
brief flags, and it needs a real rewrite, not an append. Today it has two bullets (masked-mobile
regex, masked-only mobile_number) and no header bullet at all; the header risk lives implicitly in
`get_profile`'s docstring. Replace with a bullet list where each item states endpoint, current
belief, evidence class, and the exact action if it fails:

- `enrollment/request/otp` — **RESOLVED 2026-08-24, measured.** Requires `Authorization: Bearer
  <gateway session token>`. Moves out of "risks" and into "resolved".
- `enrollment/enrol/byAadhaar` — **UNVERIFIED, header applied by inference.** Confirm on the next
  watched run. If it 401s *with* the header, the inference is wrong in the other direction and the
  header must come off — record which.
- `profile/account` — **UNVERIFIED, no `Authorization` sent, and a 401 is the expected outcome if
  the WSO2-product inference holds.** Exact fix named: pass `gateway_token=` to
  `abdm_headers(...)` in `client.get_profile` alongside the existing `x_token=`, which requires
  `service.fetch_profile` to fetch a gateway token it does not currently fetch. Cheap failure: the
  ABHA already exists at this point, no OTP is burned.
- `enrollment/auth/byAbdm` (`verify_mobile_otp`) — still `NotImplementedError`; when implemented it
  carries the gateway Bearer (§2b).
- Keep both existing bullets (masked-mobile regex, masked-only `mobile_number`) unchanged.

---

## 6. OUT OF SCOPE — hard exclusions for STEP 2

- **`verify_mobile_otp` stays `NotImplementedError`.** Docstring line only. No parameter added, no
  live branch written, no change to `service.verify_mobile_otp`'s early-fail guard.
- **`get_profile`'s auth mechanism is untouched.** No `gateway_token` added to `client.get_profile`,
  no gateway-token fetch added to `service.fetch_profile`. Confirmed genuinely different (§2c). The
  M1-tracker checklist item is the entire deliverable for it.
- **Why the Postman recording was wrong is not investigated.** Contract-provenance ticket, separate.
  The docstrings record *that* it was wrong and the evidence; they do not speculate about why beyond
  the finding's own "either incomplete, or ABDM's sandbox gateway config has changed".
- **`crypto.py` untouched.** Correct after PR #18.
- **`backend/docker-compose.yml` is never committed.** It carries `ABDM_MODE: live` locally and is
  already dirty on this branch.
- **`backend/core/` production code untouched.** The single test-only line in §4d is the sole
  exception and is justified there.
- No refactor of `abdm_headers`. It already does the job; the fix is to *use* it correctly.

---

## 7. Expected file list for STEP 2

| File | Change |
|---|---|
| `backend/abdm-adapter/abdm_adapter/client.py` | `send_otp` + `enrol_by_aadhaar` signature & header; 4 docstring sites (§3) |
| `backend/abdm-adapter/abdm_adapter/service.py` | 2 one-line call-site additions (`gateway_token=gateway_token`) — **added to the brief's expected list**, unavoidable and named in §1/§2a |
| `backend/abdm-adapter/tests/test_client_live.py` | §4a, §4b, §4c |
| `backend/core/tests/test_abha.py` | §4d, one assertion line |
| `docs/abdm/M1-tracker.md` | §5 |

`scratchpad/` is untracked and not in `.gitignore`. STEP 2 must not `git add` this memo. Adding
`scratchpad/` to `.gitignore` is a reasonable one-line hygiene fix but is **not** authorised by this
memo — Sandesh's call.

---

## 8. STEP 2 gate (same shape as PR #18)

Sonnet must report before committing: full file list (with any correction to §7 called out
explicitly, as it did last time), test-diff list, exact test names + intents, backend/core and
abdm-adapter test counts pre/post, `ruff check` + `ruff format --check` + `mypy app` clean in both
directories, and the specific assertion that proves the fix against the live shape (§4d). Then STOP.

Commit message ends with: `Live-verified:` + the verbatim §0 finding reference (401 "Missing
Credentials" / WSO2 900902 without Bearer; 400 "Invalid LoginId" with Bearer, abhasbx.abdm.gov.in,
2026-08-24).
