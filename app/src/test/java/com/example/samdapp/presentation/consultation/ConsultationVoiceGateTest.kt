package com.example.samdapp.presentation.consultation

import com.example.samdapp.domain.model.FieldProvenance
import com.example.samdapp.domain.transcription.CapturedAudio
import com.example.samdapp.domain.usecase.AddAttachmentUseCase
import com.example.samdapp.domain.usecase.CaptureAudioAttachmentUseCase
import com.example.samdapp.domain.usecase.SaveConsultationUseCase
import com.example.samdapp.testutil.FakeAuditLogger
import com.example.samdapp.testutil.FakeConsultationRepository
import com.example.samdapp.testutil.FakeTranscriptionService
import com.example.samdapp.testutil.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * PR 3b: the voice confirmation-gate state model for `impactOnDailyActivities`
 * (`scratchpad/pr3-voice-gate-design-memo.md` Part A, honest-failure edges in Part B.3).
 *
 * Nothing in the UI calls these handlers yet. The mic button, the suggestion surface and the
 * feature flag that gates them are a later step, so these tests are the only exercise the gate
 * gets in this build, which is why they cover the invariants rather than just the happy path.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConsultationVoiceGateTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun newViewModel(
        consultationRepository: FakeConsultationRepository = FakeConsultationRepository(),
        transcriptionService: FakeTranscriptionService = FakeTranscriptionService(),
    ) = ConsultationViewModel(
        patientId = "p1",
        encounterId = "enc1",
        caseRecordId = "case1",
        initialChiefComplaint = "fever",
        saveConsultationUseCase = SaveConsultationUseCase(consultationRepository),
        addAttachmentUseCase = AddAttachmentUseCase(consultationRepository),
        captureAudioAttachmentUseCase = CaptureAudioAttachmentUseCase(transcriptionService),
        auditLogger = FakeAuditLogger(),
    )

    private fun serviceReturning(transcript: String) = FakeTranscriptionService(
        Result.success(CapturedAudio(uri = "speech-session://fake", transcript = transcript)),
    )

    private fun serviceFailing() = FakeTranscriptionService(
        Result.failure(IllegalStateException("Speech recognition error code 7")),
    )

    // ── The suggestion sits beside the field, never in it ────────────────────────────────────

    @Test
    fun `a captured transcript lands beside the field, leaving the committed value untouched`() =
        runTest(mainDispatcherRule.dispatcher) {
            val service = serviceReturning("cannot lift water buckets")
            val viewModel = newViewModel(transcriptionService = service)
            viewModel.onImpactChange("typed earlier")

            viewModel.onRecordImpactVoice()
            advanceUntilIdle()

            assertEquals(1, service.captureAudioAttachmentCallCount)
            assertEquals("cannot lift water buckets", viewModel.uiState.value.impactVoiceSuggestion)
            assertEquals("typed earlier", viewModel.uiState.value.impactOnDailyActivities)
            assertFalse(viewModel.uiState.value.isCapturingImpactVoice)
        }

    // ── canSend is false while the gate is open ──────────────────────────────────────────────

    @Test
    fun `canSend is false while a suggestion is outstanding and true again after discard`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = newViewModel(transcriptionService = serviceReturning("some impact"))
            assertTrue("precondition: sendable before the gate opens", viewModel.uiState.value.canSend)

            viewModel.onRecordImpactVoice()
            advanceUntilIdle()
            assertFalse("an outstanding suggestion must block send", viewModel.uiState.value.canSend)

            viewModel.onDiscardImpactSuggestion()
            assertTrue(viewModel.uiState.value.canSend)
        }

    /**
     * Asserted against the state object rather than by driving the ViewModel, because
     * [MainDispatcherRule] uses an `UnconfinedTestDispatcher`: the capture coroutine runs eagerly
     * to completion, so the Capturing state is never observable from outside. `canSend` is a pure
     * derived property, so testing it directly tests the guard clause itself rather than racing
     * the dispatcher for a glimpse of an intermediate state.
     */
    @Test
    fun `canSend is false while the mic is live`() {
        val capturing = ConsultationUiState(chiefComplaint = "fever", isCapturingImpactVoice = true)

        assertFalse("a live mic must block send", capturing.canSend)
        assertTrue("precondition: the same state is sendable once the mic is off", capturing.copy(isCapturingImpactVoice = false).canSend)
    }

    // ── The three resolutions from Suggested ─────────────────────────────────────────────────

    @Test
    fun `Use it commits the suggestion as VOICE_CONFIRMED and clears it`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = newViewModel(transcriptionService = serviceReturning("cannot walk far"))
            viewModel.onRecordImpactVoice()
            advanceUntilIdle()

            viewModel.onUseImpactSuggestion()

            val state = viewModel.uiState.value
            assertEquals("cannot walk far", state.impactOnDailyActivities)
            assertEquals(FieldProvenance.VOICE_CONFIRMED, state.impactProvenance)
            assertNull(state.impactVoiceSuggestion)
        }

    @Test
    fun `Edit commits the suggestion as VOICE_EDITED and clears it`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = newViewModel(transcriptionService = serviceReturning("cannot walk far"))
            viewModel.onRecordImpactVoice()
            advanceUntilIdle()

            viewModel.onEditImpactSuggestion()

            val state = viewModel.uiState.value
            assertEquals("cannot walk far", state.impactOnDailyActivities)
            assertEquals(FieldProvenance.VOICE_EDITED, state.impactProvenance)
            assertNull(state.impactVoiceSuggestion)
        }

    @Test
    fun `Discard leaves the field and provenance untouched`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = newViewModel(transcriptionService = serviceReturning("misheard text"))
            viewModel.onImpactChange("what the worker typed")
            viewModel.onRecordImpactVoice()
            advanceUntilIdle()

            viewModel.onDiscardImpactSuggestion()

            val state = viewModel.uiState.value
            assertEquals("what the worker typed", state.impactOnDailyActivities)
            assertNull("a discarded suggestion stamps no provenance", state.impactProvenance)
            assertNull(state.impactVoiceSuggestion)
        }

    // ── Honest-failure edges (design memo B.3) ───────────────────────────────────────────────

    @Test
    fun `an ASR error leaves the field untouched and never enters Suggested`() =
        runTest(mainDispatcherRule.dispatcher) {
            val service = serviceFailing()
            val viewModel = newViewModel(transcriptionService = service)
            viewModel.onImpactChange("what the worker typed")

            viewModel.onRecordImpactVoice()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(1, service.captureAudioAttachmentCallCount)
            assertNull("a failed capture must not open the gate", state.impactVoiceSuggestion)
            assertEquals("what the worker typed", state.impactOnDailyActivities)
            assertNull(state.impactProvenance)
            assertFalse(state.isCapturingImpactVoice)
            assertTrue("the worker is told the capture failed", state.errorMessage != null)
        }

    /**
     * The edge a build most often misses. `AndroidSpeechRecognizerService` reads the results list
     * and calls `.orEmpty()`, so a **successful** recognition can carry "". Showing that as a
     * suggestion would put the worker in front of an empty gate, and accepting it would commit an
     * empty string over whatever they had typed. Same shape as the empty-differential-200 bug
     * this repo already fixed once: a success carrying nothing usable is a failure.
     */
    @Test
    fun `an empty transcript on a successful capture never enters Suggested`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = newViewModel(transcriptionService = serviceReturning(""))
            viewModel.onImpactChange("what the worker typed")

            viewModel.onRecordImpactVoice()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertNull("an empty transcript must not become a suggestion", state.impactVoiceSuggestion)
            assertEquals("what the worker typed", state.impactOnDailyActivities)
            assertNull(state.impactProvenance)
            assertFalse(state.isCapturingImpactVoice)
        }

    @Test
    fun `a whitespace-only transcript is treated the same as an empty one`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = newViewModel(transcriptionService = serviceReturning("   "))

            viewModel.onRecordImpactVoice()
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.impactVoiceSuggestion)
        }

    // ── Provenance transitions for keyboard edits (design memo A.3) ──────────────────────────

    @Test
    fun `hand-correcting a confirmed voice value makes it VOICE_EDITED`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = newViewModel(transcriptionService = serviceReturning("cannot walk far"))
            viewModel.onRecordImpactVoice()
            advanceUntilIdle()
            viewModel.onUseImpactSuggestion()

            viewModel.onImpactChange("cannot walk far without help")

            assertEquals(FieldProvenance.VOICE_EDITED, viewModel.uiState.value.impactProvenance)
        }

    @Test
    fun `clearing the field resets provenance to null`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel(transcriptionService = serviceReturning("cannot walk far"))
        viewModel.onRecordImpactVoice()
        advanceUntilIdle()
        viewModel.onUseImpactSuggestion()

        viewModel.onImpactChange("")

        assertNull("an empty field has no provenance to record", viewModel.uiState.value.impactProvenance)
    }

    @Test
    fun `a typed value still saves as TYPED, unchanged from before the gate`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeConsultationRepository()
            val viewModel = newViewModel(consultationRepository = repository)
            viewModel.onImpactChange("typed by hand")

            viewModel.onSend()
            advanceUntilIdle()

            assertEquals(1, repository.saved.size)
            assertEquals(FieldProvenance.TYPED, repository.saved.single().impactOnDailyActivitiesProvenance)
        }

    // ── The provenance actually reaches the persisted object ─────────────────────────────────

    @Test
    fun `a confirmed voice value is persisted carrying VOICE_CONFIRMED`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeConsultationRepository()
            val viewModel = newViewModel(
                consultationRepository = repository,
                transcriptionService = serviceReturning("cannot lift water buckets"),
            )
            viewModel.onRecordImpactVoice()
            advanceUntilIdle()
            viewModel.onUseImpactSuggestion()

            viewModel.onSend()
            advanceUntilIdle()

            val saved = repository.saved.single()
            assertEquals("cannot lift water buckets", saved.impactOnDailyActivities)
            assertEquals(FieldProvenance.VOICE_CONFIRMED, saved.impactOnDailyActivitiesProvenance)
        }

    @Test
    fun `an outstanding suggestion cannot be sent past the gate`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeConsultationRepository()
            val viewModel = newViewModel(
                consultationRepository = repository,
                transcriptionService = serviceReturning("unconfirmed text"),
            )
            viewModel.onRecordImpactVoice()
            advanceUntilIdle()

            viewModel.onSend()
            advanceUntilIdle()

            assertTrue("onSend must refuse while a suggestion is outstanding", repository.saved.isEmpty())
            assertEquals("unconfirmed text", viewModel.uiState.value.impactVoiceSuggestion)
        }
}
