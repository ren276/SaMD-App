# Handoff for Claude: Sync-Before-Assess Fix & Dynamic Local Dev Infrastructure

**Date:** 2026-09-10  
**Current Git Branch:** `feat/dynamic-local-connection`  
**Base Commit:** `293f3ab` (merged PR #51 on `master`)  
**Previous Working Branch:** `fix/sync-before-assess-v2`  

---

## 1. Executive Summary

This handoff covers two completed, verified milestones:
1. **Fix for Classifier Returning Fallback `UNAVAILABLE` Response (Race Condition Fix)**
   - **Root Cause:** When a consultation was submitted, `AssessmentWorker` ran immediately while the case record was still `PENDING` in the local outbox. `POST /api/v1/assess` sent `case_token: caseRecordId` to the backend, where `_resolve_case_record()` queried PostgreSQL. Because the outbox had not yet pushed the row, the backend returned 404 (`SAMD-ENC-4002`), which Android caught and saved as `InferenceSource.UNAVAILABLE`.
   - **Solution:** Added a Stage 0 in-process outbox drain and sync-state gate in `AssessmentRunner` before `POST /api/v1/assess` is invoked.
   - **Verification:** Verified end-to-end on physical test device `10BE3A09C700046`. Consultation for case `bc51fb0d-bbee-466b-9901-956a61dc45e4` drained outbox (`200 OK`), called assess (`200 OK`), called evaluate (`200 OK`), and returned **Type 2 diabetes mellitus (97.98% confidence)** with **Glimepiride** treatment.
2. **Build Failure Fix (`No space left on device`)**
   - Room KSP annotation processing failed during `org.sqlite.SQLiteJDBCLoader.extractAndLoadLibraryFile` because `/tmp` on `/dev/nvme0n1p2` fell below ext4 non-root reserve.
   - Redirected `java.io.tmpdir` and `org.sqlite.tmpdir` in `gradle.properties` to `/media/sandesh/extra-ssd/tmp` (607GB free).
3. **Dynamic Local Connection & ADB Reverse Infrastructure (`feat/dynamic-local-connection`)**
   - Replaced hardcoded LAN IP fallbacks with dynamic host IP resolution in Gradle.
   - Added OkHttp interceptor with transparent multi-candidate failover (`127.0.0.1:8080` reverse tunnel -> LAN IP -> `10.0.2.2`).
   - Created developer helper `tools/dev-connect.sh`.

---

## 2. Git Branch & Working Tree State

### Current Branch
`feat/dynamic-local-connection`

### Modified / Created Files
#### A. Sync-Before-Assess Fix (REQ-HAN-07)
* **[NEW]** `app/src/main/java/com/example/samdapp/domain/sync/OutboxDrainer.kt`
  - Domain-layer SAM interface: `fun interface OutboxDrainer { suspend fun drainAll(): Result<Unit> }`.
* **[NEW]** `app/src/main/java/com/example/samdapp/domain/usecase/GetCaseRecordSyncState.kt`
  - Domain-layer SAM interface: `fun interface GetCaseRecordSyncState { suspend operator fun invoke(caseRecordId: String): SyncState? }`.
  - Extracted as a top-level file to prevent circular KSP/Hilt processing errors with `AssessmentRunner`.
* **[MODIFIED]** `app/src/main/java/com/example/samdapp/data/local/dao/CaseRecordDao.kt`
  - Added one-shot read query: `@Query("SELECT syncState FROM case_records WHERE id = :caseRecordId") suspend fun getSyncState(caseRecordId: String): SyncState?`.
* **[MODIFIED]** `app/src/main/java/com/example/samdapp/data/sync/SyncOutboxDrainer.kt`
  - Implements `OutboxDrainer`: `override suspend fun drainAll(): Result<Unit> = drain()`.
* **[MODIFIED]** `app/src/main/java/com/example/samdapp/domain/usecase/AssessmentRunner.kt`
  - Injected `OutboxDrainer` and `GetCaseRecordSyncState`.
  - Added Stage 0: executes `outboxDrainer.drainAll()`. If case record's `syncState` is not `SyncState.SYNCED`, records `UNAVAILABLE` and aborts before issuing doomed network request.
* **[MODIFIED]** `app/src/main/java/com/example/samdapp/di/RepositoryModule.kt`
  - Binds `OutboxDrainer` to `SyncOutboxDrainer`.
  - Provides `GetCaseRecordSyncState` via `caseRecordDao.getSyncState(caseRecordId)`.
* **[MODIFIED]** `app/src/test/java/com/example/samdapp/domain/usecase/AssessmentRunnerTest.kt`
  - Added 4 unit tests covering: pre-assess drain execution, drain error tolerance, and PENDING/FAILED sync state gating.

#### B. Gradle & Build Fixes
* **[MODIFIED]** `gradle.properties`
  - Added `-Djava.io.tmpdir=/media/sandesh/extra-ssd/tmp -Dorg.sqlite.tmpdir=/media/sandesh/extra-ssd/tmp`.
* **[MODIFIED]** `app/build.gradle.kts`
  - Added `resolveDevHostIp()` for physical interface detection.
  - Added `unitTests.isReturnDefaultValues = true`.

#### C. Dynamic Dev Connection Infrastructure
* **[NEW]** `app/src/main/java/com/example/samdapp/data/remote/dev/DevServerConfig.kt`
* **[NEW]** `app/src/main/java/com/example/samdapp/data/remote/dev/DevDynamicHostInterceptor.kt`
* **[NEW]** `app/src/dev/java/com/example/samdapp/receiver/DevServerReceiver.kt`
* **[NEW]** `app/src/test/java/com/example/samdapp/data/remote/dev/DevDynamicHostInterceptorTest.kt`
* **[NEW]** `tools/dev-connect.sh`
* **[MODIFIED]** `app/src/main/java/com/example/samdapp/di/NetworkModule.kt`
* **[MODIFIED]** `app/src/dev/AndroidManifest.xml`
* **[MODIFIED]** `backend/README.md`
* **[MODIFIED]** `backend/docker-compose.yml`

#### D. Local Test Scripts & Config
* **[MODIFIED]** `backend/core/app/scripts/seed_accounts.py` (added `--no-must-change-pin` flag for dev convenience)
* **[MODIFIED]** `local.properties` (updated to active host IP)

---

## 3. How to Run & Verify

### 1. Verification of Unit Tests
```bash
TMPDIR=/media/sandesh/extra-ssd/tmp ./gradlew testDevDebugUnitTest
```
*Expected:* `BUILD SUCCESSFUL` (all unit tests pass, including `AssessmentRunnerTest` and `DevDynamicHostInterceptorTest`).

### 2. Verification of Build Assembly
```bash
TMPDIR=/media/sandesh/extra-ssd/tmp ./gradlew :app:assembleDevDebug
```
*Expected:* `BUILD SUCCESSFUL` (outputs `app-dev-debug.apk`).

### 3. Setting Up Network & Device Connection
When connected to USB with device attached:
```bash
./tools/dev-connect.sh
```
This automatically:
- Queries active physical IP
- Checks Docker backend (`:8080`) and ML classifier (`:8000`) health
- Sets reverse tunnels (`adb reverse tcp:8080 tcp:8080`, `tcp:8000`, `tcp:8090`)
- Updates `local.properties`
- Broadcasts `SET_BACKEND_URL` to the running app

---

## 4. Architectural Rules & Constraints Addressed (`.agents/AGENTS.md`)

- **REQ-ID:** `REQ-HAN-07` (Async assessment queue execution without false-fallback race condition).
- **Audit Actions:** Existing `AuditAction.KERNEL_ASSESSMENT_COMPLETED` and `AuditAction.KERNEL_ASSESSMENT_FAILED` remain the authoritative records.
- **Approach:** In-process stage 0 outbox drain via `OutboxDrainer` before calling `/api/v1/assess`, gating on `GetCaseRecordSyncState`.
- **Cost / Tradeoff:** If device is offline when the worker executes, the stage 0 drain will fail fast, and the check gates the case directly to `UNAVAILABLE` without wasting network calls on doomed backend roundtrips.

---

## 5. What Was NOT Done (Out of Scope / Next Steps)

Per `.agents/AGENTS.md` Rule 5:
1. **Broader Identity Merge / Duplicate ABHA Reconciliation:**
   - The findings in `scratchpad/AUDIT4-offline-identity-sync-classifier.md` (offline-created patient ID reconciliation, server merge, ABHA deduplication) remain as an upcoming planned roadmap.
2. **Room DB Migrations:**
   - No migration was needed for this fix since `CaseRecordDao.getSyncState` queries the pre-existing `syncState` column. Schema is still at v13 (`MIGRATION_12_13`).
3. **Branch Organization / PR:**
   - Changes are currently sitting unstaged/uncommitted on branch `feat/dynamic-local-connection`.
   - You may choose to split this into two PRs:
     1. `fix/sync-before-assess-v2`: Only the assessment sync-gate changes and test fixes.
     2. `feat/dynamic-local-connection`: Only the dev connection / ADB reverse / host resolution changes.
