package com.example.samdapp.data.assessment

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mirrors [com.example.samdapp.data.sync.WorkManagerSyncOutboxScheduler]'s constraints and
 * backoff exactly — connectivity-constrained, exponential backoff from WorkManager's own
 * minimum. The one deliberate divergence is [ExistingWorkPolicy.KEEP] instead of `REPLACE`: the
 * outbox's single `sync_push_now` name uses `REPLACE` because a later drain subsumes an earlier
 * one, but assessment work is per case, and a second enqueue for a case already running must
 * leave the first attempt alone rather than cancel and restart it.
 */
@Singleton
class WorkManagerAssessmentScheduler @Inject constructor(
    @ApplicationContext context: Context,
) : AssessmentQueueScheduler {

    private val workManager = WorkManager.getInstance(context)

    private val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    override fun enqueueAssessment(caseRecordId: String) {
        val request = OneTimeWorkRequestBuilder<AssessmentWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
            .setInputData(AssessmentWorker.inputData(caseRecordId))
            .build()
        workManager.enqueueUniqueWork(uniqueWorkName(caseRecordId), ExistingWorkPolicy.KEEP, request)
    }

    override fun observeWorkState(caseRecordId: String): Flow<AssessmentWorkState> =
        workManager.getWorkInfosForUniqueWorkFlow(uniqueWorkName(caseRecordId)).map { infos ->
            when {
                infos.any { it.state == WorkInfo.State.RUNNING } -> AssessmentWorkState.RUNNING
                infos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED } -> AssessmentWorkState.QUEUED
                else -> AssessmentWorkState.NONE
            }
        }

    companion object {
        fun uniqueWorkName(caseRecordId: String): String = "assess_$caseRecordId"
    }
}
