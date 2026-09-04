package com.example.samdapp.presentation.documents

import android.content.Context
import android.content.ContextWrapper
import com.example.samdapp.domain.auth.UserRole
import com.example.samdapp.domain.auth.UserSession
import com.example.samdapp.domain.model.ConsultationDocument
import com.example.samdapp.domain.model.DepartmentCode
import com.example.samdapp.domain.model.DocumentSource
import com.example.samdapp.domain.model.RecordTypeCode
import com.example.samdapp.testutil.FakeAuditLogger
import com.example.samdapp.testutil.FakeAuthSession
import com.example.samdapp.testutil.FakeConsultationDocumentRepository
import com.example.samdapp.testutil.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant

/**
 * H-18, Build 3c: the cadre gate as wired into the real viewer ViewModel. [FakeConsultationDocumentRepository.readDecryptedCallCount]
 * is the load-bearing assertion — it proves the decrypt path is genuinely unreached for a denied
 * viewer, not just that a UI flag says so, mirroring how the 3a capture tests assert absence.
 *
 * A real `android.content.Context` isn't available in this plain-JVM test (no Robolectric in this
 * module), so [fakeContext] is a [ContextWrapper] with only [Context.getCacheDir] overridden — the
 * one Context member [DocumentViewerViewModel] touches before a render call. `PdfRenderer`/
 * `BitmapFactory` themselves are Android stubs here and throw once actually invoked, which is fine:
 * every assertion below is about whether decrypt was REACHED, not about a successful render (that
 * needs a real device/emulator and is out of scope for this file).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DocumentViewerViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun fakeContext(): Context = object : ContextWrapper(null) {
        override fun getCacheDir(): File = tempFolder.root
    }

    private fun document(uploaderUserId: String) = ConsultationDocument(
        id = "doc-1", consultationId = "c1", patientId = "p1", abhaNumber = null, label = "label",
        canonicalName = "canonical", departmentCode = DepartmentCode.ORTHO, recordTypeCode = RecordTypeCode.IMAGING,
        storageKey = "key", mimeType = "application/pdf", sizeBytes = 500L, sha256 = "hash",
        source = DocumentSource.DIRECT_FILE, pageCount = null, uploadedAt = Instant.EPOCH, uploaderUserId = uploaderUserId,
        uploaderRole = "ASHA_WORKER", retractedAt = null, retractionReason = null,
    )

    /** [DocumentViewerViewModel.loadContent] hops to the real `Dispatchers.IO` thread pool, which
     *  [MainDispatcherRule]'s virtual test scheduler cannot see — `advanceUntilIdle()` drains the
     *  Main queue only, so a granted-path assertion racing the real IO thread needs a real-time
     *  poll instead. The denied path never reaches this hop (it returns before `loadContent`), so
     *  it needs no such wait — that asymmetry is itself part of what these tests prove. */
    private fun awaitDecryptAttempt(repo: FakeConsultationDocumentRepository, timeoutMs: Long = 2000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (repo.readDecryptedCallCount == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
        }
    }

    private fun viewModel(
        repo: FakeConsultationDocumentRepository,
        session: UserSession,
        audit: FakeAuditLogger = FakeAuditLogger(),
    ) = DocumentViewerViewModel(
        documentId = "doc-1",
        context = fakeContext(),
        repository = repo,
        authSession = FakeAuthSession(session),
        auditLogger = audit,
    )

    @Test
    fun `a physician DOCTOR opens a document they did not upload — granted, decrypt is attempted`() = runTest(mainDispatcherRule.dispatcher) {
        val repo = FakeConsultationDocumentRepository().apply { saved["doc-1"] = document(uploaderUserId = "worker-1") }
        val vm = viewModel(repo, UserSession("doc-9", "Dr. Someone", UserRole.DOCTOR))

        advanceUntilIdle()
        awaitDecryptAttempt(repo)

        assertTrue(vm.uiState.value.canViewContent)
        assertEquals(1, repo.readDecryptedCallCount)
    }

    /** Load-bearing: denial must stop the flow BEFORE decrypt, not merely flip a UI flag, so a
     *  denied view cannot leak content through a race or a partial render. */
    @Test
    fun `a NURSE taps a document they did not upload — denied, viewer does NOT decrypt`() = runTest(mainDispatcherRule.dispatcher) {
        val repo = FakeConsultationDocumentRepository().apply { saved["doc-1"] = document(uploaderUserId = "worker-1") }
        val vm = viewModel(repo, UserSession("worker-2", "A Nurse", UserRole.NURSE))

        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.canViewContent)
        assertEquals(0, repo.readDecryptedCallCount)
        assertNull(state.content)
        assertFalse(state.isLoading)
    }

    @Test
    fun `a COMPOUNDER taps a document they did not upload — denied, viewer does NOT decrypt`() = runTest(mainDispatcherRule.dispatcher) {
        val repo = FakeConsultationDocumentRepository().apply { saved["doc-1"] = document(uploaderUserId = "worker-1") }
        val vm = viewModel(repo, UserSession("worker-2", "A Compounder", UserRole.COMPOUNDER))

        advanceUntilIdle()

        assertFalse(vm.uiState.value.canViewContent)
        assertEquals(0, repo.readDecryptedCallCount)
    }

    @Test
    fun `an ASHA_WORKER taps a document they did not upload — denied, viewer does NOT decrypt`() = runTest(mainDispatcherRule.dispatcher) {
        val repo = FakeConsultationDocumentRepository().apply { saved["doc-1"] = document(uploaderUserId = "worker-1") }
        val vm = viewModel(repo, UserSession("worker-2", "An ASHA", UserRole.ASHA_WORKER))

        advanceUntilIdle()

        assertFalse(vm.uiState.value.canViewContent)
        assertEquals(0, repo.readDecryptedCallCount)
    }

    @Test
    fun `uploader exception — a NURSE who uploaded the document opens it, decrypt is attempted despite their tier`() = runTest(mainDispatcherRule.dispatcher) {
        val repo = FakeConsultationDocumentRepository().apply { saved["doc-1"] = document(uploaderUserId = "worker-2") }
        val vm = viewModel(repo, UserSession("worker-2", "A Nurse", UserRole.NURSE))

        advanceUntilIdle()
        awaitDecryptAttempt(repo)

        assertTrue(vm.uiState.value.canViewContent)
        assertEquals(1, repo.readDecryptedCallCount)
    }

    @Test
    fun `uploader exception — an ASHA_WORKER who uploaded the document opens it despite their tier`() = runTest(mainDispatcherRule.dispatcher) {
        val repo = FakeConsultationDocumentRepository().apply { saved["doc-1"] = document(uploaderUserId = "worker-2") }
        val vm = viewModel(repo, UserSession("worker-2", "An ASHA", UserRole.ASHA_WORKER))

        advanceUntilIdle()
        awaitDecryptAttempt(repo)

        assertTrue(vm.uiState.value.canViewContent)
        assertEquals(1, repo.readDecryptedCallCount)
    }

    @Test
    fun `DOCUMENT_VIEWED audit fires on a denied attempt with accessResult denied_tier`() = runTest(mainDispatcherRule.dispatcher) {
        val repo = FakeConsultationDocumentRepository().apply { saved["doc-1"] = document(uploaderUserId = "worker-1") }
        val audit = FakeAuditLogger()
        viewModel(repo, UserSession("worker-2", "A Nurse", UserRole.NURSE), audit)

        advanceUntilIdle()

        val entry = audit.logged.single { it.action == "document_viewed" }
        assertTrue(entry.payload.contains("\"accessResult\":\"denied_tier\""))
        assertTrue(entry.payload.contains("\"viewerRole\":\"NURSE\""))
        assertTrue(entry.payload.contains("\"documentId\":\"doc-1\""))
    }
}
