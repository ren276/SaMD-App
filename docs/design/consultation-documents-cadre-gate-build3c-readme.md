# Build 3c developer README - consultation documents: cadre role-visibility gate

**Branch:** `feat/consultation-documents-cadre-gate`, off `origin/master` (contains merged Build 3a
`616eedd` and Build 3b `62bcc0c`).
**Status:** built, unit-tested, NOT committed - awaiting operator authorization.

Read `scratchpad/consultation-documents-and-prescription-gate-memo.md` Feature 1 Part B7 and
`docs/domain/phc-workforce-scope.md` (the three-tier cadre model) first. This is orientation on top
of both, not a replacement.

## Scope

**In scope (built):** the cadre tier model (`CadreTier`), the `UserRole → CadreTier` mapping, the
single `DocumentAccessAuthorizer` seam, wiring it into `DocumentViewerViewModel` in place of Build
3a's interim gate, the uploader exception, the extended `document_viewed` audit payload
(`accessResult`), and the risk-file/domain-doc updates.

**Out of scope, explicitly (per the build brief, not a design choice made here):**
- **CHO.** Not a `UserRole` value. The insertion point (`UserRole.toCadreTier()`, one commented
  line) exists; adding the role is a separate, future change.
- **Per-`RecordTypeCode` gating.** Tier-uniform for now — every interpretive document type is
  gated the same way. `METADATA` (`LICENSED_CLINICAL`) and `ABSTRACTED` (`COMMUNITY`) are kept as
  distinct `DocumentAccessOutcome`-adjacent concepts in the design so a future refinement can
  diverge them, but they render the same "no raw content" branch today.
- **Any change to backend sync, storage, or the upload/retract paths.** Untouched — this build is
  authorization logic over rows that already exist.

## Where the decision lives

One function: `DocumentAccessAuthorizer.authorize(document, session)`
(`domain/document/DocumentAccessAuthorizer.kt`). It returns one of three outcomes:

- `GRANTED_TIER` — the viewer's `CadreTier` is `PHYSICIAN`.
- `GRANTED_UPLOADER` — `session.userId == document.uploaderUserId`, checked FIRST, regardless of
  tier. A `NURSE` or `ASHA_WORKER` who uploaded a document can always open it.
- `DENIED_TIER` — everyone else (`LICENSED_CLINICAL`, `COMMUNITY`, non-uploader).

`DocumentViewerViewModel.init` calls this once, sets `canViewContent` from `outcome.granted`, and —
this is the load-bearing property — returns from the coroutine before ever calling `loadContent`
when denied. `loadContent` is where decryption and rendering happen. There is no code path in which
a denied viewer's bytes are decrypted and then hidden by the UI layer; the UI layer never gets the
chance to receive them.

The documents LIST (`PatientSummaryViewModel.documents`) is untouched: it was already
metadata-only for every role (the flow never resolved raw content, only DB rows), so nothing needed
to change there. The gate has always been a viewer-only decision, on tapping a row.

## The two-schedulers trap this build's tests hit

`DocumentViewerViewModel.loadContent` does `withContext(Dispatchers.IO) { ... }` for the actual
decrypt+render. In a unit test using `MainDispatcherRule` (a virtual `TestDispatcher` on `Main`
only), that `withContext` hops onto the REAL `Dispatchers.IO` thread pool — a scheduler
`advanceUntilIdle()` cannot see or drain. The denied-path tests never hit this (they return before
`loadContent` is called at all, entirely on the virtual Main queue), which is exactly why they were
reliably green on the first run while the granted-path "decrypt was attempted" assertions raced and
failed about a third of the time. Fixed with a small bounded real-time poll
(`awaitDecryptAttempt` in the test file) rather than touching production code — this ViewModel has
no injected IO dispatcher to substitute, and adding one was out of scope for a gate build.

This is also why there is no test asserting the `document_viewed` audit fires with
`accessResult: "granted"` on an actually-successful render: `PdfRenderer`/`BitmapFactory` are
Android's "not mocked" stubs in this module's plain-JVM unit tests (no Robolectric configured), so
a granted view's render always throws before reaching the post-render audit call — caught by the
existing generic `catch (e: Exception)`, same as it would in production for a genuinely corrupt
file. That gap pre-dates this build (Build 3a shipped with zero tests on this ViewModel) and isn't
newly introduced by it; closing it needs a real device/emulator render, which this commit doesn't
add.

## Audit payload

`document_viewed`'s payload gained one field:

```
{"documentId": "...", "viewerRole": "NURSE", "accessResult": "denied_tier"}
```

`accessResult` is one of `granted` / `granted_uploader` / `denied_tier` — `DocumentAccessOutcome.auditValue`,
never re-derived elsewhere. The action string itself (`document_viewed`) is unchanged, so
`audit_actions_device.py`'s set-agreement test needed no change.
