package com.example.samdapp.presentation.consultation

import com.example.samdapp.domain.document.DocumentBytes
import com.example.samdapp.domain.model.DepartmentCode
import com.example.samdapp.domain.model.RecordTypeCode
import com.example.samdapp.domain.usecase.AddAttachmentUseCase
import com.example.samdapp.domain.usecase.CaptureAudioAttachmentUseCase
import com.example.samdapp.domain.usecase.SaveConsultationUseCase
import com.example.samdapp.domain.usecase.UploadConsultationDocumentUseCase
import com.example.samdapp.testutil.FakeAuditLogger
import com.example.samdapp.testutil.FakeAuthSession
import com.example.samdapp.testutil.FakeConsultationDocumentRepository
import com.example.samdapp.testutil.FakeConsultationRepository
import com.example.samdapp.testutil.FakeDocumentCaptureStore
import com.example.samdapp.testutil.FakeTranscriptionService
import com.example.samdapp.testutil.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * H-18, Build 3b. The capture loop's ORDER, MEMBERSHIP and ABANDON behaviour (R5, R7), which live
 * in the ViewModel and are therefore provable on the JVM. The bytes-level guarantees
 * (encrypt-as-captured, undecodable-page-aborts, one bitmap at a time) need real Keystore,
 * BitmapFactory and PdfDocument and are covered by `DocumentCaptureAssemblyTest` in `androidTest`.
 */
class ConsultationDocumentCaptureTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val captureStore = FakeDocumentCaptureStore()
    private val auditLogger = FakeAuditLogger()

    private fun newViewModel() = ConsultationViewModel(
        patientId = "p1",
        encounterId = "enc1",
        caseRecordId = "case1",
        initialChiefComplaint = "",
        saveConsultationUseCase = SaveConsultationUseCase(FakeConsultationRepository()),
        addAttachmentUseCase = AddAttachmentUseCase(FakeConsultationRepository()),
        captureAudioAttachmentUseCase = CaptureAudioAttachmentUseCase(FakeTranscriptionService()),
        uploadConsultationDocumentUseCase =
            UploadConsultationDocumentUseCase(FakeConsultationDocumentRepository(), auditLogger),
        documentCaptureStore = captureStore,
        authSession = FakeAuthSession(),
        auditLogger = auditLogger,
    )

    /** The capture surface is gated on the same controlled-vocabulary selections the file picker
     *  is, so every test has to make them first - which is itself the point of this helper.
     *  [rotationDegrees] is fixed per call (a real capture session can vary it per page; no test
     *  here needs that, so a single shared value keeps call sites short). */
    private fun TestScope.startCaptureWith(viewModel: ConsultationViewModel, pages: Int, rotationDegrees: Int = 0) {
        viewModel.onDocumentDepartmentSelected(DepartmentCode.CARDIO)
        viewModel.onDocumentRecordTypeSelected(RecordTypeCode.LAB_REPORT)
        viewModel.onStartDocumentCapture()
        repeat(pages) {
            viewModel.onDocumentPageCaptured(fakeJpegBytes(), rotationDegrees)
            advanceUntilIdle()
        }
    }

    /** A fresh, non-empty, non-shared array each call - the ViewModel zeroes its own copy after
     *  ingest (A3), so a test that needs to assert on the bytes after the fact must keep its own
     *  reference rather than reuse one already handed to the ViewModel. */
    private fun fakeJpegBytes(): ByteArray = ByteArray(8) { (it + 1).toByte() }

    @Test
    fun `capture cannot start without a selected department and record type`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = newViewModel()

            viewModel.onStartDocumentCapture()

            assertNull(viewModel.uiState.value.documentCapture)
        }

    @Test
    fun `each captured page joins the strip in capture order`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel()

        startCaptureWith(viewModel, pages = 3)

        val capture = viewModel.uiState.value.documentCapture!!
        assertEquals(3, capture.pages.size)
        assertEquals(captureStore.sessions.getValue(capture.sessionId), capture.pages.map { it.pageId })
    }

    /** R7. The assembler is handed the list in its FINAL order, after every move - not the order
     *  the pages were photographed in. Page order is clinical meaning in a multi-page report. */
    @Test
    fun `pages are assembled in the worker's final reordered order, not capture order`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = newViewModel()
            startCaptureWith(viewModel, pages = 3)
            val captureOrder = viewModel.uiState.value.documentCapture!!.pages.map { it.pageId }

            // The worker drags the last page to the front, then swaps the middle pair.
            viewModel.onMoveDocumentPage(2, 0)
            viewModel.onMoveDocumentPage(2, 1)
            viewModel.onFinishDocumentCapture()
            advanceUntilIdle()

            val expected = listOf(captureOrder[2], captureOrder[1], captureOrder[0])
            assertEquals(expected, captureStore.assembledOrders.single())
        }

    @Test
    fun `a deleted page leaves the document entirely, not just the strip`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = newViewModel()
            startCaptureWith(viewModel, pages = 3)
            val pages = viewModel.uiState.value.documentCapture!!.pages.map { it.pageId }

            viewModel.onDeleteDocumentPage(pages[1])
            advanceUntilIdle()
            viewModel.onFinishDocumentCapture()
            advanceUntilIdle()

            assertEquals(listOf(pages[0], pages[2]), captureStore.assembledOrders.single())
        }

    @Test
    fun `an out-of-range move is ignored rather than corrupting the order`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = newViewModel()
            startCaptureWith(viewModel, pages = 2)
            val before = viewModel.uiState.value.documentCapture!!.pages.map { it.pageId }

            viewModel.onMoveDocumentPage(0, 5)
            viewModel.onMoveDocumentPage(-1, 0)

            assertEquals(before, viewModel.uiState.value.documentCapture!!.pages.map { it.pageId })
        }

    /** R5, first half: a worker who took four pages and backs out is ASKED, not silently emptied.
     *  Nothing is discarded while the question is on screen. */
    @Test
    fun `backing out of a capture with pages asks first and discards nothing yet`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = newViewModel()
            startCaptureWith(viewModel, pages = 4)

            viewModel.onRequestDiscardDocumentCapture()
            advanceUntilIdle()

            val capture = viewModel.uiState.value.documentCapture!!
            assertTrue(capture.confirmDiscard)
            assertEquals(4, capture.pages.size)
            assertTrue(captureStore.discardedSessions.isEmpty())
        }

    /** R5, second half: confirming discards ALL pages and the session directory with them. No
     *  draft is kept - encrypted PHI with no metadata row, no audit and no owner is a worse
     *  posture than losing the photos. */
    @Test
    fun `confirming the discard deletes the whole session and queues nothing`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = newViewModel()
            startCaptureWith(viewModel, pages = 4)
            val sessionId = viewModel.uiState.value.documentCapture!!.sessionId

            viewModel.onRequestDiscardDocumentCapture()
            viewModel.onConfirmDiscardDocumentCapture()
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.documentCapture)
            assertEquals(listOf(sessionId), captureStore.discardedSessions)
            assertFalse(captureStore.sessions.containsKey(sessionId))
            assertTrue(viewModel.uiState.value.pendingDocuments.isEmpty())
        }

    @Test
    fun `keeping the capture after the discard prompt leaves every page in place`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = newViewModel()
            startCaptureWith(viewModel, pages = 2)

            viewModel.onRequestDiscardDocumentCapture()
            viewModel.onDismissDiscardDocumentCapture()
            advanceUntilIdle()

            val capture = viewModel.uiState.value.documentCapture!!
            assertFalse(capture.confirmDiscard)
            assertEquals(2, capture.pages.size)
            assertTrue(captureStore.discardedSessions.isEmpty())
        }

    /** An empty capture has nothing to lose, so the confirmation would be noise. */
    @Test
    fun `backing out of an empty capture discards immediately without asking`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = newViewModel()
            startCaptureWith(viewModel, pages = 0)

            viewModel.onRequestDiscardDocumentCapture()
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.documentCapture)
            assertEquals(1, captureStore.discardedSessions.size)
        }

    /** Option A replacement for the old "camera backed out, discard the staging file" case: there
     *  is no staging file to strand any more (H-18, Build 3b, Option A) - a CameraX capture
     *  failure never produces bytes in the first place, so the only things left to assert are
     *  that no page was added and the surface stays usable with an explicit error. */
    @Test
    fun `a failed camera capture adds no page and surfaces an error`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = newViewModel()
            startCaptureWith(viewModel, pages = 1)

            viewModel.onDocumentPageCaptureFailed("Capture failed")
            advanceUntilIdle()

            val capture = viewModel.uiState.value.documentCapture!!
            assertEquals(1, capture.pages.size)
            assertFalse(capture.isIngestingPage)
            assertEquals("Capture failed", capture.errorMessage)
        }

    /** A5: the viewfinder itself could not start. No fallback capture path exists - the surface
     *  is expected to show an explicit error instead (asserted at the Compose layer in
     *  DocumentCaptureSurface; this is the ViewModel-state half of that contract). */
    @Test
    fun `camera unavailable is recorded and blocks further pages`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = newViewModel()
            viewModel.onDocumentDepartmentSelected(DepartmentCode.CARDIO)
            viewModel.onDocumentRecordTypeSelected(RecordTypeCode.LAB_REPORT)
            viewModel.onStartDocumentCapture()

            viewModel.onCameraUnavailable("No back camera is available on this device.")
            advanceUntilIdle()

            val capture = viewModel.uiState.value.documentCapture!!
            assertTrue(capture.cameraUnavailable)
            assertFalse(capture.canAddPage)
            assertEquals("No back camera is available on this device.", capture.errorMessage)
        }

    /** A3: the ViewModel owns zeroing the byte array once ingest resolves, regardless of
     *  success or failure - the caller's copy must not remain live plaintext on the heap after
     *  its one use. */
    @Test
    fun `the captured byte array is zeroed after a successful ingest`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = newViewModel()
            viewModel.onDocumentDepartmentSelected(DepartmentCode.CARDIO)
            viewModel.onDocumentRecordTypeSelected(RecordTypeCode.LAB_REPORT)
            viewModel.onStartDocumentCapture()
            val bytes = fakeJpegBytes()

            viewModel.onDocumentPageCaptured(bytes, rotationDegrees = 0)
            advanceUntilIdle()

            assertTrue(bytes.all { it == 0.toByte() })
        }

    @Test
    fun `the captured byte array is zeroed even when ingest fails`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = newViewModel()
            captureStore.ingestResult = { _, _ -> Result.failure(IllegalStateException("The captured image could not be read")) }
            viewModel.onDocumentDepartmentSelected(DepartmentCode.CARDIO)
            viewModel.onDocumentRecordTypeSelected(RecordTypeCode.LAB_REPORT)
            viewModel.onStartDocumentCapture()
            val bytes = fakeJpegBytes()

            viewModel.onDocumentPageCaptured(bytes, rotationDegrees = 0)
            advanceUntilIdle()

            assertTrue(bytes.all { it == 0.toByte() })
        }

    /** A1's plumbing half: each page's CameraX rotation reaches `ingestPage`, and the SAME value
     *  (read back from the final page list, per page) reaches `assemble` via `OrderedPage` - the
     *  pixel-level rotation math itself is real-`Matrix`/`Canvas` work covered in
     *  `DocumentCaptureAssemblyTest` (androidTest), not here. */
    @Test
    fun `each page's capture rotation is threaded through to assembly`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = newViewModel()
            viewModel.onDocumentDepartmentSelected(DepartmentCode.CARDIO)
            viewModel.onDocumentRecordTypeSelected(RecordTypeCode.LAB_REPORT)
            viewModel.onStartDocumentCapture()
            listOf(0, 90, 180, 270).forEach { rotation ->
                viewModel.onDocumentPageCaptured(fakeJpegBytes(), rotation)
                advanceUntilIdle()
            }
            val pageIds = viewModel.uiState.value.documentCapture!!.pages.map { it.pageId }

            viewModel.onFinishDocumentCapture()
            advanceUntilIdle()

            assertEquals(listOf(0, 90, 180, 270), pageIds.map { captureStore.ingestedRotations.getValue(it) })
            assertEquals(listOf(0, 90, 180, 270), captureStore.assembledRotations.single())
        }

    /** A finished capture queues exactly one document, on the same pending-document list the
     *  file picker feeds, carrying the page count the audit row will report (R9). */
    @Test
    fun `finishing queues one assembled document carrying its page count`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = newViewModel()
            startCaptureWith(viewModel, pages = 3)

            viewModel.onFinishDocumentCapture()
            advanceUntilIdle()

            val queued = viewModel.uiState.value.pendingDocuments.single()
            val bytes = queued.bytes as DocumentBytes.AssembledCapture
            assertEquals(3, bytes.pageCount)
            assertEquals(DepartmentCode.CARDIO, queued.departmentCode)
            assertEquals(RecordTypeCode.LAB_REPORT, queued.recordTypeCode)
            assertNull(viewModel.uiState.value.documentCapture)
        }

    /** R4 as the worker experiences it: an aborted assembly queues NOTHING and keeps every page,
     *  so the fix is retake-and-retry rather than a silently shorter document. */
    @Test
    fun `an aborted assembly queues no document and keeps the pages for a retry`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = newViewModel()
            captureStore.assembleResult =
                { _, _ -> Result.failure(IllegalStateException("Captured page 2 could not be read")) }
            startCaptureWith(viewModel, pages = 3)

            viewModel.onFinishDocumentCapture()
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.pendingDocuments.isEmpty())
            val capture = viewModel.uiState.value.documentCapture!!
            assertEquals(3, capture.pages.size)
            assertFalse(capture.isAssembling)
            assertEquals("Captured page 2 could not be read", capture.errorMessage)
        }

    @Test
    fun `the page cap closes the add-another-page affordance`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel()
        startCaptureWith(viewModel, pages = captureStore.maxPages)

        val capture = viewModel.uiState.value.documentCapture!!
        assertEquals(captureStore.maxPages, capture.pages.size)
        assertFalse(capture.canAddPage)
        assertTrue(capture.canFinish)
    }

    /**
     * R5 under a race: the camera result and the discard button are two coroutines, and the store
     * recreates the session directory on write. A page that lands after `discardSession` would be
     * encrypted PHI with no session, no metadata row and no owner - the exact posture R5 exists to
     * prevent - so the discard has to wait for the in-flight ingestion, not merely start after it.
     */
    @Test
    fun `discarding waits for an in-flight page ingestion before deleting the session`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = newViewModel()
            viewModel.onDocumentDepartmentSelected(DepartmentCode.CARDIO)
            viewModel.onDocumentRecordTypeSelected(RecordTypeCode.LAB_REPORT)
            viewModel.onStartDocumentCapture()
            val sessionId = viewModel.uiState.value.documentCapture!!.sessionId

            val stillEncrypting = CompletableDeferred<Unit>()
            captureStore.ingestGate = stillEncrypting
            viewModel.onDocumentPageCaptured(fakeJpegBytes(), rotationDegrees = 0)
            advanceUntilIdle()

            viewModel.onConfirmDiscardDocumentCapture()
            advanceUntilIdle()
            stillEncrypting.complete(Unit)
            advanceUntilIdle()

            assertTrue(captureStore.discardedSessions.contains(sessionId))
            assertFalse(captureStore.sessions.containsKey(sessionId))
            assertNull(viewModel.uiState.value.documentCapture)
        }
}
