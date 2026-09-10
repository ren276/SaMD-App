# AUDIT: "classifier not running" during consultation on emulator

Run date: 2026-09-09. Branch `feat/slm-approved-record-reader`.

## Symptom reported

During a consultation, the AI Assessment screen shows a mock result labeled *"Offline
fallback (mock) — ML server unavailable"* instead of a real classifier prediction.

## Layer 1 — environment (found broken, now fixed and verified)

Three unrelated infra problems, all confirmed fixed:

1. **`backend-api-1` and `backend-db-1` containers were `Exited`.** `samd-classifier` container was
   already `Up (healthy)` the whole time — the classifier itself was never the problem.
   Fixed: `docker compose up -d db api` in `backend/`.

2. **`local.properties` had a stale LAN IP.** `BACKEND_BASE_URL`/`KERNEL_BASE_URL` pointed at
   `10.203.6.84` (a DHCP lease from a previous session); the machine's current IP is `10.203.3.29`
   (`ip -4 addr show`, `wlp0s20f3`). The emulator's dev-flavor build bakes this IP into
   `BuildConfig` at build time, so the app was pointing at a dead host. Fixed: updated both values
   in `local.properties` and rebuilt.

3. **Emulator `/data` partition was full** (`INSTALL_FAILED_INSUFFICIENT_STORAGE`, "Requested
   internal only, but not enough space" / "Failed to override installation location" from a raw
   `adb install`). Root cause: an unrelated app, `com.google.aiedge.gallery` (Google's on-device
   model-gallery demo app, nothing to do with SaMDApp), had **6.1 GB** in
   `/data/media/0/Android/data/`, plus 750 MB of stale junk in `/data/local/tmp`. Cleared both
   (`pm clear com.google.aiedge.gallery`, `rm -rf /data/local/tmp/*`) — freed 8.2 GB.

After these three fixes, rebuilt + reinstalled `app-dev-debug.apk`, confirmed via `nc -z` from
inside the emulator that it can reach the host's backend on the new IP:8080, and confirmed
`docker exec backend-api-1 python3 -c "urllib.request.urlopen('http://host.docker.internal:8000/health')"`
succeeds — the backend-to-classifier link is fine.

**None of this was the reported bug.** Environment is now clean; the app still reproduces the
exact symptom on a real consultation.

## Layer 2 — the actual reproducible bug

Signed in as a seeded ASHA worker, picked the "Anita Kumari" demo patient, ran the registration →
consultation → "Confirm & send" flow. Backend log for that request:

```
{"code": "SAMD-ENC-4002", "status": 404, "event": "samd_error", "method": "POST", "path": "/api/v1/assess", ...}
```

`SAMD-ENC-4002` = `ErrorCode.ENC_CASE_NOT_FOUND` (`backend/core/app/services/kernel.py:93`,
`_resolve_case_record`): **the backend has no `case_records` row with that id.** The kernel/
classifier call never even fires — this 404 happens before the backend would call the classifier
at all. Confirmed by querying Postgres directly: `case_records` had no row for the case just
created in the emulator.

### Why the case doesn't exist server-side yet

- `GenerateKernelReportUseCase` (KDoc, verified against source): "if the real call fails for ANY
  reason (IOException, HttpException, timeout, server offline)... asks `kernelFallbackSource` for
  a fallback... dev binds a mock scenario source." So a 404 (a data problem) and a genuine
  classifier outage are **indistinguishable** to this code path — both render as
  "ML server unavailable." That label is misleading; the real cause here is unrelated to the
  classifier container's health.

- `AssessmentWorker`/`AssessmentRunner` (the single orchestrator for both first-assessment and
  every retry) calls `/api/v1/assess` with no dependency on the case having been pushed to the
  backend first.

- `SyncStatusImpl` (`data/sync/SyncStatusImpl.kt`) only auto-syncs on an **offline→online
  transition** ("`drop(1)`... an app that launches already online shouldn't trigger a sync it
  didn't ask for") or on `SyncOutboxScheduler`'s periodic WorkManager job (15-minute-class
  interval). Neither fires in the seconds between "register a demo patient" and "Confirm & send."

So: register + immediately assess, while already online, races ahead of the only two things that
would have pushed the case to the backend. This is a same-session ordering bug, not a classifier
problem — and it would hit a real ASHA worker on a real device the same way, any time they
register a new patient and request an AI assessment right away without an intervening sync tick.

## Fix attempted (applied, compiles, unit-tested, does NOT fully resolve it)

Added a call to the existing `domain.sync.SyncStatus.syncNow()` seam at the top of
`AssessmentRunner.run()` (best-effort, ignored on failure — offline still falls through to the
existing honest UNAVAILABLE/mock path). This is the correct architectural seam: it's already used
elsewhere for "sync now" callers, keeps the domain layer's existing boundary (no `data.sync` import
into `domain.usecase`), and is the single place both first-assessment and retry route through.

Files touched:
- `app/src/main/java/com/example/samdapp/domain/usecase/AssessmentRunner.kt`
- `app/src/test/java/com/example/samdapp/domain/usecase/AssessmentRunnerTest.kt` (added
  `FakeSyncStatus` wiring + one assertion that `run()` calls `syncNow()`)

Unit tests pass, `compileDevDebugKotlin` clean.

**Re-ran the live UI test after this fix.** Backend log now shows the push actually firing:

```
{"status": 200, ..., "path": "/api/v1/sync/push", ...}
{"code": "SAMD-ENC-4002", "status": 404, ..., "path": "/api/v1/assess", ...}   <- 2 seconds later
```

`sync/push` now runs immediately before `assess`, and returns HTTP 200 — but Postgres still has
**no new `case_records` row**, and `/assess` still 404s. So the fix was necessary but not
sufficient.

## Open question — where this needs to go next

`sync/push`'s 200 is a **batch envelope**; `SyncOutboxDrainer.sendAndApply` already expects
per-record results inside it (`result.data.results.forEach { ackResult -> ... }`, applied via
`SyncOutboxRepository.applyAck`, which can record a `rejected`/`SAMD-SYNC-xxxx` status per row).
The overall HTTP 200 does not mean the `case_records` row was accepted — it likely means the batch
was received and processed, with that specific record's upsert rejected internally and silently
swallowed at the UI layer (nothing surfaces per-record outbox failures on this screen).

Confirmed locally that the outbox **does** pick up the case: `CaseRecordEntity.syncState` defaults
to `PENDING` on creation, and `RoomSyncOutboxRepository.collectPendingRecords()`
(`RoomSyncOutboxRepository.kt:69`) includes `caseRecordDao.getPendingForSync()` in the packed
batch. So the row is queued and sent — something in the backend's per-record validation for a
freshly-created `case_records` upsert is rejecting it.

**Could not go further**: the app's local Room DB is SQLCipher-encrypted
(`implementation(libs.sqlcipher.android)` in `app/build.gradle.kts`), so `sqlite3` against the
pulled `.db` file fails with "file is not a database" without the runtime passphrase — couldn't
directly confirm the local `syncErrorCode`/`syncState` column reads `FAILED`/rejected after the
push.

**Next steps for whoever picks this up:**
1. Inspect the actual `/api/v1/sync/push` request/response body for this case's record — either
   add a temporary debug log around `SyncOutboxDrainer.sendAndApply`'s `result.data.results`, or
   turn on the OkHttp logging interceptor (`ENABLE_NETWORK_LOGGING=true` is already set in
   `local.properties`) and read Logcat for the raw JSON.
2. Check the backend's `/sync/push` handler for `case_records` for what it rejects on a
   first-time upsert — likely candidates: an FK-order problem (patient/encounter rows in the same
   batch not yet applied when `case_records` is validated), a required field the "demo patient"
   quick-fill path leaves null/zero, or a worker/facility mismatch.
3. Once the per-record rejection reason is visible, decide whether the fix belongs in the batch
   packer (ordering/dependency between tables in one batch) or the backend's upsert validation.
4. Separately, worth flagging to whoever owns `GenerateKernelReportUseCase`: collapsing "case not
   found" (404, a client-side sync bug) into the same "ML server unavailable" mock-fallback label
   as a genuine kernel outage is misleading during debugging — this audit took a while to get past
   that label to the real cause.
