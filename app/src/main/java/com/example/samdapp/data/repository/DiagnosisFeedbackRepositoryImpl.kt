package com.example.samdapp.data.repository

import com.example.samdapp.data.local.dao.DiagnosisFeedbackDao
import com.example.samdapp.data.local.entity.DiagnosisFeedbackEntity
import com.example.samdapp.domain.model.DiagnosisFeedback
import com.example.samdapp.domain.repository.DiagnosisFeedbackRepository
import kotlinx.coroutines.flow.first
import java.time.Instant
import javax.inject.Inject

class DiagnosisFeedbackRepositoryImpl @Inject constructor(
    private val diagnosisFeedbackDao: DiagnosisFeedbackDao,
) : DiagnosisFeedbackRepository {

    override suspend fun save(feedback: DiagnosisFeedback): Result<Unit> = asDataResult {
        // upsert() is REPLACE: without this read, a re-saved feedback would silently wipe
        // serverVersion (syncstate-reset session). syncState needs no explicit reset —
        // DiagnosisFeedbackEntity's default is already PENDING, and REPLACE always writes the
        // full default set.
        val existingServerVersion = diagnosisFeedbackDao.getServerVersion(feedback.id)
        diagnosisFeedbackDao.upsert(
            DiagnosisFeedbackEntity(
                id = feedback.id,
                caseRecordId = feedback.caseRecordId,
                icdCandidate = feedback.icdCandidate,
                physicianDecision = feedback.physicianDecision,
                physicianFinalDiagnosis = feedback.physicianFinalDiagnosis,
                clinicalNote = feedback.clinicalNote,
                createdAt = feedback.createdAt,
                localModifiedAt = Instant.now(),
                serverVersion = existingServerVersion,
            ),
        )
    }

    override suspend fun getForCase(caseRecordId: String): DiagnosisFeedback? {
        val entity = diagnosisFeedbackDao.observeForCase(caseRecordId).first() ?: return null
        return DiagnosisFeedback(
            id = entity.id,
            caseRecordId = entity.caseRecordId,
            icdCandidate = entity.icdCandidate,
            physicianDecision = entity.physicianDecision,
            physicianFinalDiagnosis = entity.physicianFinalDiagnosis,
            clinicalNote = entity.clinicalNote,
            createdAt = entity.createdAt,
        )
    }
}
