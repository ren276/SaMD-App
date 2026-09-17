package com.example.samdapp.data.assessment

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pins `ExistingWorkPolicy.KEEP` on the assessment queue.
 *
 * This exists because of a navigation claim, not a WorkManager one. `SendingRoute` is safe to
 * restore into after process death, and it is safe for exactly one reason: `SendingViewModel.init`
 * enqueues the assessment again, and that re-enqueue is a no-op for a case already queued or
 * running. The restore design memo called that out as the one route whose safety rests on an
 * existing deliberate design choice rather than on anything the restore work added, and said in as
 * many words that a test must pin it. Nothing did.
 *
 * What breaks if it is not pinned: someone changes `KEEP` to `REPLACE` to match the sync outbox
 * (the two schedulers are deliberately near-identical, and the outbox does use `REPLACE`), and
 * every restore into `SendingRoute` then cancels an in-flight assessment and restarts it. There is
 * no other test anywhere that would fail.
 */
@RunWith(AndroidJUnit4::class)
class AssessmentSchedulerIdempotencyTest {

    private lateinit var workManager: WorkManager
    private lateinit var scheduler: WorkManagerAssessmentScheduler

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Synchronous executor so enqueue completes before the assertions, and no initial delay
        // handling, which keeps this about the policy rather than about scheduling timing.
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setExecutor(SynchronousExecutor())
                .build(),
        )
        workManager = WorkManager.getInstance(context)
        scheduler = WorkManagerAssessmentScheduler(context)
    }

    @Test
    fun aSecondEnqueueForTheSameCaseDoesNotReplaceTheFirst() {
        val caseRecordId = "case-1"

        scheduler.enqueueAssessment(caseRecordId)
        val afterFirst = workManager.getWorkInfosForUniqueWork(WorkManagerAssessmentScheduler.uniqueWorkName(caseRecordId)).get()
        assertEquals("The first enqueue should produce exactly one work item", 1, afterFirst.size)
        val firstId = afterFirst.single().id

        // What SendingViewModel.init does again on every restore into SendingRoute.
        scheduler.enqueueAssessment(caseRecordId)

        val afterSecond = workManager.getWorkInfosForUniqueWork(WorkManagerAssessmentScheduler.uniqueWorkName(caseRecordId)).get()
        assertEquals("A re-enqueue must not add a second work item", 1, afterSecond.size)
        assertEquals(
            "A re-enqueue must KEEP the first attempt, not cancel and REPLACE it. If this fails, " +
                "check ExistingWorkPolicy in WorkManagerAssessmentScheduler: restoring into " +
                "SendingRoute would now restart an in-flight assessment.",
            firstId,
            afterSecond.single().id,
        )
        assertTrue(
            "And the original work must not have been cancelled by the second enqueue",
            afterSecond.single().state != WorkInfo.State.CANCELLED,
        )
    }

    /** KEEP is per case, not global: a different case must still get its own work. Without this,
     *  a scheduler that simply ignored every enqueue after the first would also pass the test
     *  above. */
    @Test
    fun aDifferentCaseGetsItsOwnWork() {
        scheduler.enqueueAssessment("case-1")
        scheduler.enqueueAssessment("case-2")

        assertEquals(1, workManager.getWorkInfosForUniqueWork(WorkManagerAssessmentScheduler.uniqueWorkName("case-1")).get().size)
        assertEquals(1, workManager.getWorkInfosForUniqueWork(WorkManagerAssessmentScheduler.uniqueWorkName("case-2")).get().size)
    }
}
