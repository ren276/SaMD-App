package com.example.samdapp.presentation.kernelassessment

import com.example.samdapp.data.assessment.AssessmentWorkState
import com.example.samdapp.domain.model.InferenceSource
import com.example.samdapp.testutil.FakeAssessmentQueueScheduler
import com.example.samdapp.domain.model.Attachment
import com.example.samdapp.domain.model.AttachmentType
import com.example.samdapp.domain.auth.CadreTier
import com.example.samdapp.domain.auth.UserSession
import com.example.samdapp.domain.auth.UserRole
import com.example.samdapp.testutil.FakeAuditLogger
import com.example.samdapp.testutil.FakeAuthSession
import com.example.samdapp.testutil.FakeConsultationRepository
import com.example.samdapp.testutil.testConsultation
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
import java.time.Instant

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
        consultationRepository: FakeConsultationRepository = FakeConsultationRepository(),
        authSession: FakeAuthSession = FakeAuthSession(),
        networkMonitor: com.example.samdapp.testutil.FakeNetworkMonitor = com.example.samdapp.testutil.FakeNetworkMonitor(),
    ): KernelAssessmentViewModel = KernelAssessmentViewModel(
        caseRecordId = caseRecordId,
        consultationId = "consult-$caseRecordId",
        evaluateReportRepository = evaluateReportRepository,
        kernelReportRepository = kernelReportRepository,
        consultationRepository = consultationRepository,
        assessmentQueueScheduler = scheduler,
        auditLogger = FakeAuditLogger(),
        authSession = authSession,
        networkMonitor = networkMonitor,
    )

    private fun tierFor(role: UserRole?): CadreTier {
        val session = role?.let { UserSession(userId = "u", name = "n", role = it) }
        val vm = viewModel("case-1", authSession = FakeAuthSession(session))
        mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()
        return vm.uiState.value.cadreTier
    }

    @Test
    fun `a null session fails closed to a worker tier and the score is hidden`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel("case-1", authSession = FakeAuthSession(null))
        advanceUntilIdle()

        assertEquals(CadreTier.COMMUNITY, vm.uiState.value.cadreTier)
        assertFalse(vm.uiState.value.showModelScore)
    }

    @Test
    fun `only the physician tier is shown the model score`() = runTest(mainDispatcherRule.dispatcher) {
        advanceUntilIdle()
        assertEquals(CadreTier.PHYSICIAN, tierFor(UserRole.DOCTOR))
        assertEquals(CadreTier.LICENSED_CLINICAL, tierFor(UserRole.NURSE))
        assertEquals(CadreTier.LICENSED_CLINICAL, tierFor(UserRole.COMPOUNDER))
        assertEquals(CadreTier.COMMUNITY, tierFor(UserRole.ASHA_WORKER))
        assertTrue(KernelAssessmentUiState(cadreTier = CadreTier.PHYSICIAN).showModelScore)
        assertFalse(KernelAssessmentUiState(cadreTier = CadreTier.LICENSED_CLINICAL).showModelScore)
    }

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

    /**
     * The audio leg is now decided by the consultation's own AUDIO attachment row rather than by
     * a uri carried through three routes. `firstOrNull` matches the semantics the route argument
     * had (`pendingAttachments.firstOrNull { it.type == AUDIO }`), so a worker who recorded twice
     * still gets their FIRST take, not their last.
     */
    @Test
    fun `audioUri resolves from the consultation's first AUDIO attachment row`() = runTest(mainDispatcherRule.dispatcher) {
        val consultation = testConsultation(
            encounterId = "case-1",
            attachments = listOf(
                Attachment("att-1", "consult-case-1", AttachmentType.IMAGE, "file:///photo.jpg", Instant.EPOCH),
                Attachment("att-2", "consult-case-1", AttachmentType.AUDIO, "file:///first-take.m4a", Instant.EPOCH),
                Attachment("att-3", "consult-case-1", AttachmentType.AUDIO, "file:///second-take.m4a", Instant.EPOCH),
            ),
        )
        val vm = viewModel(
            "case-1",
            consultationRepository = FakeConsultationRepository(byEncounter = mapOf("case-1" to consultation)),
        )

        advanceUntilIdle()

        assertEquals("file:///first-take.m4a", vm.uiState.value.audioUri)
    }

    /** No attachment row means no audio leg: the case goes straight to Acknowledgement. A uri that
     *  survived three screens was never evidence the attachment was persisted; the row is. */
    @Test
    fun `audioUri is null when the consultation has no audio attachment`() = runTest(mainDispatcherRule.dispatcher) {
        val consultation = testConsultation(encounterId = "case-1")
        val vm = viewModel(
            "case-1",
            consultationRepository = FakeConsultationRepository(byEncounter = mapOf("case-1" to consultation)),
        )

        advanceUntilIdle()

        assertEquals(null, vm.uiState.value.audioUri)
    }

    @Test
    fun `queued with no network says it is waiting for a connection, not an endless spinner`() = runTest(mainDispatcherRule.dispatcher) {
        // The job waits on NetworkType.CONNECTED, which is the OS network, so that is what the
        // screen reads. On master this state was a bare spinner with nothing said.
        val scheduler = FakeAssessmentQueueScheduler().apply { setWorkState("case-1", AssessmentWorkState.QUEUED) }
        val network = com.example.samdapp.testutil.FakeNetworkMonitor(initial = false)
        val viewModel = viewModel("case-1", scheduler = scheduler, networkMonitor = network)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.waitingForNetwork)

        network.setAvailable(true)
        advanceUntilIdle()

        assertFalse("back online, the queued job runs: an ordinary wait", viewModel.uiState.value.waitingForNetwork)
        assertTrue(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `a report on screen is never covered by the waiting state`() = runTest(mainDispatcherRule.dispatcher) {
        val kernel = FakeKernelReportRepository().apply {
            save(com.example.samdapp.testutil.testKernelReportOutput("case-1", com.example.samdapp.domain.model.InferenceSource.REAL_INFERENCE))
        }
        val scheduler = FakeAssessmentQueueScheduler().apply { setWorkState("case-1", AssessmentWorkState.QUEUED) }
        val viewModel = viewModel(
            "case-1", kernelReportRepository = kernel, scheduler = scheduler,
            networkMonitor = com.example.samdapp.testutil.FakeNetworkMonitor(initial = false),
        )
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.waitingForNetwork)
    }

    @Test
    fun `tried again with a network says the visit is being sent first, and without one it still says waiting`() = runTest(mainDispatcherRule.dispatcher) {
        val scheduler = FakeAssessmentQueueScheduler().apply { setWorkState("case-1", AssessmentWorkState.RETRYING) }
        val network = com.example.samdapp.testutil.FakeNetworkMonitor(initial = true)
        val viewModel = viewModel("case-1", scheduler = scheduler, networkMonitor = network)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.sendingFirst)
        assertFalse(viewModel.uiState.value.waitingForNetwork)
        assertTrue(viewModel.uiState.value.isLoading)

        network.setAvailable(false)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.waitingForNetwork)
        assertFalse(viewModel.uiState.value.sendingFirst)
    }

    @Test
    fun `a first attempt that is running is an ordinary check, not sending first`() = runTest(mainDispatcherRule.dispatcher) {
        val scheduler = FakeAssessmentQueueScheduler().apply { setWorkState("case-1", AssessmentWorkState.RUNNING) }
        val viewModel = viewModel("case-1", scheduler = scheduler)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.sendingFirst)
        assertFalse(viewModel.uiState.value.waitingForNetwork)
    }

    @Test
    fun `no retry affordance shows while the retries run, and the not-sent-yet card only after the cap`() = runTest(mainDispatcherRule.dispatcher) {
        val kernel = FakeKernelReportRepository()
        val scheduler = FakeAssessmentQueueScheduler().apply { setWorkState("case-1", AssessmentWorkState.RETRYING) }
        val viewModel = viewModel("case-1", kernelReportRepository = kernel, scheduler = scheduler)
        advanceUntilIdle()
        assertEquals("no row yet, so no card and no Try again", null, viewModel.uiState.value.display)

        kernel.save(
            com.example.samdapp.testutil.testKernelReportOutput("case-1", com.example.samdapp.domain.model.InferenceSource.UNAVAILABLE)
                .copy(failureCode = com.example.samdapp.domain.kernel.KernelFailure.CASE_NOT_SENT_YET),
        )
        scheduler.setWorkState("case-1", AssessmentWorkState.NONE)
        advanceUntilIdle()

        val display = requireNotNull(viewModel.uiState.value.display)
        assertTrue(display.isUnavailable)
        assertFalse(viewModel.uiState.value.sendingFirst)
        assertFalse(viewModel.uiState.value.isRetrying)
    }

}
