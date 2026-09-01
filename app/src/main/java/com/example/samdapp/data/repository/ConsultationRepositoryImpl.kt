package com.example.samdapp.data.repository

import com.example.samdapp.data.local.dao.AttachmentDao
import com.example.samdapp.data.local.dao.ConsultationDao
import com.example.samdapp.data.local.entity.AttachmentEntity
import com.example.samdapp.data.local.entity.ConsultationEntity
import com.example.samdapp.domain.DataError
import com.example.samdapp.domain.model.Attachment
import com.example.samdapp.domain.model.Consultation
import com.example.samdapp.domain.model.FieldProvenance
import com.example.samdapp.domain.repository.ConsultationRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.time.Instant
import javax.inject.Inject

class ConsultationRepositoryImpl @Inject constructor(
    private val consultationDao: ConsultationDao,
    private val attachmentDao: AttachmentDao,
) : ConsultationRepository {

    /**
     * The `VOICE_UNCONFIRMED` write-refusal (`scratchpad/pr3-voice-gate-design-memo.md` B.1/B.2,
     * from the field-audit memo's B.2). An ASR suggestion that no worker has read and accepted
     * must never reach the database, and the check lives here rather than in a ViewModel so that
     * every caller crosses it. Today there is exactly one caller
     * ([com.example.samdapp.domain.usecase.SaveConsultationUseCase]), which is precisely why the
     * ViewModel would be the wrong place: this has to survive the second caller that does not
     * exist yet, and a future screen that forgets the confirmation gate.
     *
     * This is a **backstop**, not the primary control. The primary control is that no code path
     * constructs `VOICE_UNCONFIRMED` at all; nothing does yet, since the gate UI and state model
     * are later steps.
     *
     * Refused, not thrown and not dropped. A throw would be caught by [asDataResult] and remapped
     * to [DataError.Local] ("Local storage error"), reporting a deliberate policy decision as a
     * storage fault. A silent drop would return success with nothing persisted, which is the exact
     * shape CLAUDE.md's assert-the-persisted-row rule exists to catch. The check runs before any
     * DAO call, so a refused write leaves no partial row behind.
     */
    override suspend fun saveConsultation(consultation: Consultation): Result<Unit> {
        if (consultation.impactOnDailyActivitiesProvenance == FieldProvenance.VOICE_UNCONFIRMED) {
            return Result.failure(
                DataError.Refused(
                    reason = "impactOnDailyActivitiesProvenance is VOICE_UNCONFIRMED; an unconfirmed " +
                        "voice value cannot be persisted",
                    message = "Please confirm the voice suggestion before saving",
                ),
            )
        }
        return asDataResult { consultationDao.insert(consultation.toEntity()) }
    }

    override suspend fun addAttachment(attachment: Attachment): Result<Unit> = asDataResult {
        attachmentDao.insert(attachment.toEntity())
    }

    /** No provenance refusal here, deliberately: this path writes only `transcription`,
     *  `updatedAt`/`localModifiedAt` and `syncState`, never a provenance column, so it cannot
     *  carry `VOICE_UNCONFIRMED`. See [saveConsultation]'s KDoc for the refusal that does apply. */
    override suspend fun updateTranscription(consultationId: String, transcription: String): Result<Unit> =
        asDataResult {
            consultationDao.updateTranscription(consultationId, transcription, Instant.now())
        }

    override fun observeForEncounter(encounterId: String): Flow<Consultation?> =
        consultationDao.observeForEncounter(encounterId).flatMapLatest { entity ->
            if (entity == null) {
                flowOf(null)
            } else {
                attachmentDao.observeForConsultation(entity.id).map { attachments ->
                    entity.toDomain(attachments.map { it.toDomain() })
                }
            }
        }
}

private fun Consultation.toEntity() = ConsultationEntity(
    id = id,
    patientId = patientId,
    encounterId = encounterId,
    chiefComplaint = chiefComplaint,
    onset = onset,
    durationBucket = durationBucket,
    severityScore = severityScore,
    aggravatingFactors = aggravatingFactors,
    relievingFactors = relievingFactors,
    impactOnDailyActivities = impactOnDailyActivities,
    impactOnDailyActivitiesProvenance = impactOnDailyActivitiesProvenance,
    relevantHistory = relevantHistory,
    transcription = transcription,
    createdAt = createdAt,
    updatedAt = updatedAt,
    localModifiedAt = updatedAt,
)

private fun ConsultationEntity.toDomain(attachments: List<Attachment>) = Consultation(
    id = id,
    patientId = patientId,
    encounterId = encounterId,
    chiefComplaint = chiefComplaint,
    onset = onset,
    durationBucket = durationBucket,
    severityScore = severityScore,
    aggravatingFactors = aggravatingFactors,
    relievingFactors = relievingFactors,
    impactOnDailyActivities = impactOnDailyActivities,
    impactOnDailyActivitiesProvenance = impactOnDailyActivitiesProvenance,
    relevantHistory = relevantHistory,
    transcription = transcription,
    attachments = attachments,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun Attachment.toEntity() = AttachmentEntity(
    id = id,
    consultationId = consultationId,
    type = type,
    uri = uri,
    createdAt = createdAt,
    localModifiedAt = createdAt,
)

private fun AttachmentEntity.toDomain() = Attachment(
    id = id,
    consultationId = consultationId,
    type = type,
    uri = uri,
    createdAt = createdAt,
)
