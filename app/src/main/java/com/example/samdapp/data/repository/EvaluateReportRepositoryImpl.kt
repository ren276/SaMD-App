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
        evaluateReportDao.upsert(
            EvaluateReportEntity(
                id = report.id,
                caseRecordId = report.caseRecordId,
                payloadJson = gson.toJson(payload),
                inferenceStartedAt = report.inferenceStartedAt,
                inferenceEndedAt = report.inferenceEndedAt,
            ),
        )
    }

    override suspend fun getForCase(caseRecordId: String): EvaluateReportOutput? {
        val entity = evaluateReportDao.observeForCase(caseRecordId).first() ?: return null
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
}
