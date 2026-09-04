package com.example.samdapp.presentation.consultation

import com.example.samdapp.domain.usecase.AddAttachmentUseCase
import com.example.samdapp.domain.usecase.CaptureAudioAttachmentUseCase
import com.example.samdapp.domain.usecase.SaveConsultationUseCase
import com.example.samdapp.domain.usecase.UploadConsultationDocumentUseCase
import com.example.samdapp.testutil.FakeAuditLogger
import com.example.samdapp.testutil.FakeAuthSession
import com.example.samdapp.testutil.FakeConsultationDocumentRepository
import com.example.samdapp.testutil.FakeDocumentCaptureStore
import com.example.samdapp.testutil.FakeConsultationRepository
import com.example.samdapp.testutil.FakeTranscriptionService
import com.example.samdapp.testutil.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** chiefComplaint stays fully keyboard-usable while FeatureFlags.VOICE_INPUT_ENABLED is off, and
 *  the voice handlers stop before ever reaching TranscriptionService.
 *
 *  Originally written for fix/asr-offdevice-exposure, when the risk being held back was the
 *  platform recogniser's off-device transmission. PR 4a deleted that class outright and bound the
 *  on-device engine in its place, so the exposure this comment used to name no longer exists.
 *  VOICE_INPUT_ENABLED stays false for the reason its own KDoc gives: chiefComplaint reaches
 *  /api/v1/evaluate and is governed by no confirmation gate. */
@OptIn(ExperimentalCoroutinesApi::class)
class ConsultationViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun newViewModel(
        consultationRepository: FakeConsultationRepository = FakeConsultationRepository(),
        transcriptionService: FakeTranscriptionService = FakeTranscriptionService(),
        auditLogger: FakeAuditLogger = FakeAuditLogger(),
        documentCaptureStore: FakeDocumentCaptureStore = FakeDocumentCaptureStore(),
    ) = ConsultationViewModel(
        patientId = "p1",
        encounterId = "enc1",
        caseRecordId = "case1",
        initialChiefComplaint = "",
        saveConsultationUseCase = SaveConsultationUseCase(consultationRepository),
        addAttachmentUseCase = AddAttachmentUseCase(consultationRepository),
        captureAudioAttachmentUseCase = CaptureAudioAttachmentUseCase(transcriptionService),
        uploadConsultationDocumentUseCase = UploadConsultationDocumentUseCase(FakeConsultationDocumentRepository(), auditLogger),
        documentCaptureStore = documentCaptureStore,
        authSession = FakeAuthSession(),
        auditLogger = auditLogger,
    )

    @Test
    fun `typed chief complaint entry updates state`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel()

        viewModel.onChiefComplaintChange("fever and cough")

        assertEquals("fever and cough", viewModel.uiState.value.chiefComplaint)
    }

    @Test
    fun `onRecordChiefComplaintVoice does not invoke the recognizer while voice input is disabled`() =
        runTest(mainDispatcherRule.dispatcher) {
            val transcriptionService = FakeTranscriptionService()
            val viewModel = newViewModel(transcriptionService = transcriptionService)
            viewModel.onChiefComplaintChange("typed value")

            viewModel.onRecordChiefComplaintVoice()
            advanceUntilIdle()

            assertEquals(0, transcriptionService.captureAudioAttachmentCallCount)
            assertEquals("typed value", viewModel.uiState.value.chiefComplaint)
            assertTrue(!viewModel.uiState.value.isRecordingVoice)
        }

    @Test
    fun `onRecordAudioAttachment does not invoke the recognizer while voice input is disabled`() =
        runTest(mainDispatcherRule.dispatcher) {
            val transcriptionService = FakeTranscriptionService()
            val viewModel = newViewModel(transcriptionService = transcriptionService)

            viewModel.onRecordAudioAttachment()
            advanceUntilIdle()

            assertEquals(0, transcriptionService.captureAudioAttachmentCallCount)
            assertTrue(viewModel.uiState.value.pendingAttachments.isEmpty())
        }
}

// Note: the onSuccess-branch fix in onRecordChiefComplaintVoice (dropping the transcript instead
// of writing it into chiefComplaint, field-audit memo C-1) is defense-in-depth for a branch this
// build cannot reach, because FeatureFlags.VOICE_INPUT_ENABLED is a compile-time const and the
// guard above returns before that branch runs. It is verified here by code inspection, not by a
// dedicated unit test, since making the flag injectable to exercise that branch in isolation
// would be scope beyond this fix. Re-verify it directly once PR 3/4 flips the flag on.
