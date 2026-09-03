package com.example.samdapp.presentation.consultation

import com.example.samdapp.domain.transcription.CapturedAudio
import com.example.samdapp.domain.usecase.AddAttachmentUseCase
import com.example.samdapp.domain.usecase.CaptureAudioAttachmentUseCase
import com.example.samdapp.domain.usecase.SaveConsultationUseCase
import com.example.samdapp.domain.usecase.UploadConsultationDocumentUseCase
import com.example.samdapp.testutil.FakeAuditLogger
import com.example.samdapp.testutil.FakeAuthSession
import com.example.samdapp.testutil.FakeConsultationDocumentRepository
import com.example.samdapp.testutil.FakeConsultationRepository
import com.example.samdapp.testutil.FakeTranscriptionService
import com.example.samdapp.testutil.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * PR 3d (`scratchpad/pr3-voice-gate-design-memo.md` Part C). Proves the four `VOICE_FIELD_*`
 * breadcrumbs emit at the right transitions with a metadata-only payload, and that the payload
 * never carries a transcript, corrected text, URI or patient name (C.4). The feature is still
 * dark (`FeatureFlags.VOICE_FIELD_IMPACT_ENABLED` is false), so these handlers have no caller in
 * a shipped build; this is the only place the emission logic is exercised in this build.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConsultationVoiceGateBreadcrumbsTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun payloadFields(json: String): Map<String, String?> =
        Json.parseToJsonElement(json).jsonObject.mapValues { (_, value) -> value.jsonPrimitive.contentOrNull }

    private fun newViewModel(
        auditLogger: FakeAuditLogger = FakeAuditLogger(),
        transcriptionService: FakeTranscriptionService = FakeTranscriptionService(),
        consultationRepository: FakeConsultationRepository = FakeConsultationRepository(),
    ) = ConsultationViewModel(
        patientId = "p1",
        encounterId = "enc1",
        caseRecordId = "case1",
        initialChiefComplaint = "fever",
        saveConsultationUseCase = SaveConsultationUseCase(consultationRepository),
        addAttachmentUseCase = AddAttachmentUseCase(consultationRepository),
        captureAudioAttachmentUseCase = CaptureAudioAttachmentUseCase(transcriptionService),
        uploadConsultationDocumentUseCase = UploadConsultationDocumentUseCase(FakeConsultationDocumentRepository(), auditLogger),
        authSession = FakeAuthSession(),
        auditLogger = auditLogger,
    )

    private fun serviceReturning(transcript: String) = FakeTranscriptionService(
        Result.success(CapturedAudio(uri = "speech-session://fake", transcript = transcript)),
    )

    private fun serviceFailing() = FakeTranscriptionService(
        Result.failure(IllegalStateException("Speech recognition error code 7")),
    )

    // ── SUGGESTED ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a non-blank transcript emits VOICE_FIELD_SUGGESTED with charCount and no dwellMs`() =
        runTest(mainDispatcherRule.dispatcher) {
            val auditLogger = FakeAuditLogger()
            val viewModel = newViewModel(
                auditLogger = auditLogger,
                transcriptionService = serviceReturning("cannot lift water buckets"),
            )

            viewModel.onRecordImpactVoice()
            advanceUntilIdle()

            val entry = auditLogger.logged.single()
            assertEquals("voice_field_suggested", entry.action)
            assertEquals("case1", entry.caseRecordId)
            assertEquals("p1", entry.patientId)
            val fields = payloadFields(entry.payload)
            assertEquals("IMPACT_ON_DAILY_ACTIVITIES", fields["slot"])
            assertEquals("VOICE_UNCONFIRMED", fields["provenance"])
            assertEquals("sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8", fields["asrModelId"])
            assertEquals("sherpa-onnx-1.13.7", fields["asrModelVersion"])
            assertEquals("cannot lift water buckets".length.toString(), fields["charCount"])
            assertNull("no dwellMs at the moment the suggestion is shown", fields["dwellMs"])
            assertNull("editDistance only applies to VOICE_FIELD_EDITED", fields["editDistance"])
        }

    // ── CONFIRMED ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `Use it emits VOICE_FIELD_CONFIRMED with charCount and a non-negative dwellMs`() =
        runTest(mainDispatcherRule.dispatcher) {
            val auditLogger = FakeAuditLogger()
            val viewModel = newViewModel(
                auditLogger = auditLogger,
                transcriptionService = serviceReturning("cannot lift water buckets"),
            )
            viewModel.onRecordImpactVoice()
            advanceUntilIdle()

            viewModel.onUseImpactSuggestion()
            advanceUntilIdle()

            val entry = auditLogger.logged.last()
            assertEquals("voice_field_confirmed", entry.action)
            val fields = payloadFields(entry.payload)
            assertEquals("VOICE_CONFIRMED", fields["provenance"])
            assertEquals("cannot lift water buckets".length.toString(), fields["charCount"])
            val dwellMs = fields["dwellMs"]?.toLongOrNull()
            assertTrue("dwellMs must be present and non-negative on Use it", dwellMs != null && dwellMs >= 0)
            assertNull(fields["editDistance"])
        }

    // ── EDITED, emitted at save ──────────────────────────────────────────────────────────────

    @Test
    fun `Edit does not emit at the tap`() = runTest(mainDispatcherRule.dispatcher) {
        val auditLogger = FakeAuditLogger()
        val viewModel = newViewModel(
            auditLogger = auditLogger,
            transcriptionService = serviceReturning("cannot lift water buckets"),
        )
        viewModel.onRecordImpactVoice()
        advanceUntilIdle()
        auditLogger.logged.clear()

        viewModel.onEditImpactSuggestion()

        assertTrue("Edit records a confirmed edit at save, not an abandoned one at the tap", auditLogger.logged.isEmpty())
    }

    @Test
    fun `saving an edited suggestion emits VOICE_FIELD_EDITED with editDistance and dwellMs`() =
        runTest(mainDispatcherRule.dispatcher) {
            val auditLogger = FakeAuditLogger()
            val viewModel = newViewModel(
                auditLogger = auditLogger,
                transcriptionService = serviceReturning("cannot lift water buckets"),
            )
            viewModel.onRecordImpactVoice()
            advanceUntilIdle()
            viewModel.onEditImpactSuggestion()
            viewModel.onImpactChange("cannot lift heavy water buckets")

            viewModel.onSend()
            advanceUntilIdle()

            val entry = auditLogger.logged.first { it.action == "voice_field_edited" }
            val fields = payloadFields(entry.payload)
            assertEquals("VOICE_EDITED", fields["provenance"])
            assertEquals("cannot lift heavy water buckets".length.toString(), fields["charCount"])
            val expectedDistance = com.example.samdapp.domain.audit.levenshteinDistance(
                "cannot lift water buckets",
                "cannot lift heavy water buckets",
            )
            assertEquals(expectedDistance.toString(), fields["editDistance"])
            val dwellMs = fields["dwellMs"]?.toLongOrNull()
            assertTrue("dwellMs must be present and non-negative on a saved edit", dwellMs != null && dwellMs >= 0)
        }

    @Test
    fun `saving a plain typed value never emits VOICE_FIELD_EDITED`() =
        runTest(mainDispatcherRule.dispatcher) {
            val auditLogger = FakeAuditLogger()
            val viewModel = newViewModel(auditLogger = auditLogger)
            viewModel.onImpactChange("typed by hand")

            viewModel.onSend()
            advanceUntilIdle()

            assertTrue(auditLogger.logged.none { it.action == "voice_field_edited" })
        }

    // ── REJECTED: discard, ASR error, empty-on-success ───────────────────────────────────────

    @Test
    fun `Discard emits VOICE_FIELD_REJECTED with the discarded suggestion's charCount and dwellMs`() =
        runTest(mainDispatcherRule.dispatcher) {
            val auditLogger = FakeAuditLogger()
            val viewModel = newViewModel(
                auditLogger = auditLogger,
                transcriptionService = serviceReturning("misheard text"),
            )
            viewModel.onRecordImpactVoice()
            advanceUntilIdle()

            viewModel.onDiscardImpactSuggestion()
            advanceUntilIdle()

            val entry = auditLogger.logged.last()
            assertEquals("voice_field_rejected", entry.action)
            val fields = payloadFields(entry.payload)
            assertEquals("VOICE_UNCONFIRMED", fields["provenance"])
            assertEquals("misheard text".length.toString(), fields["charCount"])
            val dwellMs = fields["dwellMs"]?.toLongOrNull()
            assertTrue("dwellMs must be present and non-negative on Discard", dwellMs != null && dwellMs >= 0)
        }

    @Test
    fun `an ASR error emits VOICE_FIELD_REJECTED with no charCount and no dwellMs`() =
        runTest(mainDispatcherRule.dispatcher) {
            val auditLogger = FakeAuditLogger()
            val viewModel = newViewModel(auditLogger = auditLogger, transcriptionService = serviceFailing())

            viewModel.onRecordImpactVoice()
            advanceUntilIdle()

            val entry = auditLogger.logged.single()
            assertEquals("voice_field_rejected", entry.action)
            val fields = payloadFields(entry.payload)
            assertNull("no transcript was ever produced", fields["charCount"])
            assertNull("no suggestion was shown, so no dwell interval exists", fields["dwellMs"])
        }

    @Test
    fun `an empty transcript on success emits VOICE_FIELD_REJECTED with charCount zero and no dwellMs`() =
        runTest(mainDispatcherRule.dispatcher) {
            val auditLogger = FakeAuditLogger()
            val viewModel = newViewModel(auditLogger = auditLogger, transcriptionService = serviceReturning(""))

            viewModel.onRecordImpactVoice()
            advanceUntilIdle()

            val entry = auditLogger.logged.single()
            assertEquals("voice_field_rejected", entry.action)
            val fields = payloadFields(entry.payload)
            assertEquals("0", fields["charCount"])
            assertNull("no suggestion was shown, so no dwell interval exists", fields["dwellMs"])
        }

    // ── C.4 guard: no content or PHI in any payload ──────────────────────────────────────────

    @Test
    fun `no payload ever carries the transcript, the corrected text, a uri, or the patient id`() =
        runTest(mainDispatcherRule.dispatcher) {
            val auditLogger = FakeAuditLogger()
            val transcript = "cannot lift water buckets because of the shoulder injury"
            val correctedText = "cannot lift water buckets because of a shoulder injury, worse in the morning"
            val viewModel = newViewModel(auditLogger = auditLogger, transcriptionService = serviceReturning(transcript))

            // Walk every emitting transition in one run: SUGGESTED, then Edit (deferred, no
            // emission yet), then a further keyboard correction, then save (EDITED).
            viewModel.onRecordImpactVoice()
            advanceUntilIdle()
            viewModel.onEditImpactSuggestion()
            viewModel.onImpactChange(correctedText)
            viewModel.onSend()
            advanceUntilIdle()

            assertTrue("at least SUGGESTED and EDITED must have emitted", auditLogger.logged.size >= 2)
            auditLogger.logged.forEach { entry ->
                assertFalse(
                    "payload for ${entry.action} must never contain the transcript",
                    entry.payload.contains(transcript),
                )
                assertFalse(
                    "payload for ${entry.action} must never contain the corrected text",
                    entry.payload.contains(correctedText),
                )
                assertFalse(
                    "payload for ${entry.action} must never contain a uri",
                    entry.payload.contains("speech-session://"),
                )
                assertFalse(
                    "payload for ${entry.action} must never contain the patient id",
                    entry.payload.contains("p1"),
                )
            }
        }
}
