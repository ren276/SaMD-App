package com.example.samdapp.domain.usecase

import com.example.samdapp.domain.model.Attachment
import com.example.samdapp.domain.model.AttachmentType
import com.example.samdapp.domain.model.Consultation
import com.example.samdapp.domain.model.FieldProvenance
import com.example.samdapp.domain.repository.ConsultationRepository
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

class SaveConsultationUseCase @Inject constructor(
    private val repository: ConsultationRepository,
) {
    suspend operator fun invoke(
        patientId: String,
        encounterId: String,
        chiefComplaint: String,
        onset: String?,
        durationBucket: String?,
        severityScore: Int?,
        aggravatingFactors: String?,
        relievingFactors: String?,
        impactOnDailyActivities: String?,
        relevantHistory: String?,
    ): Result<Consultation> {
        if (chiefComplaint.isBlank()) {
            return Result.failure(IllegalArgumentException("Chief complaint is required"))
        }
        val now = Instant.now()
        val consultation = Consultation(
            id = UUID.randomUUID().toString(),
            patientId = patientId,
            encounterId = encounterId,
            chiefComplaint = chiefComplaint,
            onset = onset,
            durationBucket = durationBucket,
            severityScore = severityScore,
            aggravatingFactors = aggravatingFactors,
            relievingFactors = relievingFactors,
            impactOnDailyActivities = impactOnDailyActivities,
            // No voice capture UI exists on this branch (PR 3 adds it), so every value reaching
            // this use case was typed. TYPED here, not null, matches the migration backfill for
            // pre-existing rows: both state the same honest fact about how the value was entered.
            impactOnDailyActivitiesProvenance = FieldProvenance.TYPED,
            relevantHistory = relevantHistory,
            transcription = null,
            attachments = emptyList(),
            createdAt = now,
            updatedAt = now,
        )
        return repository.saveConsultation(consultation).map { consultation }
    }
}

class AddAttachmentUseCase @Inject constructor(
    private val repository: ConsultationRepository,
) {
    suspend operator fun invoke(consultationId: String, type: AttachmentType, uri: String): Result<Unit> =
        repository.addAttachment(
            Attachment(
                id = UUID.randomUUID().toString(),
                consultationId = consultationId,
                type = type,
                uri = uri,
                createdAt = Instant.now(),
            )
        )
}
