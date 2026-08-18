package com.example.samdapp.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
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

    override suspend fun doWork(): Result =
        drainer.drain().fold(
            onSuccess = { Result.success() },
            // WorkManager's own configured backoff (exponential, set at enqueue time) governs the
            // retry delay; a network/backend failure is always retryable here, never permanent —
            // a permanently-bad row is handled inside the drain itself (FAILED state), not by
            // failing the whole worker.
            onFailure = { Result.retry() },
        )
}
