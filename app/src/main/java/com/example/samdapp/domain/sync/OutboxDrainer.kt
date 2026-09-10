package com.example.samdapp.domain.sync

/**
 * Drain the sync outbox in-process, bypassing WorkManager scheduling.
 *
 * This exists so [com.example.samdapp.domain.usecase.AssessmentRunner] can ensure all locally-
 * created records (patient, encounter, case_record, etc.) have been pushed to the backend
 * BEFORE it calls `/api/v1/assess`. The backend's `_resolve_case_record` 404s when the case
 * record is absent, so an assess call that races ahead of the sync outbox always falls to the
 * fallback path, producing a false UNAVAILABLE result even though the classifier is healthy.
 *
 * Why not use [SyncStatus.syncNow]? That goes through WorkManager (`runNowAndAwait`), which
 * schedules a SEPARATE CoroutineWorker. Inside the AssessmentWorker (which IS a CoroutineWorker
 * already), calling the drain directly avoids the indirection and guarantees the full drain loop
 * completes synchronously before the assess call runs. A drain-failure (network down, 401 that
 * can't be refreshed) is not fatal: the assess call simply proceeds and the backend's 404
 * produces the existing UNAVAILABLE fallback, same behavior as today.
 */
fun interface OutboxDrainer {
    suspend fun drainAll(): Result<Unit>
}
