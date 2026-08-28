package com.example.samdapp.data.assessment

import kotlinx.coroutines.flow.Flow

/** Whether the async submission queue currently has live work for a case — the WorkInfo-derived
 *  half of "is this case's assessment queued, processing, or done" (the other half is whether a
 *  report row exists yet, read separately from [com.example.samdapp.domain.repository
 *  .KernelReportRepository]/[com.example.samdapp.domain.repository.EvaluateReportRepository]).
 *  Display-only, like the day-ordinal receipt: never used to look a case up, never a substitute
 *  for [caseRecordId]. */
enum class AssessmentWorkState { QUEUED, RUNNING, NONE }

/** Seam between [com.example.samdapp.presentation.sending.SendingViewModel] (and any retry
 *  affordance) and WorkManager, mirroring
 *  [com.example.samdapp.data.sync.SyncOutboxScheduler]'s reason for existing: the JVM-testable
 *  boundary around a dependency ([androidx.work.WorkManager]) that needs a `Context` and its own
 *  instrumented test harness. */
interface AssessmentQueueScheduler {
    /** Enqueues the connectivity-constrained, backoff-retried assessment for [caseRecordId] —
     *  or, if one is already enqueued or running for this case, leaves it alone
     *  (`ExistingWorkPolicy.KEEP`). Fire-and-forget: does not suspend for the result. Safe to call
     *  for a first assessment or a retry — both are the same unique work keyed on [caseRecordId],
     *  which is what keeps a retry from ever running concurrently with an assessment already in
     *  flight for the same case (the concurrency precondition the kernel/evaluate upsert race
     *  depends on). */
    fun enqueueAssessment(caseRecordId: String)

    /** Live [AssessmentWorkState] for [caseRecordId]'s unique assessment work, so a case with no
     *  report row yet can be told apart as still processing (show a wait state) versus genuinely
     *  stalled (no row, nothing running — offer the same retry affordance an UNAVAILABLE row
     *  already offers, not a second failure-looking state). */
    fun observeWorkState(caseRecordId: String): Flow<AssessmentWorkState>
}
