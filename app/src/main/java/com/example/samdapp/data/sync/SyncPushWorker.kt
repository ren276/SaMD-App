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
 * itself (FAILED state), not by failing the whole worker. Two kinds end the run without a retry,
 * and neither changes any outbox row (only the drain's acks do):
 * - [DrainFailure.OWNER_MISMATCH]: retrying cannot help until someone signs in again (the drain
 *   stays held, facility-scope memo 3.5).
 * - [DrainFailure.LOCAL_NETWORK_NOT_ALLOWED] (memo P2-R8): a periodic job in backoff runs at the
 *   backoff delay, up to 5 h, not its interval; a failure re-enqueues it for its next period with
 *   the attempt count reset. Home's resume enqueue and Sync now still wake a drain at once.
 */
internal fun syncPushWorkResult(drain: kotlin.Result<Unit>): ListenableWorker.Result = drain.fold(
    onSuccess = { ListenableWorker.Result.success() },
    onFailure = { e ->
        when (drainFailureFor(e)) {
            DrainFailure.OWNER_MISMATCH, DrainFailure.LOCAL_NETWORK_NOT_ALLOWED -> ListenableWorker.Result.failure()
            else -> ListenableWorker.Result.retry()
        }
    },
)
