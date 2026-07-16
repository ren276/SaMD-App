package com.example.samdapp.data.repository

import com.example.samdapp.data.local.dao.AttachmentDao
import com.example.samdapp.data.local.dao.ConsultationDao
import com.example.samdapp.data.local.entity.AttachmentEntity
import com.example.samdapp.data.local.entity.ConsultationEntity
import com.example.samdapp.domain.model.Attachment
import com.example.samdapp.domain.model.Consultation
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

    override suspend fun saveConsultation(consultation: Consultation): Result<Unit> = asDataResult {
        consultationDao.insert(consultation.toEntity())
    }

    override suspend fun addAttachment(attachment: Attachment): Result<Unit> = asDataResult {
        attachmentDao.insert(attachment.toEntity())
    }

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
    relevantHistory = relevantHistory,
    transcription = transcription,
    createdAt = createdAt,
    updatedAt = updatedAt,
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
)

private fun AttachmentEntity.toDomain() = Attachment(
    id = id,
    consultationId = consultationId,
    type = type,
    uri = uri,
    createdAt = createdAt,
)
