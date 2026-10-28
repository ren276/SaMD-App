package com.example.samdapp.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.example.samdapp.domain.sync.DrainFailure
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * The WorkManager CoroutineWorker that actually runs a [SyncOutboxDrainer.drain] (sync-design.md
 * §2 item 2). Deliberately thin: all pack/send/ack orchestration lives in [SyncOutboxDrainer],
 * which has no WorkManager dependency and is unit-tested directly — this class only adapts that
 * suspend call to WorkManager's `Result` vocabulary. Connectivity constraint and exponential
 * backoff are configured where this worker is enqueued
 * ([WorkManagerSyncOutboxScheduler]), not here.
 */
@HiltWorker
class SyncPushWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val drainer: SyncOutboxDrainer,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = syncPushWorkResult(drainer.drain())
}

/**
 * WorkManager's own configured backoff (exponential, set at enqueue time) governs the retry delay;
 * a network/backend failure is retryable here, and a permanently-bad row is handled inside the drain
 * itself (FAILED state), not by failing the whole worker. The one exception is
 * [DrainFailure.OWNER_MISMATCH]: retrying cannot help until someone signs in again, so the run ends
 * without a retry (the periodic schedule still runs, and the drain stays held, facility-scope memo
 * 3.5).
 */
internal fun syncPushWorkResult(drain: kotlin.Result<Unit>): ListenableWorker.Result = drain.fold(
    onSuccess = { ListenableWorker.Result.success() },
    onFailure = { e ->
        if (drainFailureFor(e) == DrainFailure.OWNER_MISMATCH) ListenableWorker.Result.failure() else ListenableWorker.Result.retry()
    },
)
