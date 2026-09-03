package com.example.samdapp.domain.usecase

import com.example.samdapp.domain.DataError
import com.example.samdapp.domain.auth.UserRole
import com.example.samdapp.domain.auth.UserSession
import com.example.samdapp.domain.model.ConsultationDocument
import com.example.samdapp.domain.model.DepartmentCode
import com.example.samdapp.domain.model.DocumentSource
import com.example.samdapp.domain.model.RecordTypeCode
import com.example.samdapp.testutil.FakeAuditLogger
import com.example.samdapp.testutil.FakeAuthSession
import com.example.samdapp.testutil.FakeConsultationDocumentRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** H-18, Build 3a: who can retract (uploader or DOCTOR only), and the audit row it emits. */
class RetractConsultationDocumentUseCaseTest {

    private fun document(uploaderUserId: String) = ConsultationDocument(
        id = "doc-1", consultationId = "c1", patientId = "p1", abhaNumber = null, label = "label",
        canonicalName = "canonical", departmentCode = DepartmentCode.ORTHO, recordTypeCode = RecordTypeCode.IMAGING,
        storageKey = "key", mimeType = "application/pdf", sizeBytes = 500L, sha256 = "hash",
        source = DocumentSource.DIRECT_FILE, uploadedAt = Instant.EPOCH, uploaderUserId = uploaderUserId,
        uploaderRole = "ASHA_WORKER", retractedAt = null, retractionReason = null,
    )

    @Test
    fun `the uploader can retract their own upload`() = runTest {
        val repo = FakeConsultationDocumentRepository().apply { saved["doc-1"] = document(uploaderUserId = "worker-1") }
        val audit = FakeAuditLogger()
        val useCase = RetractConsultationDocumentUseCase(
            repo, FakeAuthSession(UserSession("worker-1", "A Worker", UserRole.ASHA_WORKER)), audit,
        )

        val result = useCase("doc-1", "wrong patient")

        assertTrue(result.isSuccess)
        val entry = audit.logged.single { it.action == "document_retracted" }
        assertTrue(entry.payload.contains("\"actorRole\":\"ASHA_WORKER\""))
        assertTrue(entry.payload.contains("\"bytesDeleted\":\"500\""))
    }

    @Test
    fun `a DOCTOR can retract a document they did not upload`() = runTest {
        val repo = FakeConsultationDocumentRepository().apply { saved["doc-1"] = document(uploaderUserId = "worker-1") }
        val audit = FakeAuditLogger()
        val useCase = RetractConsultationDocumentUseCase(
            repo, FakeAuthSession(UserSession("doc-9", "Dr. Someone", UserRole.DOCTOR)), audit,
        )

        val result = useCase("doc-1", null)

        assertTrue(result.isSuccess)
        assertTrue(audit.logged.any { it.action == "document_retracted" })
    }

    @Test
    fun `a non-uploader non-doctor cannot retract — refused, no audit row, H-06 caveat applies`() = runTest {
        val repo = FakeConsultationDocumentRepository().apply { saved["doc-1"] = document(uploaderUserId = "worker-1") }
        val audit = FakeAuditLogger()
        val useCase = RetractConsultationDocumentUseCase(
            repo, FakeAuthSession(UserSession("worker-2", "Another Worker", UserRole.NURSE)), audit,
        )

        val result = useCase("doc-1", null)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is DataError.Refused)
        assertFalse(audit.logged.any { it.action == "document_retracted" })
    }
}
