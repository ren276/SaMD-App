# Nav back stack restore on process death (H4-a, saveable back stack)

> **SUPERSEDED IN PART BY THE IMPLEMENTATION. Read this box before trusting any section below.**
>
> This memo is the design record as written BEFORE phases 1 and 2 were built. Two of its load
> bearing assumptions did not survive contact with the code. It is committed for the decision
> history and for section 8.2.1, not as a description of what shipped. Where it disagrees with the
> code, the code is right.
>
> 1. **"Restore policy: pop to ..." throughout section 2, and the whole restore-time framing, is
>    wrong about what it achieves.** `onSaveInstanceState` writes the Bundle BEFORE the process
>    dies, so a policy applied at restore runs after that write already landed: it decides what the
>    worker comes back to and removes nothing from saved state, which was the entire point. What
>    shipped is a SAVE-time transform in `NavBackStackSaver.transformForSave`, applied to a copy
>    before `encodeToSavedState`, leaving the live back stack untouched. The per-route targets the
>    memo derived are still correct; only the moment they are applied changed.
> 2. **D1's recommendation (a), a draft `consultations` row carrying `chiefComplaint`, was not
>    needed and was not built.** Under a save-time transform the `ConsultationRoute` entry is
>    replaced by `Compounder` in resume shape, synthesized from the route's own
>    patientId/encounterId/caseRecordId, so nothing reads the complaint back after a restore and
>    there is nothing to persist it for. That removed the only item in the track that would have
>    touched the data layer, and the one-row-per-encounter migration it implied.
>
> Also settled since: D3 (the audio attachment is read from the consultation's own AUDIO attachment
> row, no new DAO query), D7 (added to the shared device-action vocabulary, both sides, commit
> `c5dfb06`). D2 was taken as written, at save time. D4 is deferred to phase 5 with RR-03.

**DESIGN ONLY when written. Phases 1 and 2 have since been implemented.** Branch:
`fix/navstack-process-death`, cut from `master` after the capture fix (PR #54, merge `5630f37`)
landed.

Status of every risk-file entry below: **PROPOSED, AWAITING OPERATOR SIGN-OFF. NOT APPROVED.**

## 0. The defect, restated against the code

`AppNavHost.kt:90`:

```kotlin
val backStack = remember { mutableStateListOf<Any>(Home) }
```

`remember`, not `rememberSaveable`. Activity recreation keeps it (the composition survives);
process death does not. Every screen drops to `Home` on relaunch. The only recovery path that
exists today is Home's "Resume in-progress consultation?" prompt, which covers exactly one case
(a `DRAFT` case record) and nothing else.

Verification basis for this memo: `gradle/libs.versions.toml`, `AppNavHost.kt`, `Routes.kt`, the
per-route ViewModels named inline, and the **1.1.4 sources jars** unpacked from the Gradle module
cache (see section 1). Nothing here is quoted from release notes or from memory of the API.

## 1. Navigation3 API, verified against the version this project actually resolves

Version catalog (`gradle/libs.versions.toml`):

| Key | Version |
|-----|---------|
| `navigation3` (`navigation3-runtime`, `navigation3-ui`) | **1.1.4** |
| `lifecycleViewmodelNavigation3` | 2.11.0 |
| `composeBom` | 2026.06.01 (resolves `runtime-saveable` **1.11.4**) |
| `kotlin` | 2.3.10, with `org.jetbrains.kotlin.plugin.serialization` already applied in `app/build.gradle.kts` |
| `kotlinxSerializationJson` | 1.11.0 |

Read from `navigation3-runtime-android-1.1.4-sources.jar`:

- `NavKey` (`commonMain/androidx/navigation3/runtime/NavKey.kt`) is a **bare marker interface**:
  `public interface NavKey`. It carries no members and imposes no serializer by itself.
- `NavBackStack<T : NavKey>` (`NavBackStack.kt`) is `@Serializable(with = NavBackStackSerializer::class)`
  and delegates `MutableList<T>`, `StateObject` and `RandomAccess` to an internal
  `SnapshotStateList<T>`. It is therefore a drop-in for the current `SnapshotStateList<Any>`
  everywhere the code mutates it (`add`, `clear`, `removeLastOrNull`, `lastOrNull`, `asReversed`).
- `rememberNavBackStack` exists in **two** forms:
  - `androidMain/.../RememberNavBackStack.android.kt`: `rememberNavBackStack(vararg elements: NavKey): NavBackStack<NavKey>`,
    which wraps `rememberSerializable(serializer = NavBackStackSerializer(NavKeySerializer()))`.
  - `commonMain/.../RememberNavBackStack.kt`: an overload taking a `SavedStateConfiguration` whose
    `serializersModule` must declare `polymorphic(NavKey::class) { ... }`. It `require`s a
    non-default module and throws if you pass the default one.
- `NavKeySerializer` (`androidMain/.../serialization/NavKeySerializer.android.kt`) writes
  `value::class.java.name` as a `type` string, then the concrete `@Serializable` payload, and on
  read does `Class.forName(className).kotlin.serializer()`. Two consequences that drive sections 4
  and 6: the **fully qualified class name is the wire format**, and **restore throws** on an
  unknown/renamed class rather than degrading.
- `NavBackStackSerializer` delegates to `SnapshotStateListSerializer(elementSerializer)`.

Read from `navigation3-ui-android-1.1.4-sources.jar`: `NavDisplay` is
`public fun <T : Any> NavDisplay(backStack: List<T>, ...)`. The bound is `Any`, not `NavKey`, so
`NavBackStack<NavKey>` satisfies it with no other change at the call site.

Read from `runtime-saveable-android-1.11.4-sources.jar` (`RememberSerializable.kt:89`):

```kotlin
public fun <T : Any> rememberSerializable(
    vararg inputs: Any?,
    serializer: KSerializer<T>,
    configuration: SavedStateConfiguration = DEFAULT,
    init: () -> T,
): T
```

**`rememberNavBackStack` exposes no `inputs` and no failure path.** That is the single most
important API fact in this memo. It means the library helper cannot express either of the two
hard requirements the operator set (discard on session change, section 4; never crash on a bad
payload, section 6), so the design below does not call it.

### 1.1 The approach, against 1.1.4

**Route declarations** (`Routes.kt`): every route gets `@Serializable` and `: NavKey`.

```kotlin
@Serializable data object Home : NavKey
@Serializable data class PatientSummary(val patientId: String) : NavKey
```

`data object` is serializable under kotlinx.serialization 1.11.0 with no extra ceremony.
`EmergencyOverrideRoute`'s `List<String>` is serializable as-is, though section 3 removes it.

**Back stack construction** (`AppNavHost.kt:90`), instead of `rememberNavBackStack(Home)`:

```kotlin
val backStack = rememberSaveable(
    session.userId,
    saver = navBackStackSaver,
) { NavBackStack<NavKey>(Home) }
```

with a hand-written saver in the navigation package:

```kotlin
private val navStackSerializer = NavBackStackSerializer<NavKey>(NavKeySerializer())

private val navBackStackSaver: Saver<NavBackStack<NavKey>, SavedState> = Saver(
    save = { stack -> runCatching { encodeToSavedState(navStackSerializer, stack) }
        .getOrNull()?.takeIf { it.fitsNavBudget() } },
    restore = { saved -> runCatching { decodeFromSavedState(navStackSerializer, saved) }.getOrNull() },
)
```

`encodeToSavedState` / `decodeFromSavedState` are verified present in
`androidx.savedstate:savedstate-android:1.4.0`
(`commonMain/androidx/savedstate/serialization/SavedStateEncoder.kt:109` and
`SavedStateDecoder.kt:106`). A `Saver` whose `restore` returns `null` makes `rememberSaveable`
fall back to `init()`, which is the "discard the stack and go Home" behaviour item 6 asks for,
with no try/catch inside composition and no custom crash handler.

This is **nine lines more** than `rememberNavBackStack(Home)` and buys the session key, the size
bound and the no-crash guarantee. If the operator prefers the library helper, all three have to be
re-solved some other way, and two of them cannot be solved at all from outside the helper.

**Alternative considered and rejected:** the `SavedStateConfiguration` overload with an explicit
`polymorphic(NavKey::class) { subclass(Home::class) ... }` module. It removes the reflection and
the fully-qualified-name wire format (good for a future R8 flip), but it still has no `inputs` and
no failure path, and it adds a registration list that a new route can silently miss. Keep it on
the table only if optimization is enabled later (section 6.3).

## 2. Per-route restore-safety table

Every route in `Routes.kt`. "State lives" is where the destination's data comes from **after** a
cold start, not where it came from on the forward pass.

| Route | Args | State the destination needs | Where that state lives | Restore verdict |
|---|---|---|---|---|
| `Home` | none | today's roster, resumable DRAFT case | DB | **SAFE** |
| `Patients` | none | patient roster | DB | **SAFE** |
| `Referrals` | none | this device's referral outbox | DB | **SAFE** |
| `Profile` | none | session name/role, sync toggle | auth session, DB | **SAFE** |
| `OpenSourceLicensesRoute` | none | static text | app resources | **SAFE** |
| `AbhaEntry` | none | nothing | none | **SAFE** |
| `AbhaSignUp` | none | typed mock sign-up form | ViewModel memory only | **SAFE-IF** losing the typed form is acceptable; the screen re-opens empty |
| `AbhaLogin` | none | typed ABHA id | ViewModel memory only | **SAFE-IF** as above |
| `AbhaOtpRoute` | `abhaId` | OTP entry field (mock flow) | ViewModel memory | **SAFE-IF** entering the screen does not auto-resend an OTP; mock path, no server transaction to expire |
| `AbhaAadhaarEntry` | none | typed Aadhaar number | ViewModel memory only | **SAFE**. Losing the typed Aadhaar on restore is the desired behaviour, not a regression |
| `AbhaCreateOtpRoute` | `sessionId`, `maskedMobile` | live backend registration transaction, OTP already delivered, masked mobile for display | backend session (time-bounded), `maskedMobile` **nowhere** | **UNSAFE** (see 2.1) |
| `Register` | `abhaId?` | typed registration form | ViewModel memory only | **SAFE-IF** the worker is told the form was cleared (see D4) |
| `MedicalBackground` | `patientId` | patient row + typed history form | DB (patient), ViewModel memory (form) | **SAFE-IF** the patient row still exists; else pop to `Patients` |
| `PatientSummary` | `patientId` | patient, consultation history, documents | DB | **SAFE-IF** patient row exists; else pop to `Patients` |
| `AbhaProfileRoute` | `abhaId` | remote ABHA profile | backend, re-fetched on entry (idempotent GET) | **SAFE** |
| `ConsentRoute` | `patientId`, `followUpOfEncounterId?` | patient identity for the consent text | DB | **SAFE**. Consent is recorded on Continue, not on entry, so re-showing it records nothing twice |
| `Compounder` | `patientId`, `followUpOfEncounterId?`, `resumeEncounterId?`, `resumeCaseRecordId?` | encounter + case record; vitals form; Pi acquisition sub-state | DB (encounter, case, persisted `VitalsSnapshot`), ViewModel memory (unsaved form, `acquisitionJob`) | **SAFE-IF** the entry carries `resumeEncounterId`/`resumeCaseRecordId`. Without them, `StartCaseUseCase` runs again on restore and mints a **second encounter and case record for the same visit**, which is the orphaned-draft/two-doctors defect class again. See 2.2 |
| `EmergencyOverrideRoute` | `reasons: List<String>` | the tripped threshold reasons; no case id at all | args only; the patient banner resolves from the entry beneath | **UNSAFE as declared**, **SAFE** after the IDs-only reshape in section 3 (re-derive from the persisted vitals row) |
| `ConsultationRoute` | `patientId`, `encounterId`, `caseRecordId`, `chiefComplaint` | chief complaint text, typed consultation content, any in-flight audio capture | args (`chiefComplaint`), ViewModel memory (everything else). **No `consultations` row exists yet at this point**: `CompounderViewModel.kt:717` passes the complaint as an effect, and the row is written only at save (`ConsultationViewModel.kt:1381`) | **UNSAFE as declared** (PHI in the Bundle, section 3). **SAFE-IF** D1 is taken: a draft `consultations` row exists for `caseRecordId`; else pop to `Compounder` in resume shape |
| `SendingRoute` | `caseRecordId`, `consultationId`, `audioUri?`, `encounterId` | assessment enqueue | `SendingViewModel.init` calls `enqueueAssessment`, which is `enqueueUniqueWork(name = "assess_$caseRecordId", ExistingWorkPolicy.KEEP)` (`WorkManagerAssessmentScheduler.kt:44`) | **SAFE**. Re-entry is idempotent by construction. This one is safe because of an existing deliberate design choice, so a test must pin it (section 7) |
| `KernelAssessmentRoute` | `caseRecordId`, `consultationId`, `audioUri?` | assessment result / work state | DB + WorkManager, observed (`KernelAssessmentViewModel.kt:171` collects, it does not submit) | **SAFE** |
| `TranscriptionRoute` | `consultationId`, `audioUri`, `caseRecordId` | audio file, transcription result | `TranscriptionViewModel.init` **re-runs `transcribeAudioUseCase` and persists**, and logs `TRANSCRIPTION_COMPLETED` again (`TranscriptionViewModel.kt:44`) | **UNSAFE**. Restore duplicates a clinical write and an audit row, against an `audioUri` that may already be gone. See 2.3 |
| `AcknowledgementRoute` | `caseRecordId` | saved case summary | DB | **SAFE** |
| `ReportRoute` | `caseRecordId` | report | DB | **SAFE-IF** the case/report row exists; else pop to `Home` |
| `ConsultationChainRoute` | `patientId`, `rootEncounterId` | follow-up chain | DB | **SAFE-IF** both rows exist; else pop to `PatientSummary`, else `Patients` |
| `DoctorListRoute` | none | cross-patient tracker | DB | **SAFE** |
| `DoctorAssignmentConfirmRoute` | `caseRecordId` | case status, resolved continuity doctor | DB | **SAFE-IF** the case is not already `SENT_TO_DOCTOR`. If it is, restoring offers a second assignment for a case that already has one. See 2.4 |
| `PatientAuditRoute` | `patientId` | audit rows | DB | **SAFE** |
| `DocumentViewerRoute` | `documentId` | encrypted document, decrypt-to-`cacheDir` temp | DB + `filesDir`; the temp file is swept at every app start (`SaMDApplication.onCreate`) and re-created by the viewer | **SAFE**. Re-decrypt is idempotent; the `document_viewed` audit row fires again at actual render, which is correct, not a duplicate: it was viewed again |

### 2.0 Vitals / Pi acquisition screen

There is **no acquisition route**. The Pi gateway acquisition UI is a sub-state of
`CompounderScreen` (`AcquisitionControls`, `CompounderScreen.kt:265`), driven by
`CompounderViewModel`'s `acquiringInstrument`, `selectedInstrument`, `selectedScenario`,
`acquisitionError`, `fieldProvenance`, and a plain `acquisitionJob: Job?` field
(`CompounderViewModel.kt:304`).

Restore policy: **acquisition state is never saved.** The stack restores to `Compounder`; the
acquisition UI comes back idle. Two required behaviours on top of that:

1. On restore, call `StopDeviceAcquisitionUseCase` **once**, unconditionally, so a gateway session
   that the dead process left open is released rather than blocking the next `onStartAcquisition`.
   The ViewModel already does this in its cleanup path (`CompounderViewModel.kt:604-610`); process
   death skips that path, so the gateway must be told on the way back in.
2. Do **not** persist device-sourced field values or `fieldProvenance`. A restored `DEVICE`
   provenance mark that no acquisition in this process ever produced is a fabricated-provenance
   claim, which is the H-13 direction. The worker re-acquires. This is a deliberate
   data-loss-over-false-provenance trade (D5).

### 2.1 `AbhaCreateOtpRoute`, UNSAFE, mid-OTP

Three independent reasons, any one sufficient:
- The backend registration transaction is time-bounded (ABDM OTP validity), and the app has no way
  to ask "is this `sessionId` still live" before drawing a screen that says "enter the code we
  sent you".
- `maskedMobile` is unrecoverable by design. `Routes.kt` states it plainly: "no endpoint returns it
  a second time". A restored screen either drops the only context the worker has for which number
  the code went to, or carries a contact quasi-identifier through the Bundle (section 3).
- The worker's mental model after a crash is "did that OTP already get used?". Re-presenting the
  entry field invites a second submit against an unknown transaction state.

**Restore policy: pop to the nearest safe ancestor, `AbhaAadhaarEntry`.** Enrolment restarts from
Aadhaar entry, which is the one step that can always be redone safely. If (and only if) a
session-cancel endpoint exists, fire it for the abandoned `sessionId` on restore; otherwise let it
expire. Do not route to `Home`: the worker is mid-registration with a patient in front of them, and
`AbhaAadhaarEntry` keeps them in the task.

### 2.2 `Compounder` without resume args, UNSAFE

`Compounder(patientId)` with null resume args means "start a case". Restoring that entry after the
case already exists re-runs `StartCaseUseCase`, producing a second `Encounter` and a second
`CaseRecord` for one visit. That is the same failure family as the fixed orphaned-draft bug and as
`MIGRATION_15_16`'s one-current-assessment-per-case contract.

**Restore policy: make the entry self-healing rather than popping.** As soon as
`CompounderViewModel` has an `encounterId`/`caseRecordId` (started or resumed), the nav layer
replaces the top entry in place:

```kotlin
backStack[backStack.lastIndex] = Compounder(patientId, followUpOfEncounterId, encounterId, caseRecordId)
```

After that point every save of the stack carries the resume shape, so restore always resumes and
never starts. Before that point (worker crashed within the first moments of the screen), restoring
the start shape is correct: no case exists yet.

This rewrite is load-bearing and is the one behavioural change in the refactor that is not purely
mechanical. It is also independently useful: it makes the existing Home resume path and the
restored path converge on one shape (section 5).

### 2.3 `TranscriptionRoute`, UNSAFE

`TranscriptionViewModel.init` transcribes and persists with no confirmation gate (the H-15.C2
behaviour the flag `VOICE_AUDIO_ATTACHMENT_ENABLED` already guards). Restoring the entry means:
a second transcription run, a second persisted result, a second `TRANSCRIPTION_COMPLETED` audit
row, against an `audioUri` whose backing file may have been swept or may no longer be grantable.

**Restore policy: pop to `AcknowledgementRoute(caseRecordId)`.** Transcription is an optional,
flag-gated leg, and `KernelAssessmentScreen`'s own `onContinue` already treats
"no audio, or flag off" as "go straight to Acknowledgement". Restore takes the same branch. The
case is not harmed: if a transcription was persisted before the crash, it is already on the row.

### 2.4 `DoctorAssignmentConfirmRoute`, conditionally UNSAFE

If the case reached `SENT_TO_DOCTOR` before process death, restoring the confirm screen offers to
assign a doctor to a case that has one. **Restore policy: check `CaseRecord.status` on entry; if it
is `SENT_TO_DOCTOR` or later, replace the entry with `Home`** (the crash-recovery surface), because
the encounter is finished and there is no in-task ancestor worth returning to. Otherwise restore
normally.

### 2.5 Summary: the UNSAFE list

| Route | Verdict | Policy |
|---|---|---|
| `AbhaCreateOtpRoute` | UNSAFE always | pop to `AbhaAadhaarEntry` |
| `TranscriptionRoute` | UNSAFE always | pop to `AcknowledgementRoute(caseRecordId)` |
| `ConsultationRoute` | UNSAFE as declared today | becomes SAFE-IF under D1; otherwise pop to `Compounder` in resume shape |
| `EmergencyOverrideRoute` | UNSAFE as declared today | becomes SAFE under the IDs-only reshape (section 3); never pop, this screen is a red flag the worker must still see |
| `Compounder` (start shape) | UNSAFE after the case exists | in-place entry rewrite to the resume shape (2.2) |
| `DoctorAssignmentConfirmRoute` | UNSAFE if already assigned | replace with `Home` |
| Pi acquisition sub-state | not restorable | always restore idle, and stop the gateway once (2.0) |

## 3. PHI in saved state

The saved stack lands in the Activity's `onSaveInstanceState` Bundle. It is process memory held by
the system server, not encrypted, not covered by SQLCipher, and outside the
`samd_document_key`/`DatabasePassphraseProvider` boundary that the rest of this app's PHI sits
behind. Everything that goes in should be an opaque local id and nothing else.

### 3.1 Every arg that is PHI or free text

| Route.arg | Kind | Verdict |
|---|---|---|
| `ConsultationRoute.chiefComplaint` | **free text, clinical** | must go. Known case, confirmed |
| `EmergencyOverrideRoute.reasons` | **clinical text**, vitals-derived threshold statements | must go |
| `AbhaCreateOtpRoute.maskedMobile` | contact quasi-identifier (masked, still a partial number) | must go |
| `AbhaOtpRoute.abhaId`, `AbhaProfileRoute.abhaId`, `Register.abhaId` | **national health identifier**, a direct identifier. H-18 failure direction (f) is specifically about ABHA leaking into filenames, log lines and manifests; a savedInstanceState Bundle is the same class of surface | see 3.3, needs an operator call (D2) |
| `SendingRoute.audioUri`, `KernelAssessmentRoute.audioUri`, `TranscriptionRoute.audioUri` | pointer to a patient voice recording | must go (also stale-by-restore) |
| `DocumentViewerRoute.documentId` | opaque local id | keep |
| `patientId`, `encounterId`, `caseRecordId`, `consultationId`, `rootEncounterId`, `sessionId`, `followUpOfEncounterId` | opaque local ids, the app's existing data-minimisation shape (`Patient.id` is a 12-char UID by design) | keep |

### 3.2 IDs-only route shape

```kotlin
@Serializable data class ConsultationRoute(
    val patientId: String, val encounterId: String, val caseRecordId: String,
) : NavKey

@Serializable data class EmergencyOverrideRoute(
    val patientId: String, val caseRecordId: String,
) : NavKey

@Serializable data class AbhaCreateOtpRoute(val sessionId: String) : NavKey

@Serializable data class SendingRoute(
    val caseRecordId: String, val consultationId: String, val encounterId: String,
) : NavKey

@Serializable data class KernelAssessmentRoute(
    val caseRecordId: String, val consultationId: String,
) : NavKey

@Serializable data class TranscriptionRoute(
    val consultationId: String, val caseRecordId: String,
) : NavKey
```

Where each removed value comes from instead:

| Removed | Loads from |
|---|---|
| `chiefComplaint` | the `consultations` row for `caseRecordId`. **This row does not exist yet at the hand-off**, which is why D1 is a blocking decision and not a detail |
| `reasons` | re-derived by `CheckEmergencyThresholdsUseCase` from the persisted `VitalsSnapshot` for `caseRecordId`. The vitals **are** saved before the emergency check runs (`CompounderViewModel.kt` records vitals, then checks thresholds), so this re-derivation is exact, not approximate. It also removes the current oddity that the screen has no case id of its own |
| `maskedMobile` | nowhere. The screen keeps it in ViewModel memory for the live pass and, on restore, never renders (the route pops, 2.1) |
| `audioUri` | the audio attachment row for `consultationId` (`CaptureAudioAttachmentUseCase`). D3 confirms the lookup exists; if it does not, `TranscriptionRoute` pops regardless (2.3) and the other two only pass the value through |

`KernelAssessmentRoute` currently forwards `audioUri` purely to decide the next hop. After the
reshape that decision reads the attachment row instead, which is also the more honest check: the
question is "does an audio attachment exist for this consultation", not "did a string survive the
last three screens".

### 3.3 The ABHA id question

`abhaId` cannot be replaced by a local id on `AbhaOtpRoute`/`AbhaProfileRoute` without inventing a
lookup table, because at those points there is no local patient row yet. Options for D2:

(a) **Accept it.** ABHA ids on those three routes stay in the Bundle. Justification: the routes are
already in `SECURED_ROUTE_TYPES`, the exposure window is a live process's saved state, and the
alternative adds a mapping table that is itself a new PHI store.
(b) **Pop instead of restore** for `AbhaOtpRoute` and `Register(abhaId)`, so the id never needs to
be written. `AbhaProfileRoute` (read-only view opened from `PatientSummary`) pops to
`PatientSummary`, which already knows the `abhaNumber`.

Recommendation: **(b) for the enrolment routes, (a) for nothing.** The enrolment leg is short, the
worker is at the tablet, and `AbhaEntry`/`AbhaAadhaarEntry` are cheap to re-reach. That leaves the
saved stack with **zero** direct identifiers, which is a much simpler claim to make in the risk
file than a justified exception.

## 4. Auth on restore

Today the gate is structural: `AppNavHost` only composes `MainNavHost` under
`AuthUiState.SignedIn`, and `MainNavHost`'s KDoc relies on `remember` giving each fresh sign-in a
clean stack. Making the stack saveable breaks that second half. Three requirements:

1. **The stack is keyed to the session.** `rememberSaveable(session.userId, saver = ...)`. When the
   session changes, `inputs` change, the restored value is dropped, and `init()` rebuilds
   `NavBackStack(Home)`. This is why the design does not use `rememberNavBackStack`, which has no
   `inputs` parameter (verified, section 1). Without the key, a sign-out and sign-in **inside the
   same process** can hand worker B the tail of worker A's stack, with worker A's `patientId` in
   it. That is a wrong-context PHI exposure, H-03 shaped, and it is created by this refactor, which
   is why it needs its own risk row (section 8).
2. **An expired session goes to Login and the stack is discarded.** Already structural: the
   `AuthSession.currentUser()` flow emits null, `AuthUiState` becomes `SignedOut`, `MainNavHost`
   leaves the composition, `LoginScreen` shows. The saved value is never consumed, and the userId
   key guarantees it cannot be adopted by the next sign-in.
3. **A restored stack must re-check, not assume.** The token store is local state; a token revoked
   server-side (PIN change revokes every refresh chain, `BackendAuthSession.kt:55-60`) still looks
   valid locally until a call fails. Restoring straight into `ConsultationRoute` on a revoked
   session means the worker types a consultation that cannot sync. D6 decides between:
   (a) rely on the existing 401 handling of the first authenticated call the restored screen makes,
   or (b) add a one-shot `/auth/me` probe on cold start before `MainNavHost` mounts, gating restore
   on it. (b) is correct and costs an offline-tolerance question: PHC tablets are routinely offline,
   and a probe that cannot reach the backend must not sign the worker out.

Recommendation: **(a) plus one guard** - restore is allowed offline, but `MustChangePin` already
routes away from `MainNavHost` entirely, so the only uncovered case is a server-side revocation
that the device has not learned about yet. That is the pre-existing H-06 residual, not a new one.

## 5. Interaction with Home's crash-recovery prompt

`HomeScreen.kt:99-107`: the prompt shows whenever `HomeViewModel` reports a resumable `DRAFT` case,
suppressed only by `dismissedResumeId`, which is `remember`, not `rememberSaveable`.

Two collisions the saveable stack creates:

1. **Double-entry.** A restored deep stack has `Home` at index 0 and, say, `ConsultationRoute` on
   top. The dialog does not draw while Home is not the displayed entry, but the moment the worker
   backs out to Home mid-encounter, the prompt offers to resume the exact case they are already
   inside, and accepting pushes a **second** `Compounder` entry for it. Same class of defect as the
   consult-sent-to-two-doctors bug, reached from a new direction.
2. **Re-prompt after dismissal.** `dismissedResumeId` dies with the process, so a worker who
   dismissed the prompt gets it again on relaunch even though the restored stack already put them
   where they wanted to be.

Design:

- `HomeScreen` takes a new parameter, `resumeSuppressedFor: (caseRecordId: String) -> Boolean`,
  wired in `AppNavHost` to a scan of the live back stack for an entry carrying that
  `caseRecordId`. The scan is the same shape as the existing `currentPatientId(backStack)` helper
  in `Routes.kt` and belongs next to it as `backStackContainsCase(backStack, caseRecordId)`.
- `dismissedResumeId` becomes `rememberSaveable`.
- The prompt therefore fires in exactly one situation: the worker is on Home, and no entry anywhere
  in the stack is about that case. That covers both "restore was discarded, stack is `[Home]`" and
  "the worker genuinely walked away from a draft", and it cannot fire for a case the restored stack
  is already showing.

Stated as an invariant for the tests: **at most one live path to a given `caseRecordId` exists at
any time, whether it arrived by restore or by the resume prompt.**

## 6. Saved-state size and deserialization failure

### 6.1 Bound

The Bundle crosses a Binder transaction at `onSaveInstanceState`. The hard ceiling is
`TransactionTooLargeException` at roughly 1 MB, **shared with everything else in the transaction**,
so the nav stack's own budget must be far below it.

Per entry, `NavKeySerializer` writes the fully qualified class name (about 55 to 75 bytes for
`com.example.samdapp.presentation.navigation.*`) plus the args. With IDs-only routes, args are at
most four 12 to 36 character ids, so an entry costs on the order of 150 to 250 bytes. Realistic
depth is under 10, because `switchTab` clears the stack on every tab switch and three terminal
screens (`EmergencyOverrideRoute`, `DoctorAssignmentConfirmRoute`, plus the Home resets) already do
`clear(); add(Home)`.

**Budget: 64 KB and 50 entries.** Either exceeded, the saver returns `null` from `save`, which
persists nothing, and restore falls back to `[Home]`. 64 KB is roughly 300 entries of headroom over
a stack that should never exceed 10, so hitting it means something is looping, and silently
dropping to Home is the right answer for a loop.

### 6.2 A route that fails to deserialize

`NavKeySerializer.deserialize` does `Class.forName(className).kotlin.serializer()`. A route class
that was renamed, moved, or deleted between the version that saved and the version that restored
throws `ClassNotFoundException` (or `SerializationException`) **from inside composition**.
`rememberNavBackStack` does not catch it. The result would be a crash on every launch until the
user clears app data: a permanent boot loop on a clinical device, which is a field-recall class
defect, not a bug report.

**Design: `runCatching` inside the `Saver`, `null` on failure, `rememberSaveable` falls back to
`init()` = `NavBackStack(Home)`.** No crash, no boot loop, and Home's (now correctly gated) resume
prompt is exactly the right recovery surface for an interrupted encounter.

The discard must be **traceable**, not silent: write one audit row,
`nav_stack_restore_discarded`, with the discard reason (`corrupt`, `too_large`, `session_changed`)
and **no route contents**. Add the action to the single backend device-action vocabulary (the same
place the document and vitals actions are defined) rather than inventing a local constant.

### 6.3 R8

`app/build.gradle.kts:155-160` has `release { optimization { enable = false } }`, so route class
names are not renamed today and the class-name wire format is safe. If optimization is ever
enabled, **every** route fails `Class.forName` and every restore silently discards, which is the
worst kind of regression because it is invisible. Two required follow-ons, both cheap:
keep rules for the route package and its generated `$$serializer` companions, and the corrupt-state
test from section 7 run against a minified variant. Note it in the memo, do not build it now.

## 7. Test plan

### 7.1 Restoration test per route category

`StateRestorationTester` (compose-ui-test) for the Compose-level round trip, since it exercises the
real saver without needing a process kill. Categories mirror section 2:

1. **Tab roots.** Restore `[Patients]`, `[Referrals]`, `[Profile]`: the same tab root is displayed
   and the bottom bar shows the same selection.
2. **Read-only detail.** Restore `[Home, PatientSummary(p), ReportRoute(c)]`: top entry is
   `ReportRoute`, and the patient banner still resolves `p` through `currentPatientId`.
3. **Encounter mid-flow, the important one.** Restore `[Home, PatientSummary(p), ConsentRoute(p),
   Compounder(p, resume...), ConsultationRoute(p, e, c)]` and assert **against the database**:
   `encounters` and `case_records` row counts for `p` are unchanged by the restore, and the
   surviving case is still the same `caseRecordId`. Per `CLAUDE.md`, a restore test that only
   asserts what the screen shows is not sufficient; the row count is the assertion that catches a
   second `StartCaseUseCase` run.
4. **UNSAFE pops.** A stack ending in `AbhaCreateOtpRoute` restores with `AbhaAadhaarEntry` on top;
   a stack ending in `TranscriptionRoute` restores with `AcknowledgementRoute` on top; a stack
   ending in `DoctorAssignmentConfirmRoute` for a `SENT_TO_DOCTOR` case restores as `[Home]`.
5. **Idempotent-entry pins.** Restore into `SendingRoute` and assert exactly one unique work item
   named `assess_<caseRecordId>` exists (pins the `ExistingWorkPolicy.KEEP` behaviour the SAFE
   verdict depends on). Restore into `TranscriptionRoute`'s replacement and assert **no** second
   `TRANSCRIPTION_COMPLETED` audit row.
6. **Auth.** Restore with a cleared token store: `LoginScreen` shows; sign in again and the stack
   is `[Home]`, not the restored one. Second case: sign out and sign in as a **different**
   `userId` in one process, and assert the stack is `[Home]` (this is the section 4 hazard).
7. **Corrupt state.** Hand the saver a `SavedState` whose element `type` is
   `com.example.samdapp.presentation.navigation.RouteThatNoLongerExists`: restore yields `[Home]`,
   no exception, and one `nav_stack_restore_discarded` audit row with reason `corrupt`.
8. **Acquisition.** Restore into `Compounder` while `acquiringInstrument` was set: the UI comes
   back idle, `StopDeviceAcquisitionUseCase` was called exactly once, and no field carries a
   `DEVICE` provenance mark that this process did not produce.

### 7.2 PHI-free saved-state assertion

`NavBackStackPhiFreeTest`: build a deep stack containing every route that carries an arg, using
recognisable probe values (`chiefComplaint = "ZZPROBE cough three days"`, masked mobile
`"XXXXXX9999"`, an emergency reason string, an audio path under `cacheDir`). Serialize through the
production saver, flatten the resulting `SavedState`/Bundle to a string, and assert:

- none of the free-text or PHI probes appears anywhere in it;
- the opaque ids **do** appear (otherwise the test passes trivially on an empty stack);
- after the D2 decision, no ABHA-shaped value appears either.

This test is the regression guard for the whole of section 3 and should fail loudly the first time
someone adds a `String` arg to a route without thinking.

### 7.3 `scripts/process_death_check.sh`, deep-stack mode

Extend, do not fork. The script already owns the `always_finish_activities` reset-on-every-exit
discipline and the adb plumbing; add a second mode rather than a second script.

`scripts/process_death_check.sh [serial] [duration] [--deep-stack]`

In `--deep-stack` mode:
1. Preconditions as today (device, package running), plus: the app must be deeper than the tab root.
   Read depth from a **dev-flavor-only** logcat marker the app emits whenever the stack changes,
   `NAVSTACK depth=<n> top=<simple class name>`. No instrumentation hook, no debug broadcast
   receiver, just a log line that already has to exist for the audit row in 6.2 to be testable.
2. Record `top` and `depth` before the kill.
3. Background the app (`input keyevent KEYCODE_HOME`), wait for `oom_score_adj` to leave 0, then
   `am kill <package>` and confirm the pid is gone. The existing foreground-refusal check is why
   backgrounding first is required, and that asymmetry is worth a comment in the script: the
   capture check asserts the kill is **refused**, this one asserts it **succeeds**.
4. Relaunch with `monkey -p <package> 1` or `am start`, wait for the marker, and assert
   `top` and `depth` match what was recorded, **or** match the documented pop policy for an UNSAFE
   route (the script prints both the expected and the actual so a pop reads as a pass with a note,
   not a failure).
5. Exit non-zero on mismatch. Keep the existing `trap cleanup EXIT`.

Two runs make the acceptance case: one on a deep SAFE stack (expect exact match), one parked on
`TranscriptionRoute` (expect `AcknowledgementRoute`).

## 8. Risk-file delta, PROPOSED

Next free hazard id in `docs/quality/risk-management-file.md` is **H-26** (H-01 through H-25 taken).
Next free residual id is **RR-03**.

### 8.1 New hazard row, section 2

Same row format and same PROPOSED banner convention as H-18 through H-25.

> **PROPOSED, AWAITING OPERATOR SIGN-OFF. NOT APPROVED. Drafted 2026-09-17, navigation back-stack
> process-death restore design memo (`scratchpad/navstack-restore-memo.md`), H4-a** **H-26** |
> Restoring a persisted navigation back stack places the worker back into a clinical screen whose
> context is no longer the context that stack was saved in |
> (a) **wrong-session restore**: a stack saved under worker A is adopted by worker B's sign-in in
> the same process, putting another worker's patient on screen and attributing whatever follows to
> B, which is H-03's wrong-patient harm and H-06's accountability gap reached through a new route;
> (b) **wrong-encounter restore**: a `Compounder` entry restored in start shape mints a second
> encounter and case record for one visit, splitting one clinical record in two, the same family as
> the orphaned-draft defect and as `MIGRATION_15_16`'s one-current-assessment-per-case contract;
> (c) **duplicate clinical write**: a route whose ViewModel performs work in `init`
> (`TranscriptionRoute`) re-runs it on restore, persisting a second result and a second audit row
> for one clinical act; (d) **stale external transaction**: an enrolment OTP screen restored
> against an expired ABDM registration session invites a submit whose outcome the app cannot
> predict; (e) **PHI in unencrypted saved state**: free-text clinical content
> (`ConsultationRoute.chiefComplaint`, `EmergencyOverrideRoute.reasons`) and identifiers written
> into an `onSaveInstanceState` Bundle sit outside the SQLCipher and `samd_document_key`
> boundaries, the same class of surface as H-18 direction (f); (f) **boot loop**: a route class
> that cannot be deserialized after an app update throws from inside composition, crashing every
> launch until app data is cleared |
> High | **Not established.** The control does not exist yet; no field data bears on it. This row
> declines to assert a probability, following the H-15/H-17/H-18 precedent |
> **PROPOSED, not built** (design memo only): (i) back stack keyed to `session.userId` via
> `rememberSaveable(inputs)`, so a session change discards rather than adopts, which the library's
> own `rememberNavBackStack` cannot express; (ii) the `Compounder` entry is rewritten in place to
> its resume shape as soon as the case exists, so restore always resumes and never starts;
> (iii) a per-route restore-safety classification with an explicit pop policy for every UNSAFE
> route, `AbhaCreateOtpRoute` to `AbhaAadhaarEntry` and `TranscriptionRoute` to
> `AcknowledgementRoute`; (iv) IDs-only route args, with chief complaint, threshold reasons, masked
> mobile and audio URI re-derived from the database instead of carried in the Bundle, pinned by a
> PHI-free saved-state test; (v) a `Saver` that returns `null` on any encode or decode failure and
> on a 64 KB / 50-entry budget overrun, so a corrupt or oversized stack degrades to `[Home]` and
> can never boot-loop, with a `nav_stack_restore_discarded` audit row carrying a reason and no
> route contents; (vi) Home's resume prompt suppressed for any case the live stack already contains
> |
> Depends on D1 (draft consultation row) and D2 (ABHA id in saved state) below; the residual is
> RR-03. R8 is disabled today (`app/build.gradle.kts`), so the class-name wire format is safe; if
> optimization is ever enabled, keep rules for the route package and its generated serializers
> become a prerequisite, and the corrupt-state test must run against a minified variant

### 8.2 New residual, section 4.1

> **RR-03 - Unrecoverable in-screen state after process death. Status: PROPOSED, not accepted.**
> The back stack is restored; the contents of the screen it restores into are not. Typed
> registration and medical-background forms, an unsaved vitals form, an in-flight Pi gateway
> acquisition and its device provenance marks, and a typed consultation body all live in ViewModel
> memory and are gone. For the acquisition case this is deliberate: persisting a `DEVICE`
> provenance mark that no acquisition in this process produced would be a fabricated-provenance
> claim (H-13's direction), so the design prefers losing the reading to asserting an unearned one.
> The worker sees the right screen with an empty form, which is honest but can read as "my work was
> saved" if the screen does not say otherwise. Mitigation deferred: a per-screen saved-state draft
> for the plain text forms, and a restored-after-restart notice on the screens that lose input.

### 8.2.1 H-26 residual additions, recorded during phase 2 (2026-09-17)

Both of these belong in H-26's residual when the risk file is edited in phase 5. The risk file
itself is deliberately untouched until then; this is the staging area.

> **Owed to phase 4: `DoctorAssignmentConfirmRoute` restored for an already-assigned case.**
> If the case reached `SENT_TO_DOCTOR` before process death, restoring this entry offers a second
> doctor assignment for a case that already has one. Deliberately NOT closed in phase 2: the policy
> needs a `CaseRecord.status` read, and a `Saver` has no coroutine scope and cannot suspend, so
> closing it would have required a second mechanism (a ViewModel-level observe-and-replace) beside
> the save-time transform for the only conditionally-unsafe route in the set. Phase 4 handles it as
> a status observe-and-replace, alongside the emergency-reason Robolectric test and
> `process_death_check.sh`. Bounded: the case is already assigned, so the failure is a redundant
> assignment offer, not a lost or mis-routed case.

> **Coupling: the `Compounder` content key depends on the resume-path gating.**
> `AppNavHost` pins the Compounder entry's `contentKey` to
> `"Compounder:${patientId}:${followUpOfEncounterId}"` rather than letting it default to
> `key.toString()`. Without the pin, the in-place rewrite into resume shape changes the key,
> `ViewModelStoreNavEntryDecorator` clears the store, and the rebuilt `CompounderViewModel` takes
> the resume branch and logs `ENCOUNTER_RESUMED` for a visit that was started seconds earlier and
> never left: a fabricated row on a clinical audit trail. The pinned key is unique only while no
> two `Compounder` entries share a patient AND a follow-up parent.
> `NavBackStackPolicyTest` pins the collision precondition, and `CompounderContentKeyTest` proves
> the pin's effect directly: with it, no `ENCOUNTER_RESUMED` and one ViewModel construction; with
> the default key, one fabricated row and two constructions.
>
> **CORRECTED 2026-09-18.** As first written, this note said the uniqueness was held by Home's
> resume gating (`resumeSuppressedFor` / `backStackContainsCase`). That is wrong, and it named a
> mechanism that cannot fire. `Home` exists only at index 0: the stack is seeded with it
> (`AppNavHost.kt:103`), `switchTab` clears before adding a tab root (`:126-136`), and both other
> insertions are `backStack.clear(); backStack.add(Home)` on one line (`:360`, `:421`). `NavDisplay`
> draws the last entry, so `HomeScreen` composes only when the stack is exactly `[Home]`, and
> `backStackContainsCase` is therefore always false at the moment it is consulted.
>
> WHAT ACTUALLY ENFORCES IT is a structural property of `AppNavHost`: only one `Compounder` can be
> in the stack at all. There are two push sites, Home's resume prompt (reachable only at `[Home]`)
> and `ConsentRoute` (`:184`, `:299`). Reaching either again while a `Compounder` is live requires a
> tab switch, which clears, or popping back past that entry. A second one cannot be pushed on top of
> the first.
>
> The three-way coupling still stands, with the middle leg restated:
> 1. the pinned content key is unique only under "at most one live `Compounder` per patient and
>    follow-up parent";
> 2. that property is held by the single-`Compounder` structure above, NOT by the resume gate. The
>    gate is retained as defence in depth against a future multi-pane or list-detail scene that
>    could compose `Home` with a non-empty tail, at which point it becomes the live guard. See the
>    comment at its call site;
> 3. `transformForSave`'s whole-stack ancestor search for `ConsultationRoute` matches on `patientId`
>    alone and takes the nearest match, which is the OWNING `Compounder` only because a second
>    same-patient one cannot be live. (Nearest and owning otherwise coincide anyway:
>    `ConsultationRoute` is pushed only from the adjacent `Compounder`, `AppNavHost.kt:304`.)
>
> So the thing not to weaken in isolation is the single-`Compounder` structure: do not add a
> `Compounder` push site, and do not introduce a scene that composes `Home` with a non-empty tail,
> without revisiting both the content key and the ancestor search. One caveat recorded honestly:
> "popping back removes the entry" is standard `NavDisplay` behaviour that has been reasoned about
> rather than read out of the library, and phase 4 settles it while reading the back handler for
> `process_death_check.sh`.

### 8.3 Cross-links to H-18

- **H-18 (f), ABHA in durable surfaces.** H-18 closed that direction "by construction rather than
  by convention": `Patient.id` is the only identifier in a filename or storage key. The saved-state
  Bundle is a new durable-ish surface that the same argument has to cover, so the D2 recommendation
  (pop the enrolment routes rather than persist `abhaId`) extends H-18 (f)'s construction rather
  than opening an exception to it. H-18's row should gain a one-line pointer to H-26.
- **H-18's decrypt-to-`cacheDir` temp window.** `DocumentViewerRoute` restore is SAFE precisely
  because that window is swept at every app start and re-created by the viewer, and because the
  `document_viewed` audit row fires at actual decrypt-and-render. Restore re-renders, so it
  re-audits, which is correct. H-26 relies on that property; if the sweep or the audit point ever
  moves, `DocumentViewerRoute`'s verdict has to be re-examined.
- **H-18 Build 3b Option A (`scratchpad/capture-process-death-memo.md`).** That memo's claim is
  that the capture screen never leaves the foreground and so is never an LMK candidate. H-26 is the
  complement: every other screen **is** killable, and this is the design for what happens when one
  is. The two memos share `scripts/process_death_check.sh`, which is why section 7.3 extends it
  rather than adding a second script, and why the opposite-polarity `am kill` expectations
  (refused during capture, expected to succeed for the deep-stack case) need a comment in the
  script.
- **H-15.C2 / `VOICE_AUDIO_ATTACHMENT_ENABLED`.** `TranscriptionRoute`'s UNSAFE verdict is a direct
  consequence of the no-confirmation-gate persist that H-15.C2 already records. The pop policy is a
  second control on the same underlying behaviour, not a new one.

## 9. Open decisions

| # | Decision | Why it is blocking | Recommendation |
|---|---|---|---|
| **D1** | Where does `chiefComplaint` live between `Compounder` and `ConsultationRoute`? | It is in the nav arg today and **nowhere in the database** until the consultation is saved. Removing it from the route (section 3) with no replacement loses the text on every forward navigation, not just on restore. Options: (a) `Compounder` upserts a draft `consultations` row carrying the complaint before navigating, and `ConsultationViewModel` reads it; (b) keep the complaint in the route and accept clinical free text in the Bundle; (c) drop it and have the worker retype after restore only, which needs the value to survive the forward hop some other way | **(a)**. It reuses the write that already exists at consultation save, it makes `ConsultationRoute` restorable, and a draft row is the same shape the DRAFT case status already implies. It is also the one item in this refactor that touches the data layer, which is the scope question in the model check below |
| **D2** | ABHA id in saved state: persist, or pop the enrolment routes? | H-18 (f) treats ABHA in a durable surface as a distinct failure direction | **Pop** (`AbhaOtpRoute`, `Register(abhaId)` to `AbhaEntry`; `AbhaProfileRoute` to `PatientSummary`). Leaves the saved stack with zero direct identifiers |
| **D3** | Is there a query that returns the audio attachment for a `consultationId`? | Decides whether `audioUri` can be dropped from three routes or only from `TranscriptionRoute` (which pops anyway) | Confirm in `CaptureAudioAttachmentUseCase` / the attachment DAO before the build starts. If absent, the reshape still holds, the lookup is just one more small query |
| **D4** | `Register` and `MedicalBackground` restore to an empty form. Silent, or with a notice? | A worker who typed half a registration and gets a blank form back may assume it saved | A one-line notice on restore. Cheap, and it is the honest reading of RR-03 |
| **D5** | Acquisition provenance: persist device-sourced values across process death, or never? | Persisting a `DEVICE` mark this process did not produce is an H-13 shaped fabricated-provenance claim | **Never persist.** Worker re-acquires |
| **D6** | Session re-check on restore: rely on the next call's 401, or probe `/auth/me` on cold start? | A server-side revocation (PIN change revokes every refresh chain) is invisible to the device until a call fails | Rely on the 401 path. A probe that fails offline must not sign a PHC worker out, and the gap is the pre-existing H-06 residual, not a new one |
| **D7** | `nav_stack_restore_discarded` audit action: add to the shared device-action vocabulary now, or log-only? | The existing rule is a single point of definition for device audit actions in the backend | Add it to the shared vocabulary. A discard that leaves no trace is a restore failure nobody can investigate from the field |
