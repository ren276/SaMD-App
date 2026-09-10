package com.example.samdapp.domain.usecase

import com.example.samdapp.domain.model.SyncState

/**
 * Functional interface for querying a case_record's transport syncState.
 *
 * Keeps the domain/data boundary intact: [com.example.samdapp.data.local.dao.CaseRecordDao.getSyncState]
 * is the sole implementation, wired via a @Provides in [com.example.samdapp.di.RepositoryModule].
 */
fun interface GetCaseRecordSyncState {
    suspend operator fun invoke(caseRecordId: String): SyncState?
}
