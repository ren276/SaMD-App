package com.example.samdapp.data.repository

import com.example.samdapp.data.local.dao.EvaluateReportDao
import com.example.samdapp.data.local.entity.EvaluateReportEntity
import com.example.samdapp.domain.model.EvaluateBrandMapping
import com.example.samdapp.domain.model.EvaluateDiagnosticSummary
import com.example.samdapp.domain.model.EvaluateNlemTreatment
import com.example.samdapp.domain.model.EvaluateReportOutput
import com.example.samdapp.domain.model.EvaluateSafetyAndTriage
import com.example.samdapp.domain.model.IndianBrandSuggestion
import com.example.samdapp.domain.repository.EvaluateReportRepository
import com.google.gson.Gson
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

/** [EvaluateReportPayload] round-trips the deeply-nested diagnosticSummary/nlemTreatment/
 *  brandMapping/safetyAndTriage tree through one Gson blob column instead of a per-nested-field
 *  TypeConverter scheme (see [EvaluateReportEntity]). */
class EvaluateReportRepositoryImpl @Inject constructor(
    private val evaluateReportDao: EvaluateReportDao,
) : EvaluateReportRepository {

    private val gson = Gson()

    private data class EvaluateReportPayload(
        val diagnosticSummary: EvaluateDiagnosticSummary,
        val nlemTreatment: EvaluateNlemTreatment,
        val brandMapping: EvaluateBrandMapping?,
        val safetyAndTriage: EvaluateSafetyAndTriage,
        val topIndianBrand: IndianBrandSuggestion?,
    )

    override suspend fun save(report: EvaluateReportOutput): Result<Unit> = asDataResult {
        val payload = EvaluateReportPayload(
            diagnosticSummary = report.diagnosticSummary,
            nlemTreatment = report.nlemTreatment,
            brandMapping = report.brandMapping,
            safetyAndTriage = report.safetyAndTriage,
            topIndianBrand = report.topIndianBrand,
        )
        // Resolved by caseRecordId, not report.id (which GenerateEvaluateReportUseCase mints
        // fresh on every attempt): this is what makes upsert()'s REPLACE actually replace the
        // one row this case already has — including a prior H-14 failure row, clearing
        // failureCode back to null — rather than insert a second, orphaned row. See
        // EvaluateReportDao.getIdForCase's KDoc. existingServerVersion piggybacks on the same
        // resolved id so a re-saved report doesn't silently wipe it (syncstate-reset session).
        val existingId = evaluateReportDao.getIdForCase(report.caseRecordId)
        val id = existingId ?: report.id
        val existingServerVersion = evaluateReportDao.getServerVersion(id)
        evaluateReportDao.upsert(
            EvaluateReportEntity(
                id = id,
                caseRecordId = report.caseRecordId,
                payloadJson = gson.toJson(payload),
                inferenceStartedAt = report.inferenceStartedAt,
                inferenceEndedAt = report.inferenceEndedAt,
                localModifiedAt = Instant.now(),
                serverVersion = existingServerVersion,
                failureCode = null,
            ),
        )
    }

    override suspend fun saveFailure(caseRecordId: String, failureCode: String): Result<Unit> = asDataResult {
        val existingId = evaluateReportDao.getIdForCase(caseRecordId)
        val id = existingId ?: UUID.randomUUID().toString()
        val existingServerVersion = evaluateReportDao.getServerVersion(id)
        val now = Instant.now()
        evaluateReportDao.upsert(
            EvaluateReportEntity(
                id = id,
                caseRecordId = caseRecordId,
                // Placeholder, never deserialized: getForCase/getFailureCodeForCase callers must
                // check failureCode first (see EvaluateReportEntity.failureCode's KDoc).
                payloadJson = "{}",
                inferenceStartedAt = now,
                inferenceEndedAt = now,
                localModifiedAt = now,
                serverVersion = existingServerVersion,
                failureCode = failureCode,
            ),
        )
    }

    override suspend fun getForCase(caseRecordId: String): EvaluateReportOutput? {
        val entity = evaluateReportDao.observeForCase(caseRecordId).first() ?: return null
        if (entity.failureCode != null) return null
        val payload = gson.fromJson(entity.payloadJson, EvaluateReportPayload::class.java)
        return EvaluateReportOutput(
            id = entity.id,
            caseRecordId = entity.caseRecordId,
            diagnosticSummary = payload.diagnosticSummary,
            nlemTreatment = payload.nlemTreatment,
            brandMapping = payload.brandMapping,
            safetyAndTriage = payload.safetyAndTriage,
            topIndianBrand = payload.topIndianBrand,
            inferenceStartedAt = entity.inferenceStartedAt,
            inferenceEndedAt = entity.inferenceEndedAt,
        )
    }

    override suspend fun getFailureCodeForCase(caseRecordId: String): String? =
        evaluateReportDao.getFailureCode(caseRecordId)
}
