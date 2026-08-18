package com.example.samdapp.data.repository

import com.example.samdapp.data.local.dao.KernelReportDao
import com.example.samdapp.data.local.entity.KernelReportEntity
import com.example.samdapp.domain.model.KernelReportOutput
import com.example.samdapp.domain.repository.KernelReportRepository
import kotlinx.coroutines.flow.first
import java.time.Instant
import javax.inject.Inject

class KernelReportRepositoryImpl @Inject constructor(
    private val kernelReportDao: KernelReportDao,
) : KernelReportRepository {

    override suspend fun save(report: KernelReportOutput): Result<Unit> = asDataResult {
        // upsert() is REPLACE: without this read, a re-saved report would silently wipe
        // serverVersion (syncstate-reset session). syncState needs no explicit reset —
        // KernelReportEntity's default is already PENDING, and REPLACE always writes the full
        // default set.
        val existingServerVersion = kernelReportDao.getServerVersion(report.id)
        kernelReportDao.upsert(report.toEntity(serverVersion = existingServerVersion))
    }

    override suspend fun getForCase(caseRecordId: String): KernelReportOutput? =
        kernelReportDao.observeForCase(caseRecordId).first()?.toDomain()
}

private fun KernelReportOutput.toEntity(serverVersion: Int?) = KernelReportEntity(
    id = id,
    caseRecordId = caseRecordId,
    predictedCondition = predictedCondition,
    confidenceScore = confidenceScore,
    differentials = differentials,
    reasoningSummary = reasoningSummary,
    evidenceFor = evidenceFor,
    evidenceAgainst = evidenceAgainst,
    modelVersion = modelVersion,
    icdCode = icdCode,
    deviceId = deviceId,
    softwareVersion = softwareVersion,
    dataQualityScore = dataQualityScore,
    uncertaintyScore = uncertaintyScore,
    riskCategory = riskCategory,
    urgencyLevel = urgencyLevel,
    inferenceStartedAt = inferenceStartedAt,
    inferenceEndedAt = inferenceEndedAt,
    requiredHumanVerification = requiredHumanVerification,
    inferenceSource = inferenceSource,
    localModifiedAt = Instant.now(),
    serverVersion = serverVersion,
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
    icdCode = icdCode,
    deviceId = deviceId,
    softwareVersion = softwareVersion,
    dataQualityScore = dataQualityScore,
    uncertaintyScore = uncertaintyScore,
    riskCategory = riskCategory,
    urgencyLevel = urgencyLevel,
    inferenceStartedAt = inferenceStartedAt,
    inferenceEndedAt = inferenceEndedAt,
    requiredHumanVerification = requiredHumanVerification,
    inferenceSource = inferenceSource,
)
