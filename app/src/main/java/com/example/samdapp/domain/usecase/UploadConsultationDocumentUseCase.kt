package com.example.samdapp.domain.usecase

import com.example.samdapp.domain.audit.AuditAction
import com.example.samdapp.domain.audit.AuditLogger
import com.example.samdapp.domain.audit.auditPayload
import com.example.samdapp.domain.document.DocumentBytes
import com.example.samdapp.domain.model.ConsultationDocument
import com.example.samdapp.domain.model.DepartmentCode
import com.example.samdapp.domain.model.RecordTypeCode
import com.example.samdapp.domain.repository.ConsultationDocumentRepository
import javax.inject.Inject

/**
 * H-18, Builds 3a and 3b. The ONE upload use case for BOTH paths: [bytes] is either
 * [DocumentBytes.DirectFile] (PATH A, an existing PDF/JPEG/PNG the worker already has) or
 * [DocumentBytes.AssembledCapture] (PATH B, the multi-page camera capture this app assembled into
 * a PDF on-device). There is deliberately no second use case and no second audit action: a
 * camera-assembled document is the same clinical artefact, recorded the same way, and a reviewer
 * reading the audit trail should not have to know which button produced it.
 *
 * [departmentCode]/[recordTypeCode] are worker-SELECTED from the controlled-vocabulary dropdowns
 * on both paths, never free text. Validation, the size cap, encryption, and naming all happen in
 * [ConsultationDocumentRepository.upload]; this use case's own job is only the audit row on
 * success, mirroring [SubmitDoctorDecisionUseCase]'s save-then-audit shape.
 */
class UploadConsultationDocumentUseCase @Inject constructor(
    private val repository: ConsultationDocumentRepository,
    private val auditLogger: AuditLogger,
) {
    suspend operator fun invoke(
        consultationId: String,
        bytes: DocumentBytes,
        label: String,
        departmentCode: DepartmentCode,
        recordTypeCode: RecordTypeCode,
        uploaderUserId: String,
        uploaderRole: String,
    ): Result<ConsultationDocument> {
        val result = repository.upload(
            consultationId = consultationId,
            bytes = bytes,
            label = label,
            departmentCode = departmentCode,
            recordTypeCode = recordTypeCode,
            uploaderUserId = uploaderUserId,
            uploaderRole = uploaderRole,
        )
        result.onSuccess { document ->
            auditLogger.log(
                action = AuditAction.DOCUMENT_UPLOADED,
                patientId = document.patientId,
                payload = auditPayload(
                    "documentId" to document.id,
                    "source" to document.source.name,
                    // Null for a direct-file upload, where no page count was ever measured -
                    // `auditPayload` writes a JSON null rather than inventing a number.
                    "pageCount" to document.pageCount?.toString(),
                    "departmentCode" to document.departmentCode.name,
                    "recordTypeCode" to document.recordTypeCode.name,
                    "mimeType" to document.mimeType,
                    "sizeBytes" to document.sizeBytes.toString(),
                    "sha256" to document.sha256,
                    "uploaderRole" to document.uploaderRole,
                ),
            )
        }
        return result
    }
}
