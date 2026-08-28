package com.example.samdapp.presentation.kernelassessment

import com.example.samdapp.data.assessment.AssessmentWorkState
import com.example.samdapp.domain.model.InferenceSource
import com.example.samdapp.testutil.FakeAssessmentQueueScheduler
import com.example.samdapp.testutil.FakeAuditLogger
import com.example.samdapp.testutil.FakeEvaluateReportRepository
import com.example.samdapp.testutil.FakeKernelReportRepository
import com.example.samdapp.testutil.MainDispatcherRule
import com.example.samdapp.testutil.testKernelReportOutput
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Async submission queue: this screen's report reads are a collected Flow, not a one-shot read
 * (mandatory per the async-queue design memo — a one-shot read renders an empty state forever
 * once the queue is async), and retry is a fire-and-forget enqueue through
 * [com.example.samdapp.data.assessment.AssessmentQueueScheduler], not an inline re-run. The
 * assessment itself ([com.example.samdapp.domain.usecase.AssessmentRunner]) is exercised
 * separately in `AssessmentRunnerTest` — this class only has to prove it renders whatever the
 * repositories and the scheduler's [AssessmentWorkState] say, as they change.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KernelAssessmentViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun viewModel(
        caseRecordId: String,
        kernelReportRepository: FakeKernelReportRepository = FakeKernelReportRepository(),
        evaluateReportRepository: FakeEvaluateReportRepository = FakeEvaluateReportRepository(),
        scheduler: FakeAssessmentQueueScheduler = FakeAssessmentQueueScheduler(),
    ): KernelAssessmentViewModel = KernelAssessmentViewModel(
        caseRecordId = caseRecordId,
        evaluateReportRepository = evaluateReportRepository,
        kernelReportRepository = kernelReportRepository,
        assessmentQueueScheduler = scheduler,
        auditLogger = FakeAuditLogger(),
    )

    @Test
    fun `UNAVAILABLE kernel result maps to isUnavailable true and a distinguishing source label`() = runTest(mainDispatcherRule.dispatcher) {
        val repo = FakeKernelReportRepository().apply {
            saved["case-1"] = testKernelReportOutput("case-1", InferenceSource.UNAVAILABLE, predictedCondition = "Assessment unavailable", requiredHumanVerification = true)
        }
        val vm = viewModel("case-1", repo)

        advanceUntilIdle()

        val display = vm.uiState.value.display
        requireNotNull(display)
        assertFalse(vm.uiState.value.isLoading)
        assertTrue(display.isUnavailable)
        assertFalse(display.isMockFallback)
        assertTrue(display.sourceLabel.contains("unavailable", ignoreCase = true))
    }

    @Test
    fun `REAL_INFERENCE kernel result is not flagged unavailable or mock`() = runTest(mainDispatcherRule.dispatcher) {
        val repo = FakeKernelReportRepository().apply {
            saved["case-1"] = testKernelReportOutput("case-1", InferenceSource.REAL_INFERENCE)
        }
        val vm = viewModel("case-1", repo)

        advanceUntilIdle()

        val display = vm.uiState.value.display
        requireNotNull(display)
        assertFalse(display.isUnavailable)
        assertFalse(display.isMockFallback)
    }

    @Test
    fun `no report yet but work is live shows a loading state, not the empty-forever or stalled state`() = runTest(mainDispatcherRule.dispatcher) {
        val scheduler = FakeAssessmentQueueScheduler().apply { setWorkState("case-1", AssessmentWorkState.RUNNING) }
        val vm = viewModel("case-1", scheduler = scheduler)

        advanceUntilIdle()

        assertTrue(vm.uiState.value.isLoading)
        assertEquals(null, vm.uiState.value.display)
    }

    @Test
    fun `no report and no live work is stalled, and renders the same retry affordance as an UNAVAILABLE row`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel("case-1")

        advanceUntilIdle()

        val display = vm.uiState.value.display
        requireNotNull(display)
        assertFalse(vm.uiState.value.isLoading)
        assertTrue(display.isUnavailable)
    }

    @Test
    fun `the Flow conversion emits once the row lands, from a loading state that had nothing yet`() = runTest(mainDispatcherRule.dispatcher) {
        val repo = FakeKernelReportRepository()
        val scheduler = FakeAssessmentQueueScheduler().apply { setWorkState("case-1", AssessmentWorkState.RUNNING) }
        val vm = viewModel("case-1", repo, scheduler = scheduler)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.isLoading)

        // The worker finishes: it writes the row, then the WorkInfo settles to NONE — mirrors
        // AssessmentWorker's actual order (AssessmentRunner.run's save happens before doWork()
        // returns, which is what flips WorkInfo terminal). save(), not direct map mutation, so
        // the fake's Flow actually emits the change, the same way a real Room upsert would.
        repo.save(testKernelReportOutput("case-1", InferenceSource.REAL_INFERENCE))
        scheduler.setWorkState("case-1", AssessmentWorkState.NONE)
        advanceUntilIdle()

        val display = vm.uiState.value.display
        requireNotNull(display)
        assertFalse(vm.uiState.value.isLoading)
        assertFalse(display.isUnavailable)
    }

    @Test
    fun `retry enqueues the same case through the scheduler rather than running inline`() = runTest(mainDispatcherRule.dispatcher) {
        val repo = FakeKernelReportRepository().apply {
            saved["case-1"] = testKernelReportOutput("case-1", InferenceSource.UNAVAILABLE)
        }
        val scheduler = FakeAssessmentQueueScheduler()
        val vm = viewModel("case-1", repo, scheduler = scheduler)
        advanceUntilIdle()

        vm.onRetry()

        assertEquals(listOf("case-1"), scheduler.enqueued)
    }

    @Test
    fun `while a retry is live over a previously-unavailable display, isRetrying is true`() = runTest(mainDispatcherRule.dispatcher) {
        val repo = FakeKernelReportRepository().apply {
            saved["case-1"] = testKernelReportOutput("case-1", InferenceSource.UNAVAILABLE)
        }
        val scheduler = FakeAssessmentQueueScheduler()
        val vm = viewModel("case-1", repo, scheduler = scheduler)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.isRetrying)

        vm.onRetry()
        scheduler.setWorkState("case-1", AssessmentWorkState.RUNNING)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.isRetrying)
        assertTrue(vm.uiState.value.display!!.isUnavailable)
    }

    @Test
    fun `retry that succeeds updates the display to REAL_INFERENCE and clears isUnavailable`() = runTest(mainDispatcherRule.dispatcher) {
        val repo = FakeKernelReportRepository().apply {
            saved["case-1"] = testKernelReportOutput("case-1", InferenceSource.UNAVAILABLE)
        }
        val scheduler = FakeAssessmentQueueScheduler()
        val vm = viewModel("case-1", repo, scheduler = scheduler)
        advanceUntilIdle()

        vm.onRetry()
        scheduler.setWorkState("case-1", AssessmentWorkState.RUNNING)
        advanceUntilIdle()
        repo.save(testKernelReportOutput("case-1", InferenceSource.REAL_INFERENCE, predictedCondition = "Viral fever"))
        scheduler.setWorkState("case-1", AssessmentWorkState.NONE)
        advanceUntilIdle()

        val display = vm.uiState.value.display
        requireNotNull(display)
        assertFalse(vm.uiState.value.isRetrying)
        assertFalse(display.isUnavailable)
        assertEquals("Viral fever", display.predictedCondition)
    }

    @Test
    fun `retry that fails again stays honestly unavailable, not a silently kept-stale display`() = runTest(mainDispatcherRule.dispatcher) {
        val repo = FakeKernelReportRepository().apply {
            saved["case-1"] = testKernelReportOutput(
                "case-1",
                InferenceSource.UNAVAILABLE,
                predictedCondition = "STALE - should not survive retry",
            )
        }
        val scheduler = FakeAssessmentQueueScheduler()
        val vm = viewModel("case-1", repo, scheduler = scheduler)
        advanceUntilIdle()

        vm.onRetry()
        scheduler.setWorkState("case-1", AssessmentWorkState.RUNNING)
        advanceUntilIdle()
        repo.save(testKernelReportOutput("case-1", InferenceSource.UNAVAILABLE, predictedCondition = "Assessment unavailable"))
        scheduler.setWorkState("case-1", AssessmentWorkState.NONE)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.display!!.isUnavailable)
        assertTrue(vm.uiState.value.display!!.predictedCondition != "STALE - should not survive retry")
        assertFalse(vm.uiState.value.isRetrying)
    }
}
