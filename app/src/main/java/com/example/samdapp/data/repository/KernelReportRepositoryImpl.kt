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
        // Resolved by caseRecordId, not report.id (which GenerateKernelReportUseCase mints fresh
        // on every attempt): this is what makes upsert()'s REPLACE actually replace the one row
        // this case already has — including an InferenceSource.UNAVAILABLE row a retry supersedes
        // — rather than insert a second row that observeForCase would then arbitrate between.
        // Same shape as EvaluateReportRepositoryImpl.save; MIGRATION_15_16's unique index on
        // caseRecordId is the structural enforcement.
        //
        // upsert() is REPLACE, so serverVersion must be read back and threaded through or a
        // re-saved report silently wipes it (syncstate-reset session). Routing that read through
        // the RESOLVED id is what makes it work at all: getServerVersion(report.id) could only
        // ever return null, since report.id was brand new on every retry. syncState needs no
        // explicit reset — KernelReportEntity's default is already PENDING, and REPLACE always
        // writes the full default set.
        val id = kernelReportDao.getIdForCase(report.caseRecordId) ?: report.id
        val existingServerVersion = kernelReportDao.getServerVersion(id)
        kernelReportDao.upsert(report.toEntity(id = id, serverVersion = existingServerVersion))
    }

    // .first() takes the single row the unique index on caseRecordId guarantees (MIGRATION_15_16),
    // not an arbitrary pick from several.
    override suspend fun getForCase(caseRecordId: String): KernelReportOutput? =
        kernelReportDao.observeForCase(caseRecordId).first()?.toDomain()
}

private fun KernelReportOutput.toEntity(id: String, serverVersion: Int?) = KernelReportEntity(
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
