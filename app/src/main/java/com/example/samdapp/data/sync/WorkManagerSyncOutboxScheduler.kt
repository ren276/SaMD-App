package com.example.samdapp.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkManagerSyncOutboxScheduler @Inject constructor(
    @ApplicationContext context: Context,
) : SyncOutboxScheduler {

    private val workManager = WorkManager.getInstance(context)

    private val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    override fun ensurePeriodicWork() {
        val request = PeriodicWorkRequestBuilder<SyncPushWorker>(PERIODIC_INTERVAL_MINUTES, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
            .build()
        // KEEP, not REPLACE: this is called on every app start (SyncStatusImpl's init) and must
        // not reset an already-scheduled periodic job's cycle each time.
        workManager.enqueueUniquePeriodicWork(PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    override suspend fun runNowAndAwait(): Result<Unit> {
        val request = OneTimeWorkRequestBuilder<SyncPushWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
            .build()
        workManager.enqueueUniqueWork(ONE_TIME_WORK_NAME, ExistingWorkPolicy.REPLACE, request)

        // Await this attempt's own outcome, not WorkManager's full backoff-retry schedule: if the
        // worker returns Result.retry(), WorkInfo goes RUNNING -> ENQUEUED again rather than
        // FAILED (WorkManager has no built-in retry cap), and a caller here waiting for a
        // genuinely terminal state could block through several backoff cycles. sawRunning tracks
        // whether the one attempt we're watching has actually run; the underlying request keeps
        // retrying under WorkManager's own backoff afterward regardless of whether anyone is
        // still listening.
        var sawRunning = false
        // Bounded: if connectivity drops after the isOnline check above, WorkManager leaves this
        // request ENQUEUED indefinitely and this wait would otherwise never complete, wedging
        // SyncStatusImpl.syncNow()'s isSyncing forever. The request itself is left enqueued on
        // timeout — only this caller's wait gives up, so WorkManager still runs it when
        // connectivity returns.
        val info = withTimeoutOrNull(AWAIT_TIMEOUT_MILLIS) {
            workManager.getWorkInfoByIdFlow(request.id).filterNotNull().first { info ->
                when (info.state) {
                    WorkInfo.State.RUNNING -> { sawRunning = true; false }
                    WorkInfo.State.SUCCEEDED, WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> true
                    WorkInfo.State.ENQUEUED -> sawRunning
                    WorkInfo.State.BLOCKED -> false
                }
            }
        } ?: return Result.failure(IllegalStateException("Sync timed out waiting for connectivity"))
        return if (info.state == WorkInfo.State.SUCCEEDED) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("Sync did not succeed (state=${info.state})"))
        }
    }

    private companion object {
        const val PERIODIC_WORK_NAME = "sync_push_periodic"
        const val ONE_TIME_WORK_NAME = "sync_push_now"
        const val PERIODIC_INTERVAL_MINUTES = 15L
        const val AWAIT_TIMEOUT_MILLIS = 60_000L
    }
}
