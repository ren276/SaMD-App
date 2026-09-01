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
        /** Null means the value was typed, which is the only path that existed before the voice
         *  confirmation gate; a caller that ran the gate passes the stamp the worker earned
         *  (`VOICE_CONFIRMED` or `VOICE_EDITED`). `VOICE_UNCONFIRMED` is refused one layer down,
         *  in `ConsultationRepositoryImpl.saveConsultation`, so every caller crosses that check
         *  rather than each one repeating it here. */
        impactOnDailyActivitiesProvenance: FieldProvenance? = null,
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
            // Only stamp when there is a value to attribute: an absent field has no provenance to
            // record. A caller that ran the voice gate supplies its own stamp; a caller that did
            // not gets TYPED, which is the pre-gate behaviour unchanged.
            impactOnDailyActivitiesProvenance = impactOnDailyActivities?.let {
                impactOnDailyActivitiesProvenance ?: FieldProvenance.TYPED
            },
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
