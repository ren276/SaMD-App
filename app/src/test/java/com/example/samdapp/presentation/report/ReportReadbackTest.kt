package com.example.samdapp.presentation.report

import com.example.samdapp.config.FeatureFlags
import com.example.samdapp.domain.auth.UserRole
import com.example.samdapp.domain.auth.UserSession
import com.example.samdapp.domain.model.CaseRecord
import com.example.samdapp.domain.model.CaseStatus
import com.example.samdapp.domain.model.KernelDecision
import com.example.samdapp.domain.model.MedicationLine
import com.example.samdapp.domain.model.Prescription
import com.example.samdapp.domain.report.ReportFormatter
import com.example.samdapp.domain.slm.ApprovedRecordReader
import com.example.samdapp.domain.slm.MAX_QUESTION_CHARS
import com.example.samdapp.domain.slm.SlmEngine
import com.example.samdapp.domain.slm.SlmEngineError
import com.example.samdapp.domain.slm.SlmEngineException
import com.example.samdapp.domain.slm.SlmFinishReason
import com.example.samdapp.domain.slm.SlmReadbackUseCase
import com.example.samdapp.domain.slm.SlmRefusal
import com.example.samdapp.domain.usecase.AssembleReportUseCase
import com.example.samdapp.domain.usecase.CreateReferralUseCase
import com.example.samdapp.testutil.FakeAbhaProfileRepository
import com.example.samdapp.testutil.FakeAilmentRepository
import com.example.samdapp.testutil.FakeAuditLogger
import com.example.samdapp.testutil.FakeAuthSession
import com.example.samdapp.testutil.FakeCaseRecordRepository
import com.example.samdapp.testutil.FakeConsultationRepository
import com.example.samdapp.testutil.FakeDoctorRepository
import com.example.samdapp.testutil.FakeEncounterRepository
import com.example.samdapp.testutil.FakeEvaluateReportRepository
import com.example.samdapp.testutil.FakeKernelReportRepository
import com.example.samdapp.testutil.FakePatientRepository
import com.example.samdapp.testutil.FakePrescriptionRepository
import com.example.samdapp.testutil.FakeReferralRepository
import com.example.samdapp.testutil.FakeVitalsRepository
import com.example.samdapp.testutil.testAilmentEntry
import com.example.samdapp.testutil.testPatient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Modifier
import java.time.Instant

/**
 * PR-7: the read-back surface on the report screen, and the properties a worker's safety rests on.
 *
 * Driven through the **real** [SlmReadbackUseCase] over the real [ApprovedRecordReader], the real
 * [AssembleReportUseCase] and the real [ReportFormatter], with only the repositories and the engine
 * faked. Same construction `SlmReadbackUseCaseTest` uses and for the same reason: the refusals this
 * surface renders have to be the refusals the production path actually produces, not ones a
 * hand-built stand-in was told to emit.
 *
 * `ReportPdfExporter` is the one dependency that needs an Android `Context`, and it is injected
 * lazily in production precisely so this file can exist. The `Lazy` below throws if anything ever
 * reaches it, which is itself an assertion: nothing on the read-back path touches PDF export.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReportReadbackTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    // ------------------------------------------------ the flag, proved from both sides

    @Test
    fun `the flag as shipped is off in every flavor`() {
        // The literal claim, on the real constant. Everything below tests the predicate; this is
        // the one assertion that tests what is actually compiled into the app, and it is what makes
        // "the flag builds the surface, it does not turn it on" checkable rather than asserted.
        assertFalse(
            "SLM_READBACK_ENABLED must stay false. Turning it on is not a flag change: the " +
                "generation service does not exist in this repository, has never run end to end " +
                "on real hardware, and six hazard items covering this path are unsigned.",
            FeatureFlags.SLM_READBACK_ENABLED,
        )
    }

    @Test
    fun `the flag off makes the entry point unreachable even on a fully approved record`() {
        // Both inputs varied, which is the point. A test that only ran the shipped `false` would
        // pass identically if the approval condition were doing all the work, or if the gate were
        // wired to a constant; it would report "the button is hidden" without proving WHAT hides
        // it. The row that matters is the second: an otherwise perfectly eligible record, and the
        // flag alone is what closes the door.
        assertFalse(canOpenReadback(flagEnabled = false, hasPhysicianDecision = false))
        assertFalse(
            "a record a doctor has approved must still not offer the read-back while the flag is off",
            canOpenReadback(flagEnabled = false, hasPhysicianDecision = true),
        )
        assertFalse(
            "the flag alone must not be enough; the record still has to be approved",
            canOpenReadback(flagEnabled = true, hasPhysicianDecision = false),
        )
        assertTrue(canOpenReadback(flagEnabled = true, hasPhysicianDecision = true))
    }

    @Test
    fun `with the flag off the use case is never reached, whatever the surface is asked to do`() = runTest(dispatcher) {
        // The behavioural half, on the real ViewModel: not "the button is not drawn" but "the
        // generation path has no reachable caller". Same belt-and-braces TranscribeAudioUseCase
        // applies to its own flag, and for the same stated reason: the guard has to hold for any
        // caller, not only for the one screen that has one today.
        val engine = RecordingEngine()
        val viewModel = viewModel(engine = engine)
        advanceUntilIdle()

        viewModel.onOpenReadback()
        viewModel.onReadbackQuestionChange("What are the medicines?")
        viewModel.onAskReadback()
        advanceUntilIdle()

        assertFalse("the sheet must not open while the flag is off", viewModel.uiState.value.showReadbackSheet)
        assertEquals("the engine must never be reached", 0, engine.callCount)
        assertEquals(ReadbackState.Idle, viewModel.uiState.value.readback)

        // The non-vacuity half, and it is the half that makes the three lines above mean anything.
        // The identical sequence against an identical ViewModel that differs ONLY in the flag does
        // reach the engine. Without this, every assertion above would pass just as well if the
        // engine were unreachable for some entirely unrelated reason.
        val enabledEngine = RecordingEngine()
        val enabled = openedViewModel(enabledEngine)
        advanceUntilIdle()
        enabled.onOpenReadback()
        enabled.onReadbackQuestionChange("What are the medicines?")
        enabled.onAskReadback()
        advanceUntilIdle()
        assertTrue(enabled.uiState.value.showReadbackSheet)
        assertEquals("the flag, and only the flag, is what closed the path", 1, enabledEngine.callCount)
    }

    @Test
    fun `a generation cannot be started behind a closed sheet`() = runTest(dispatcher) {
        // Stronger than the flag check and independent of it, so it still holds the day the flag is
        // turned on. A generation started behind a closed surface is one the worker never sees and
        // can never stop, and it costs a full generation on a single-worker service either way.
        val engine = RecordingEngine()
        val viewModel = openedViewModel(engine)
        advanceUntilIdle()
        viewModel.onReadbackQuestionChange("What are the medicines?")

        // Never opened.
        viewModel.onAskReadback()
        advanceUntilIdle()
        assertEquals(0, engine.callCount)

        // And once opened, the identical call does run, so the line above is not passing because
        // the question was rejected or the engine was unreachable.
        viewModel.onOpenReadback()
        viewModel.onAskReadback()
        advanceUntilIdle()
        assertEquals(1, engine.callCount)
    }

    // ------------------------------------------------ the generating state

    @Test
    fun `the generating state is set before the request completes, not on the first byte`() = runTest(dispatcher) {
        // There is no token trickle to wait for: the grounding gate withholds the whole generation
        // until it completes, so the first thing that ever arrives is the entire answer. A state
        // entered on the first byte would be a state that never appeared. PR-5 measured p50 at
        // 4.36 s and a full-length answer at 10.8 s, so this is what a worker looks at for seconds
        // with a patient in the room.
        val gate = CompletableDeferred<Unit>()
        val engine = RecordingEngine(hold = gate)
        val viewModel = openedViewModel(engine)
        advanceUntilIdle()
        viewModel.onOpenReadback()

        viewModel.onReadbackQuestionChange("What are the medicines?")
        viewModel.onAskReadback()

        // No advanceUntilIdle: the coroutine has been launched and has not been dispatched. The
        // state must already be Generating, because it is set before the launch and not inside it.
        assertEquals(ReadbackState.Generating, viewModel.uiState.value.readback)
        assertFalse("no input may be on screen while generating", viewModel.uiState.value.readback.showsQuestionInput())

        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.readback is ReadbackState.Answer)
    }

    @Test
    fun `an answer is passed through exactly as the seam produced it`() = runTest(dispatcher) {
        val viewModel = openedViewModel(RecordingEngine(output = GROUNDED))
        advanceUntilIdle()
        viewModel.onOpenReadback()
        viewModel.onReadbackQuestionChange("What are the medicines?")
        viewModel.onAskReadback()
        advanceUntilIdle()

        // Unedited. Section 6.2 forbids meaning-level filtering, so the surface either shows the
        // whole generation or shows none of it; there is no trimming for layout here.
        assertEquals(ReadbackState.Answer(GROUNDED), viewModel.uiState.value.readback)
    }

    // ------------------------------------------------ no second turn, anywhere

    @Test
    fun `the question input and an answer are never on screen together`() {
        // The single-turn rule at this layer, as a property of the type rather than of the
        // composable's discipline. Exactly one state carries an input, and it is the one with no
        // answer in it.
        assertTrue(ReadbackState.Idle.showsQuestionInput())
        assertFalse(ReadbackState.Generating.showsQuestionInput())
        assertFalse(ReadbackState.Answer("x").showsQuestionInput())
        assertFalse(ReadbackState.Refused(SlmRefusal.ENGINE_FAILED).showsQuestionInput())
    }

    @Test
    fun `the readback state has no slot a conversation could be built in`() {
        // Same construction SlmReadbackUseCaseTest asserts on SlmInvocation, applied where a
        // transcript would actually be assembled. Answer holds one string; there is no list, no
        // history and no turn index anywhere in the hierarchy, so a conversation view is
        // unrepresentable rather than merely discouraged.
        val answerFields = ReadbackState.Answer::class.java.declaredFields
            .filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) }
        assertEquals(listOf("text"), answerFields.map { it.name })
        assertEquals(String::class.java, answerFields.single().type)

        val refusedFields = ReadbackState.Refused::class.java.declaredFields
            .filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) }
        // The structural form of "a refusal never displays partial generated text": there is no
        // field it could be carried in. The seam's own Refused holds a reason and nothing else,
        // and this mirrors it rather than widening it.
        assertEquals(listOf("reason"), refusedFields.map { it.name })
    }

    @Test
    fun `asking something else clears the previous question and the previous answer`() = runTest(dispatcher) {
        val viewModel = openedViewModel(RecordingEngine(output = GROUNDED))
        advanceUntilIdle()
        viewModel.onOpenReadback()
        viewModel.onReadbackQuestionChange("What are the medicines?")
        viewModel.onAskReadback()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.readback is ReadbackState.Answer)

        viewModel.onAskAnotherReadback()

        // Both cleared, which is what makes this a new single turn rather than a follow-up.
        // Nothing from the previous turn is on screen and nothing travels with the next one.
        assertEquals(ReadbackState.Idle, viewModel.uiState.value.readback)
        assertEquals("", viewModel.uiState.value.readbackQuestion)
    }

    // ------------------------------------------------ refusals

    @Test
    fun `a refusal reaches the surface as a refusal and carries no text`() = runTest(dispatcher) {
        // Driven through the real seam with a real engine failure, so the refusal is the one the
        // production path produces. A TLS failure is chosen because it is the class PR-6 added and
        // the one whose copy must not invite a retry.
        val engine = RecordingEngine(failure = SlmEngineException(SlmEngineError.SECURE_CONNECTION_FAILED))
        val viewModel = openedViewModel(engine)
        advanceUntilIdle()
        viewModel.onOpenReadback()
        viewModel.onReadbackQuestionChange("What are the medicines?")
        viewModel.onAskReadback()
        advanceUntilIdle()

        val state = viewModel.uiState.value.readback
        assertEquals(ReadbackState.Refused(SlmRefusal.SECURE_CONNECTION_FAILED), state)
        assertFalse("a refusal must not leave an answer on screen", state is ReadbackState.Answer)
        assertEquals(SlmRetryOffer.NONE, slmRefusalCopy((state as ReadbackState.Refused).reason).retry)
    }

    @Test
    fun `a truncated generation refuses and no part of the text reaches the surface`() = runTest(dispatcher) {
        // The most dangerous failure on this path and the only place it is catchable. The engine
        // returns a complete, grounded-looking body with finish_reason length; the seam refuses on
        // the envelope, and what must not happen is any of that body appearing.
        val engine = RecordingEngine(output = GROUNDED, finishReason = SlmFinishReason.LENGTH)
        val viewModel = openedViewModel(engine)
        advanceUntilIdle()
        viewModel.onOpenReadback()
        viewModel.onReadbackQuestionChange("What are the medicines?")
        viewModel.onAskReadback()
        advanceUntilIdle()

        val state = viewModel.uiState.value.readback
        assertEquals(ReadbackState.Refused(SlmRefusal.OUTPUT_TRUNCATED), state)
        // The text the engine produced is nowhere in the state. Structural, because Refused has no
        // field for it, and asserted anyway because "structurally impossible" is a claim that
        // should cost a line to check.
        assertFalse(state.toString().contains("Amoxicillin"))
        assertEquals(SlmRetryOffer.ASK_AGAIN, slmRefusalCopy(SlmRefusal.OUTPUT_TRUNCATED).retry)
    }

    @Test
    fun `an over-long question is refused before the engine is reached`() = runTest(dispatcher) {
        val engine = RecordingEngine()
        val viewModel = openedViewModel(engine)
        advanceUntilIdle()
        viewModel.onOpenReadback()
        viewModel.onReadbackQuestionChange("x".repeat(MAX_QUESTION_CHARS + 1))

        // The button is disabled, so the round trip never starts. The seam refuses it too, which is
        // why QUESTION_TOO_LONG still has copy: this is the cheaper of two independent gates, not
        // the only one.
        // Asserted on the pure predicate, not only on the engine's call count: with the flag off
        // the count would be zero whatever the question said, so the count alone would be a check
        // that passes for the wrong reason.
        assertFalse(canAskReadback("x".repeat(MAX_QUESTION_CHARS + 1), ReadbackState.Idle, MAX_QUESTION_CHARS))
        assertFalse(viewModel.uiState.value.canAskReadback)
        viewModel.onAskReadback()
        advanceUntilIdle()
        assertEquals(0, engine.callCount)
        assertEquals(ReadbackState.Idle, viewModel.uiState.value.readback)
    }

    @Test
    fun `a blank question cannot be asked`() = runTest(dispatcher) {
        val engine = RecordingEngine()
        val viewModel = viewModel(engine = engine, flagEnabled = true)
        advanceUntilIdle()
        assertFalse(canAskReadback("   ", ReadbackState.Idle, MAX_QUESTION_CHARS))
        assertFalse(canAskReadback("", ReadbackState.Idle, MAX_QUESTION_CHARS))
        // And a well-formed question is askable, so the two rows above are not passing because the
        // predicate returns false for everything.
        assertTrue(canAskReadback("What are the medicines?", ReadbackState.Idle, MAX_QUESTION_CHARS))
        viewModel.onReadbackQuestionChange("   ")
        assertFalse(viewModel.uiState.value.canAskReadback)
        viewModel.onAskReadback()
        advanceUntilIdle()
        assertEquals(0, engine.callCount)
    }

    // ------------------------------------------------ cancellation

    @Test
    fun `stopping a generation leaves no partial state and keeps the question`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val engine = RecordingEngine(output = GROUNDED, hold = gate)
        val viewModel = openedViewModel(engine)
        advanceUntilIdle()
        viewModel.onOpenReadback()
        viewModel.onReadbackQuestionChange("What are the medicines?")
        viewModel.onAskReadback()
        assertEquals(ReadbackState.Generating, viewModel.uiState.value.readback)

        viewModel.onCancelReadback()
        // Release the engine afterwards: a cancelled job that still delivered its result would show
        // up here as an Answer appearing after the worker stopped it, which is the partial state
        // this asserts against.
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(ReadbackState.Idle, viewModel.uiState.value.readback)
        assertEquals(
            "the question is kept, so a worker who stopped because it was slow need not retype",
            "What are the medicines?",
            viewModel.uiState.value.readbackQuestion,
        )
        // Stop puts the worker back at the question box, not back on the report: the sheet stays
        // open, so the question they just stopped is still in front of them.
        assertTrue("the sheet stays open on Stop", viewModel.uiState.value.showReadbackSheet)
    }

    @Test
    fun `tapping away cancels the generation and clears everything`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val viewModel = openedViewModel(RecordingEngine(output = GROUNDED, hold = gate))
        advanceUntilIdle()
        viewModel.onOpenReadback()
        viewModel.onReadbackQuestionChange("What are the medicines?")
        viewModel.onAskReadback()
        assertEquals(ReadbackState.Generating, viewModel.uiState.value.readback)

        viewModel.onDismissReadback()
        gate.complete(Unit)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.showReadbackSheet)
        assertEquals(ReadbackState.Idle, viewModel.uiState.value.readback)
        assertEquals("", viewModel.uiState.value.readbackQuestion)
    }

    @Test
    fun `a stopped generation can be asked again`() = runTest(dispatcher) {
        // A cancelled job that left the surface or the binding's mutex stuck would show up as a
        // feature that works exactly once per screen, which a single happy-path test never sees.
        val gate = CompletableDeferred<Unit>()
        val engine = RecordingEngine(output = GROUNDED, hold = gate)
        val viewModel = openedViewModel(engine)
        advanceUntilIdle()
        viewModel.onOpenReadback()
        viewModel.onReadbackQuestionChange("What are the medicines?")
        viewModel.onAskReadback()
        viewModel.onCancelReadback()
        gate.complete(Unit)
        advanceUntilIdle()

        viewModel.onAskReadback()
        advanceUntilIdle()
        assertEquals(ReadbackState.Answer(GROUNDED), viewModel.uiState.value.readback)
    }

    // ------------------------------------------------ helpers

    /**
     * The ViewModel over the real seam. [flagEnabled] cannot move `FeatureFlags`, which is a
     * `const val`, so the tests that need the surface open call the actions directly: `canOpenReadback`
     * gates `onOpenReadback` only, and every other action is reachable once the sheet is open. The
     * flag's own effect is proved separately, above, from both sides of the predicate and from the
     * real constant.
     */
    private suspend fun viewModel(engine: SlmEngine, flagEnabled: Boolean = false): ReportViewModel {
        val caseRecords = FakeCaseRecordRepository(listOf(CASE))
        val patients = FakePatientRepository().apply { register(testPatient(id = "p1", fullName = "Anita Kumari")) }
        val prescriptions = FakePrescriptionRepository().apply { save(PRESCRIPTION) }
        val ailments = FakeAilmentRepository().apply {
            addAilment(testAilmentEntry(id = "a-public", description = "Sore throat, 3 days"))
        }
        val authSession = FakeAuthSession(UserSession(userId = "u-1", name = "Test User", role = UserRole.ASHA_WORKER))
        val assemble = AssembleReportUseCase(
            caseRecordRepository = caseRecords,
            patientRepository = patients,
            encounterRepository = FakeEncounterRepository(),
            abhaProfileRepository = FakeAbhaProfileRepository(),
            consultationRepository = FakeConsultationRepository(),
            ailmentRepository = ailments,
            vitalsRepository = FakeVitalsRepository(),
            prescriptionRepository = prescriptions,
            kernelReportRepository = FakeKernelReportRepository(),
            evaluateReportRepository = FakeEvaluateReportRepository(),
            doctorRepository = FakeDoctorRepository(),
            reportFormatter = ReportFormatter(),
        )
        return ReportViewModel(
            caseRecordId = "case-1",
            assembleReportUseCase = assemble,
            // Throws if anything reaches it. Nothing on the read-back path exports a PDF, and the
            // lazy injection that makes this file possible is what lets that be an assertion.
            pdfExporter = dagger.Lazy { error("PDF export must not be reached from the read-back path") },
            createReferralUseCase = CreateReferralUseCase(FakeReferralRepository()),
            auditLogger = FakeAuditLogger(),
            authSession = authSession,
            slmReadbackUseCase = SlmReadbackUseCase(
                authSession = authSession,
                approvedRecordReader = ApprovedRecordReader(
                    authSession = authSession,
                    caseRecordRepository = caseRecords,
                    assembleReportUseCase = assemble,
                ),
                engine = engine,
            ),
            // The whole reason FeatureFlagModule exists. A `const val` cannot be moved at runtime,
            // so a guard that read it inline could only ever be exercised on the shipped `false`,
            // and every assertion below would pass whether the guard checked the flag or checked
            // nothing. This is what makes the flag's effect a proof.
            readbackEnabled = flagEnabled,
        )
    }

    /** The ViewModel with the surface open, which is where every behavioural test starts. */
    private suspend fun openedViewModel(engine: SlmEngine): ReportViewModel =
        viewModel(engine = engine, flagEnabled = true)

    /** Counts calls and can be held open, so the generating state has something to be observed on. */
    private class RecordingEngine(
        private val output: String = "",
        private val failure: Throwable? = null,
        private val hold: CompletableDeferred<Unit>? = null,
        private val finishReason: SlmFinishReason? = SlmFinishReason.STOP,
    ) : SlmEngine {
        var callCount = 0
            private set

        override fun generate(caseRecordId: String, prompt: String, maxOutputTokens: Int): Flow<String> = flow {
            callCount++
            hold?.await()
            failure?.let { throw it }
            emit(output)
        }

        override fun servedModelId(): String? = com.example.samdapp.domain.slm.SANITIZER_TARGET_MODEL_ID
        override fun finishReason(): SlmFinishReason? = finishReason
    }

    private companion object {
        val CASE = CaseRecord(
            id = "case-1", patientId = "p1", encounterId = "e1", status = CaseStatus.SENT_TO_DOCTOR,
            assignedDoctorId = "doc-1", createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
        )
        val AMOXICILLIN = MedicationLine(
            genericName = "Amoxicillin", brandName = "Mox", strength = "500 mg", dosage = "1 capsule",
            frequency = "three times a day", route = "oral", duration = "5 days", quantity = "15",
            foodRelation = "after food", instructions = null,
        )
        val PRESCRIPTION = Prescription(
            id = "rx-1", patientId = "p1", encounterId = "e1", caseRecordId = "case-1", doctorId = "doc-1",
            diagnosis = "Acute bacterial pharyngitis", medications = listOf(AMOXICILLIN),
            kernelDecision = KernelDecision.AGREE, createdAt = Instant.EPOCH,
        )

        /** Grounded: every drug token and numeral appears in the formatted medication line. */
        const val GROUNDED =
            "The doctor approved Amoxicillin 500 mg by mouth, three times a day, for 5 days, 15 in total. " +
                "Ask the doctor if anything is unclear."
    }
}
