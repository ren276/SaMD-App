package com.example.samdapp.domain.repository

import com.example.samdapp.domain.model.EvaluateReportOutput

interface EvaluateReportRepository {
    suspend fun save(report: EvaluateReportOutput): Result<Unit>
    suspend fun getForCase(caseRecordId: String): EvaluateReportOutput?
}
