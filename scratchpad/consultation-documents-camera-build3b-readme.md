# Build 3b developer README - consultation documents: camera multi-capture to on-device PDF assembly

**Branch:** `feat/consultation-documents-camera`, off merged Build 3a (`616eedd`).
**Status:** built, tested, NOT committed - awaiting operator authorization.

Read `scratchpad/consultation-documents-and-prescription-gate-memo.md` Feature 1 Part B2 and
`scratchpad/consultation-documents-storage-build3a-readme.md` first. This is orientation on top of
both, not a replacement.

## Scope

**In scope (built):** PATH B - multi-page camera capture, per-page encryption at capture, on-device
consolidation into one PDF, reorder and per-page delete, abandon-discards-everything, a startup
orphan sweep, and integration into Build 3a's existing storage/audit/retract path. Plus the
`pageCount` column Build 3a shipped without.

**Out of scope, explicitly:**
- **The cadre role gate** - still Build 3c. A camera-assembled document is subject to exactly the
  same interim gate (uploader or `DOCTOR`) as a directly-uploaded one, because it goes through the
  same viewer.
- **Backend sync of the document row or its bytes** - unchanged from 3a, still a pre-production
  gate (memo B8). No backend change was needed for this build at all.
- **CameraX** - see "the one residual" below.

## The schema gap this build closes

The memo's column table (B3) lists `pageCount Int?` on `consultation_documents`. Build 3a's
`MIGRATION_17_18` and its exported `18.json` do not have it - it was simply dropped somewhere
between memo and implementation. `DocumentSource.CAMERA_ASSEMBLED` *was* pre-declared in 3a, so
the enum needed nothing; the column did.

`MIGRATION_18_19`, DB version 18 → 19: `ALTER TABLE consultation_documents ADD COLUMN pageCount
INTEGER`. Nullable, no default, no backfill - every pre-3b row is `DIRECT_FILE`, for which a page
count was never measured, and inventing a number there would be fabricating clinical metadata.
`app/schemas/.../19.json` committed alongside. Verified on-device against a real SQLCipher database
(`MigrationTest18To19`), including that a row written before the column existed survives the alter
with `pageCount` NULL.

`pageCount` is stored, not derived at read time from `PdfRenderer.pageCount`, because the audit row
needs it at the moment of upload - it is a write-time integrity fact, and the witness for the
"a page silently went missing from an assembled report" hazard.

## One upload path, two byte provenances

`ConsultationDocumentRepository.upload` used to take `sourceUri` + `claimedMimeType`. It now takes:

```kotlin
sealed interface DocumentBytes {
    data class DirectFile(val sourceUri: String, val claimedMimeType: String?) : DocumentBytes
    data class AssembledCapture(
        val captureSessionId: String, val pageCount: Int, val sizeBytes: Long, val sha256: String,
    ) : DocumentBytes
}
```

That `when` in `ConsultationDocumentRepositoryImpl.upload` is the ONLY place the two paths differ.
Below it, `storageKeyFor`, `buildDocument` and `insertOrRollBack` are shared, so the storage key
scheme, the canonical name, the `patientId` derivation, the ABHA exclusion and the roll-back-on-
insert-failure behaviour cannot drift between paths. `UploadConsultationDocumentUseCase` is
unchanged except for the parameter type and one added payload key. There is no second use case and
no second audit action: a reviewer reading the trail should not have to know which button produced
the document.

PATH B's `storeAssembledCapture` does no re-encryption and no magic-byte sniffing. The bytes are
already a PDF encrypted under the same Keystore key by this app's own code, so it renames the
assembled file into `filesDir/documents/<consultationId>/<storageKey>` (falling back to
copy-then-delete) and inserts the row. Only after the row is durable is the capture session torn
down.

## Where captured pages live

```
filesDir/documents/<consultationId>/          <- Build 3a: stored documents (unchanged)
filesDir/documents/.capture/<sessionId>/      <- Build 3b: in-progress pages, each encrypted
filesDir/documents/.capture/<sessionId>/assembled.enc
cacheDir/document_capture_staging/            <- the transient plaintext frame (see residual)
```

A `consultationId` is a UUID and can never be the literal `.capture`, so the capture tree and the
document tree cannot collide and the sweep can never reach a stored document (asserted by
`DocumentCaptureAssemblyTest.theStartupSweepClearsCaptureSessionsButNeverStoredDocuments`).

## Encrypt-when, and why not the other way round

Pages are encrypted individually AS CAPTURED and decrypted one at a time DURING assembly; the
assembled PDF is encrypted BEFORE anything is deleted. The alternative - hold N plaintext JPEGs
until the worker taps done, then assemble and encrypt once - would leave every page of a clinical
document readable on disk for the whole capture loop and across any process death inside it. That
is the exact posture this feature exists to avoid.

Making that work needed one addition to `DocumentEncryptionProvider`: a push-mode overload

```kotlin
fun encryptToFile(destFile: File, maxBytes: Long, writePlaintext: (OutputStream) -> Unit): DocumentEncryptResult
```

because `PdfDocument.writeTo` takes an `OutputStream` and offers no `InputStream`. The existing
`InputStream` overload now delegates to it, so both share one key, one per-file random IV, one
single-pass plaintext size + SHA-256 measurement, and one delete-on-failure. Its catch is
`Throwable`, not `Exception`, so a cancelled assembly cannot leave a half-written ciphertext.

## Memory discipline

- `computeInSampleSize` was lifted out of `DocumentViewerViewModel` (where 3a had it privately)
  into `domain/document/ImageDownscale.kt`, so the viewer and the assembler downscale by the same
  rule and one unit test covers both.
- Every page is downscaled AT DECODE (`inJustDecodeBounds`, then `inSampleSize`) to a 1600 px long
  edge. A full-resolution 12 MP frame is never allocated.
- Exactly one page is alive at a time: decrypt → decode → `startPage` → draw → `finishPage` →
  `recycle` → next page. `finishPage` has serialised the page into the native document before
  `recycle()` runs.
- Output streams: `document.writeTo(cipherSink)`. The PDF never exists as a whole `ByteArray`.
- Page count capped at 20.

`PdfDocument` does accumulate finished pages' compressed content natively until `writeTo` - the
platform API has no incremental write. That is what the 20-page cap bounds, and it is compressed
page content, not N decoded bitmaps.

**The one page-size knob.** `PAGE_MAX_DIMENSION = 1600` on an A4 page is roughly 190 dpi. If a
20-page assembly ever trips the 20 MB document cap in the field, lower that constant first - it is
the only thing that moves output size, and the failure it produces is loud (an explicit error and
no document) rather than silent.

## R4: an unreadable page aborts the whole assembly

This is the requirement a loop structure invites implementing as a skip. The control flow that
makes a skip impossible:

```kotlin
orderedPageIds.forEachIndexed { index, pageId ->
    callerContext.ensureActive()
    drawPage(document, sessionId, pageId, index)   // finishes the page, or throws
    onProgress(index + 1, orderedPageIds.size)
}
document.writeTo(sink)
```

The loop body has no `catch`, no `continue`, no `?.let`, no `getOrNull`. `drawPage` has exactly two
outcomes: it finishes a page, or it throws `DocumentPageUnreadableException(index, pageId)` - on a
GCM authentication failure, a missing file, zero decoded bounds, or a null decode. The throw
propagates out of `writePlaintext`, out of `encryptToFile` (which deletes its own partial
destination on the way past), and into the single `catch` in `assemble`, which deletes the output
again and returns `Result.failure`. No document is produced, so `upload` is never called and no
metadata row is written.

Three tests hold that line, one per way a page can be unreadable:
`aCorruptedPageAbortsTheAssemblyAndProducesNoDocument`,
`aMissingPageFileAbortsTheAssemblyRatherThanShorteningTheDocument`,
`anUndecodablePageAbortsEvenThoughItsCiphertextIsIntact` - plus
`CameraAssembledDocumentStorageTest.anAbortedAssemblyLeavesNoDocumentRowAndNoStoredBytes`, which
asserts the ABSENCE of the row in a real database, per CLAUDE.md's rule.

## Abandon and the orphan sweep

- Back or cancel with pages captured raises "Discard N pages?" - a worker who took four pages does
  not lose them silently. An empty capture skips the question; there is nothing to lose.
- Confirming deletes the session directory, every encrypted page in it, and any staging leftovers
  from a capture the camera never completed.
- Process death does not run that path, so `SaMDApplication.onCreate` calls
  `sweepOrphanedCaptureSessions(this)` alongside Build 3a's `sweepOrphanedViewerTempFiles(this)`.
  **Policy: delete everything under `filesDir/documents/.capture/` and
  `cacheDir/document_capture_staging/` unconditionally at app start.** A capture session's page
  list lives only in `ConsultationViewModel` state, so no session can survive process death; any
  directory present at process start is orphaned by definition, and an age heuristic or liveness
  registry would be complexity with nothing to protect.
- **Reconciliation with 3a's sweep:** two separate calls, not one merged sweep. They cover disjoint
  directories (`cacheDir/document_viewer_temp` vs. the two above) and answer different questions;
  merging them would only hide which one failed. No operator decision was needed.

The one gap this leaves: a worker who navigates away from the whole consultation screen (rather
than out of the capture surface) while pages exist strands the session until the next app start,
when the sweep collects it. That is exactly the case the sweep is for, and no encrypted page is
ever reachable without the ViewModel state that named it.

## R7: reorder and per-page delete

A `LazyRow` thumbnail strip in capture order, each thumbnail carrying a per-page Delete and two
move buttons, with long-press-drag over the strip as the fast path. Both affordances call the same
`ConsultationActions.onMoveDocumentPage(from, to)`, so there is one ordering operation to test
rather than two - and the buttons are the half that works with TalkBack, with gloves, and where a
long press competes with the row's own scroll. The drag resolves the moved page from the live list
by id, never from the index the gesture lambda closed over, so a reorder mid-drag cannot move the
wrong page.

Thumbnails are small in-memory JPEGs produced from the staging file before it is deleted, so
rendering the strip never costs a second decrypt and never puts a plaintext page back on disk.

`assemble` is handed `capture.pages.map { it.pageId }` read once, in its final order, at the moment
the worker taps done. There is no second ordering step downstream that could disagree with what
the strip showed.

## R8: off-main-thread, cancellable, progress

`assemble` runs in `withContext(Dispatchers.Default)`; `ingestPage`/`deletePage`/`discardSession`
run on `Dispatchers.IO`. Progress is `(pagesDone, pageCount)` per page, shown as a determinate
`LinearProgressIndicator`. Cancel cancels the stored `assemblyJob`; the store's
`catch (CancellationException)` deletes the partial output and rethrows, so the coroutine still
ends as cancelled rather than being swallowed into a `Result`.

Cancellation is checked with a `CoroutineContext` captured in the suspend frame and
`ensureActive()`d inside the loop, because the encryption provider's push-mode sink takes an
ordinary `(OutputStream) -> Unit`, not a suspend function type.

## The one residual: a transient plaintext staging file

`ActivityResultContracts.TakePicture` - the same primitive `ConsultationScreen`'s affected-area
photo already uses - hands a granted URI to a **separate camera process**. That process cannot
write into our encrypted store, so one plaintext frame necessarily lands on disk before this app
can touch it. This is structural to the API, not a shortcut.

What is done about it:
- Its own directory, `cacheDir/document_capture_staging/`, with its own `file_paths.xml` entry, so
  the `FileProvider` grant covers exactly the staging file and nothing else.
- Encrypted into the session and deleted in a `finally` inside the same result callback, so the
  plaintext is gone before `ingestPage` returns.
- The UI does not re-enable "add another page" until that result is observed
  (`canAddPage` is false while `pendingPageId` is set), so page N+1 can never be captured while
  page N's plaintext is still on disk. At most one page of plaintext exists at any instant.
- Anything a process death strands is swept at the next app start.

What is NOT claimed: zero plaintext. CameraX's `ImageCapture.takePicture(OnImageCapturedCallback)`
would deliver the frame in-process and close the window entirely, at the cost of four new
`androidx.camera` dependencies and a self-built viewfinder. Operator decision on 2026-09-04: keep
`TakePicture`, record the residual on the H-18 entry. This is a millisecond-scale, single-page,
non-persistent window, not the H-04 threat model.

The existing affected-area photo path's plaintext-`cacheDir`-forever posture (H-19) was
deliberately not copied.

## Tests

**Unit (JVM), 21 new:**
- `ImageDownscaleTest` (5) - the sampling arithmetic, including the 12 MP case.
- `ConsultationDocumentCaptureTest` (13) - capture order, reorder including a two-step reorder,
  per-page delete removing the page from the assembly and not just the strip, out-of-range moves
  ignored, the discard confirmation and that nothing is discarded while it is on screen, empty
  capture discarding without asking, a backed-out camera stranding nothing, the queued document
  carrying its page count, an aborted assembly queuing nothing and keeping the pages, the page cap.
- `UploadConsultationDocumentUseCaseTest` (+2) - the camera-assembled audit payload carries
  `source` and `pageCount` and still never the label; a direct-file upload reports a null page
  count rather than a fabricated one.

**Instrumented (`emulator-5554`), 19 new:**
- `MigrationTest18To19` (2).
- `DocumentCaptureAssemblyTest` (13) - the real-bytes guarantees: no plaintext after ingest and
  none after a multi-page capture, abandon deleting everything including a never-ingested staging
  file, the sweep sparing stored documents, the three abort cases, final-order page identity read
  back by rendering each PDF page, per-page progress, a full 20-page assembly of 8 MP sources
  completing without OOM, the cap and empty-list refusals, and that the reported size and SHA-256
  are of the plaintext PDF.
- `CameraAssembledDocumentStorageTest` (4) - the full stack against a real Room database: row
  shape, key schemes, ABHA absence from both names, bytes read back and rendered as a 3-page PDF
  through 3a's reader, the capture session gone; absence of any row after an abort; a vanished
  session refused rather than stored as an empty row; retract keeping the row and the page count
  while deleting the bytes.

`testDevDebugUnitTest`: 355 passed, 0 failed. `connectedDevDebugAndroidTest`
(`notPackage=com.example.samdapp.data.transcription`): 90 passed, 0 failed. The ASR mic-hardware
tests are a known pre-existing emulator flake and are excluded, not fixed, by this build.

**Note on the emulator.** `emulator-5554` refused the 757 MB dev APK with
`INSTALL_FAILED_INSUFFICIENT_STORAGE` (2.4 GB free, but ~6 GB of `/data/media` belongs to
`com.google.aiedge.gallery`'s downloaded models). Worked around by temporarily lowering
`sys_storage_threshold_percentage`/`sys_storage_threshold_max_bytes`, which were **deleted again
after the run**. Nothing on the device was uninstalled or wiped. If this recurs, that is the knob.
