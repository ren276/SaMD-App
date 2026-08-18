package com.example.samdapp.data.sync

/** Seam between [SyncStatusImpl] and WorkManager, so the CaseRecord-assignment-queue behaviors
 *  [SyncStatusImpl] preserves from the old `MockSyncStatus` (offline refusal, auto-sync-on-
 *  transition, no-auto-sync-from-already-online-cold-start) stay JVM-testable against
 *  [FakeSyncOutboxScheduler] — a real [WorkManagerSyncOutboxScheduler] needs a `Context` and
 *  WorkManager's own test harness (`androidx.work:work-testing`), neither available to a plain
 *  JVM test. */
interface SyncOutboxScheduler {
    /** Enqueues the connectivity-constrained, backoff-retried periodic drain (sync-design.md §2
     *  item 2) if it is not already enqueued. Idempotent — safe to call on every app start. */
    fun ensurePeriodicWork()

    /** Enqueues (or replaces) a one-time drain and suspends until its first attempt finishes,
     *  for "sync now" callers that want a prompt answer rather than WorkManager's own
     *  exponential-backoff retry schedule. */
    suspend fun runNowAndAwait(): Result<Unit>
}
