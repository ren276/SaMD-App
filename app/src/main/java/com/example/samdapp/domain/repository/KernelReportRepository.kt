package com.example.samdapp.domain.repository

import com.example.samdapp.domain.model.KernelReportOutput

/**
 * Read + write for the kernel's assessment (Phase 4 writes; Phase 3 report assembly reads).
 * [getForCase] returns null until the kernel step has run — the preliminary report relies on that.
 */
interface KernelReportRepository {
    suspend fun save(report: KernelReportOutput): Result<Unit>
    suspend fun getForCase(caseRecordId: String): KernelReportOutput?
}
