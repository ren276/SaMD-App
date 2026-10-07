package com.example.samdapp.data.local.dao

import com.example.samdapp.domain.model.AssessGateSnapshot
import com.example.samdapp.domain.model.SyncChainRow
import com.example.samdapp.domain.model.SyncState

/**
 * One case's sync chain in one row: the case record joined to its encounter and patient. The
 * encounter and patient columns are nullable because the joins are LEFT JOINs: a parent this phone
 * does not hold must not hide the case. The case columns are non-null, so a misspelt alias there
 * fails the build (Room only warns on a nullable alias mismatch, which is why the parent columns
 * are pinned by an instrumented test instead).
 */
data class AssessGateRow(
    val caseSyncState: SyncState,
    val caseServerVersion: Int?,
    val caseSyncErrorCode: String?,
    val caseSyncErrorMessage: String?,
    val encounterSyncState: SyncState?,
    val encounterServerVersion: Int?,
    val encounterSyncErrorCode: String?,
    val encounterSyncErrorMessage: String?,
    val patientSyncState: SyncState?,
    val patientServerVersion: Int?,
    val patientSyncErrorCode: String?,
    val patientSyncErrorMessage: String?,
) {
    fun toSnapshot() = AssessGateSnapshot(
        caseRecord = SyncChainRow(caseSyncState, caseServerVersion, caseSyncErrorCode, caseSyncErrorMessage),
        encounter = encounterSyncState?.let { SyncChainRow(it, encounterServerVersion, encounterSyncErrorCode, encounterSyncErrorMessage) },
        patient = patientSyncState?.let { SyncChainRow(it, patientServerVersion, patientSyncErrorCode, patientSyncErrorMessage) },
    )
}
