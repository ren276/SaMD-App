package com.example.samdapp.presentation.consultation

import com.example.samdapp.domain.audit.levenshteinDistance
import com.example.samdapp.domain.transcription.CapturedAudio
import com.example.samdapp.domain.usecase.AddAttachmentUseCase
import com.example.samdapp.domain.usecase.CaptureAudioAttachmentUseCase
import com.example.samdapp.domain.usecase.SaveConsultationUseCase
import com.example.samdapp.domain.usecase.UploadConsultationDocumentUseCase
import com.example.samdapp.testutil.FakeAuditLogger
import com.example.samdapp.testutil.FakeAuthSession
import com.example.samdapp.testutil.FakeConsultationDocumentRepository
import com.example.samdapp.testutil.FakeConsultationRepository
import com.example.samdapp.testutil.FakeDocumentCaptureStore
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * PR5, voice field-expansion. Proves the same four `VOICE_FIELD_*` breadcrumbs
 * ([ConsultationVoiceGateBreadcrumbsTest] proves them for `impactOnDailyActivities`) fire at the
 * right transitions for `aggravatingFactors`, `relievingFactors` and `relevantHistory`, each
 * behind its own flag (see `FeatureFlags.VOICE_FIELD_AGGRAVATING_ENABLED` KDoc for the one
 * deliberate difference from the impact gate: no persisted `FieldProvenance` column for these
 * three, so the audit log is their provenance record).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConsultationVoiceFieldExpansionBreadcrumbsTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private data class FieldGate(
        val slot: String,
        val value: (ConsultationUiState) -> String,
        val record: ConsultationActions.() -> Unit,
        val use: ConsultationActions.() -> Unit,
        val edit: ConsultationActions.() -> Unit,
        val discard: ConsultationActions.() -> Unit,
        val change: ConsultationActions.(String) -> Unit,
    )

    private val fieldGates = listOf(
        FieldGate(
            slot = "AGGRAVATING_FACTORS",
            value = { it.aggravatingFactors },
            record = { onRecordAggravatingVoice() },
            use = { onUseAggravatingSuggestion() },
            edit = { onEditAggravatingSuggestion() },
            discard = { onDiscardAggravatingSuggestion() },
            change = { onAggravatingFactorsChange(it) },
        ),
        FieldGate(
            slot = "RELIEVING_FACTORS",
            value = { it.relievingFactors },
            record = { onRecordRelievingVoice() },
            use = { onUseRelievingSuggestion() },
            edit = { onEditRelievingSuggestion() },
            discard = { onDiscardRelievingSuggestion() },
            change = { onRelievingFactorsChange(it) },
        ),
        FieldGate(
            slot = "RELEVANT_HISTORY",
            value = { it.relevantHistory },
            record = { onRecordRelevantHistoryVoice() },
            use = { onUseRelevantHistorySuggestion() },
            edit = { onEditRelevantHistorySuggestion() },
            discard = { onDiscardRelevantHistorySuggestion() },
            change = { onRelevantHistoryChange(it) },
        ),
    )

    private fun payloadFields(json: String): Map<String, String?> =
        Json.parseToJsonElement(json).jsonObject.mapValues { (_, value) -> value.jsonPrimitive.contentOrNull }

    private fun newViewModel(
        auditLogger: FakeAuditLogger,
        transcriptionService: FakeTranscriptionService,
    ) = ConsultationViewModel(
        patientId = "p1",
        encounterId = "enc1",
        caseRecordId = "case1",
        initialChiefComplaint = "fever",
        saveConsultationUseCase = SaveConsultationUseCase(FakeConsultationRepository()),
        addAttachmentUseCase = AddAttachmentUseCase(FakeConsultationRepository()),
        captureAudioAttachmentUseCase = CaptureAudioAttachmentUseCase(transcriptionService),
        uploadConsultationDocumentUseCase = UploadConsultationDocumentUseCase(FakeConsultationDocumentRepository(), auditLogger),
        documentCaptureStore = FakeDocumentCaptureStore(),
        authSession = FakeAuthSession(),
        auditLogger = auditLogger,
    )

    private fun serviceReturning(transcript: String) = FakeTranscriptionService(
        Result.success(CapturedAudio(uri = "speech-session://fake", transcript = transcript)),
    )

    @Test
    fun `a non-blank transcript emits VOICE_FIELD_SUGGESTED for every expanded field`() =
        runTest(mainDispatcherRule.dispatcher) {
            fieldGates.forEach { gate ->
                val auditLogger = FakeAuditLogger()
                val viewModel = newViewModel(auditLogger, serviceReturning("worse at night"))

                viewModel.record(gate)
                advanceUntilIdle()

                val entry = auditLogger.logged.single()
                assertEquals(gate.slot, "voice_field_suggested", entry.action)
                val fields = payloadFields(entry.payload)
                assertEquals(gate.slot, gate.slot, fields["slot"])
                assertEquals(gate.slot, "VOICE_UNCONFIRMED", fields["provenance"])
                assertEquals(gate.slot, "worse at night".length.toString(), fields["charCount"])
            }
        }

    @Test
    fun `Use it emits VOICE_FIELD_CONFIRMED and commits the transcript into the field`() =
        runTest(mainDispatcherRule.dispatcher) {
            fieldGates.forEach { gate ->
                val auditLogger = FakeAuditLogger()
                val viewModel = newViewModel(auditLogger, serviceReturning("worse at night"))
                viewModel.record(gate)
                advanceUntilIdle()

                viewModel.use(gate)
                advanceUntilIdle()

                val entry = auditLogger.logged.last()
                assertEquals(gate.slot, "voice_field_confirmed", entry.action)
                assertEquals(gate.slot, "worse at night", gate.value(viewModel.uiState.value))
                val dwellMs = payloadFields(entry.payload)["dwellMs"]?.toLongOrNull()
                assertTrue(gate.slot, dwellMs != null && dwellMs >= 0)
            }
        }

    @Test
    fun `saving an edited suggestion emits VOICE_FIELD_EDITED with editDistance`() =
        runTest(mainDispatcherRule.dispatcher) {
            fieldGates.forEach { gate ->
                val auditLogger = FakeAuditLogger()
                val viewModel = newViewModel(auditLogger, serviceReturning("worse at night"))
                viewModel.record(gate)
                advanceUntilIdle()
                viewModel.edit(gate)
                viewModel.change(gate, "much worse at night")

                viewModel.onSend()
                advanceUntilIdle()

                val entry = auditLogger.logged.first { it.action == "voice_field_edited" }
                val fields = payloadFields(entry.payload)
                assertEquals(gate.slot, "VOICE_EDITED", fields["provenance"])
                val expectedDistance = levenshteinDistance("worse at night", "much worse at night")
                assertEquals(gate.slot, expectedDistance.toString(), fields["editDistance"])
            }
        }

    @Test
    fun `Discard emits VOICE_FIELD_REJECTED and leaves no committed value`() =
        runTest(mainDispatcherRule.dispatcher) {
            fieldGates.forEach { gate ->
                val auditLogger = FakeAuditLogger()
                val viewModel = newViewModel(auditLogger, serviceReturning("worse at night"))
                viewModel.record(gate)
                advanceUntilIdle()

                viewModel.discard(gate)
                advanceUntilIdle()

                val entry = auditLogger.logged.last()
                assertEquals(gate.slot, "voice_field_rejected", entry.action)
                assertEquals(gate.slot, "VOICE_UNCONFIRMED", payloadFields(entry.payload)["provenance"])
                assertEquals(gate.slot, "", gate.value(viewModel.uiState.value))
            }
        }

    @Test
    fun `canSend is false while any expanded field has a pending suggestion`() =
        runTest(mainDispatcherRule.dispatcher) {
            fieldGates.forEach { gate ->
                val auditLogger = FakeAuditLogger()
                val viewModel = newViewModel(auditLogger, serviceReturning("worse at night"))

                viewModel.record(gate)
                advanceUntilIdle()

                assertTrue(gate.slot, !viewModel.uiState.value.canSend)
            }
        }

    @Test
    fun `a plain typed value on an expanded field never emits VOICE_FIELD_EDITED`() =
        runTest(mainDispatcherRule.dispatcher) {
            fieldGates.forEach { gate ->
                val auditLogger = FakeAuditLogger()
                val viewModel = newViewModel(auditLogger, serviceReturning("worse at night"))
                viewModel.change(gate, "typed by hand")

                viewModel.onSend()
                advanceUntilIdle()

                assertTrue(gate.slot, auditLogger.logged.none { it.action == "voice_field_edited" })
            }
        }

    private fun ConsultationViewModel.record(gate: FieldGate) = gate.record(this)
    private fun ConsultationViewModel.use(gate: FieldGate) = gate.use(this)
    private fun ConsultationViewModel.edit(gate: FieldGate) = gate.edit(this)
    private fun ConsultationViewModel.discard(gate: FieldGate) = gate.discard(this)
    private fun ConsultationViewModel.change(gate: FieldGate, value: String) = gate.change(this, value)
}
