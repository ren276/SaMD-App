# Facility scope of local clinical data: design memo (STOP FS0)

Status: DESIGN, AWAITING OPERATOR RULINGS (section 10). Nothing here is built yet.
Branch: `feat/facility-scope`, worktree `/media/sandesh/extra-ssd/wt-on-device-slm`, base
origin/master `213bb0a` (PR #81 merged).
Date: 2026-10-26. Rating: HIGH (on-device SLM memo 7.1, ruling Y-21).

This is the product-wide PR that Y-21 and Y-21' filed (on-device SLM memo 7.1, 15.2): local
clinical data visible across facilities on a shared phone, on every screen. It also provides the
real `LocalDataOwnerFacility` that the readback seam waits for. It is step 3 of the operator's
order of 2026-10-26, and the NPU slices start once it does (NPU memo 9.1).

Tag convention as in the on-device SLM memo: VERIFIED is a file:line at `213bb0a`, or a command
and its output; INFERRED gives its reason; UNVERIFIED needs a device or the operator.

---

## 1. Finding that leads: a pending outbox is pushed under the next worker's session

Y-21 was framed as a *read* exposure. Reading the sync path shows a *write* hazard that is worse,
exists today in every flavor, and needs no SLM at all.

- The periodic sync job runs whenever the network is up, under whatever session is current:
  `ensurePeriodicWork` on every app start (`data/sync/WorkManagerSyncOutboxScheduler.kt:33-40`);
  the drainer sends with the stored token and device id (`data/sync/SyncOutboxDrainer.kt:195`).
- Sign-out clears tokens only. The outbox stays (`data/local/auth/BackendAuthSession.kt:67-73`;
  on-device SLM memo 7.1).
- The backend stamps every **new** clinical row with the **pushing** worker's facility:
  `model(..., facility_id=worker.facility_id, ...)` (`backend/core/app/services/sync.py:511-515`).
  A row another facility already holds is refused TERMINAL, "id: belongs to another facility"
  (`:446-454`).
- A device audit row keeps its own `user_id` as actor (`:599-606`, `actor_id=user_id` at `:650`),
  but its facility is again the pusher's (`facility_id=worker.facility_id`, `:649`).

So: worker A (facility A) records a patient offline and signs out; worker B (facility B) signs in
on the same phone; the next drain sends A's new patient, encounter and case rows, and the server
files them under **facility B**, while A's edits to rows the server already has go FAILED
permanently. A's audit rows land in facility B's audit chain. Facility A never receives the
records. This is new hazard **H-57** (section 7).

The same mechanism within one facility is harmless for facility (same id) and keeps the audit
actor; clinical rows carry no author column either way (out of scope, section 9).

## 2. The read exposure Y-21 named

Unchanged from on-device SLM memo 7.1 and re-checked at `213bb0a`:
- No local entity carries a facility or owning worker; the Room schema is version 22 with 22
  entities (`data/local/AppDatabase.kt:50-77`).
- Reads have no facility filter (`data/repository/CaseRecordRepositoryImpl.kt:62,68`).
- Nothing pulls clinical data from the server into Room: the only `@GET`s are `auth/me` and two
  ABHA registration-session reads (VERIFIED, grep over `data/remote/api`). Every local clinical row
  was created on this phone.
- Each account belongs to exactly one facility (`user_accounts.facility_id`,
  `backend/core/app/models/user.py:53`); no role spans facilities (INFERRED: one column, and
  `docs/domain/phc-workforce-scope.md` describes facility-bound cadres).
- Sign-in is online only: the facility arrives in the login response and is stored at sign-in
  (`BackendAuthSession.kt:30-52`, `DataStoreAuthTokenStore.kt:102-122`). So the facility of the
  next session is always known **before** its tokens are saved.

New hazard **H-58**: a worker of facility B sees facility A's patients on every screen (H-54 is
the readback-only case of it).

## 3. Design

One rule closes both hazards: **a session of facility B never exists on a phone that holds
facility A's data.** Then nothing pushes A's rows under B (H-57) and nothing shows them to B
(H-58, H-54), with no per-screen filtering and no schema change to clinical tables.

### 3.1 The owner marker (FS-3)

A single-row Room table `device_data_owner(facility_id, facility_name, claimed_at,
claimed_by_worker_id)`, migration 22 to 23. In the same encrypted database as the data it
describes: a Keystore-loss reset (`DatabasePassphraseProvider.kt:40`, `deleteDatabaseFiles`)
removes data and marker together, so they cannot disagree. `LocalDataOwnerFacility` reads it.

### 3.2 The decision at sign-in (FS-1)

After the server accepts the PIN and before `saveLogin`, a pure function decides from the login's
facility id, the marker and the outbox counts (`data/sync/OutboxCounts.kt:17-38`):

| Marker | Outbox | Decision |
|---|---|---|
| none, database empty | any | CLAIM: write the marker for this facility, continue |
| same facility id | any | CONTINUE |
| other facility | every row SYNCED (`pendingClinical`, `heldClinical`, `pendingAudit`, `needsReview` all 0) | WIPE (3.4), then CLAIM, then continue |
| other facility | anything not SYNCED | BLOCK: revoke the new refresh token (`logout`), save nothing, show who must sign in |
| none, database not empty (upgrade from 22) | any | FS-4 |

Facility ids compare exactly, never by name (same rule as `facilityScopeAllows`,
`domain/slm/FacilityScope.kt`). A BLOCK leaves the phone exactly as it was: A's data, no session,
so no drain under B. The copy names facility A from the marker ("This phone has records from
<facility A> that have not been sent. A worker from <facility A> must sign in to send them.").

### 3.3 Rows that can never drain (FS-2)

FAILED and CONFLICT rows never leave on their own and the app has no record edit path, so "block
until drained" can lock a phone for good. Proposed release path: a facility A worker, signed in,
uses "Release this phone": a drain runs; release is allowed only with `pendingClinical`,
`heldClinical` and `pendingAudit` at 0; the screen lists the FAILED and CONFLICT counts; on
confirmation the device writes a `local_data_released` audit row (counts per table, no record
content) and drains it, and only then wipes and clears the marker. The data loss is explicit,
attributed to a facility A worker and on the server before it happens.

### 3.4 The wipe (FS-5)

Scope, from the stores found (VERIFIED, grep for `filesDir`, `cacheDir`, DataStore and
SharedPreferences names under `app/src/main/java`):
- Room: every table (`clearAllTables`), then `VACUUM`.
- `filesDir/documents/` including `.capture` (`ConsultationDocumentRepositoryImpl.kt:58`,
  `AndroidDocumentCaptureStore.kt:81,93`); `filesDir/ailment_audio/`
  (`AndroidAilmentAudioRecorder.kt:27`).
- `cacheDir/reports/` (`ReportPdfExporter.kt:49`), `cacheDir/attachments/`
  (`ConsultationScreen.kt:774`), the document viewer temp dir (`DocumentViewerViewModel.kt:51,139`).
- The `sync_outbox` DataStore (in-flight batch, `InFlightBatchStore.kt:53`) and the drain outcome.
- The persisted navigation back stack (it can carry patient ids; it already discards on a session
  change, `NavBackStackSaver.kt:55,331`, but is cleared anyway).

Kept: the auth store (the new session is about to be written), the device id, the database
passphrase, the SLM and ASR model files (not clinical). Crash safety: a `wipe_pending` flag in its
own DataStore is set first and cleared last; app start re-runs an interrupted wipe before any
screen opens (the wipe is idempotent). INFERRED: `clearAllTables` with Room open avoids a process
restart; deleting the database file instead would need one.

### 3.5 Server-side defence in depth (FS-6)

The device rule is the primary control, but one bug in it re-opens H-57 silently. Proposed: the
push envelope carries the marker's `owner_facility_id`; the backend refuses the whole batch when
it differs from the token's facility (new error code, TERMINAL for the batch, nothing applied),
before any row is read. Requires an api-contract change, a backend change and tests that assert
the persisted rows (CLAUDE.md backend convention). The device then shows the batch refusal under
the existing drain-failure states.

### 3.6 The readback (FS-7)

`LocalDataOwnerFacility` is bound to the marker in every flavor, replacing
`UnknownLocalDataOwnerFacility` in staging and prod and the R-DEV session-facility override in
dev. With 3.2 in place, the seam's check (step 0) becomes a real comparison everywhere.
`SLM_READBACK_ENABLED` stays false in staging and prod: this PR enables nothing.

### 3.7 Audit

New device actions, each with the backend mirror in the same commit (the standing rule in
`AuditLogger.kt` and `audit_actions_device.py`): `local_data_owner_claimed`,
`cross_facility_sign_in_blocked`, `local_data_wiped`, `local_data_released`. Payloads: facility
ids, counts, the decision; never patient or record content. A blocked attempt's row is a device row
with B's worker id as `user_id`; it reaches the server under the next facility A session, stamped
facility A, which is where it happened.

---

## 4. What happens to existing phones (FS-4)

A phone upgraded from schema 22 has data and no marker. Its owner cannot be derived on the device.
Options: (a) the first sign-in after the upgrade claims it, with `local_data_owner_claimed`
marking `legacy=true`; correct only if no phone has been shared across facilities before this
ships. (b) Treat it as an unknown owner: CLAIM only if every row is SYNCED (wipe first, as in
3.2), else BLOCK with copy that cannot name a facility. Recommended: (a), if the operator confirms
no phone in the field has held two facilities' data (UNVERIFIED: the memos describe dev, the
Pixel and the emulator only).

## 5. Untouched

Clinical table schemas, the sync wire format of rows (FS-6 adds an envelope field only), the
readback seam's logic, the remote engine, every flag, same-facility sign-ins (no wipe, no block).

## 6. Tests

- The decision function: a JVM table test over every row of 3.2, plus mutation rows (compare by
  name; treat FAILED as drained; skip the revoke on BLOCK; claim over a non-empty legacy database
  under FS-4(b)).
- Sign-in: a BLOCK saves no token and calls logout with the new refresh token; a WIPE completes
  before `saveLogin`; a failed wipe saves no token (fail closed).
- Migration 22 to 23: `MigrationTestHelper` (instrumented, runs on the API 36 emulator, no phone).
- Wipe: an instrumented test seeds every table and directory in 3.4 and asserts each is empty
  after, and that an interrupted wipe resumes at the next start.
- Backend (FS-6): a mismatched batch is refused and **no row is persisted**, read back from the
  database, not from the response (CLAUDE.md).
- Readback: `ReadbackFlavorGraphTest` updated: the owner is the marker in every flavor.

## 7. Hazards (PROPOSED, AWAITING OPERATOR SIGN-OFF)

- **H-57 (new):** clinical and audit rows recorded under one facility are pushed under another
  facility's session after a sign-in change, and the server files them under the wrong facility
  (new rows) or refuses them permanently (edits); the source facility never receives them.
  Controls: 3.2 BLOCK, 3.3, 3.5. Severity INFERRED high (records reach the wrong facility's
  registers; the source facility's follow-up never sees the patient).
- **H-58 (new):** facility A's patients and records are readable on every screen by a facility B
  worker signed in on the same phone. Controls: 3.2 (B's session cannot exist with A's data).
- **H-54** (readback case) updated: the owner becomes the marker; the always-UNKNOWN control is
  replaced by 3.2.
- **H-59 (new):** the wipe destroys clinical data that never reached the server. Controls: WIPE
  only when every row is SYNCED; FAILED and CONFLICT only through the audited release (3.3);
  crash-safe resumable wipe.

## 8. Slices

| Slice | Content | Needs |
|---|---|---|
| P1 | Decision function and its JVM tests | nothing |
| P2 | Marker table, migration 23, `MigrationTest` | emulator |
| P3 | Sign-in integration, BLOCK copy (string resources), revoke | nothing (JVM); live check on a device |
| P4 | Wipe executor, `wipe_pending` resume | emulator |
| P5 | Release path UI (if FS-2 approved) | emulator; live check |
| P6 | Audit actions plus the backend mirror, one commit | nothing |
| P7 | Backend envelope check (if FS-6 approved), persisted-row tests | backend test DB |
| P8 | Readback owner binding swap | nothing |
| P9 | RMF rows H-54, H-57 to H-59, DHF entry | nothing |

None needs the Pixel; P2, P4 and P5 run on the API 36 emulator. The live cross-facility check
(two seeded accounts in two facilities, one phone) needs a device or the emulator against the
staging-like backend.

## 9. Not in this PR

Author attribution for clinical rows within one facility; a pull path; multi-facility roles;
remote wipe or device management; anything about the SLM beyond the owner binding.

## 10. Rulings requested

- **FS-1** Adopt the decision table in 3.2 (CLAIM, CONTINUE, WIPE only when fully SYNCED, BLOCK
  otherwise, with the new refresh token revoked).
- **FS-2** Release path for FAILED and CONFLICT rows as in 3.3 (recommended), or no release path
  (the phone stays locked to facility A until support intervenes).
- **FS-3** Marker in a Room table, migration 22 to 23 (recommended), not DataStore.
- **FS-4** Legacy phones: (a) first sign-in claims, audited `legacy=true` (recommended, needs the
  operator's confirmation that no field phone has held two facilities' data), or (b) unknown owner.
- **FS-5** Wipe scope and mechanism as in 3.4 (`clearAllTables` plus `VACUUM`, the listed
  directories and stores, resumable flag).
- **FS-6** Backend defence in depth as in 3.5, in this PR (recommended) or as a follow-up.
- **FS-7** Bind `LocalDataOwnerFacility` to the marker in every flavor, replacing the R-DEV
  override; no flag changes.
- **FS-8** Hazard rows H-57, H-58, H-59 and the H-54 update as PROPOSED.

## 11. Merge order

`feat/local-network-permission` (F1, unmerged) changes `LoginViewModel`, `LoginScreen`,
`strings.xml`, `TokenAuthenticator` tests and the drainer tests, which P3 also touches. Whichever
merges second merges master in with a merge commit (Y-14 rule); the P3 sign-in change is the
likely conflict and is resolved by hand, not by a rebase.
