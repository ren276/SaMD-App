package com.example.samdapp.data.repository

import com.example.samdapp.data.local.dao.KernelReportDao
import com.example.samdapp.data.local.entity.KernelReportEntity
import com.example.samdapp.domain.model.KernelReportOutput
import com.example.samdapp.domain.repository.KernelReportRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

class KernelReportRepositoryImpl @Inject constructor(
    private val kernelReportDao: KernelReportDao,
) : KernelReportRepository {

    override suspend fun save(report: KernelReportOutput): Result<Unit> = asDataResult {
        kernelReportDao.upsert(report.toEntity())
    }

    override suspend fun getForCase(caseRecordId: String): KernelReportOutput? =
        kernelReportDao.observeForCase(caseRecordId).first()?.toDomain()
}

private fun KernelReportOutput.toEntity() = KernelReportEntity(
    id = id,
    caseRecordId = caseRecordId,
    predictedCondition = predictedCondition,
    confidenceScore = confidenceScore,
    differentials = differentials,
    reasoningSummary = reasoningSummary,
    evidenceFor = evidenceFor,
    evidenceAgainst = evidenceAgainst,
    modelVersion = modelVersion,
    inferenceTimestamp = inferenceTimestamp,
    requiredHumanVerification = requiredHumanVerification,
)

private fun KernelReportEntity.toDomain() = KernelReportOutput(
    id = id,
    caseRecordId = caseRecordId,
    predictedCondition = predictedCondition,
    confidenceScore = confidenceScore,
    differentials = differentials,
    reasoningSummary = reasoningSummary,
    evidenceFor = evidenceFor,
    evidenceAgainst = evidenceAgainst,
    modelVersion = modelVersion,
    inferenceTimestamp = inferenceTimestamp,
    requiredHumanVerification = requiredHumanVerification,
)
