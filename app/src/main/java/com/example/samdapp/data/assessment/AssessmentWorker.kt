package com.example.samdapp.data.assessment

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.example.samdapp.domain.usecase.AssessmentRunner
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * The WorkManager `CoroutineWorker` that runs one [AssessmentRunner.run] (async submission
 * queue). Deliberately thin, mirroring
 * [com.example.samdapp.data.sync.SyncPushWorker]: all orchestration lives in [AssessmentRunner],
 * which has no WorkManager dependency and is unit-tested directly.
 *
 * Always returns [Result.success], never [Result.retry]: every reachable failure inside
 * [AssessmentRunner.run] already ends as a written UNAVAILABLE row (see
 * [com.example.samdapp.domain.usecase.GenerateKernelReportUseCase.recordUnavailable]), so there
 * is no failure state left for WorkManager's own retry to usefully act on, and WorkManager has no
 * built-in retry cap — see
 * [com.example.samdapp.data.sync.WorkManagerSyncOutboxScheduler.runNowAndAwait]'s KDoc for that
 * same fact. The one unrecoverable case (the DB save inside [AssessmentRunner] itself failing, so
 * not even an UNAVAILABLE row can be written) is logged and left as a stalled case: no report row
 * and no live [androidx.work.WorkInfo], surfaced by the case list rather than retried here.
 */
@HiltWorker
class AssessmentWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val runner: AssessmentRunner,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val caseRecordId = inputData.getString(KEY_CASE_RECORD_ID)
        if (caseRecordId.isNullOrBlank()) return Result.success()
        runner.run(caseRecordId)
        return Result.success()
    }

    companion object {
        private const val KEY_CASE_RECORD_ID = "case_record_id"

        fun inputData(caseRecordId: String): Data =
            Data.Builder().putString(KEY_CASE_RECORD_ID, caseRecordId).build()
    }
}
