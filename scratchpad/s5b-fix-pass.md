# S-5b: docstring correction, test-suite guard, sweep

Tree at `a219da9` plus every pre-existing uncommitted change (PR-0 through PR-3, F6C, S-5). All left
exactly as found. Nothing committed. No ABDM gateway contacted; every test run in this session used
`ABDM_MODE=stub` set explicitly on the command line. No `.env`, `.env.*`, `local.properties`,
keystore or credential file was opened. `docs/quality/risk-management-file.md` not edited.

Every claim below is tagged MEASURED or INFERRED.

---

## Part 1: `write_out_of_band` docstring

**MEASURED** (inherited from S-5, `scratchpad/s5-abdm-response-unpacking.md` section 2, `pg_stat_activity`
sampled three times at 8s intervals during a killed 70s hang): the deadlock is not a row lock. It is
`pg_advisory_xact_lock(facility)`, taken by `app.services.audit.append`, transaction scoped. A
request session that has appended one audit row holds that lock until its transaction ends; an
out-of-band session appending for the same facility (e.g. `_fail`) blocks on the identical key while
the request session is itself awaiting `write_out_of_band`'s return — a permanent hang, not an error,
with no statement timeout configured to break it.

Rewrote `backend/core/app/db/session.py`, `write_out_of_band`'s docstring, rule 2, to state this: name
the lock (`pg_advisory_xact_lock(facility)`, taken inside `audit.append`), name the consequence
(permanent hang, no timeout), and state the ordering constraint — finish every audit append the
request will make before the first call that can reach an out-of-band write, or fold the whole
failure path (state change + audit row) into one out-of-band call the way `abdm_adapter.service._fail`
already does. The pre-existing row-level case (Phase 1's refresh-token reuse path) is kept as the
narrower instance of the same family, not deleted. The `pg_stat_activity` evidence itself stays in
the record (S-5's memo and this one), not in the docstring, per instruction. Nothing else in
`session.py` changed.

---

## Part 2: test-suite live-gateway guard, and the corrected record

**MEASURED** (S-5, same memo, section 0): `app.config.Settings` (`env_file=".env"`) reads
`ABDM_MODE` from the environment / `.env` ahead of its own `"stub"` field default. This tree's
uncommitted `backend/docker-compose.yml` sets `ABDM_MODE: live` (flagged separately in
`scratchpad/AUDIT5-antigravity-uncommitted-work.md` as blocking, untouched by me). Any contributor
or agent running the backend suite without an explicit `ABDM_MODE=stub` override risks making
`tests/test_abha.py` transact against `https://dev.abdm.gov.in`.

**Guard added**: `backend/core/tests/conftest.py`, new autouse session-scoped fixture
`_stub_abdm_gateway`. It sets `os.environ["ABDM_MODE"] = "stub"` for the whole test session and
restores whatever was there afterward. `test_settings` now takes it as an explicit dependency so
ordering (guard before any `Settings()` construction) does not rely on fixture-registration order
between two same-scope autouse fixtures. Bypass is exactly one env var,
`SAMD_ALLOW_LIVE_ABDM_TESTS=1`; nothing else lifts it. Chose an env var over a pytest marker because
the leak this guards against is itself environment-sourced (`.env` / `docker-compose.yml`), so the
fix and the failure mode live in the same place, and a marker would need to be applied per-test
rather than closing the whole suite's exposure at once.

**MEASURED, this session, single process, `ABDM_MODE=stub` on the command line:**

    cd backend/core && ABDM_MODE=stub ./.venv/bin/python -m pytest tests/ -q
    280 passed, 316 warnings in 223.03s

280 matches S-5's own baseline for `backend/core/tests/` (`s5-abdm-response-unpacking.md` section 4).
Zero failures, zero errors, no live-gateway KeyError. The guard does not regress anything.

**Diagnostic note, not a finding**: two earlier runs in this session (one full-suite, one subset)
launched overlapping against the same Postgres test DB — `conftest.py`'s `_database` fixture does
`DROP ALL` / `CREATE ALL` per test function, so two pytest processes racing that against one database
produced 20 and 55 `sqlalchemy.exc.DBAPIError` collection/setup errors respectively. Self-inflicted by
running commands in parallel against a shared resource, not a regression from this session's changes.
Discarded; the number above is from a single clean process.

**Record corrected.** Two scratchpad notes stated the 14 backend failures were "environmental,
caused by missing ABDM credentials." That is wrong for 13 of the 14; corrected in place, in each
file, with a dated correction paragraph rather than a silent rewrite of the original claim:

- `scratchpad/pr2-failure-taxonomy.md` (§ "Full backend suite: 260 passed, 14 failed"). This one had
  already named the right mechanism (`ABDM_MODE: live` in `docker-compose.yml`) for the 13
  `test_abha.py` failures; the correction is that calling them "environmental" alongside the
  credentials bullet read as "expected noise", when they are actual failed live calls. The
  `test_config.py::test_prod_accepts_real_secrets` bullet (1 of the 14, a config validator
  correctly requiring real prod secrets this session is forbidden to supply) is unaffected and
  still correctly described.
- `scratchpad/sync-failure-taxonomy-diagnosis.md` (§4.3, "The 14 failing ABDM tests are an
  environment without gateway credentials, not this bug."). This sentence was the wrong claim
  outright: the `KeyError: 'accessToken'` is what a live sandbox response missing that field
  produces, not evidence of an environment lacking credentials. The surrounding verdict — that the
  typed `_result_from_transport_error` handling itself is correct, working code — is untouched;
  only the causal claim for why the suite reaches a live gateway at all was wrong.

**Third note not found.** The task description named three notes with this claim. I searched
`scratchpad/*.md` and `docs/` repeatedly (`grep -rn -i "credential"`, `"environmental"`, `"14 fail"`,
`"pre-existing"` combined with `abha`/`abdm`/`ABDM_MODE`, and a full-text pass over every file that
mentions `test_abha` or `ABDM_MODE`) and found exactly these two. Reporting this rather than
fabricating a third correction: if a third exists, it used different wording than any of the above
searches caught, or it is not in scratchpad/docs at all (e.g. a chat-only claim never written down).

---

## Part 3: the sweep. READ-ONLY, no edits.

**Search used**: every call site of `app.services.audit.append` and `app.db.session.write_out_of_band`
across the whole backend, tests excluded, plus a manual read of the caller chain for each:

    grep -rn "audit_service.append\|write_out_of_band" --include=*.py core abdm-adapter | grep -v /tests/

Eight files matched. Each checked for whether an in-request `audit.append` (on the caller's own
request session) can precede a `write_out_of_band` call reachable in the same request:

| File | Shape | In-request audit before an out-of-band write? |
|---|---|---|
| `core/app/db/session.py:77` | defines `write_out_of_band` | n/a |
| `core/app/services/kernel.py:142,159,380,397` | `_fail` and `_record_success` both write their audit row *inside* the out-of-band session only; `assess`/`evaluate`/`_forward` never call `audit_service.append` on the request session at all | **No** — `_forward` (`kernel.py:164-300`) takes no session; every audit row for a kernel call is out-of-band, success or failure, never split |
| `core/app/api/v1/auth.py:68,118,184,226,289` | `login` (`:94-105`): `_audit_out_of_band` only reached from the `except SamdError` branch of `authenticate`, which commits its lockout counter (`auth.py:270`, no audit call) then raises — no prior in-request audit. `refresh` (`:159-182`): `rotate_refresh` (`services/auth.py:325-397`) never calls `audit_service.append`; on `AUTH_REFRESH_REVOKED` with `log_context` the route does its own recovery session then `_audit_out_of_band` — no prior in-request audit | **No** on every path checked |
| `core/app/services/sync.py:431,634` | both calls are on the request session (`session_scope`); `push()`'s own docstring states it deliberately never uses `write_out_of_band` | **N/A** — no out-of-band write exists in this file to reach |
| `core/app/api/v1/patients.py:46` | route-local `_audit` helper, request session only; file never imports or calls `write_out_of_band`, never reaches kernel/abdm services | **N/A** |
| `core/app/api/v1/encounters.py:40` | same shape as `patients.py` | **N/A** |
| `core/app/middleware/audit.py:93` | writes `request_completed` **after** `self.app(scope, receive, send_wrapper)` returns (`audit.py:60-61`) — i.e. after the request's own `session_scope` dependency has already committed/rolled back and released its advisory lock. Sequential, not concurrent, with anything the handler did | **No** — cannot race a lock that is already released by the time this runs |
| `abdm-adapter/abdm_adapter/service.py:86,130,146` | `_audit` (in-request) is called only as the LAST statement of each state-changing function (`:221,301,404,416,497,571`); every `_fail` call in the same function is upstream of it, on the `try`/`except` branches that precede the eventual audit. Confirmed by reading `submit_identity`, `verify_otp`, `verify_mobile_otp`, `fetch_profile` line by line | **No** — this is S-5's own fix already landed (uncommitted, present in this tree); the wide-form guard is documented in-source at `service.py:364-385` |

**No second `_audit`-then-`_fail` (or equivalent in-request-audit-then-out-of-band-write) site found.**
The pattern S-5 fixed in `abdm_adapter/service.py` does not recur elsewhere: `kernel.py` never mixes
in-request and out-of-band audit writes for the same call; `sync.py` never uses `write_out_of_band`
at all; `auth.py`'s two out-of-band call sites are both reached before any in-request audit append in
the same request; the audit middleware runs strictly after the request transaction (and its lock)
has already closed.

Sweep is clean. Not stopping; proceeding to the close-out below.

---

## For the owner. Recorded, not acted on.

**Hazard register candidate**, joining the three items already pending sign-off named in this
session's brief: a clinical request against the ABHA registration flow (identity verification) can
hang indefinitely — not error, not time out — if a future edit reintroduces an in-request
`audit.append` before a reachable `_fail`/out-of-band write in the same request, in
`abdm-adapter/abdm_adapter/service.py` or anywhere a similar out-of-band audit pattern is added later.
S-5's fix and this session's docstring correction address the currently-shipped code; they do not
prevent a future regression of the same shape, since nothing in the type system enforces the
ordering. `docs/quality/risk-management-file.md` was not touched, per instruction; this paragraph is
the flag for whoever next opens it.

---

STOP: S-5b complete, uncommitted. 280 tests green, run with `ABDM_MODE=stub` on the command line.
Sweep found 0 second deadlock sites. Third "14 failures environmental" scratchpad note not located
despite a repeated search (two corrected, documented above; searches listed). Confirm before S-4.
