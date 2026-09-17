# Handoff — connectivity triage and the assess-before-sync fix

**Date:** 2026-09-11
**Branch:** `feat/dynamic-local-connection`
**Started from:** "no connection to samd server"
**State:** all code changes are **uncommitted**. 515 dev unit tests pass, staging/prod compile, dev build installed on device.

**Redacted for a public repository (post-review correction).** Case UUIDs, an audit `request_id`,
the pilot facility id, the test device's serial, and the campus Wi-Fi SSID/network addresses that
would identify the pilot site have been replaced with placeholders below. Every technical claim,
the ordering proof included, is unchanged; only the identifying particulars are removed.

---

## TL;DR

What started as one "no connection" report turned out to be **three unrelated problems** stacked on top of each other. Each one masked the next.

1. **Phone off-LAN** — handset on cellular, couldn't reach backend or Pi. Worked around via USB tunnels.
2. **`kernel-hub.local` unresolvable on Android** — no mDNS in Android's system resolver. Fixed with an NSD-backed `okhttp3.Dns`. Needs a Pi-side Avahi file that is **still not installed**.
3. **Assessment 404 = `SAMD-ENC-4002`** — not a routing/availability problem at all. Cases were assessed before they were synced, so the backend couldn't resolve them. Fixed by pushing before assessing.

Problem 3 is the one with real clinical impact; 1 and 2 are dev-environment.

---

## Problem 1 — phone not on the LAN (environment, worked around)

Test device `<redacted-device-serial>` (vivo I2302) is on **cellular**, not Wi-Fi:

- Active default network `<redacted>` = `MOBILE[NR]`, IPv6-only + 464XLAT (carrier-assigned address)
- No `wlan0` address. Wi-Fi is *enabled* but never associates — loops `L2ConnectingState -> DisconnectedState` against the pilot site's campus Wi-Fi (WPA_EAP)

Host is on the pilot site's LAN (`<redacted-subnet>`). The Pi gateway is on the same subnet, but the phone can reach neither.

**Worked around with USB tunnels, not fixed.** Connecting Ethernet to the Pi changed nothing for the phone — the phone's problem is its own network.

Note: campus WPA_EAP networks often have client isolation, so even a successfully-associated phone may still not reach the host. The USB path avoids that question entirely.

---

## Problem 2 — `.local` doesn't resolve on Android

`PI_GATEWAY_BASE_URL` defaulted to `http://kernel-hub.local:8090/`. Android's `getaddrinfo` has **no mDNS path**, so `Dns.SYSTEM` throws `UnknownHostException` and every gateway call failed — looking exactly like "gateway down", made worse by the deliberate 2s connect timeout in `PiGatewayNetworkModule.kt`.

### The thing that isn't obvious

The Pi advertises **no DNS-SD service** for the gateway. `NsdManager` does *service discovery*, not bare hostname lookup, so there was nothing to find. All that exists is Avahi's automatic record:

```
= <iface> IPv4 kernel-hub   Workstation   local
   hostname = [kernel-hub.local]   address = [<redacted-pi-ip>]   port = [9]
```

`_workstation._tcp`, port **9** (discard) — not the gateway, and disabled by default in many Avahi configs. So the fix is necessarily **two-sided**.

### App side (done)

`app/src/dev/java/com/example/samdapp/data/vitalssource/NsdGatewayDns.kt` — new.

An `okhttp3.Dns`, **deliberately not an Interceptor**. This was the deciding constraint: commit `b13913e` removed dynamic-host failover because it replayed a non-idempotent Aadhaar submit (confirmed cause of the ABHA OTP regression). `startSession` is a POST, so an interceptor here would have reintroduced that exact hazard. A `Dns` maps name→address before the connection opens — never sees a response, never rewrites a URL, never re-issues.

- Installed on the Pi gateway client only; backend and ABHA stacks keep `Dns.SYSTEM`
- Non-`.local` names delegate to the system resolver
- An mDNS miss **throws** rather than falling back — instrument traffic reaching some *other* machine is worse than reaching nothing
- 60s TTL cache, marked with a `ponytail:` comment naming the ceiling (a `Dns` can't see whether the connection it fed succeeded)
- Service type `_samd-gw._tcp.`, dedicated rather than `_http._tcp` — a campus LAN has many HTTP advertisers
- `getSystemService` deferred into the discovery lambda, so building the Hilt graph touches no framework class

Tests: `app/src/testDev/.../NsdGatewayDnsTest.kt`, 6 tests. The `NsdManager` half isn't exercised (needs a device + LAN); everything wrapped around it is — which names go to mDNS, miss-throws-not-falls-back, TTL cache hit and expiry.

**Refactor this forced:** `providePiGatewayOkHttpClient` needed a `Context`, which would have broken `PiGatewayVitalsSourceTest` — that test calls the provider directly on the JVM specifically to assert real production wiring (notably the *absent* auth interceptors). Split into `piGatewayOkHttpClient(dns: Dns = Dns.SYSTEM)`, which stays Context-free. The test now calls that and keeps its original value.

### Pi side — **NOT DONE, BLOCKS THE `.local` PATH**

`tools/kernel-hub-avahi.service` exists in the repo but is **not installed on the Pi**. Verified still missing: `avahi-browse -rt _samd-gw._tcp` returns nothing.

I can't deploy it — `ssh kernel-hub.local` gives `Permission denied (publickey,password)`. A first attempt failed because the file lives on the dev machine, not on the Pi (`install: cannot stat 'tools/kernel-hub-avahi.service'`). A `sudo tee` heredoc was provided instead; it appears not to have been run, or `/etc/avahi/services/` doesn't exist — worth checking with `ls -la /etc/avahi/services/`.

On the Pi:
```bash
sudo tee /etc/avahi/services/kernel-hub.service > /dev/null <<'EOF'
<?xml version="1.0" standalone='no'?><!--*-nxml-*-->
<!DOCTYPE service-group SYSTEM "avahi-service.dtd">
<service-group>
  <name replace-wildcards="yes">%h</name>
  <service>
    <type>_samd-gw._tcp</type>
    <port>8090</port>
  </service>
</service-group>
EOF
sudo systemctl reload avahi-daemon
avahi-browse -rt _samd-gw._tcp    # expect kernel-hub, <pi-ip>, port 8090
```

**This is not currently on the critical path** — with `PI_GATEWAY_BASE_URL` pinned to `127.0.0.1`, `NsdGatewayDns` delegates to the system resolver and NSD never runs. The NSD work only starts paying off once the phone is on the Pi's LAN.

---

## Problem 3 — the real bug: assess ran before sync

### Symptom

```
KernelUseCase   W  Kernel API unavailable — trying fallback source. Reason: HTTP 404 Not Found
EvaluateUseCase W  Evaluate API unavailable — recording an honest failure marker. Reason: HTTP 404 Not Found
```

Reads like a routing or availability problem. It is neither.

### What was ruled out

- **Backend→classifier routing is correct.** From inside `backend-api-1`: `/v1/assess` → 422, `/api/v1/evaluate` → 422 (field validation, so the routes exist), `/health` → 200. `host.docker.internal:8000` resolves fine despite `backend_default` vs `samdclassifier_default` being separate networks.
- The classifier's asymmetry (`/v1/assess` *without* `/api`, `/api/v1/evaluate` *with* it) is already mirrored exactly in `backend/core/app/adapters/kernel/client.py:26`. Ugly, correct, not the bug.
- **The classifier has never failed.** Every `kernel_call_log` row is `SUCCESS/200`, and there were *no rows at all* for the failing attempts — proving `_forward` was never entered.

### Actual cause

The 404 is `SAMD-ENC-4002` = `ENC_CASE_NOT_FOUND`, raised at `backend/core/app/services/kernel.py:93`:

```python
if case is None or case.facility_id != worker.facility_id:
    raise SamdError(ErrorCode.ENC_CASE_NOT_FOUND)
```

`case is None` branch — only one facility exists in this pilot deployment (`<redacted-facility-id>`), all cases in it, so mismatch was ruled out.

**The proof of ordering.** All audit rows for `<redacted-case-uuid>` carry `<redacted-request-id>`, which is the `sync_batch_received` at **T+5:09**:

```
T+5:09  sync_batch_received                        (req <redacted-request-id>)
T+0:12  case_sent_to_doctor      <redacted-case-uuid> (req <redacted-request-id>)
T+0:09  kernel_response_received <redacted-case-uuid> (req <redacted-request-id>)
T+0:09  evaluate_response_failed <redacted-case-uuid> (req <redacted-request-id>)
T+0:00  encounter_started        <redacted-case-uuid> (req <redacted-request-id>)
```

`created_at` at T+0:00 is **device** time replayed through sync — the row was actually *inserted* at T+5:09. So when assess ran live at T+0:09, the case genuinely did not exist server-side. It appeared roughly five minutes later.

Every new case gets assessed the instant it's created; its `case_records` row only reaches the backend in the *next* sync batch. Assess always runs ahead of the push that would make it resolvable.

### Supporting finding — worth its own look

**13 case ids appear in `audit_events` with no matching `case_records` row.** Audit is append-only and hash-chained so it accepts device-replayed rows without requiring the case to exist. Defensible in isolation, but it means for those encounters the audit trail is the *only* record they happened. Not investigated further. **Recommend a separate look.** (The 13 case ids themselves are redacted here; the full list is in the original audit query, not reproduced in this public file.)

### The fix (option 1 of the two offered — chosen by user)

`AssessmentRunner.kt:82`, after resolution succeeds, before either kernel leg:

```kotlin
syncStatus.syncNow().onFailure { e ->
    logger.warning("Pre-assessment sync failed for case $caseRecordId: ${e.message}")
}
```

Design notes that matter if this gets revisited:

- **No new abstraction.** `SyncStatus` is already a *domain* interface (`domain/sync/SyncStatus.kt`) whose `syncNow()` does exactly this push.
- **Why not `SyncOutboxScheduler`:** it lives in `data/sync`, and `grep` shows **zero** domain files importing `com.example.samdapp.data.` — that boundary is strictly enforced.
- **Why `AssessmentRunner` and not `AssessmentWorker`:** the runner's own KDoc states all orchestration lives there, and it covers assess + evaluate in one place. `AssessmentWorker` is the only caller of `run()`.
- **Failure is non-fatal on purpose.** `syncNow()` refuses when offline; a failed push means the kernel call fails anyway and lands in the existing honest `UNAVAILABLE` state.

Tests added to `AssessmentRunnerTest.kt` (3, reusing the existing `FakeSyncStatus`):
- `the case is pushed before the kernel call runs` — the ENC-4002 regression guard
- `a failed pre-assessment sync still lets the assessment run`
- `a case that cannot be resolved is never pushed`

**Mutation-checked:** moving `syncNow()` after the kernel call fails that first test and *only* that test (`10 tests completed, 1 failed`). Independently reproduced during the post-review pass on `fix/assess-ordering-and-chip-wrapping`: identical result, same single test failed.

**Known cost:** `syncNow()` drains the *whole* outbox, not just this case, so the first assessment after a backlog pauses on the full push. Fine at PHC volumes; narrowing it needs a per-case push API that doesn't exist.

---

## NOT YET VERIFIED LIVE

**The fix is installed on the device but has not been exercised end to end.** Nobody has run an encounter through to assessment since the install.

Success looks like a fresh `SUCCESS/200` pair in `kernel_call_log` — the first since this session's fix:

```bash
docker exec backend-db-1 psql -U samd -d samd -c \
  "select endpoint, outcome, http_status, started_at from kernel_call_log order by started_at desc limit 6;"
```

Also watch: `docker logs backend-api-1 -f | grep -E 'assess|evaluate|sync/push'`

---

## Environment state — EPHEMERAL, will not survive a reboot

### Host processes
```bash
socat TCP-LISTEN:8090,fork,reuseaddr TCP:<redacted-pi-ip>:8090 &
```
Relays the Pi gateway through the host. **If it dies, the Pi gateway goes with it.**

### adb reverse tunnels
```bash
adb reverse tcp:8080 tcp:8080   # backend
adb reverse tcp:8090 tcp:8090   # Pi gateway, via the socat relay above
```
Cleared by a USB replug or `adb kill-server` — re-run both after either.

### `local.properties` (gitignored)
```properties
BACKEND_BASE_URL=http://127.0.0.1:8080/
KERNEL_BASE_URL=http://127.0.0.1:8080/
ENABLE_NETWORK_LOGGING=true
PI_GATEWAY_BASE_URL=http://127.0.0.1:8090/   # delete once phone is on Pi LAN + Avahi file installed
```

### Docker
`backend-api-1` (8080), `backend-db-1` (5432), `samd-classifier` (8000/8001). Backend was restarted this session, so `docker logs` only covers since then.

### One-shot restore after a reboot
```bash
cd backend && docker compose up -d && cd ..
socat TCP-LISTEN:8090,fork,reuseaddr TCP:<redacted-pi-ip>:8090 &
adb reverse tcp:8080 tcp:8080 && adb reverse tcp:8090 tcp:8090
```

---

## Uncommitted changes

```
 M app/build.gradle.kts                                                  # PI_GATEWAY_BASE_URL comment, plus resolveDevHostIp() - see correction below
 M app/src/dev/java/com/example/samdapp/di/PiGatewayNetworkModule.kt     # NsdGatewayDns wiring + client split
 M app/src/main/java/com/example/samdapp/domain/usecase/AssessmentRunner.kt      # pre-assessment sync
 M app/src/test/java/com/example/samdapp/domain/usecase/AssessmentRunnerTest.kt  # 3 tests
 M app/src/testDev/java/com/example/samdapp/data/vitalssource/PiGatewayVitalsSourceTest.kt
?? app/src/dev/java/com/example/samdapp/data/vitalssource/NsdGatewayDns.kt
?? app/src/testDev/java/com/example/samdapp/data/vitalssource/NsdGatewayDnsTest.kt
?? tools/                        # dev-connect.sh (pre-existing), kernel-hub-avahi.service (new)
```

**Correction (post-review, `scratchpad/resolve-dev-host-ip-review.md`).** The
`app/build.gradle.kts` line above understated this file's change: it also added
`resolveDevHostIp()`, a build-time function that scans host network interfaces to compute
`BACKEND_BASE_URL` when `local.properties` leaves it blank or sets it to `auto`. That mechanism
is unrelated to the PI_GATEWAY_BASE_URL/.local problem this handoff describes and was not
recorded here at the time. **It is dev-flavor-gated, not release-build-type-gated**: `staging`
and `prod` are separate `productFlavors` blocks that never reference it, so a `stagingRelease`
or `prodRelease` build - the artifact actually distributed - cannot reach it. A `devRelease`
variant (dev flavor, release build type) is still buildable and still reaches it; the gate is
the flavor, not the build type, and the record should not say otherwise. Full review, including
the two residuals it did not close: `scratchpad/resolve-dev-host-ip-review.md`. **Residual not
closed by this fix, recorded here because a scratchpad file does not itself travel through PR
review**: a build-time log line (added in a follow-up commit) reports whatever host was resolved,
but does not stop a multi-homed host (a VPN interface up alongside the real LAN adapter) from
having its address picked instead of the developer's actual LAN IP - the deny-list in
`resolveDevHostIp()` names six specific virtual-adapter prefixes and no VPN-client prefixes.
Closing that needs a wider deny-list or an allow-list, out of scope for the log-only fix.

**Second correction (PR 55 review, CodeRabbit).** Two further findings on this branch's dev
connectivity layer are recorded, not fixed: the dev flavor's `usesCleartextTraffic="true"` plus
`DevServerConfig` accepting non-HTTPS runtime overrides means Aadhaar/OTP traffic can travel in
cleartext on a dev device; and `NsdGatewayDns` resolves the Pi gateway by exact mDNS service name
with no TLS or identity check, so any same-LAN advertiser of that service name is trusted. Both
are real questions about the threat model for a LAN-local, dev-flavor-only tool, deferred to an
operator decision rather than fixed unilaterally as part of a review pass.

Pre-existing, not mine, **decide before committing**:
```
 M backend/docker-compose.yml     # ABDM_MODE: stub -> live   <-- container is running live ABDM
 M backend/README.md
 M .idea/deploymentTargetSelector.xml
```

Suggested split — two logically separate changes:
1. `fix(dev): resolve the Pi gateway's .local host over NSD` — NsdGatewayDns + test + module split + gradle comment + `tools/kernel-hub-avahi.service`
2. `fix(assessment): push the case before assessing it` — AssessmentRunner + tests

Per CLAUDE.md: no `Co-Authored-By` AI trailers, and no push without explicit per-turn consent.

---

## Open items / to discuss

1. **Verify the fix live** — highest priority, see above.
2. **Install the Avahi file on the Pi** — needed before the `.local` + NSD path can ever run.
3. **13 orphaned `case_record_id`s in `audit_events`** — data-integrity question, unexamined.
4. **`GenerateKernelReportUseCase.kt:250` conflates failure modes.** One `catch (e: Exception)` reports network-down, timeout, HTTP error and parse error alike as *"Kernel API unavailable"*. A 404 meaning "your case isn't synced" gets announced as the ML server being unreachable — this is precisely what sent us down the routing rabbit hole. Splitting 4xx (client/state) from transport failure would have made this session much shorter. Only transport failure is genuinely "unavailable", and arguably only that justifies a fallback. **I'd start here next session.**
5. **`ENC_CASE_NOT_FOUND` collapses two causes** (missing case vs. facility mismatch) into one code. Fine as a client response; a distinguishing server-side log line is cheap and would have saved time.
6. **Phone Wi-Fi never associates** with the campus network. Unresolved; USB tunnels sidestep it.
7. **`ABDM_MODE: live`** in the uncommitted compose diff — intentional? An ABDM call already failed this session with `Temporary failure in name resolution` (`SAMD-ABHA-2006`, 502) before a later one succeeded.

---

## Gotchas found this session

- Device DB is **SQLCipher-encrypted** (not `SQLite format 3`). `adb exec-out run-as ... cat databases/samd_app.db` pulls it but it won't open without the key. Main db is 4KB; everything lives in the 2MB WAL.
- `created_at` on synced rows is **device** time, not insert time. Correlate `request_id` against `sync_batch_received` to find when a row actually landed — that's what cracked problem 3.
- `kernel_call_log` has `started_at`, **not** `created_at`.
- There is no `sync_log_entries` table (the model is `SyncLogEntry`; check the real table name).
- Backend `user_accounts` uses `worker_id`, not `username`.
- Android device has no `curl` and no usable `nc` — test reachability from the host side instead.
- `local.properties` has **no trailing newline**; a bare `>>` append joins onto the last line.
