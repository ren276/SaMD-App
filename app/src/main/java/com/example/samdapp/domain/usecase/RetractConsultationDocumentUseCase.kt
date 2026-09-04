package com.example.samdapp.domain.usecase

import com.example.samdapp.domain.DataError
import com.example.samdapp.domain.audit.AuditAction
import com.example.samdapp.domain.audit.AuditLogger
import com.example.samdapp.domain.audit.auditPayload
import com.example.samdapp.domain.auth.AuthSession
import com.example.samdapp.domain.auth.UserRole
import com.example.samdapp.domain.model.RetractionReasonCode
import com.example.samdapp.domain.repository.ConsultationDocumentRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * H-18, Build 3a. Who can retract: the uploader, plus any [UserRole.DOCTOR] — reads the
 * signed-in session directly rather than trusting a caller-supplied actor.
 *
 * **H-06 caveat, stated here rather than assumed:** `UserRole` is self-asserted at login
 * (`MockAuthSession`/`BackendAuthSession` have no separate per-action credential check beyond the
 * sign-in PIN), so this is an accountability/intent gate on who is recorded as retracting a
 * document, not access control. Same caveat as the `UserRole.DOCTOR` decision-surface gate
 * (H-17, Build 1, `PatientSummaryViewModel.canOpenDoctorReview`).
 *
 * [reasonCode] and [reasonText] are deliberately separate (added on review of PR 37, same
 * reasoning as [com.example.samdapp.domain.model.RetractionReasonCode]'s KDoc): [reasonCode] is
 * what reaches the durable `DOCUMENT_RETRACTED` audit row; [reasonText] is optional free-text
 * elaboration persisted only on the local, unsynced [com.example.samdapp.domain.model.ConsultationDocument]
 * row (`ConsultationDocumentRepository.retract`'s existing `reason` parameter), never the audit
 * trail.
 */
class RetractConsultationDocumentUseCase @Inject constructor(
    private val repository: ConsultationDocumentRepository,
    private val authSession: AuthSession,
    private val auditLogger: AuditLogger,
) {
    suspend operator fun invoke(
        documentId: String,
        reasonCode: RetractionReasonCode,
        reasonText: String? = null,
    ): Result<Unit> {
        val session = authSession.currentUser().first()
            ?: return Result.failure(IllegalStateException("No signed-in session"))
        val document = repository.getById(documentId)
            ?: return Result.failure(DataError.NotFound("Document"))

        val isUploader = session.userId == document.uploaderUserId
        val isDoctor = session.role == UserRole.DOCTOR
        if (!isUploader && !isDoctor) {
            return Result.failure(
                DataError.Refused(
                    reason = "actor ${session.userId} (${session.role}) is neither the uploader nor a DOCTOR",
                    message = "Only the uploader or a physician can retract this document",
                ),
            )
        }

        val result = repository.retract(documentId, reasonText)
        result.onSuccess {
            auditLogger.log(
                action = AuditAction.DOCUMENT_RETRACTED,
                patientId = document.patientId,
                payload = auditPayload(
                    "documentId" to documentId,
                    "reason" to reasonCode.name,
                    "actorRole" to session.role.name,
                    "bytesDeleted" to document.sizeBytes.toString(),
                ),
            )
        }
        return result
    }
}
