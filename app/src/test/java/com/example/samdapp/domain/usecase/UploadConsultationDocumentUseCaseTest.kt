package com.example.samdapp.domain.usecase

import com.example.samdapp.domain.model.DepartmentCode
import com.example.samdapp.domain.model.RecordTypeCode
import com.example.samdapp.testutil.FakeAuditLogger
import com.example.samdapp.testutil.FakeConsultationDocumentRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** H-18, Build 3a: the audit row shape, and that the worker's free-text label never reaches it. */
class UploadConsultationDocumentUseCaseTest {

    @Test
    fun `a successful upload emits DOCUMENT_UPLOADED with provenance but never the label`() = runTest {
        val repo = FakeConsultationDocumentRepository()
        val audit = FakeAuditLogger()
        val useCase = UploadConsultationDocumentUseCase(repo, audit)

        val result = useCase(
            consultationId = "c1",
            sourceUri = "content://fake-uri",
            claimedMimeType = "application/pdf",
            label = "Patient Rajesh's blood report — do not leak this",
            departmentCode = DepartmentCode.CARDIO,
            recordTypeCode = RecordTypeCode.LAB_REPORT,
            uploaderUserId = "worker-1",
            uploaderRole = "ASHA_WORKER",
        )

        assertTrue(result.isSuccess)
        val entry = audit.logged.single { it.action == "document_uploaded" }
        assertTrue(entry.payload.contains("\"departmentCode\":\"CARDIO\""))
        assertTrue(entry.payload.contains("\"recordTypeCode\":\"LAB_REPORT\""))
        assertTrue(entry.payload.contains("\"uploaderRole\":\"ASHA_WORKER\""))
        assertFalse(entry.payload.contains("Rajesh"))
        assertFalse(entry.payload.contains("blood report"))
    }

    @Test
    fun `a repository failure emits no audit row`() = runTest {
        val repo = FakeConsultationDocumentRepository().apply {
            uploadResult = { Result.failure(IllegalStateException("rejected")) }
        }
        val audit = FakeAuditLogger()
        val useCase = UploadConsultationDocumentUseCase(repo, audit)

        val result = useCase(
            consultationId = "c1", sourceUri = "content://fake-uri", claimedMimeType = null,
            label = "x", departmentCode = DepartmentCode.GEN_PHYS, recordTypeCode = RecordTypeCode.OTHER,
            uploaderUserId = "worker-1", uploaderRole = "ASHA_WORKER",
        )

        assertTrue(result.isFailure)
        assertEquals(0, audit.logged.size)
    }
}
