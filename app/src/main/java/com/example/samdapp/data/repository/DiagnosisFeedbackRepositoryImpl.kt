package com.example.samdapp.data.repository

import com.example.samdapp.data.local.dao.DiagnosisFeedbackDao
import com.example.samdapp.data.local.entity.DiagnosisFeedbackEntity
import com.example.samdapp.domain.model.DiagnosisFeedback
import com.example.samdapp.domain.repository.DiagnosisFeedbackRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

class DiagnosisFeedbackRepositoryImpl @Inject constructor(
    private val diagnosisFeedbackDao: DiagnosisFeedbackDao,
) : DiagnosisFeedbackRepository {

    override suspend fun save(feedback: DiagnosisFeedback): Result<Unit> = asDataResult {
        diagnosisFeedbackDao.upsert(
            DiagnosisFeedbackEntity(
                id = feedback.id,
                caseRecordId = feedback.caseRecordId,
                icdCandidate = feedback.icdCandidate,
                physicianDecision = feedback.physicianDecision,
                physicianFinalDiagnosis = feedback.physicianFinalDiagnosis,
                clinicalNote = feedback.clinicalNote,
                createdAt = feedback.createdAt,
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
