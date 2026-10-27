# Facility scope of local clinical data: design memo

Status: DESIGN, RULED 2026-10-27 (10.1), REDUCED by ruling FS-R the same day (10.2): one phone per
facility, BLOCK for any other facility, no in-app wipe and no release path. Sections 3, 7 and 8
carry FS-R; moving a phone is the support procedure in section 12. P1 and P2 done; P3 onward wait
for their own STOP.
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
(H-58, H-54), with no per-screen filtering and no schema change to clinical tables. Since FS-R
(10.2) each facility has its own phone: a sign-in by another facility is always blocked, and a phone
moves between facilities only through the support procedure (section 12), never in the app.

### 3.1 The owner marker (FS-3)

A single-row Room table `device_data_owner(facilityId, facilityName, claimedAt,
claimedByWorkerId, legacy)` (camelCase like every other table, accepted at STOP P2), migration 22
to 23. A single row is enforced by a fixed primary key. In the same encrypted database as the data
it describes: a Keystore-loss reset (`DatabasePassphraseProvider.kt:40`, `deleteDatabaseFiles`)
removes data and marker together, so they cannot disagree, and so does clearing the app's storage
in the support procedure. `LocalDataOwnerFacility` reads it.

The DAO has an atomic claim (an insert that succeeds only when no owner exists) and a read. P2's
clear, built for the in-app wipe that FS-R dropped, is deleted in P3 (FS-R2). Per FS-3
the migration is tested by actually running the upgrade with `MigrationTestHelper`; it is
roll-forward only and is never edited once merged.

### 3.2 The decision at sign-in (FS-1 as reduced by FS-R)

After the server accepts the PIN and before `saveLogin`, a pure function in `domain`
(`decideFacilitySignIn`, `domain/auth/FacilitySignIn.kt`) decides from the login's facility id,
the marker and whether the database holds any clinical row:

| Marker | Database | Decision |
|---|---|---|
| none | empty | CLAIM: write the marker for this facility, continue |
| none | holds data (upgrade from 22) | LEGACY_CLAIM: claim with `legacy = true` (FS-4(a), section 4) |
| same facility id | any | CONTINUE |
| other facility | any, whatever the sync state | BLOCK: save nothing, revoke the new refresh token (`logout`), name the owner facility |
| (login carries a blank facility id) | any | refused; P3 handles it like BLOCK |

There is no WIPE_THEN_CLAIM outcome. The outbox state (the same shared predicates the drain uses,
P1's `signInOutboxOf`) still reaches the function, so "blocked whatever the sync state" is its own
tested property, and P3 records the counts in the audit; it never changes the outcome.

If the revoke call on BLOCK fails, the sign-in still saves nothing (fail closed), and the failure
is recorded in the audit (FS-1(2)).

Facility ids compare exactly, never by name (same rule as `facilityScopeAllows`,
`domain/slm/FacilityScope.kt`). A BLOCK leaves the phone exactly as it was: A's data, no session,
so no drain under B. The BLOCK copy (FS-R, to the operator for approval at STOP P3) names the owner
facility from the marker: "This phone belongs to <Facility A>. Sign in with a <Facility A> account,
or contact support to move this phone."

### 3.3 Rows that can never drain

FAILED and CONFLICT rows never leave on their own and the app has no record edit path. Under FS-R
this no longer locks anything in the app: the phone stays with its facility, whose workers keep
signing in. There is no release path (FS-2 is superseded); the support procedure (section 12)
covers a phone that must move.

### 3.4 No in-app wipe

FS-R drops the wipe (FS-5 is superseded) and slice P4. The P4 work in progress was never
committed. Its stash was dropped (FS-R2) after checking it byte for byte against the patch and tar
kept under `/media/sandesh/extra-ssd/samd-slm-parity/fs/p4-dropped/`. Clearing a phone's data is the support
procedure's "clear the app's storage", which removes the database, every file and every DataStore
at once.

### 3.5 Server-side defence in depth (FS-6)

The device rule is the primary control, but one bug in it re-opens H-57 silently. In this PR
(FS-6): the push envelope carries the marker's `owner_facility_id` as a REQUIRED field, with no
fallback (nothing is in the field); the backend refuses the whole batch when it is missing or
differs from the token's facility (new error code, TERMINAL for the batch, nothing applied),
before any row is read. Requires the api-contract change (`docs/backend/api-contract.md` 6.1), a
backend change and tests that assert the persisted rows read back from the database (CLAUDE.md
backend convention). The device then shows the batch refusal under
the existing drain-failure states.

### 3.6 The readback (FS-7)

Per FS-7, `LocalDataOwnerFacility` is bound to the marker in every flavor, replacing
`UnknownLocalDataOwnerFacility` in staging and prod and the R-DEV session-facility override in
dev. With 3.2 in place, the seam's check (step 0) becomes a real comparison everywhere.
`SLM_READBACK_ENABLED` stays false in staging and prod: this PR enables nothing.

### 3.7 Audit

New device actions, each with the backend mirror in the same commit (the standing rule in
`AuditLogger.kt` and `audit_actions_device.py`): `local_data_owner_claimed` and
`cross_facility_sign_in_blocked` (FS-R drops `local_data_wiped` and `local_data_released`).
Payloads: facility ids, counts, the decision, `legacy` on a claim (FS-4(a)), and on a block
whether the revoke call failed (FS-1(2)); never patient or record content. A blocked attempt's row
is a device row with B's worker id as `user_id`; it reaches the server under the next facility A
session, stamped facility A, which is where it happened.

---

## 4. What happens to existing phones (FS-4)

A phone upgraded from schema 22 has data and no marker. Its owner cannot be derived on the device.
Ruled FS-4(a): the first sign-in after the upgrade claims it (LEGACY_CLAIM), with the marker's
`legacy` column and the `local_data_owner_claimed` audit row both marking `legacy=true`. Basis
(operator, 2026-10-27): no field pilot exists; only dev test phones with mock data. Option (b),
an unknown owner, is not built.

## 5. Untouched

Clinical table schemas, the sync wire format of rows (FS-6 adds an envelope field only), the
readback seam's logic, the remote engine, every flag, same-facility sign-ins (no block).

## 6. Tests

Instrumented tests run on the Pixel (USB serial 67041FDKX004ZY, `adb install` plus
`am instrument`), not the emulator (operator correction, 10.1). They use their own test database
files and directories, never the app's real database or files, and the operator confirms the
phone is free before any adb command.

- The decision function: a JVM table test over every row of 3.2 (FS-R) plus FS-4(a), each over
  every outbox shape. Mutation rows at P1: compare by name; LEGACY_CLAIM without the legacy flag;
  decide a blank id; and the outbox fold rows. At FS-R: compare by name; let BLOCK pass when
  everything is synced; continue on a blank id.
- Sign-in: a BLOCK saves no token and calls logout with the new refresh token; a failed revoke
  still saves nothing and is audited; a blank id is refused the same way.
- Marker and migration 22 to 23: `MigrationTestHelper` runs the upgrade on a seeded v22 test
  database (every existing row survives, the new table exists empty); two concurrent claims,
  exactly one wins.
- Backend (FS-6): a mismatched batch is refused and **no row is persisted**, read back from the
  database, not from the response (CLAUDE.md).
- Readback: `ReadbackFlavorGraphTest` updated: the owner is the marker in every flavor.

## 7. Hazards (PROPOSED, approved as PROPOSED under FS-8; controls per FS-R)

- **H-57 (new):** clinical and audit rows recorded under one facility are pushed under another
  facility's session after a sign-in change, and the server files them under the wrong facility
  (new rows) or refuses them permanently (edits); the source facility never receives them.
  Controls: 3.2 BLOCK and the server envelope check (3.5). Severity high, patient-safety
  consequence: facility A's follow-up never sees the patient (the records reach facility B's
  registers, or are refused, and never reach facility A).
- **H-58 (new):** facility A's patients and records are readable on every screen by a facility B
  worker signed in on the same phone. Control: 3.2 BLOCK (B's session cannot exist on A's phone).
- **H-54** (readback case) updated: the owner becomes the marker; the always-UNKNOWN control is
  replaced by 3.2.
- **H-59 (new), residual risk of the support procedure:** clearing a phone's storage destroys
  clinical data that never reached the server. Not controlled by software: the control is the
  procedure's documented pre-check (section 12, step 1).

## 8. Slices

| Slice | Content | Status |
|---|---|---|
| P1 | Decision function and its JVM tests, the outbox-state consistency test | done (`26a419f`), reduced to FS-R (`93b3f22`) |
| P2 | Marker table, migration 23, `MigrationTestHelper` test | done (`3dade60`) |
| P3 | Sign-in wiring per 3.2 (FS-R): claim, continue, block, blank id; BLOCK and blank-id copy; revoke | next |
| P4 | Wipe executor | DROPPED by FS-R (never committed, 3.4) |
| P5 | Release path UI | DROPPED by FS-R |
| P6 | Audit actions `local_data_owner_claimed` and `cross_facility_sign_in_blocked` plus the backend mirror, one commit | later |
| P7 | Backend envelope check (FS-6), api-contract, persisted-row tests | later |
| P8 | Readback owner binding swap (FS-7) | later |
| P9 | RMF rows H-54, H-57 to H-59 (per section 7), DHF entry, the support procedure | later |

P3 also handles a login with a blank facility id (operator, 2026-10-27, after STOP P1; FS-R keeps
it): on the persistence side exactly like BLOCK (nothing saved, the new refresh token revoked, a
failed revoke recorded in the audit), with its own string resource for the message. That copy and
the BLOCK copy go to the operator for approval at STOP P3.

Per the operator's correction (10.1), instrumented tests run on the Pixel, not the emulator. The
live cross-facility check (two seeded accounts in two facilities, one phone) needs the Pixel
against the staging-like backend.

## 9. Not in this PR

Author attribution for clinical rows within one facility; a pull path; multi-facility roles;
remote wipe or device management; an in-app wipe or release path (FS-R); anything about the SLM
beyond the owner binding.

## 10. Rulings

The questions as asked (STOP FS0), then the operator's rulings verbatim (10.1), then the scope
reduction FS-R verbatim (10.2), which supersedes parts of 10.1, then FS-R1 and FS-R2 (10.3).

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

### 10.1 Operator rulings, 2026-10-27 (verbatim)

FS-1 Approved, plus: (1) "everything sent" is computed from the SAME shared predicates the drain uses (SyncSql, held rows, the EVALUATE_SYNCABLE exclusion of local-only evaluate markers), with a consistency test in the style of CountDrainConsistencyTest; local-only rows are facility A data and are wiped, but never block. (2) If the revoke call fails, still save nothing (fail closed) and record the failure in the audit.

FS-2 Approved, restricted: only a facility A worker at LICENSED_CLINICAL or PHYSICIAN tier may release (never COMMUNITY); typed confirmation; the local_data_released row must be acknowledged by the server before the wipe starts.

FS-3 Approved: Room table, migration 22 to 23, tested by actually running the upgrade with MigrationTestHelper; roll-forward only; never edited once merged.

FS-4 (a): the first sign-in after the upgrade claims existing data, audited legacy=true. Basis: no field pilot exists; only dev test phones with mock data.

FS-5 Approved, plus: cancel and await all clinical WorkManager work (sync, assessment) and hold the SyncOutboxDrainer mutex for the whole wipe; clear the image loader's disk cache; PRAGMA wal_checkpoint(TRUNCATE) before VACUUM; an instrumented test seeds a sentinel into every listed location and asserts it is absent after the wipe.

FS-6 Approved in this PR: owner_facility_id is a REQUIRED envelope field (no fallback; nothing is in the field); api-contract doc updated; backend tests assert persisted rows read back from the database.

FS-7 Approved: LocalDataOwnerFacility reads the marker in every flavor, replacing UnknownLocalDataOwnerFacility and the R-DEV override; no flag changes.

FS-8 Approved as PROPOSED; H-57's severity text names the patient-safety consequence (facility A's follow-up never sees the patient).

Correction: instrumented tests (P2, P4, P5) run on the Pixel (USB serial 67041FDKX004ZY, adb install plus am instrument), not the emulator, per the operator's standing rule. They use their own test database files and directories, never the app's real database or files. Ask the operator to confirm the phone is free before any adb command.

### 10.2 Ruling FS-R, 2026-10-27 (verbatim)

Scope change from the operator: each facility gets its own device, so the design is reduced.

Ruling FS-R (2026-10-27), supersedes FS-1 WIPE, FS-2, FS-5 and the P3 progress-state addition:
- Sign-in decision: CLAIM (fresh phone), LEGACY_CLAIM (FS-4(a)), CONTINUE (same facility), and BLOCK for ANY other facility regardless of sync state; a blank facility id is refused like BLOCK. There is no WIPE_THEN_CLAIM outcome. BLOCK saves nothing and revokes the new refresh token (a failed revoke is recorded in the audit). BLOCK copy names the owner facility: "This phone belongs to <Facility A>. Sign in with a <Facility A> account, or contact support to move this phone." (Copy to me for approval at STOP P3.)
- No in-app wipe and no release path. P4 and P5 are dropped. Moving a phone to another facility is a documented support procedure: a facility A worker confirms Home shows nothing waiting; support clears the app's storage; support re-delivers the model; the new facility signs in and claims.
- Kept: P1 (simplify the table to the above), P2 (done), P3 (sign-in wiring per the above), P6 (audit actions claimed and blocked, with backend mirrors; local_data_wiped and local_data_released are dropped), P7 (the server envelope check, FS-6 as ruled), P8 (the readback owner binding), P9 (risk rows).
- Hazards: H-57 controls become BLOCK plus the server check; H-58 control is BLOCK; H-59 becomes a residual risk of the support procedure (clearing storage with unsent data), controlled by the documented pre-check, not by software.

### 10.3 Rulings FS-R1 and FS-R2, 2026-10-27 (verbatim)

FS-R1 Support-procedure pre-check: Home's sync caption reads exactly "Up to date" (by the #76 truth table that state means nothing pending, failed, conflicted or blocked). Any other caption means the phone goes to support first; storage is never cleared while records are unsent or stuck.

FS-R2 Delete DeviceDataOwnerDao.clear() and its test in P3 (no caller remains). Drop the P4 stash (git stash drop on that exact entry; verify the SSD backup in samd-slm-parity/fs/p4-dropped/ exists first).

### 10.4 Rulings FS-R3 to FS-R6, 2026-10-27 (verbatim)

FS-R3 Strings: sign_in_facility_blocked = "This phone belongs to %1$s. Sign in with a %1$s account, or ask your supervisor to move this phone." sign_in_no_facility = "This account is not linked to a facility. Tell your supervisor."

FS-R4 The audit table stays classified as clinical data.

FS-R5 No exception may leave FacilitySignInGate. CLAIM and LEGACY_CLAIM write the owner row and its audit row in ONE Room transaction; if the audit write fails, the claim rolls back, no token is saved, and LoginViewModel shows the existing generic sign-in failure string (cite which). BLOCK and blank id stay refused and still show their message even if the audit write fails; a failed audit never turns a refusal into an admit. Any unexpected state (including losing a claim and then finding no owner) is refused fail closed with the generic failure string, nothing saved, audited if possible. If the audit hash chain cannot be written inside the same transaction as the claim, STOP and report why before choosing another design.

FS-R6 logAs: restrict its visibility as narrowly as Kotlin allows, plus a source-scan test (scanned files declared as Test inputs, positive control) that only FacilitySignInGate calls it.

Operator answer, 2026-10-27, on FS-R5's generic string: no generic sign-in failure string resource exists on this branch (a failure shows `SignInResult.Failure.message`, the server's or the transport's raw text; F1's strings are cause-specific and unmerged), so a new resource `sign_in_failed_generic` is added and its copy goes to the operator for approval at STOP P3b. On the hash chain: the device audit row has no hash field (`AuditLogEntity`); the chain is built server side when the row is pushed, so the device row is a plain insert and joins the claim's transaction.

### 10.5 Approval of sign_in_failed_generic, 2026-10-27 (verbatim)

sign_in_failed_generic approved as written: "Cannot sign in on this phone right now. Try again, or tell your supervisor."

## 11. Merge order

`feat/local-network-permission` (F1, unmerged) changes `LoginViewModel`, `LoginScreen`,
`strings.xml`, `TokenAuthenticator` tests and the drainer tests, which P3 also touches. Whichever
merges second merges master in with a merge commit (Y-14 rule); the P3 sign-in change is the
likely conflict and is resolved by hand, not by a rebase.

## 12. Moving a phone to another facility (support procedure, FS-R)

The app never moves a phone between facilities. Support does, in this order:

1. **Pre-check (the H-59 control, FS-R1).** A facility A worker signs in on the phone and confirms
   Home's sync caption reads exactly "Up to date" (`sync_caption_up_to_date`, said only when
   nothing is pending, failed, conflicted or blocked: the #76 truth table, `strings.xml:131,174`,
   `SyncCaption.kt:62`). Any other caption means the phone goes to support first; storage is never
   cleared while records are unsent or stuck.
2. **Clear.** Support clears the app's storage (Android Settings, the app, Storage, Clear storage).
   This removes the database with its owner marker, every file, every DataStore and the database
   passphrase, so the next start creates a new, empty database.
3. **Model.** Support re-delivers the SLM model (on-device SLM memo 9.1 for dev; the production
   delivery of Y-10 once it exists), because clearing storage also removes `files/slm`.
4. **Claim.** A facility B worker signs in; the empty phone is claimed for facility B (CLAIM, 3.2).
