# BUILD 2 — STEP 1 design memo: ABHA profile (Android)

Branch: `feat/build-2-abha-profile-android`, cut from **`master`** (this repo has no `main`;
`master` is the default and the merge target of PR #20). No code written. No commits.

Scope note: this is a monorepo, not a separate `SaMD-App/app/` checkout. Android lives at
`app/`, backend at `backend/`. Nothing under `backend/`, no `.env`, no `local.properties`, no
`docker-compose.yml` was read or touched.

---

## 0. The headline finding: most of BUILD 2 is already shipped

Before designing anything I read the actual enrolment path end to end. Three of the five
questions this memo was asked to decide are **already answered in merged code**, not open:

| Asked | Reality on `master` |
|---|---|
| "Design `AbhaProfileEntity`" | `app/src/main/java/com/example/samdapp/data/local/entity/AbhaProfileEntity.kt` exists, is registered in `AppDatabase` (`AppDatabase.kt:62`, `:89`), and has shipped since at least the Phase 6b sync work. |
| "Which migration adds `abha_profiles`?" | **None is needed.** The table already exists at the current `version = 16` (`AppDatabase.kt:73`). BUILD 2 adds **no** entity column, so it stays v16. There is no v17. |
| "Where does the Call 4 response go?" | `EnrolAbhaUseCase.completeEnrolment()` (`EnrolAbhaUseCase.kt:82`) already calls `getProfile()`, maps to `AbhaProfile`, and persists via `AbhaProfileRepository.saveProfile()`. Option A is already implemented. |
| "Does it reach the sync outbox?" | Yes. `SyncRecordMappers.kt:235` (`AbhaProfileEntity.toSyncRecord()`), drained by `RoomSyncOutboxRepository.kt:76`, results applied at `:102`. |
| "Is there any ABHA↔patient linkage?" | Yes, de facto: `Patient.abhaNumber` (`Patient.kt:18`) is written by `RegisterViewModel.kt:222` from the ABHA-autofilled field, and read back by `AssembleReportUseCase.kt:48`. |

**So the only genuinely unbuilt part of BUILD 2 is (d), the read surface.** The memo still
decides (a), (b), (c) and (e) as asked, but decides most of them as *"already done, here is the
grounding, do not rebuild it."* Rebuilding any of it in STEP 2 would be the scope violation, not
the omission.

Two stale KDoc claims found while reading — real, but documentation-only (see §6).

---

## (a) Photo path scope decision

**Decision: Option 1 — placeholder-only render. No base64 storage, no URL fetch, no cache.**

Grounding, beyond the "don't design against an unobserved shape" rule (which alone is
sufficient):

1. The storage already exists and is already correct. `AbhaProfileEntity.photoUrlMock: String?`
   holds whatever `photo_url` carried; `EnrolAbhaUseCase.kt` maps `photoUrlMock = photoUrl`
   verbatim with an explicit KDoc saying so. Nothing needs to be built to *store* a photo value.
   The only open question was ever the *render*, and sandbox gives us `null` to render.
2. **The project has no image-loading dependency at all.** `grep coil gradle/libs.versions.toml
   app/build.gradle.kts` returns nothing. Option 3 (URL fetch + cache) is not "some code" — it is
   a new third-party dependency, a new disk cache holding patient face images inside a
   SQLCipher-encrypted app whose DB encryption would not cover that cache, and a new
   PHI-at-rest question. That is a build of its own, justified by zero observations.
3. Option 2 (base64 blob) additionally assumes a wire type we have never seen. `photo_url` is
   named `..._url`. Guessing it carries inline bytes contradicts its own field name.

What STEP 2 renders: a circular `Box` with the patient's initials over
`MaterialTheme.colorScheme.primaryContainer`. Compose + stdlib, no dependency, ~8 lines. When
`photoUrlMock` is non-null it renders **the same initials avatar** — we do not half-implement a
fetch. One `// TODO(BUILD-N)` comment on that composable naming the trigger condition:
*first production ABDM response with a non-null `photo_url`, at which point capture the real
value and design storage against it.*

Explicitly rejected: adding an `if (photoUrlMock != null) { /* future */ }` branch. A dead
branch guarding unwritten code is the future-proofing this decision exists to refuse.

---

## (b) Android storage location decision

**Decision: no schema change. The existing entity is correct and complete. DB stays at v16.**

Answering each sub-question against the code rather than in the abstract:

**Does `AbhaProfileEntity` mirror every server column, or a subset?**
Subset, and the subset is correct. Present: `abhaId` (PK), `abhaAddress`, `name`,
`dateOfBirth`, `gender`, `address`, `district`, `state`, `pincode`, `mobileNumber`,
`emailAddress`, `photoUrlMock`, `kycVerified`, `createdAt`, plus device-side sync metadata
(`syncState`, `serverVersion`, `syncErrorCode`, `lastSyncAttemptAt`, `localModifiedAt`).

Deliberately absent, each for a reason that holds:
- `mobile_blind_idx` — server-owned, see below.
- `facility_id`, `received_at`, `server_version` (server's own) — server-side bookkeeping;
  the device's `serverVersion` is the optimistic-concurrency echo, not the same column.
- `verification_source` — **observable on the wire, present on `AbhaIdentityDto` and
  `AbhaIdentity`, and then dropped** by `EnrolAbhaUseCase.toProfile()`, which does not copy it
  into `AbhaProfile`. This is a genuine (small) gap: the UI can show a KYC-verified badge but
  cannot say *verified by what*. Adding it is a v16→v17 migration for one display string.
  **Recommendation: do not add it in BUILD 2.** Listed as an open question below.

**SQLCipher / encrypted-bytea mapping — confirmed.**
`DatabaseModule.kt:91` calls `System.loadLibrary("sqlcipher")` and `:95` installs
`SupportOpenHelperFactory` as Room's `openHelperFactory`. The whole database file is encrypted at
rest by SQLCipher. The server's per-column `bytea` encryption on `name` / `mobile_number` /
`email_address` therefore correctly maps to **plain `String` / `String?` columns in Room** —
which is exactly what the shipped entity does. No `@TypeConverter`, no per-field crypto, nothing
to change. Server-side column encryption and device-side whole-file encryption are two
independent boundaries; neither needs to know about the other.

**Migration number.** None. `version = 16` stays 16. Any STEP 2 diff that touches
`AppDatabase.kt`'s `version` is out of scope and should be rejected in review.

**`photo_url_mock` naming.** Android already calls it `photoUrlMock`, matching the server.
**Keep the name, do not rename.** `EnrolAbhaUseCase.kt`'s existing KDoc already states the
rationale — *"the mock-era name of the field; the real photo URL goes in it unchanged, renaming
the column is a migration and out of scope here."* That reasoning is still correct, and a rename
would be a migration plus a wire-key change (`AbhaProfileSyncPayloadDto` sends
`@SerializedName("photo_url_mock")`) plus a coordinated backend change — for cosmetics. No.

**`mobile_blind_idx`.** Android computes nothing and sends nothing. This is already settled and
already documented in the code: `SyncPayloadDto.kt:261` carries the comment *"No
`mobile_blind_idx`: server-computed (`TableSpec.server_owned`) from `mobileNumber`, and
`AbhaProfileEntity` has no such field to send in the first place."* Correct as-is — a blind index
must be derived with the server's pepper, which must never ship to a device. Leave it.

---

## (c) The plumbing seam decision

**Decision: Option A — and it is already built. STEP 2 writes no persistence code.**

The observed seam, in order:

```
AbhaCreateOtpViewModel.kt:150   AbhaEnrolOutcome.Enrolled
  <- EnrolAbhaUseCase.completeEnrolment()            EnrolAbhaUseCase.kt:82
       abdmAbhaSource.getProfile(sessionId)          -> RetrofitAbhaSource.kt:62 (Call 4)
       identity.toProfile()                          EnrolAbhaUseCase.kt:118
       abhaProfileRepository.saveProfile(profile)    -> AbhaProfileRepositoryImpl.kt:16
         dao.upsert(...)  syncState defaults PENDING
```

and then, independently and asynchronously:

```
RoomSyncOutboxRepository.kt:76   abhaProfileDao.getPendingForSync().forEach { it.toSyncRecord() }
  -> SyncOutboxDrainer (WorkManager)  -> POST /sync/push
  -> RoomSyncOutboxRepository.kt:102  abhaProfileDao.applySyncResult(...)
```

That is precisely Option A: the enrolment path writes directly to Room, and the outbox picks the
row up afterwards because `AbhaProfileEntity.syncState` defaults to `PENDING`. The two are
decoupled by the DB row, not by a call.

**Why not Option B** (route the inbound response through the outbox): the outbox is an
*egress* queue — `getPendingForSync` / `toSyncRecord` / `applySyncResult` are all
device-to-server. There is no ingress side to reuse. Option B would mean inventing one, to
re-serialize a DTO we already hold in memory. Pure cost.

**Why not Option C:** C describes what A already does. It is not a third option; the "direct
write" and the "background push" are the same mechanism, seen at two moments.

Two properties of the existing seam worth preserving in STEP 2 review, because both are load-bearing
and neither is obvious:

- `AbhaProfileRepositoryImpl.kt:19` reads `serverVersion` back before the REPLACE upsert. Without
  that read, re-saving a profile silently wipes the optimistic-concurrency token and the next push
  conflicts. Do not "simplify" it away.
- `EnrolAbhaUseCase.kt`'s save-failure branch returns `retryable = true` with the message *"The
  ABHA account was created but could not be saved on this device."* — correct, because the account
  genuinely exists at ABDM at that point and retrying re-fetches rather than re-enrolling.

---

## (d) UI surface decision — the actual BUILD 2 work

**Decision: one new screen, `AbhaProfileScreen(abhaId)`, reached from `PatientSummaryScreen`.**

This is the user's prior (option 1, dedicated screen) on the *screen shape*, with a corrected
*entry point*, for a reason that came out of reading the nav graph:

1. **"From home" has no `abhaId` to hand it.** `Home` is a today's-patients roster.
   `AbhaProfileScreen` needs an ABHA number as its route argument. The only places in the app
   where an `abhaId` exists are (i) the moment after enrolment, and (ii) a `Patient` row whose
   `abhaNumber` is non-null. Home surfaces neither.
2. **Not the post-enrolment moment either.** `AppNavHost.kt:209` already routes
   `onEnrolled = { abhaId -> backStack.add(Register(abhaId)) }`, and `RegisterViewModel`
   immediately autofills the registration form *from that same profile*. Inserting a profile
   screen there would interrupt an in-progress flow to show the worker data the very next screen
   is about to show them again, pre-filled. Rejected.
3. **`PatientSummaryScreen` is where an `abhaId` naturally exists and where a worker has a
   reason to want it.** Entry point: a row rendered **only when `patient.abhaNumber != null`**,
   navigating to `AbhaProfileRoute(patient.abhaNumber)`.

**Naming collision — important.** `Routes.kt` already has `data object Profile`, and
`presentation/profile/ProfileScreen.kt` is the **signed-in worker's** profile (session, sync
toggle, sign-out) sitting on `BottomNavTab.PROFILE`. The new route must be
`data class AbhaProfileRoute(val abhaId: String)` and the new file
`presentation/abha/AbhaProfileScreen.kt`. It must **not** be added as a fifth bottom-nav tab, and
must not be named `ProfileScreen`.

### Fields rendered

Against the verbatim 2026-08-25 envelope, with the existing helpers rather than new ones:

| Field | Rendered as | Helper |
|---|---|---|
| avatar | initials circle, always | — (see (a)) |
| `name` | `titleLarge` | — |
| `abha_number` | `43-4221-5105-6749` | **`formatAbhaId()`** (`AbhaProfile.kt`) |
| `abha_address` | monospace-ish body | — |
| `date_of_birth` | date | already a `LocalDate?`; **`LocalDate.parse` handles `YYYY-MM-DD` natively — no custom parser** |
| `gender` | "Female"/"Male"/"Other" | **`abhaGenderToBiologicalSex()`** — `"M"` must not be shown raw |
| `kyc_verified` | M3 `AssistChip` / badge | — |
| `created_at` (= `verified_at`) | "Verified on <date>" next to the badge | — |
| `mobile_number` | as stored | — |
| `address`, `district`, `state`, `pincode` | one grouped address block | — |
| `email_address` | shown **only when non-null** | — |

`formatAbhaId`, **not** `maskAbhaId`. `maskAbhaId` exists for the printed clinical report, which
leaves the device (`ReportCanvasRenderer.kt:259`). This screen is worker-facing, on an
authenticated, SQLCipher-encrypted device, and a masked number there would be useless.

### Fields stored but deliberately not rendered

| Field | Why not |
|---|---|
| `photoUrlMock` | Decision (a). Value is stored; render is the placeholder. |
| `syncState`, `serverVersion`, `syncErrorCode`, `lastSyncAttemptAt`, `localModifiedAt` | Sync plumbing, not clinical content. A per-row sync badge here would be a second, narrower source of truth competing with the existing global failed-sync surface (`AbhaProfileDao.observeFailedSyncCount()` feeding `RoomSyncOutboxRepository.kt:119`). One surface, not two. |
| `verification_source` | **Not stored at all** — dropped at `EnrolAbhaUseCase.toProfile()`. Cannot be rendered without a v17 migration. See open questions. |

### The one runnable check STEP 2 must leave behind

A JVM unit test (`app/src/test/.../AbhaProfileWireShapeTest.kt`) that:

1. embeds the **exact 2026-08-25 envelope verbatim** as a string literal (PHI values replaced with
   equivalently-shaped placeholders, everything structural — key names, nesting, `null`s, the
   `"M"`, the `"YYYY-MM-DD"`, the 10-digit unmasked mobile — kept byte-identical);
2. parses it with **`SyncGson.create()`**, not a fresh `Gson()`. `NetworkModule.kt:136` provides
   the app's real Gson from exactly that factory, and `SyncGson`'s own KDoc says it exists so a
   wire-shape test "can never silently drift from what the app actually sends";
3. asserts every field on the resulting `AbhaIdentityDto`, including `dateOfBirth ==
   LocalDate.of(...)`, `photoUrl == null`, `emailAddress == null`, `kycVerified == true`;
4. runs it through `toProfile()` and asserts the `AbhaProfile` the UI will read.

This is the same rule PR #18 was caught by: the fixture is the observed wire, not a
hand-authored approximation of it. No hand-built `AbhaIdentityDto(...)` constructor call as the
starting point — the test starts at JSON or it is not testing the wire.

Compose UI test: **not** proposed. The non-trivial logic is the parse-and-map, which the above
covers; a `ComposeTestRule` screenshot of a static read-only card is ceremony.

---

## (e) Patient-linkage deferral decision

**Decision: defer the manual "Link to patient" step. Ship the profile view standalone.**

Justification is stronger than "ship narrow", because the linkage the manual step would build
**already exists by a different mechanism**:

- `Patient.abhaNumber: String?` (`Patient.kt:18`) is the link column, and it is already
  populated: `RegisterViewModel.kt:222` writes it from `RegisterField.ABHA_NUMBER`, which
  `loadAbhaProfile()` autofilled from the stored `AbhaProfile`.
- `AbhaProfile.abhaId`'s own KDoc states this was the design intent: *"same shape as the existing
  `Patient.abhaNumber` field (REQ-REG-02) so the two can hold the identical value and actually
  link (no duplicate id column on Patient)."*
- `AssembleReportUseCase.kt:48` already traverses it in the read direction:
  `patient.abhaNumber?.let { abhaProfileRepository.getProfile(it) }`.

So the enrol→register path *is* the linkage protocol, and it is already in production. A manual
"Link to patient" picker would add a **second** way to set the same column, with no conflict
rule between them, for patients whose ABHA was created outside this device — a case with no
backend contract and no observed flow. That is the definition of scope blowing.

`abha_transactions.linked_patient_id` on the backend stays untouched and unpopulated by BUILD 2.
Decision (d)'s entry point deliberately consumes the existing linkage (`patient.abhaNumber`)
rather than creating a new one.

---

## 6. Two stale KDoc claims the live observation contradicts

Not bugs, not BUILD 2 scope, but they will mislead the next reader and they bear directly on the
mobile-number field this memo renders:

1. `AbhaProfile.kt`, `isMaskedAbhaMobile`'s KDoc: *"The real ABDM `/profile` response never
   returns a full mobile number, only a masked one."* The 2026-08-25 live response returns
   **10 unmasked digits**. The function itself is still correct code (all-digits ⇒ not masked),
   and `RegisterViewModel.kt`'s masked-branch is simply dead on the live path. Only the claim
   is wrong.
2. `EnrolAbhaUseCase`'s class KDoc: *"`AbhaProfile.mobileNumber` is filled from
   `AbhaIdentity.mobileNumber` (ABDM's masked value, passed through unmodified)"*. Same
   correction — the mapping is right, the parenthetical is not.

Worth a one-line comment fix, in BUILD 2 or separately. It is not a behaviour change and needs no
migration.

---

## STOP script

```
=== STOP: model switch checkpoint ===
BUILD 2 STEP 1 complete. Branch feat/build-2-abha-profile-android created (off master; this repo has no main).
No code written. Memo at scratchpad/BUILD2-STEP1-abha-profile-design-memo.md.

Deliverables of STEP 1:
(a) Photo path: Option 1, placeholder-only initials avatar. Storage already exists
    (AbhaProfileEntity.photoUrlMock, populated verbatim from photo_url). Sandbox returns null, the
    field is literally named _url so base64 contradicts it, and the project has NO image-loading
    dependency at all — a URL fetch is a new dependency plus an unencrypted PHI face-image cache
    outside SQLCipher's coverage, bought with zero observations. One TODO, no dead branch.

(b) Room storage: NO SCHEMA CHANGE. AbhaProfileEntity already exists and is registered in
    AppDatabase at version = 16. There is no v17 and STEP 2 must not touch AppDatabase.version.
    Encrypted-column mapping CONFIRMED: DatabaseModule.kt:91/:95 installs SQLCipher's
    SupportOpenHelperFactory as Room's openHelperFactory, so the whole DB file is encrypted at rest
    and the server's bytea columns correctly map to plain Room Strings — no per-field crypto.
    photo_url_mock keeps its name (rename = migration + wire-key change + backend coordination,
    for cosmetics). mobile_blind_idx: Android computes and sends NOTHING; server_owned, derived
    with a server pepper that must never ship to a device. Already documented at
    SyncPayloadDto.kt:261.

(c) Plumbing seam: Option A — ALREADY IMPLEMENTED, STEP 2 writes no persistence code.
    EnrolAbhaUseCase.kt:82 calls getProfile(), maps via toProfile(), saves through
    AbhaProfileRepositoryImpl. syncState defaults to PENDING, so RoomSyncOutboxRepository.kt:76
    picks the row up independently and pushes it. The two halves are decoupled by the DB row.
    Option B rejected: the outbox is egress-only, there is no ingress side to reuse. Option C is
    just A described at two moments. Preserve the serverVersion read-back at
    AbhaProfileRepositoryImpl.kt:19 — dropping it silently wipes the concurrency token.

(d) UI surface: THE ONLY GENUINELY UNBUILT PART OF BUILD 2.
    New AbhaProfileScreen(abhaId) at presentation/abha/, route AbhaProfileRoute(abhaId).
    NAME COLLISION: Routes.kt's existing `Profile` / presentation/profile/ProfileScreen.kt is the
    signed-in WORKER's profile on BottomNavTab.PROFILE. Do not reuse the name, do not add a 5th tab.
    Entry point is PatientSummaryScreen, NOT Home — Home has no abhaId to pass, and the
    post-enrolment moment already routes to Register(abhaId) which autofills from this same
    profile. Row shown only when patient.abhaNumber != null.
    Rendered: initials avatar, name, abha_number via formatAbhaId (NOT maskAbhaId — that is for the
    printed report that leaves the device), abha_address, DOB, gender via abhaGenderToBiologicalSex
    (never raw "M"), kyc_verified badge + "verified on" date, mobile, grouped address block,
    email only when non-null.
    Stored but NOT rendered: photoUrlMock (decision a); all sync metadata (would be a second,
    narrower truth competing with the existing global observeFailedSyncCount surface).
    NOT STORED so cannot be rendered: verification_source — see open question 1.
    One runnable check: a JVM test parsing the VERBATIM 2026-08-25 envelope through
    SyncGson.create() (the app's real Gson, NetworkModule.kt:136), asserting DTO then AbhaProfile.
    Starts at JSON or it is not testing the wire. No Compose UI test.

(e) Patient-linkage: DEFER — and the linkage already exists by another mechanism.
    Patient.abhaNumber is the link column, already written by RegisterViewModel.kt:222 from the
    ABHA autofill and already read back by AssembleReportUseCase.kt:48. AbhaProfile's own KDoc
    states this was the design intent. A manual "Link to patient" picker would be a SECOND writer
    of the same column with no conflict rule, for an off-device-ABHA case that has no backend
    contract and no observed flow. abha_transactions.linked_patient_id stays untouched.

Also found while reading (documentation-only, not BUILD 2 scope):
- isMaskedAbhaMobile's KDoc and EnrolAbhaUseCase's class KDoc both claim ABDM returns a MASKED
  mobile. The 2026-08-25 live response returns 10 UNMASKED digits. The code is correct either
  way; the claims are stale and the masked-branch in RegisterViewModel is dead on the live path.

Open questions for Sandesh to answer before STEP 2:
1. verification_source ("ABDM_AADHAAR_OTP") is on the wire and on AbhaIdentity, but is DROPPED by
   EnrolAbhaUseCase.toProfile() and has no Room column. Persisting it to show "verified via
   Aadhaar OTP" under the KYC badge costs a v16->v17 migration for one display string.
   My recommendation: NO for BUILD 2, render the badge without the source. Confirm or override.
2. Entry point: I moved it from Home (your prior) to PatientSummaryScreen because Home has no
   abhaId to pass. If you specifically want a Home entry point, it needs a new "recently enrolled
   ABHA" concept on Home, which is a real feature and should be its own build. Confirm
   PatientSummary.
3. Fix the two stale KDoc claims inside BUILD 2's diff, or as a separate one-line commit?

Sandesh reviews. On approval, switch to Sonnet for STEP 2.
=== END STOP ===
```
