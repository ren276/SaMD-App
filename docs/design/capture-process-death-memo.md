# Capture process death, Phase A memo (read-only investigation)

H-18 Build 3b, PATH B. Field symptom: in consultation document capture, after capturing ONE photo
and pressing OK in the camera app, the app returns to the home screen and the page is lost.

Status: PHASE A ONLY. No production code changed. Nothing here is approved.
Branch: `fix/capture-process-death`. Date: 2026-09-17.

## 0. Test bed and what was actually run

| Item | Value |
|---|---|
| Device | iQOO I2302, arm64-v8a, Android 16, the same handset the ASR SOUP record validated on |
| Serial | `10BE3A09C700046` |
| Build | `:app:installDevDebug`, `com.example.samdapp.dev`, versionCode 1, installed 2026-09-17 10:17:18 |
| Memory at test time | MemTotal 7597400 kB, MemFree 82416 kB, MemAvailable 1920600 kB |
| Emulator | `emulator-5554` present but NOT used for the repro, and its settings were left untouched |

`always_finish_activities` was set to 1 during the run and **reset to 0 at the end** (verified by
read-back). Scratch UI dumps written to `/sdcard` were deleted.

The signed-in session on the handset was left alone. No credential, keystore or `.env` file was
opened, read or written at any point.

## 1. Verdicts

### H1. TakePicture backgrounds our process; the low-memory killer kills it. VERIFIED.

Ran:

```
adb -s 10BE3A09C700046 shell cat /proc/28841/oom_score_adj      # -> 0   (app foreground)
adb -s 10BE3A09C700046 shell am start -a android.media.action.IMAGE_CAPTURE
adb -s 10BE3A09C700046 shell cat /proc/28841/oom_score_adj      # -> No such file or directory
```

The camera took the foreground (`topResumedActivity=com.android.camera/.CameraActivity`) and our
process was gone. Events log, exact line:

```
09-17 10:18:04.055  1788  2358 I am_proc_died: [0,28841,com.example.samdapp.dev,700,15]
```

adj 700 is CACHED_APP, procState 15. The death landed inside a mass reclaim that took six other
processes within four seconds: `com.android.settings`, `com.reddit.frontpage`,
`app.revanced.android.apps.youtube.music`, `com.duolingo`, `tv.twitch.android.app`,
`com.fitbit.FitbitMobile`. Elapsed from last confirmed-alive log line (10:17:41) to death
(10:18:04) was about 23 seconds, which is well inside the time a worker spends framing and
confirming a document photo.

Two findings the hypothesis did not state, both load-bearing:

1. **`am kill` against our process while it is in the FOREGROUND is refused.** pid was unchanged
   after the call. The process only becomes killable once something else takes the foreground.
   The camera hop is therefore not an aggravating factor, it is the necessary precondition.
2. **This process is an unusually fat eviction target.** The dev APK is 757126026 bytes because
   the Parakeet weights and sherpa-onnx natives ship in `app/src/main/assets` and `app/libs`. A
   process that large, at adj 700, is near the front of the queue on a handset with 82 MB free.

### H2. The startup sweep deletes the staging file and the session dir before the activity result is delivered. VERIFIED, but it is NOT the operative cause. See section 2.

Planted a realistic mid-capture state via `run-as` (one encrypted page in the session directory,
one plaintext staging file, the exact pair that exists between the camera returning and
`ingestPage` finishing):

```
files/documents/.capture/<sessionId>/<pageId>.enc
cache/document_capture_staging/<sessionId>__<pageId>.jpg
```

Both files were confirmed present immediately after the process death, so the death itself does
not remove them. After relaunch:

```
ls: files/documents/.capture/: No such file or directory
ls: cache/document_capture_staging/: No such file or directory
```

Both directories deleted outright. That is `sweepOrphanedCaptureSessions`
(`app/src/main/java/com/example/samdapp/data/local/document/AndroidDocumentCaptureStore.kt:79-82`)
called from `SaMDApplication.onCreate`
(`app/src/main/java/com/example/samdapp/SaMDApplication.kt:36`).

The ordering claim in the hypothesis is correct and is platform-guaranteed rather than a race:
`Application.onCreate` runs before any Activity in the process is created, therefore before
`ActivityResultRegistry` can dispatch a restored pending result. There is no window in which the
result could beat the sweep.

However, on this code the pending result is never dispatched at all, because the screen that
registered the launcher is never composed after the restart. See H4.

### H3. Session id, pending pageId and page list live only in ViewModel memory, no SavedStateHandle. VERIFIED.

- `DocumentCaptureUiState` is a field of `ConsultationUiState`, held in
  `_uiState = MutableStateFlow(...)` at
  `app/src/main/java/com/example/samdapp/presentation/consultation/ConsultationViewModel.kt:367`.
- The constructor at `ConsultationViewModel.kt:343-355` takes four `@Assisted` strings and six
  injected collaborators. There is no `SavedStateHandle` parameter, and `SavedStateHandle` appears
  nowhere in the file.
- This is deliberate and documented: `ConsultationViewModel.kt:57-62` states "nothing here is
  persisted. A process death therefore loses the capture, which is the intended posture".

Wider than the hypothesis states: `documentDraftDepartment`, `documentDraftRecordType` and
`documentDraftLabel` are also `_uiState`-only, and `onFinishDocumentCapture`
(`ConsultationViewModel.kt:1205-1210`) returns early without them. So even a perfectly restored
page list could not be finished without restoring those three as well.

Confirmed by consequence on device: after the kill there was no capture surface in any state.

### H4. The return to home is caused by in-memory auth session or start-destination logic. VERIFIED that the app returns to Home. The stated cause is WRONG on both limbs.

**Not auth.** The session survived the kill. After relaunch the app showed
`Welcome, sandesh (ASHA worker)` and the Home screen, never Login. `BackendAuthSession` persists
the session and `AuthViewModel.state` (`presentation/auth/AuthViewModel.kt:38-47`) re-derives
`SignedIn` on restart.

**Not start-destination logic** in the sense of a redirect or a gate.

**Actual cause, one line:**

```
app/src/main/java/com/example/samdapp/presentation/navigation/AppNavHost.kt:90
    val backStack = remember { mutableStateListOf<Any>(Home) }
```

`remember`, not `rememberSaveable`. The entire back stack is discarded on process death and
re-seeded with `[Home]`. Nothing else is needed to explain the symptom.

Isolated on device. Navigated Home -> ABHA entry (one hop), backgrounded, `am kill`, relaunched:

```
pid after kill: ''            (process genuinely dead)
new pid:        31744
screen:         Home
```

One hop was enough. The consultation flow sits four or more hops deep
(Home -> Consent -> Compounder -> ... -> Consultation), so it loses strictly more.

Supporting detail: `rememberSaveableStateHolderNavEntryDecorator()` at `AppNavHost.kt:155` saves
per-entry UI state, not the stack itself, so it does not and cannot help here.

Second-order, worth stating because it changes the fix: even if the stack were restored,
`ConsultationRoute` (`presentation/navigation/Routes.kt:72-77`) carries only `patientId`,
`encounterId`, `caseRecordId` and `chiefComplaint`, and the ViewModel would rebuild with an empty
capture (H3).

Also observed: Home's crash-recovery prompt ("Resume in-progress consultation?") does fire after
the kill. It routes to `Compounder` with `resumeEncounterId`/`resumeCaseRecordId`
(`Routes.kt:61-66`), not to `ConsultationRoute`, so it does not recover the capture either. It is
a case-level recovery, not a screen-level one.

**H4 is not a capture bug. It is app-wide.** Every screen in this app loses its place on process
death. Capture is simply where a worker notices, because the camera reliably triggers the death.

## 2. What the field symptom actually is

Three independent losses, chained:

1. **H1 is the trigger.** The camera backgrounds us, the LMK takes a fat cached process.
2. **H4 is what the worker sees.** The back stack resets to `[Home]`. This is the "returns to the
   home screen" in the report.
3. **H3 then H2 are what destroys the work.** The capture state is gone with the ViewModel, and
   the sweep deletes the bytes at the next process start.

This ordering decides the fix, and it contradicts the framing of the brief's hypothesis list:

- Fixing H2 alone (changing the sweep policy) changes nothing the worker sees. The screen and the
  state are already gone before the files matter.
- Fixing H4 alone returns the worker to the consultation with an empty capture surface.
- Only removing the app switch removes the trigger.

## 3. Option A, in-process CameraX (the operator reviewer's recommendation)

Replace `ActivityResultContracts.TakePicture` with an in-process viewfinder, so no other app ever
takes the foreground during capture.

Shape: CameraX 1.6.2, `camera-core` + `camera-camera2` + `camera-lifecycle` + `camera-compose`
(`CameraXViewfinder`), `ImageCapture.takePicture(executor, OnImageCapturedCallback)`, encrypt
straight from the in-memory `ImageProxy` so no plaintext staging file is ever created, close the
`ImageProxy` in a `finally`, shutter disabled until ingest completes, decode and downscale on
`Dispatchers.Default`, encrypt and write on `Dispatchers.IO`, `CAPTURE_MODE_MAXIMIZE_QUALITY`,
runtime `CAMERA` permission.

What this costs and what it buys is set out in section 5.

## 4. Option B, keep TakePicture and persist the session

Keep the external camera. Add `SavedStateHandle` to `ConsultationViewModel`, persist `sessionId`,
`pendingPageId`, `pendingStagingPath`, the ordered page id list and the three draft fields. Change
the sweep from unconditional to conditional so it does not delete a session that is legitimately
being restored.

Assessment: **not recommended as the primary fix**, for three reasons.

1. It does not remove the trigger. The process still dies, roughly as often.
2. It requires a risk-control change to (xiv), not merely a code change. See section 5.
3. It is the larger change. Restoring a capture correctly means restoring page thumbnails (which
   are in-memory `ByteArray`s, not persisted), the three draft fields, and the pending-page
   in-flight state, and then making the sweep distinguish a restorable session from an orphan.
   `AndroidDocumentCaptureStore.kt:64-78` argues explicitly against exactly that complexity.

B also has a failure mode A does not: a restored session is encrypted PHI on disk that outlived
its process, which is the posture control (xiii) was written to prevent.

## 5. Recommendation

**Option A as the primary fix, plus the H4 back-stack defect handled as a separate, explicitly
scoped item. Do not take Option B.**

A removes the dominant trigger and, as a side effect, closes a residual the H-18 row currently
declares open. H4 must still be fixed because any other app switch (an incoming call, a
notification tap) reaches the same process death, and because it is an app-wide defect that
happens to be most visible here.

### 5.1 Does control (xiv) still hold?

**Under Option A: yes, unchanged, and its premise gets stronger. This is NOT a risk-control
change to (xiv).**

(xiv) rests on "a session's page list lives only in ViewModel state, so no capture can survive
process death, and anything present at process start is orphaned by definition". Option A persists
nothing new, so that premise is untouched and the unconditional sweep stays correct. The sweep
code does not change.

**Controls (xi) and (xvi) and the recorded residual DO change, and those changes are
risk-control changes that need operator sign-off:**

- (xi) currently reads "the plaintext staging file is deleted in a `finally` before that call
  returns". Under A there is no plaintext staging file at all. The control gets strictly stronger
  and its text must be rewritten, not merely re-affirmed.
- The H-18 residual that reads "**Open (Build 3b): a transient plaintext staging file exists for
  camera capture, and this row does not claim zero plaintext**" is **closed** by A. That residual
  names its own remedy: "Eliminating it entirely requires an in-process camera pipeline (CameraX
  `ImageCapture.takePicture(OnImageCapturedCallback)`)".
- `res/xml/file_paths.xml` loses its `document_capture_staging` entry, and the app stops granting
  a `FileProvider` URI to any external process for document capture. The `attachments` and
  `reports` entries stay.

**Under Option B: (xiv) must change.** Persisting a session makes a capture survive process death,
which directly falsifies "orphaned by definition". The sweep would have to become conditional, on
an age heuristic or a live-session registry. That is a risk-control change requiring operator
sign-off before any code is written.

**A decision is being reversed and must be named.** The H-18 row records that the in-process
camera pipeline "was considered and deferred on 2026-09-04 as not worth four new dependencies and
a self-built viewfinder for a millisecond-scale, single-page, non-persistent window". Option A
reverses that deferral. The new evidence justifying the reversal is section 1's H1 result: the
window is not millisecond-scale in its consequences, because the app switch itself, not the file,
is what costs the worker the page. The operator has to make that reversal explicitly.

### 5.2 Behaviour if the process still dies mid-capture (user switches apps)

**Unchanged from today, and that is deliberate.** Under Option A:

- Pages captured so far are lost. The session directory is swept at the next process start.
- No partial PDF is produced, because assembly never started. R4 is not engaged.
- No orphaned encrypted PHI survives, which is the (xiii) and (xiv) posture.
- The worker lands on Home (or, once H4 is fixed, back on the consultation with an empty capture
  surface) and re-takes the document.

Option A reduces the frequency of this, it does not change the behaviour when it happens. Stated
plainly so the memo is not read as claiming A makes capture process-death-proof. It does not.

### 5.3 How the existing guarantees are preserved

| Guarantee | Under Option A |
|---|---|
| R4, abort and never a shorter PDF | Untouched. `assemble`, `writePdf`, `drawPage`, `DocumentPageUnreadableException` and the no-catch, no-continue loop (`AndroidDocumentCaptureStore.kt:178-285`) are not modified. The change is entirely upstream of the page store. |
| One decoded page in memory | Assembly side unchanged (`drawPage` decode, draw, `finishPage`, `recycle`, next). Capture side gains one new object, the `ImageProxy`, holding one compressed frame. Closed in a `finally`. Thumbnail decode keeps `computeInSampleSize`. Peak becomes one compressed frame plus one downscaled thumbnail bitmap, which is comparable to today, where the same frame sat in a file and was decoded twice. |
| 20-page cap | Unchanged. `MAX_PAGES = 20` in the store, `canAddPage` gates the shutter. |
| Single upload path, control (xvi) | Unchanged. Still `DocumentBytes.AssembledCapture` through the same `assemble` and the same `UploadConsultationDocumentUseCase`. |
| Backpressure, at most one page in flight | Preserved and improved. Today it is `canAddPage == false` while `pendingPageId` is set. Under A the same flag disables the shutter button, and there is no longer a staging file whose lifetime has to be reasoned about. |
| Runtime CAMERA permission | Already exists and is reused unchanged: `rememberPermissionAction(Manifest.permission.CAMERA, onGranted = actions::onStartDocumentCapture)` at `ConsultationScreen.kt:194-197`, helper at `presentation/common/PermissionAction.kt:23-39`. |

Store API delta under A: `stagingPathFor` and `discardStaging` disappear from `DocumentCaptureStore`,
and `ingestPage(sessionId, pageId)` becomes a call that takes the JPEG bytes directly. `STAGING_DIR`,
`stagingFile` and the staging branch of `discardSession` go with them. The sweep keeps only its
`filesDir/documents/.capture` half.

### 5.4 Fix for H4

Separate from the capture work, because it is app-wide.

The defect is one line, `AppNavHost.kt:90`. The fix is not one line, because the back stack holds
arbitrary route objects and `remember` has to become something that survives process death. Two
sub-options, and this is an **open decision for the operator**:

- **H4-a, restore the stack.** Make the routes serializable and use a saveable back stack
  (Navigation3's `rememberNavBackStack` over `NavKey` routes, or `rememberSaveable` with an
  explicit `Saver`). Touches every route in `Routes.kt` and is the honest fix, but it is a
  cross-cutting navigation change and it is not in the capture-fix scope.
- **H4-b, leave the stack and widen the existing crash recovery.** Home already detects an
  unfinished `DRAFT` case and offers to resume. It currently resumes into `Compounder`. Making it
  resume to the right point in the flow is a smaller change confined to Home, and it uses
  machinery that already exists and is already tested.

Recommendation: **H4-a is correct, H4-b is cheap.** I do not think this decision should be made
inside a capture fix. It should be its own change with its own review.

Note for scoping: H4-a alone does not restore a capture in progress, because of H3. It returns the
worker to the consultation screen with the pages gone. That is still a large improvement over
landing on Home, and it does not require touching (xiv).

### 5.5 SOUP register entries

Four new components, all AndroidX, all Apache-2.0. Versions confirmed published on Google Maven on
2026-09-17 (`camera-compose` 1.6.2 exists; the 1.6.x line is 1.6.0, 1.6.1, 1.6.2).

Version catalog, `gradle/libs.versions.toml`:

```toml
[versions]
camerax = "1.6.2"

[libraries]
androidx-camera-core      = { group = "androidx.camera", name = "camera-core",      version.ref = "camerax" }
androidx-camera-camera2   = { group = "androidx.camera", name = "camera-camera2",   version.ref = "camerax" }
androidx-camera-lifecycle = { group = "androidx.camera", name = "camera-lifecycle", version.ref = "camerax" }
androidx-camera-compose   = { group = "androidx.camera", name = "camera-compose",   version.ref = "camerax" }
```

| Component | Coordinate | Version | Licence |
|---|---|---|---|
| CameraX Core | `androidx.camera:camera-core` | 1.6.2 | Apache-2.0 |
| CameraX Camera2 | `androidx.camera:camera-camera2` | 1.6.2 | Apache-2.0 |
| CameraX Lifecycle | `androidx.camera:camera-lifecycle` | 1.6.2 | Apache-2.0 |
| CameraX Compose | `androidx.camera:camera-compose` | 1.6.2 | Apache-2.0 |

Three consequences, all of which are work items and not automatic:

1. These are Maven-published, so unlike the ASR SOUP they **do** appear in the generated CycloneDX
   SBOM. Identity and hashes come for free. The IEC 62304 SOUP **validation record** is per
   component and still has to be written: `docs/quality/soup-validation-record.md` is currently
   scoped to the five ASR components only, so CameraX needs its own record or a new section with
   its own requirements table (the S-1 to S-5 shape).
2. `OpenSourceLicensesScreen.kt` is a hand-maintained list (Compose, Room, Hilt, SQLCipher,
   Retrofit, OkHttp, Coroutines, WorkManager and the ASR components are each listed by hand). A
   "CameraX" entry has to be added by hand or the Apache-2.0 attribution is incomplete at
   distribution.
3. CameraX pulls transitive AndroidX dependencies. The SBOM will capture them; whether each
   transitive is a separately-registered SOUP component is an operator and QA call, not a
   developer call.

### 5.6 H-18 risk-file delta, PROPOSED, separate commit

`docs/quality/risk-management-file.md`, the H-18 row. To be a **separate commit** from any code,
carrying the existing PROPOSED, AWAITING OPERATOR SIGN-OFF, NOT APPROVED banner, in the same style
as the row's Build 3a/3b/3c layering. Proposed edits:

1. **Rewrite (xi)** to drop the staging file from the control text and state that a captured frame
   is encrypted from memory and never written as plaintext.
2. **Add a new control**, next free number (xxiii), for the in-process CameraX pipeline. It should
   say plainly that removing the app switch is a **hazard-reduction control, not only a privacy
   control**: it prevents the loss-of-work and process-death path that this memo's section 1
   measured, and it removes the plaintext window as a side effect.
3. **Close the residual** "Open (Build 3b): a transient plaintext staging file exists for camera
   capture", citing the CameraX pipeline that the residual itself already names as the remedy.
4. **Reverse the recorded deferral** of 2026-09-04 explicitly, with the H1 evidence as the reason,
   rather than silently contradicting it.
5. **Keep (xiv) as it stands**, and add a sentence stating that its premise was re-tested on
   2026-09-17 and holds, with the planted-file result from section 1 as the evidence.
6. **Add a new residual** for what A does not fix: a process death from any other app switch still
   loses the captured pages, and the back-stack defect means the worker lands on Home. Cross
   reference the H4 decision so the risk team sees that it is tracked and not forgotten.
7. **Add four SOUP components** to whatever the row or the SOUP record treats as the dependency
   inventory for this feature.

Nothing in this delta should be written before the operator approves the option, because item 4 is
a reversal of the operator's own recorded decision.

### 5.7 Test plan

**Unit (`testDevDebugUnitTest`).** The existing `ConsultationDocumentCaptureTest` has 14 cases
covering order, delete, move, discard, cap, abort and the ingest-then-discard race. Under A the
fake store's `stagingPathFor`/`discardStaging` go away, so those tests need updating to the new
store API, and their assertions should be preserved exactly, not relaxed. New cases:

- the shutter is disabled while a page is in flight, and re-enabled exactly once ingest settles
- a capture failure from the camera callback surfaces an error and adds no page
- the three draft fields still gate `onFinishDocumentCapture`

**Instrumented (`androidTest`).** `DocumentCaptureAssemblyTest` (13 cases) and
`CameraAssembledDocumentStorageTest` are the R4 and memory-bound guards and must keep passing
**unmodified**, since A does not touch assembly. That is the main regression signal. New:

- ingest from an in-memory byte array leaves no file anywhere under `cacheDir`, the direct
  replacement for `ingestingAPageEncryptsItAndLeavesNoPlaintextBehind`
- the `ImageProxy` is closed on both the success and the failure path (a fake or a leak counter)
- `theStartupSweepClearsCaptureSessionsButNeverStoredDocuments` keeps passing, which is the
  automated form of the (xiv) re-test

**Scripted process-death check.** Promote what section 1 did into a repeatable script, because it
is the only check that actually exercises the reported symptom:

1. drive to the capture surface
2. plant or capture one page
3. background the app and `am kill` the process (recalling that `am kill` on a foreground process
   is refused, so the background step is mandatory)
4. relaunch and assert the landing screen and the state of
   `files/documents/.capture` and `cacheDir/document_capture_staging`
5. reset `always_finish_activities` to 0 unconditionally, in a trap, so an aborted run cannot
   leave the handset in developer-hostile state

Under A, step 3 should be re-run with the viewfinder open, to confirm the app is NOT backgrounded
and therefore NOT a candidate for the killer in the first place. That is the assertion that proves
the fix rather than the workaround.

**Not run in Phase A:** nothing was executed against `testDevDebugUnitTest` or `androidTest`. The
brief places those in Phase B.

## 6. Open decisions for the operator

1. **Option A or Option B.** Memo recommends A.
2. **The 2026-09-04 deferral reversal.** A contradicts an operator decision recorded in the H-18
   row. That reversal is the operator's to make, not the developer's.
3. **H4 scope.** H4-a (saveable back stack, app-wide, correct) or H4-b (widen Home's existing
   crash recovery, cheap, confined). And whether H4 belongs in this branch at all or in its own.
4. **Whether H4 is in the capture fix's scope.** Memo's view: it should be its own change. If it
   is excluded, Option A alone will not fully clear the field report, because a worker who takes a
   call mid-capture still lands on Home.
5. **SOUP breadth.** Whether CameraX's transitive AndroidX dependencies are separately-registered
   SOUP components or covered by the SBOM alone.
6. **Model for Phase B.** Bounded build against an approved design.
