package com.example.samdapp.domain.repository

import com.example.samdapp.domain.model.EvaluateReportOutput
import kotlinx.coroutines.flow.Flow

interface EvaluateReportRepository {
    suspend fun save(report: EvaluateReportOutput): Result<Unit>
    suspend fun getForCase(caseRecordId: String): EvaluateReportOutput?

    /** [getForCase] as a Flow, so a screen open while the async submission queue's assessment is
     *  still in flight sees the row the instant it lands, instead of a one-shot null it can never
     *  refresh from on its own. Same failure-marker semantics as [getForCase]: emits null while a
     *  failure is on record, never the placeholder payload. */
    fun observeForCase(caseRecordId: String): Flow<EvaluateReportOutput?>

    /** H-14: persists that `/api/v1/evaluate` was attempted for [caseRecordId] and failed, so the
     *  failure is readable back instead of looking identical to "hasn't run yet" (both [getForCase]
     *  and [getFailureCodeForCase] return null in that case). Upserts the same per-case row
     *  [save] does, so a later successful [save] naturally clears the failure marker. [failureCode]
     *  is local-only — see EvaluateReportEntity.failureCode's KDoc for why it must never cross
     *  the sync wire. */
    suspend fun saveFailure(caseRecordId: String, failureCode: String): Result<Unit>

    /** Null when no failure is on record for this case (either it hasn't run, or the most recent
     *  attempt succeeded and [getForCase] has the report). Never non-null at the same time as a
     *  non-null [getForCase] result for the same case. */
    suspend fun getFailureCodeForCase(caseRecordId: String): String?
}
