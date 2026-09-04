package com.example.samdapp.domain.document

import com.example.samdapp.domain.auth.UserRole
import com.example.samdapp.domain.auth.UserSession
import com.example.samdapp.domain.model.ConsultationDocument
import com.example.samdapp.domain.model.DepartmentCode
import com.example.samdapp.domain.model.DocumentSource
import com.example.samdapp.domain.model.RecordTypeCode
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/** H-18, Build 3c: the single cadre-gate seam, tested independently of the ViewModel/viewer. */
class DocumentAccessAuthorizerTest {

    private fun document(uploaderUserId: String) = ConsultationDocument(
        id = "doc-1", consultationId = "c1", patientId = "p1", abhaNumber = null, label = "label",
        canonicalName = "canonical", departmentCode = DepartmentCode.ORTHO, recordTypeCode = RecordTypeCode.IMAGING,
        storageKey = "key", mimeType = "application/pdf", sizeBytes = 500L, sha256 = "hash",
        source = DocumentSource.DIRECT_FILE, pageCount = null, uploadedAt = Instant.EPOCH, uploaderUserId = uploaderUserId,
        uploaderRole = "ASHA_WORKER", retractedAt = null, retractionReason = null,
    )

    @Test
    fun `a physician DOCTOR opens a document they did not upload — granted via tier`() {
        val outcome = DocumentAccessAuthorizer.authorize(
            document(uploaderUserId = "worker-1"),
            UserSession("doc-9", "Dr. Someone", UserRole.DOCTOR),
        )
        assertEquals(DocumentAccessOutcome.GRANTED_TIER, outcome)
        assertEquals(true, outcome.granted)
        assertEquals("granted", outcome.auditValue)
    }

    @Test
    fun `a NURSE (LICENSED_CLINICAL) opens a document they did not upload — denied`() {
        val outcome = DocumentAccessAuthorizer.authorize(
            document(uploaderUserId = "worker-1"),
            UserSession("worker-2", "A Nurse", UserRole.NURSE),
        )
        assertEquals(DocumentAccessOutcome.DENIED_TIER, outcome)
        assertEquals(false, outcome.granted)
        assertEquals("denied_tier", outcome.auditValue)
    }

    @Test
    fun `a COMPOUNDER (LICENSED_CLINICAL) opens a document they did not upload — denied`() {
        val outcome = DocumentAccessAuthorizer.authorize(
            document(uploaderUserId = "worker-1"),
            UserSession("worker-2", "A Compounder", UserRole.COMPOUNDER),
        )
        assertEquals(DocumentAccessOutcome.DENIED_TIER, outcome)
    }

    @Test
    fun `an ASHA_WORKER (COMMUNITY) opens a document they did not upload — denied`() {
        val outcome = DocumentAccessAuthorizer.authorize(
            document(uploaderUserId = "worker-1"),
            UserSession("worker-2", "An ASHA", UserRole.ASHA_WORKER),
        )
        assertEquals(DocumentAccessOutcome.DENIED_TIER, outcome)
    }

    @Test
    fun `uploader exception — a NURSE who uploaded their own document is granted despite their tier`() {
        val outcome = DocumentAccessAuthorizer.authorize(
            document(uploaderUserId = "worker-2"),
            UserSession("worker-2", "A Nurse", UserRole.NURSE),
        )
        assertEquals(DocumentAccessOutcome.GRANTED_UPLOADER, outcome)
        assertEquals(true, outcome.granted)
        assertEquals("granted_uploader", outcome.auditValue)
    }

    @Test
    fun `uploader exception — an ASHA_WORKER who uploaded their own document is granted despite their tier`() {
        val outcome = DocumentAccessAuthorizer.authorize(
            document(uploaderUserId = "worker-2"),
            UserSession("worker-2", "An ASHA", UserRole.ASHA_WORKER),
        )
        assertEquals(DocumentAccessOutcome.GRANTED_UPLOADER, outcome)
    }

    @Test
    fun `uploader match wins even for a physician — still reported as the uploader exception, not tier`() {
        val outcome = DocumentAccessAuthorizer.authorize(
            document(uploaderUserId = "doc-9"),
            UserSession("doc-9", "Dr. Someone", UserRole.DOCTOR),
        )
        assertEquals(DocumentAccessOutcome.GRANTED_UPLOADER, outcome)
    }
}
