package com.example.samdapp.domain.repository

import com.example.samdapp.domain.model.KernelReportOutput
import kotlinx.coroutines.flow.Flow

/**
 * Read + write for the kernel's assessment (Phase 4 writes; Phase 3 report assembly reads).
 * [getForCase] returns null until the kernel step has run — the preliminary report relies on that.
 */
interface KernelReportRepository {
    suspend fun save(report: KernelReportOutput): Result<Unit>
    suspend fun getForCase(caseRecordId: String): KernelReportOutput?

    /** [getForCase] as a Flow, so a screen open while the async submission queue's assessment is
     *  still in flight sees the row the instant it lands, instead of a one-shot null it can never
     *  refresh from on its own. */
    fun observeForCase(caseRecordId: String): Flow<KernelReportOutput?>
}
