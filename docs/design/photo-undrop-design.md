# STEP 1 design memo: un-drop the ABHA profilePhoto for face verification

Design only, no code in this pass. Reverses D5 for `profilePhoto`; `kycPhoto` stays
dropped. Settled inputs from the brief (profilePhoto only, Room BLOB under SQLCipher,
Base64 to Bitmap, InitialsAvatar fallback) are designed against, not re-litigated.

---

## Headline: one thing the brief did not account for

**`abha_profiles` is a synced table.** `AbhaProfileEntity.toSyncRecord()`
(`data/sync/SyncRecordMappers.kt:235-244`) pushes every profile row to the backend via
the Phase 6b outbox, and the backend rejects unknown fields hard: `_attr_map` derives
the allowed key set from the SQLAlchemy model's columns, and any key outside it returns
`SYNC_RECORD_INVALID` with `"<field>: unexpected field."`
(`backend/core/app/services/sync.py:277-281`).

Two consequences, both load-bearing for STEP 2:

1. If the photo is added to `AbhaProfileSyncPayloadDto`
   (`data/remote/dto/SyncPayloadDto.kt:262-276`) without a matching backend column plus
   Alembic migration, **every `abha_profiles` push fails**, permanently, for every
   device. Not a photo bug, a total sync outage for that table.
2. Putting a patient face photo on the sync wire is a separate regulatory decision
   about data leaving the device, which this brief does not authorize and which the
   clinical purpose (a worker verifying identity on the device, in front of the
   patient) does not require.

**Recommendation: the photo column is device-local and excluded from the sync payload.**
This is an established pattern in this repo, not an invention: `EvaluateReportEntity`'s
`failureCode` is deliberately excluded from `EvaluateReportSyncPayloadDto` for the same
class of reason (H-14, `MIGRATION_14_15`), with the exclusion proven against a real Room
DB in `EvaluateReportFailureSyncSafetyTest` rather than assumed. Follow that precedent
exactly, including the test.

Corollary: `SyncBatchPacker`'s limits (`MAX_BYTES = 4_500_000`, `MAX_RECORDS = 400`,
`SyncBatchPacker.kt:67-68`) are untouched, because no photo bytes enter the batch. Had
the photo synced, a 5640-byte base64 blob per profile would have eaten roughly 800
profiles' worth of headroom per batch and turned an `oversized` record into a real
possibility for a large real photo.

---

## 1. The D5 test reversal

Two tests guard D5, both in `backend/core/tests/test_abha.py`:

| Test | Line | Today's assertion |
|---|---|---|
| `test_d5_no_phi_in_persisted_row_or_logs` | `:893` | `"/9j/"` in `forbidden_substrings` (`:915`), asserted absent from the raw-SQL `abha_transactions` row, every `audit_events.payload`, every `sync_log.message`, and `capsys` log output |
| `test_d5_no_phi_in_persisted_row_or_logs_live_mode` | `:523` | Serves `live_photo_marker` (`"/9j/live-fixture-jpeg-bytes-not-a-real-photo"`) as **both** `profilePhoto` and `kycPhoto` (`:576-577`), then asserts `data["photo_url"] is None` (`:598`), `live_photo_marker not in response.text` (`:599`), `"/9j/" not in row_text` (`:617`), not in any audit payload, not in logs (`:625`) |

### The problem with the current fixture

The live-mode test currently sends **the same marker string for both keys** (`:576-577`).
Once `profilePhoto` legitimately flows and `kycPhoto` must still not, a single shared
marker can no longer distinguish them: an assertion that the marker is absent would fail
on the legitimate profilePhoto, and an assertion that it is present would pass even if
the leak were actually kycPhoto. **The fixture must use two distinct markers**, for
example `PROFILE_PHOTO_MARKER = "/9j/profile-fixture-..."` and
`KYC_PHOTO_MARKER = "/9j/kyc-fixture-..."`. This is the single most important mechanical
detail of the reversal, and getting it wrong produces a test that looks green while
guarding nothing.

### `test_d5_no_phi_in_persisted_row_or_logs_live_mode`, after

The one assertion `"/9j/ appears nowhere"` splits into three:

1. **profilePhoto reaches the response** (new, positive). `data["profile_photo_base64"]`
   equals `PROFILE_PHOTO_MARKER`. Replaces the `photo_url is None` assertion at `:598`
   and the blanket `live_photo_marker not in response.text` at `:599`, both of which
   now assert the opposite of the intended behavior.
2. **kycPhoto reaches nothing** (unchanged strength). `KYC_PHOTO_MARKER` absent from
   `response.text`, from the raw-SQL row, from every audit payload, from every sync_log
   message, and from log output. This is the D5 guarantee, undiminished, for the field
   that keeps it.
3. **profilePhoto never reaches a log** (new, the replacement guarantee).
   `PROFILE_PHOTO_MARKER` absent from `capsys` output, from audit payloads, and from
   sync_log messages. Present in the response body only.

Note the backend has no photo column of its own under this design, so on the backend
side there is no "encrypted column" to assert into: the backend passes the photo through
in the response and stores nothing. The at-rest half of the guarantee is Android-side and
is tested there (see the Android section and STEP 2 item 12).

### `test_d5_no_phi_in_persisted_row_or_logs` (stub-mode), after

`client.py`'s `_stub_profile()` (`:53-79`) returns the same
`"/9j/stub-base64-jpeg-bytes-not-a-real-photo"` string for both `profilePhoto` (`:67`)
and `kycPhoto` (`:68`). Same two-marker split required.

`"/9j/"` leaves `forbidden_substrings` (it can no longer be blanket-forbidden) and is
replaced by the kyc-specific marker. `AADHAAR`, `OTP_VALID`, and `"stub-x-token"` stay in
that list unchanged, with their existing raw-SQL and log assertions intact. The
profilePhoto marker gets its own separate not-in-logs assertion, since it is no longer a
"forbidden everywhere" value.

### Raw SQL: confirmed mandatory, and where

Yes, and the existing precedent is explicit about why. The current test's own comment
(`:918-920`):

> Raw SQL, not the ORM: the ORM's `EncryptedText` `column_expression` would decrypt
> `external_token_encrypted` back to plaintext on SELECT, which would make this check
> pass even if the column held the plaintext token, the exact failure mode this test
> exists to catch.

That reasoning transfers directly, but note **where** it applies under this design. The
backend stores no photo, so there is no backend column to read raw. The equivalent
at-rest check is on Android: the new Room column must be asserted through a raw
`SupportSQLiteDatabase` query in an instrumented test, not through the DAO/entity, so a
bug that wrote the photo somewhere unencrypted cannot hide behind Room handing the value
back. SQLCipher encrypts the whole database file rather than one column, so the Android
assertion is different in kind from the pgcrypto one: it proves the bytes are in the
sanctioned column of the encrypted DB and nowhere else (not in a log, not in a file under
`filesDir`), not that one column is individually ciphertext.

### Risk-file entry

New row in `docs/quality/risk-management-file.md` section 2 (hazard register). Existing
IDs run H-01 to H-14, so this is **H-15**. Draft text for the six columns:

- **ID:** H-15
- **Hazard / hazardous situation:** Patient face photo stored at rest on the device
  (deliberate reversal of D5 for `profilePhoto`)
- **Potential harm:** Privacy harm (DPDP): a biometric-adjacent identifier is now
  retained on the device where previously no photo was retained anywhere
- **Sev:** Med
- **Prob:** Low
- **Risk controls implemented:** *What D5 protected:* `profilePhoto` and `kycPhoto`
  arrived on the ABDM `profile/account` response and were never extracted by
  `abdm_adapter/mapping.py`'s `profile_to_abha_identity`, so no photo existed in any
  process past that function, in any log, or at rest anywhere. *What changed and why:*
  `profilePhoto` is now carried through to the device and stored, for a clinical purpose,
  face verification of patient identity by a PHC worker at the point of care, which
  directly serves hazard H-03 (wrong-patient record mix-up), whose own row lists
  "barcode/photo confirmation" as intended future work. *The new boundary:*
  (a) `profilePhoto` only. `kycPhoto` is Aadhaar-derived KYC/biometric-adjacent data
  under heightened DPDP and Aadhaar-Act handling obligations and remains dropped exactly
  as D5 does today, with its own test assertion. (b) Stored only in one Room column in
  the SQLCipher-encrypted database, never `filesDir`, never a cache directory, never a
  `MediaStore` entry. (c) Never logged: `REDACTED_KEYS` widened, plus a value-pattern
  scrub for base64 JPEG payloads. (d) Never synced: excluded from
  `AbhaProfileSyncPayloadDto`, so the photo never leaves the device, proven by a Room
  test on the outbox row set, following the H-14 `failureCode` precedent. (e) The screen
  showing it is already in `SECURED_ROUTE_TYPES`, so `FLAG_SECURE` blocks screenshots,
  screen recording, and the recent-apps thumbnail. (f) The PDF/report export path is
  structurally excluded: `ReportPatientBlock` has no photo field.
- **Residual / open work:** **Accepted residual risk: a patient face photo now lives at
  rest on the device.** Mitigated by SQLCipher plus a non-exportable Keystore key
  (the same control as H-04), but if that control fails, a face photo is now among what
  is exposed, where before it was not. Open: no retention or deletion policy exists for
  the photo specifically (it lives as long as the `abha_profiles` row, which nothing
  currently prunes); `docs/data-retention.md` should gain a photo line. Open: no worker
  or patient consent gesture is attached to storing the photo, distinct from the
  existing ABHA consent for the enrolment itself.

This entry must land in the **same commit** as the code that reverses the control, not a
follow-up. A weakened safety control with its risk-file justification arriving later is
exactly the traceability gap IEC 62304 and the H-12 row's own reasoning exist to prevent.

---

## 2. The backend un-drop

### Repo contradiction on the framing

The brief says the mapper "reads `profilePhoto` via `body.get()` and never assigns it
(dropped)." **That is no longer accurate.** As of the PR #25 CodeRabbit correction
(2026-08-28), there is no `body.get("profilePhoto")` call anywhere in
`profile_to_abha_identity`; the field is never read at all. `mapping.py`'s own module
docstring (`:5-9`) and the `photo_url` inline comment (`:60-62`) were both corrected to
say exactly this. So this is **adding an extraction that never existed**, not removing a
discard. Repo wins. The distinction matters for the memo's own accuracy and for anyone
reading the diff expecting to see a deletion.

### Changes

`backend/abdm-adapter/abdm_adapter/mapping.py`:
- Add `body.get("profilePhoto")` and carry it to the returned dict.
- **Do not** add any read of `kycPhoto`. Its absence stays total, and the module
  docstring must be updated to say so precisely: profilePhoto is now extracted,
  kycPhoto is still never read.
- `photo_url` is removed and replaced, not repurposed.

`backend/abdm-adapter/abdm_adapter/schemas.py:78`, `AbhaIdentity`:
- `photo_url: str | None` becomes `profile_photo_base64: str | None`.

### Field naming, and the one rename that must NOT happen

Two different fields both look like "the photo field" and they must not be conflated:

| Field | Where | Rename? |
|---|---|---|
| `AbhaIdentity.photo_url` | Backend response schema (`schemas.py:78`) and Android's `AbhaIdentityDto.photoUrl` (`AbhaDto.kt:69`) | **Yes.** Rename to `profile_photo_base64` / `profilePhotoBase64`. It is inline base64, never a URL, and the name has misled once already (`AbhaProfileScreen.kt:158-162`'s TODO reasons about "a non-null photo_url" as if a URL would arrive). This is the Android-bound response only, no sync involvement, cheap to rename. `docs/backend/api-contract.md` section 8's pinned `AbhaIdentity` example must change with it. |
| `AbhaProfileEntity.photoUrlMock` | Room column, **and on the sync wire** as `photo_url_mock` (`SyncPayloadDto.kt:274`), **and a backend column** `photo_url_mock` (`models/abha.py:153`) | **No.** Renaming costs a Room migration plus a backend Alembic migration plus a coordinated wire change, for zero functional gain. Leave it exactly as is. The new photo is a **separate new column**, not a repurposing of this one. |

Keeping these separate also keeps the change revertible: dropping the new column restores
the prior state without touching anything that already syncs.

---

## 3. REDACTED_KEYS widening

The photo now flows through the backend where before it did not exist, so the "never in
logs" half of the guarantee needs a real enforcement point, not call-site discipline.
Three layers already exist in `backend/core/app/logging.py` and the photo needs two of
them:

1. **Key-based redaction.** `REDACTED_KEYS` (`config.py:180-211`) must gain
   `profile_photo_base64`, `profilephoto`, and `profile_photo`. The redactor lowercases
   the key before matching (`logging.py:33-36`), and the set already carries both
   snake_case and squashed variants for the same reason (`abha_number`/`abhanumber`,
   `otp_value`/`otpvalue`), so all three spellings are consistent with existing practice,
   not belt-and-braces padding. Add `kycphoto`/`kyc_photo` too: it should never appear,
   and if a future change accidentally starts carrying it, redaction should already be in
   place rather than being the thing that was forgotten.
2. **Value-pattern scrub.** Key-based redaction only fires when the value sits under a
   known key. A photo embedded in a positional log argument, an exception message, or a
   nested structure whose key is something else entirely would slip through. `logging.py`
   already has exactly this escape hatch for the ABDM PEM (`:40-46`): a value starting
   with `-----BEGIN` is redacted regardless of key, "by value rather than by key, the
   same belt and braces reasoning." **Add the same for base64 JPEG:** a `str` whose
   lstripped form starts with `/9j/` is redacted. That prefix is the base64 encoding of
   the JPEG SOI marker plus JFIF header and is precisely what both D5 tests already grep
   for, so the scrub and the test agree on the same signature by construction.
3. `_drop_body_keys` (`:54-63`) already drops `body`/`request_body`/`response_body`/
   `payload`/`raw` wholesale and needs no change.

The value-pattern scrub is the load-bearing one. Without it, the "never in logs" claim
rests on nobody ever logging the photo under an unexpected key, which is the kind of
call-site discipline this module's own docstring says it exists to replace.

---

## 4. The Android chain

Nothing carries the photo today. Full plumbing, in dependency order:

1. **`data/remote/dto/AbhaDto.kt:69`.** `@SerializedName("photo_url") val photoUrl: String?`
   becomes `@SerializedName("profile_photo_base64") val profilePhotoBase64: String?`.
2. **`domain/abha/AbdmAbhaSource.kt:123`.** `AbhaIdentity.photoUrl` becomes
   `profilePhotoBase64: String?`.
3. **`data/remote/RetrofitAbhaSource.kt:100`-adjacent.** The DTO-to-domain mapping picks
   up the renamed field.
4. **`domain/model/AbhaProfile.kt:22-36`.** New field. Type is `ByteArray?` (see the
   storage decision below), named `profilePhoto`. Note `AbhaProfile` is a `data class`
   and `ByteArray` breaks structural `equals`/`hashCode`; if any code compares
   `AbhaProfile` instances for equality, that is a real trap. **Needs-review:** check
   whether anything relies on `AbhaProfile` equality before choosing `ByteArray` over a
   wrapper. Nothing found in this read, but it was not exhaustively verified.
5. **`domain/usecase/EnrolAbhaUseCase.kt:121-133`, `toProfile()`.** Wire the photo
   through. This is where the base64 string is decoded to `ByteArray` (see below).
   **Do not touch the `verificationSource` drop** at `:117-120`: that TODO stays exactly
   as it is, per the brief, and per the earlier scoping read that found it is a separate,
   smaller decision.
6. **`data/local/entity/AbhaProfileEntity.kt:10-24`.** New `val profilePhoto: ByteArray?`
   column. Room maps `ByteArray` to `BLOB` natively, no `TypeConverter` needed. Same
   `data class` equality caveat as item 4.
7. **`data/remote/dto/SyncPayloadDto.kt:262-276` and
   `data/sync/SyncRecordMappers.kt:235-244`.** **Deliberately unchanged.** The photo is
   NOT added to `AbhaProfileSyncPayloadDto` and NOT passed in `toSyncRecord()`. This is
   the whole point of the headline finding, and it needs an explicit comment on the
   entity field saying so, mirroring `EvaluateReportEntity.failureCode`'s own KDoc.
8. **`data/local/Migrations.kt`, new `MIGRATION_16_17`.** Single statement:
   `ALTER TABLE abha_profiles ADD COLUMN profilePhoto BLOB` (nullable, no default).
   Register in `di/DatabaseModule.kt:96`'s `.addMigrations(...)` chain and bump
   `AppDatabase.kt:72` to `version = 17`.
9. **`presentation/abha/AbhaProfileScreen.kt`.** Replace the unconditional
   `InitialsAvatar(name = profile.name)` call at `:88` with a branch: decode and show
   `Image` when bytes are present and decodable, `InitialsAvatar` otherwise. The existing
   `InitialsAvatar` composable (`:145-167`) is unchanged and stays the fallback. Its
   TODO at `:154-157` ("first production ABDM response with a non-null photo_url is the
   trigger") is now satisfied and should be removed, not left dangling.

### Photo-only migration, do not fold in verificationSource

The `verificationSource` TODO (`EnrolAbhaUseCase.kt:117-120`) also wants a v16 to v17
migration. **Keep them separate; this migration is photo-only.** Reason: this change
reverses a safety control and the whole point of the risk-file entry is that the
reversal is individually identifiable and individually revertible. Bundling an unrelated
display string into the same schema change means reverting the photo means reverting
`verificationSource` too, or a fiddly partial revert on a shipped schema version.
`verificationSource` can take v17 to v18 whenever it is worth doing.

### Decode failure must never crash

Three distinct absent-or-bad cases, all falling back to `InitialsAvatar`:

- **Absent:** ABDM returned no `profilePhoto` (real accounts legitimately have none).
- **Empty:** present but zero-length or blank.
- **Undecodable:** `Base64.decode` throws `IllegalArgumentException`, or
  `BitmapFactory.decodeByteArray` returns `null` (valid base64 that is not a valid
  image).

`BitmapFactory.decodeByteArray` returning `null` rather than throwing is the one people
miss; a non-null `ByteArray` is not proof of a decodable image. Both the decode at the
`toProfile()` boundary and the bitmap decode at render must be guarded, and the render
path should `remember` the decoded bitmap keyed on the byte array so decoding does not
rerun on every recomposition.

The brief notes both live photos were exactly 5640 bytes and may be sandbox placeholders,
which is a good reason to treat "present" as no guarantee of "meaningful", and a reason
the fallback is mandatory rather than defensive padding.

---

## 5. BLOB storage form: `ByteArray`, decoded at the boundary

**Recommendation: store decoded `ByteArray`, not the base64 `String`.**

- **Size.** Base64 is roughly 33 percent larger than the bytes it encodes. On an
  encrypted DB on a low-end field tablet, storing the inflated form for no reason is
  waste that compounds per profile.
- **Validation lands at the boundary.** Decoding in `toProfile()` means malformed base64
  fails once, at the point the value enters the app, and the column simply holds `null`.
  Storing the string defers that failure to every single render, so a bad value becomes a
  recurring render-time branch instead of a one-time write-time rejection. Failing early
  at a trust boundary is the same principle the repo already applies to
  `fetch_public_key_pem`'s cert parsing.
- **No repeated work.** Render decodes bytes to a bitmap; it does not also have to
  base64-decode first, on every recomposition.
- **Room native.** `ByteArray` maps to `BLOB` with no `TypeConverter`. The base64 string
  would map to `TEXT`, which is also fine mechanically, but see size above.
- **Encryption is identical either way.** SQLCipher encrypts the database file, so the
  column type has no bearing on at-rest protection. This point does not favor either
  option and should not be cited as if it does.

The one cost is the `ByteArray` equality caveat on two `data class`es (items 4 and 6
above), which is why that is flagged needs-review rather than assumed harmless.

---

## 6. Screenshot and export surface

**Screenshot: already covered, no change needed.** A `FLAG_SECURE` mechanism exists and
already includes this screen. `presentation/navigation/Routes.kt:124-140` defines
`SECURED_ROUTE_TYPES`, and `AbhaProfileRoute::class.java` is already a member. It is
applied window-wide and reactively per route in `AppNavHost.kt:119-131`, blocking
screenshots, screen recording, and the recent-apps thumbnail. The photo's arrival does
not change what that screen should do, because the screen was already classified as
patient-identifying. Worth stating positively in the risk-file entry as an existing
control the change inherits, rather than a new one to build.

**Export: structurally excluded, and worth preserving deliberately.**
`domain/report/ReportFormatter.kt:55` takes an `AbhaProfile?`, so the PDF/report path
does receive the object that will now carry the photo. It reads exactly two fields off
it: `abhaAddress` (`:86`) and `kycVerified` (`:87`). The output type
`ReportPatientBlock` has no photo field, so the photo cannot reach `ReportCanvasRenderer`
or the exported PDF without someone explicitly adding it. This is the same
structural-exclusion property H-10 relies on for `KernelPayload`, and it holds here by
construction rather than by convention. **No change needed**, but the risk-file entry
should name it, so a future session adding a photo to the report does so as a conscious
decision against a recorded control rather than as an obvious-looking one-liner.

**Sync: covered in the headline finding**, and it is the export path that actually needed
a decision.

---

## 7. STEP 2 scope

| # | Item | Classification |
|---|---|---|
| 1 | `mapping.py`: extract `profilePhoto`, carry to the returned dict, leave `kycPhoto` unread, update the module docstring and the `photo_url` comment to match | **needs-review** (this is the D5 reversal itself) |
| 2 | `schemas.py:78`: `photo_url` becomes `profile_photo_base64` | mechanical |
| 3 | `config.py`: widen `REDACTED_KEYS` with the profile/kyc photo key spellings | **needs-review** (safety net for the new "never in logs" half) |
| 4 | `logging.py`: add the `/9j/` value-pattern scrub alongside the existing `-----BEGIN` one | **needs-review** (same reason; this is the load-bearing layer) |
| 5 | `docs/backend/api-contract.md` section 8: update the pinned `AbhaIdentity` example | mechanical |
| 6 | Android DTO, domain `AbhaIdentity`, `RetrofitAbhaSource` mapping: rename to `profilePhotoBase64` | mechanical |
| 7 | `AbhaProfile` domain model plus `AbhaProfileEntity`: new `profilePhoto: ByteArray?`, with a KDoc stating it is deliberately excluded from sync | **needs-review** (the `ByteArray` on a `data class` equality caveat, item 4 above) |
| 8 | `EnrolAbhaUseCase.toProfile()`: decode base64 to `ByteArray`, null on failure. Do not touch the `verificationSource` drop | mechanical |
| 9 | `MIGRATION_16_17` plus `AppDatabase` version bump plus `DatabaseModule` registration, photo-only | **needs-review** (schema change) |
| 10 | `AbhaProfileScreen`: `Image` when decodable, existing `InitialsAvatar` otherwise, `remember`ed decode, remove the satisfied TODO | mechanical |
| 11 | Rewrite both D5 tests per section 1, including the two-distinct-markers fixture change in each | **needs-review** (reverses a safety control; the shared-marker trap makes a wrong version look green) |
| 12 | New Room instrumented test: photo bytes present in the `abha_profiles` column via a raw `SupportSQLiteDatabase` query, and absent from the sync outbox row set. Mirror `EvaluateReportFailureSyncSafetyTest`'s shape for the outbox half | **needs-review** (this is the at-rest and never-synced proof) |
| 13 | `MigrationTest16To17` androidTest, SQLCipher, on device: new column exists, existing rows survive with data intact. Mirror `MigrationTest15To16` | **needs-review** (schema, and the HARD GATE section's own thesis that a compiling androidTest is not a passing one) |
| 14 | Risk-file H-15 row, in the same commit as item 1 | **needs-review** |
| 15 | `docs/data-retention.md`: a line for the photo, per H-15's open work | mechanical |

Items 11, 12, and 13 must run on a real device before the gate is called passed, per the
HARD GATE section in PROGRESS.md.

---

## 8. Repo contradictions with the brief

1. **"Reads `profilePhoto` via `body.get()` and never assigns it (dropped)."** No longer
   true as of 2026-08-28: there is no read at all. This is an addition, not the removal
   of a discard. Section 2.
2. **"Should `AbhaProfileScreen` be on any screenshot-block list."** It already is
   (`Routes.kt:135`). The mechanism exists, is applied reactively window-wide, and
   already covers this route. Section 6.
3. **The sync surface the brief did not name.** `abha_profiles` is a synced table and the
   backend rejects unknown fields outright, so "add a column" is not a device-local act
   by default. Handled by excluding the photo from the sync payload, following the H-14
   `failureCode` precedent. Headline section.
4. **"`photo_url` (always-null) is renamed/replaced appropriately."** Two different
   fields answer to that description. The response-schema one should be renamed; the Room
   plus sync-wire `photoUrlMock` must not be. Section 2.
5. **"profilePhoto bytes appear ONLY in the one sanctioned SQLCipher-encrypted column"**
   as a thing the backend D5 tests assert. Under this design the backend stores no photo
   at all, so there is no backend column to assert into; the at-rest assertion is
   Android-side (item 12). The backend tests assert pass-through plus never-logged.
   Section 1.

---

## STOP

Design only. No code, no branch, no commit, no test modified. Awaiting review of the D5
reversal mechanics (section 1) and the H-15 risk-file entry before STEP 2 is authorized.
Per the brief, items 11 and 13 stay Opus-reviewed at the STEP 2 gate; this memo adds
items 1, 3, 4, 7, 9, 12, and 14 to that list, with reasons given per row.
