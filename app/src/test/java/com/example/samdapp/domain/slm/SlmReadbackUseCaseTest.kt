package com.example.samdapp.domain.slm

import com.example.samdapp.domain.auth.UserRole
import com.example.samdapp.domain.auth.UserSession
import com.example.samdapp.domain.model.CaseRecord
import com.example.samdapp.domain.model.CaseStatus
import com.example.samdapp.domain.model.KernelDecision
import com.example.samdapp.domain.model.MedicationLine
import com.example.samdapp.domain.model.Patient
import com.example.samdapp.domain.model.Prescription
import com.example.samdapp.domain.report.ReportFormatter
import com.example.samdapp.domain.usecase.AssembleReportUseCase
import com.example.samdapp.testutil.FakeAbhaProfileRepository
import com.example.samdapp.testutil.FakeAilmentRepository
import com.example.samdapp.testutil.FakeAuthSession
import com.example.samdapp.testutil.FakeCaseRecordRepository
import com.example.samdapp.testutil.FakeConsultationRepository
import com.example.samdapp.testutil.FakeDoctorRepository
import com.example.samdapp.testutil.FakeEncounterRepository
import com.example.samdapp.testutil.FakeEvaluateReportRepository
import com.example.samdapp.testutil.FakeKernelReportRepository
import com.example.samdapp.testutil.FakePatientRepository
import com.example.samdapp.testutil.FakePrescriptionRepository
import com.example.samdapp.testutil.FakeVitalsRepository
import com.example.samdapp.testutil.testAilmentEntry
import com.example.samdapp.testutil.testPatient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier
import java.time.Instant

/**
 * Stage 2 of the SLM build: the guardrail seam and the two scope gates
 * (`scratchpad/slm-guardrail-service-contract-memo.md` §4.4, §5.4, §7, §9.1). The engine is a
 * declared-but-unbound interface, so every test here drives the seam with [RecordingSlmEngine],
 * whose call count is the evidence for the claim that matters most: **on a refusal the model is
 * never reached.** That is the `readDecryptedCallCount == 0` pattern this project already uses for
 * proving a gate refuses before the guarded resource is touched, applied to the engine.
 *
 * Independent sourcing, per CLAUDE.md: the snapshot under test is produced by the REAL
 * [ApprovedRecordReader] over the REAL [AssembleReportUseCase] and [ReportFormatter], not by a
 * hand-built stand-in, so the record the gates see is the record production would hand them.
 * Expected medication text is recomputed via [ReportFormatter.formatMedicationLine] rather than
 * copied from the value under test. There is no write on this path, so the persisted-row rule has
 * nothing to bite on; its sibling - assert the content, not the return code - is what the
 * pass-untouched and suppression tests do.
 */
class SlmReadbackUseCaseTest {

    /**
     * Counts calls and records prompts. The count is the assertion in every refusal test: a gate
     * that refuses after the model ran is not a gate, it is a display filter.
     */
    private class RecordingSlmEngine(
        private val output: String = "",
        private val failure: Throwable? = null,
    ) : SlmEngine {
        var callCount = 0
            private set
        val prompts = mutableListOf<String>()

        override suspend fun generate(prompt: String, maxOutputTokens: Int): String {
            callCount++
            prompts += prompt
            failure?.let { throw it }
            return output
        }
    }

    private val nameCanary = "Anita Kumari"

    private val hotPatient: Patient = testPatient(id = "p1", fullName = nameCanary).copy(
        mobileNumber = "9998887776",
        abhaNumber = "12345678901234",
        village = "Rampur",
    )

    private val caseRecord = CaseRecord(
        id = "case-1", patientId = "p1", encounterId = "e1", status = CaseStatus.SENT_TO_DOCTOR,
        assignedDoctorId = "doc-1", createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
    )

    private val amoxicillin = MedicationLine(
        genericName = "Amoxicillin", brandName = "Mox", strength = "500 mg", dosage = "1 capsule",
        frequency = "three times a day", route = "oral", duration = "5 days", quantity = "15",
        foodRelation = "after food", instructions = null,
    )

    /** The formatted line the real formatter produces, computed independently of the seam. */
    private val amoxicillinLine = ReportFormatter().formatMedicationLine(amoxicillin)

    /** Grounded generation: every drug token and every numeral appears in [amoxicillinLine]. */
    private val groundedOutput =
        "The doctor approved Amoxicillin 500 mg by mouth, three times a day, for 5 days, 15 in total. " +
            "Ask the doctor if anything is unclear."

    private fun prescription(
        decision: KernelDecision?,
        medications: List<MedicationLine> = listOf(amoxicillin),
    ) = Prescription(
        id = "rx-1", patientId = "p1", encounterId = "e1", caseRecordId = "case-1", doctorId = "doc-1",
        diagnosis = "Acute bacterial pharyngitis", medications = medications,
        kernelDecision = decision, createdAt = Instant.EPOCH,
    )

    private fun session(role: UserRole) = UserSession(userId = "u-1", name = "Test User", role = role)

    /**
     * Builds the seam over the real reader, real assembler and real formatter, with only the
     * repositories faked. Mirrors `ApprovedRecordReaderTest.reader` deliberately: the snapshot the
     * gates are tested against has to be the one the production path produces.
     */
    private suspend fun useCase(
        session: UserSession?,
        engine: SlmEngine,
        cases: List<CaseRecord> = listOf(caseRecord),
        patient: Patient? = hotPatient,
        prescription: Prescription? = prescription(KernelDecision.AGREE),
    ): SlmReadbackUseCase {
        val caseRecords = FakeCaseRecordRepository(cases)
        val patients = FakePatientRepository()
        patient?.let { patients.register(it) }
        val prescriptions = FakePrescriptionRepository()
        prescription?.let { prescriptions.save(it) }
        val ailments = FakeAilmentRepository()
        ailments.addAilment(testAilmentEntry(id = "a-public", description = "Sore throat, 3 days"))
        val authSession = FakeAuthSession(session)
        return SlmReadbackUseCase(
            authSession = authSession,
            approvedRecordReader = ApprovedRecordReader(
                authSession = authSession,
                caseRecordRepository = caseRecords,
                assembleReportUseCase = AssembleReportUseCase(
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
                ),
            ),
            engine = engine,
        )
    }

    // ------------------------------------------------------------- structure of the seam (§9.1)

    /**
     * §7 in the type system. The invocation carries one snapshot and one question and nothing that
     * could hold a transcript. Asserting the exact field list is the point: adding a `history` or
     * `messages` field fails here rather than silently reopening the multi-turn bypass class.
     */
    @Test
    fun `the invocation type carries one snapshot and one question and has no history field`() {
        val fields = SlmInvocation::class.java.declaredFields
            .filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) }

        assertEquals(
            "SlmInvocation field set changed - single-turn enforcement (memo section 7) lives in this field list",
            listOf("snapshot", "question"),
            fields.map { it.name },
        )
        assertEquals(ApprovedRecordSnapshot::class.java, fields[0].type)
        assertEquals(String::class.java, fields[1].type)

        // No field could hold a sequence of turns even under a different name.
        fields.forEach { field ->
            assertFalse(
                "field ${field.name} is a collection type (${field.type.simpleName}) and could carry a transcript",
                Collection::class.java.isAssignableFrom(field.type) ||
                    Map::class.java.isAssignableFrom(field.type) ||
                    field.type.isArray,
            )
            listOf("history", "messages", "turns", "transcript", "conversation", "context").forEach { banned ->
                assertFalse("field ${field.name} looks like conversation state ($banned)", field.name.lowercase().contains(banned))
            }
        }
    }

    /** The entry point takes a case id and a question. Nothing else, and no history parameter. */
    @Test
    fun `the seam's invoke takes a case id and a question and nothing else`() {
        val invoke = SlmReadbackUseCase::class.java.declaredMethods.single { it.name == "invoke" }

        // caseRecordId, question, and the suspend Continuation.
        assertEquals(3, invoke.parameterTypes.size)
        assertEquals(String::class.java, invoke.parameterTypes[0])
        assertEquals(String::class.java, invoke.parameterTypes[1])
        invoke.parameterTypes.forEach { type ->
            assertFalse(
                "invoke takes a collection parameter (${type.simpleName}); a caller could pass a transcript",
                Collection::class.java.isAssignableFrom(type),
            )
        }
    }

    /**
     * §9.1: the engine is reachable only through the seam. This asserts the seam holds it; the
     * other half of the claim - that presentation cannot - is
     * `com.example.samdapp.config.SlmEngineIsUnreachableFromPresentationTest`.
     */
    @Test
    fun `the engine is a constructor dependency of the seam and of nothing else in domain`() {
        val constructorTypes = SlmReadbackUseCase::class.java.declaredConstructors
            .flatMap { it.parameterTypes.toList() }

        assertTrue("the seam does not hold the engine", SlmEngine::class.java in constructorTypes)
        assertTrue("SlmEngine must stay an interface with no implementation at this stage", SlmEngine::class.java.isInterface)
    }

    /** A suppression surface that carried the text would not be a suppression (§5.4). */
    @Test
    fun `the refusal result carries a reason code and no generated text`() {
        val fields = SlmReadbackResult.Refused::class.java.declaredFields
            .filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) }

        assertEquals(listOf("reason"), fields.map { it.name })
        assertEquals(SlmRefusal::class.java, fields.single().type)
    }

    // --------------------------------------------------------------- single turn, byte for byte

    /**
     * §7's check: two sequential invocations with the same snapshot and the same question produce
     * identical prompts, byte for byte. If any history were accumulating in the seam, the second
     * prompt would differ.
     */
    @Test
    fun `two sequential invocations with the same snapshot and question produce byte-identical prompts`() = runTest {
        val engine = RecordingSlmEngine(output = groundedOutput)
        val seam = useCase(session(UserRole.ASHA_WORKER), engine)
        val question = "Explain what the doctor prescribed in plain language"

        seam("case-1", question)
        seam("case-1", question)

        assertEquals(2, engine.callCount)
        assertTrue(
            "the second prompt differs from the first, so state is leaking across invocations",
            engine.prompts[0].toByteArray(Charsets.UTF_8)
                .contentEquals(engine.prompts[1].toByteArray(Charsets.UTF_8)),
        )
        // The prompt is built from the record, not from a stub: the independently formatted
        // medication line is in it, and the question is in it exactly once.
        assertTrue("the approved medication line is not in the prompt", engine.prompts[0].contains(amoxicillinLine))
        assertEquals(1, engine.prompts[0].windowed(question.length).count { it == question })
        // §4.1/§10.2 carried forward: identity never reaches the model boundary.
        assertFalse("patient identity reached the prompt", engine.prompts[0].contains(nameCanary))
    }

    // ------------------------------------------------------------------- hard rejects (§4.4)

    @Test
    fun `refuses an empty question and never calls the engine`() = runTest {
        val engine = RecordingSlmEngine(output = groundedOutput)

        val result = useCase(session(UserRole.ASHA_WORKER), engine)("case-1", "")

        assertEquals(SlmReadbackResult.Refused(SlmRefusal.EMPTY_QUESTION), result)
        assertEquals(0, engine.callCount)
    }

    /** Harness F5 was an empty prompt hallucinating to about 2000 tokens; whitespace is empty. */
    @Test
    fun `refuses a whitespace-only question and never calls the engine`() = runTest {
        val engine = RecordingSlmEngine(output = groundedOutput)

        val result = useCase(session(UserRole.ASHA_WORKER), engine)("case-1", "   \n\t  ")

        assertEquals(SlmReadbackResult.Refused(SlmRefusal.EMPTY_QUESTION), result)
        assertEquals(0, engine.callCount)
    }

    @Test
    fun `refuses a question over the character budget and never calls the engine`() = runTest {
        val engine = RecordingSlmEngine(output = groundedOutput)
        val tooLong = "Explain " + "a".repeat(MAX_QUESTION_CHARS)

        val result = useCase(session(UserRole.ASHA_WORKER), engine)("case-1", tooLong)

        assertEquals(SlmReadbackResult.Refused(SlmRefusal.QUESTION_TOO_LONG), result)
        assertEquals(0, engine.callCount)
    }

    /** A question one character inside the budget is not refused for length. */
    @Test
    fun `accepts a question exactly at the character budget`() = runTest {
        val engine = RecordingSlmEngine(output = groundedOutput)
        val atBudget = "Explain the prescription " + "a".repeat(MAX_QUESTION_CHARS - "Explain the prescription ".length)

        val result = useCase(session(UserRole.ASHA_WORKER), engine)("case-1", atBudget)

        assertEquals(MAX_QUESTION_CHARS, atBudget.length)
        assertNotEquals(SlmReadbackResult.Refused(SlmRefusal.QUESTION_TOO_LONG), result)
    }

    /** §4.1: no committed decision means the record is not physician-approved. Delegated to the
     *  stage-1 reader rather than re-checked here. */
    @Test
    fun `refuses when the case has no committed kernelDecision and never calls the engine`() = runTest {
        val engine = RecordingSlmEngine(output = groundedOutput)

        val result = useCase(
            session(UserRole.ASHA_WORKER), engine, prescription = prescription(decision = null),
        )("case-1", "Explain the prescription in plain language")

        assertEquals(SlmReadbackResult.Refused(SlmRefusal.NOT_APPROVED), result)
        assertEquals(0, engine.callCount)
    }

    @Test
    fun `refuses an unresolvable case and never calls the engine`() = runTest {
        val engine = RecordingSlmEngine(output = groundedOutput)

        val result = useCase(session(UserRole.ASHA_WORKER), engine, cases = emptyList())(
            "case-1", "Explain the prescription in plain language",
        )

        assertEquals(SlmReadbackResult.Refused(SlmRefusal.CASE_UNRESOLVABLE), result)
        assertEquals(0, engine.callCount)
    }

    @Test
    fun `refuses when the report cannot be assembled and never calls the engine`() = runTest {
        val engine = RecordingSlmEngine(output = groundedOutput)

        val result = useCase(session(UserRole.ASHA_WORKER), engine, patient = null)(
            "case-1", "Explain the prescription in plain language",
        )

        assertEquals(SlmReadbackResult.Refused(SlmRefusal.ASSEMBLY_FAILED), result)
        assertEquals(0, engine.callCount)
    }

    /** §4.4: an oversized record is refused, never truncated into the prompt. */
    @Test
    fun `refuses a record that overflows the prompt budget and never calls the engine`() = runTest {
        val engine = RecordingSlmEngine(output = groundedOutput)
        val many = (1..120).map { amoxicillin.copy(genericName = "Amoxicillin variant $it") }

        val result = useCase(
            session(UserRole.ASHA_WORKER), engine,
            prescription = prescription(KernelDecision.AGREE, medications = many),
        )("case-1", "Explain the prescription in plain language")

        assertTrue(
            "the test record is not actually over budget",
            many.sumOf { ReportFormatter().formatMedicationLine(it).length } > MAX_PROMPT_CHARS,
        )
        assertEquals(SlmReadbackResult.Refused(SlmRefusal.RECORD_TOO_LARGE), result)
        assertEquals(0, engine.callCount)
    }

    @Test
    fun `an engine failure is a failure, with no substitute output`() = runTest {
        val engine = RecordingSlmEngine(failure = IllegalStateException("model not loaded"))

        val result = useCase(session(UserRole.ASHA_WORKER), engine)(
            "case-1", "Explain the prescription in plain language",
        )

        assertEquals(SlmReadbackResult.Refused(SlmRefusal.ENGINE_FAILED), result)
        assertEquals(1, engine.callCount)
    }

    // ------------------------------------------------------------- input scope gate (§5.2/§5.4)

    /**
     * **The worked case: harness finding F7.** A bare "is ibuprofen safe with lisinopril" produced
     * a full drug-interaction answer in the harness. Here it is refused at the input scope gate
     * for both worker-tier cadres, before the engine is reached: `callCount == 0` is the whole
     * assertion. Nothing is generated, so there is no answer to leak.
     *
     * COMMUNITY (`ASHA_WORKER`) and LICENSED_CLINICAL (`NURSE`, `COMPOUNDER`) are all asserted,
     * because §5.1's decision to put LICENSED_CLINICAL in the worker tier is the one most likely
     * to be quietly reversed later.
     */
    @Test
    fun `the harness F7 question is refused for every worker-tier cadre before the engine is reached`() = runTest {
        listOf(UserRole.ASHA_WORKER, UserRole.NURSE, UserRole.COMPOUNDER).forEach { role ->
            val engine = RecordingSlmEngine(output = "Ibuprofen and lisinopril can interact...")

            val result = useCase(session(role), engine)("case-1", "is ibuprofen safe with lisinopril")

            assertEquals(
                "$role reached the model with an untethered interaction question",
                SlmReadbackResult.Refused(SlmRefusal.OUT_OF_SCOPE_PHRASING),
                result,
            )
            assertEquals("the engine was called for $role", 0, engine.callCount)
        }
    }

    /** Entity containment on its own: a readback-shaped question about a drug that is not in the
     *  approved record. No denied phrasing, so this is the containment mechanism refusing. */
    @Test
    fun `refuses a readback-shaped question about a drug absent from the record`() = runTest {
        val engine = RecordingSlmEngine(output = groundedOutput)

        val result = useCase(session(UserRole.ASHA_WORKER), engine)(
            "case-1", "explain how many ibuprofen tablets to give",
        )

        assertEquals(SlmReadbackResult.Refused(SlmRefusal.OUT_OF_SCOPE_UNTETHERED), result)
        assertEquals(0, engine.callCount)
    }

    /** Containment covers dosing numerals too: a dose that is not in the record is not a readback. */
    @Test
    fun `refuses a question naming a dose that is not in the record`() = runTest {
        val engine = RecordingSlmEngine(output = groundedOutput)

        val result = useCase(session(UserRole.ASHA_WORKER), engine)(
            "case-1", "explain how to take 250 mg of this",
        )

        assertEquals(SlmReadbackResult.Refused(SlmRefusal.OUT_OF_SCOPE_UNTETHERED), result)
        assertEquals(0, engine.callCount)
    }

    /** The allowlist refuses an unrecognised shape rather than passing it: an unfamiliar question
     *  is not evidence of safety. */
    @Test
    fun `refuses a question that matches no readback intent`() = runTest {
        val engine = RecordingSlmEngine(output = groundedOutput)

        val result = useCase(session(UserRole.ASHA_WORKER), engine)(
            "case-1", "the patient wants something stronger for her cousin",
        )

        assertEquals(SlmReadbackResult.Refused(SlmRefusal.OUT_OF_SCOPE_INTENT), result)
        assertEquals(0, engine.callCount)
    }

    /** The gate is not a blanket refusal: a tethered readback question reaches the engine. */
    @Test
    fun `a tethered readback question passes the input gate and reaches the engine`() = runTest {
        val engine = RecordingSlmEngine(output = groundedOutput)

        val result = useCase(session(UserRole.ASHA_WORKER), engine)(
            "case-1", "Explain what the doctor prescribed in plain language",
        )

        assertEquals(SlmReadbackResult.Answer(groundedOutput, PROMPT_TEMPLATE_VERSION), result)
        assertEquals(1, engine.callCount)
    }

    // ------------------------------------------------------------------ tier resolution (§5.1)

    @Test
    fun `tier resolution is fail-closed and only PHYSICIAN is open`() {
        assertTrue(session(UserRole.DOCTOR).isOpenSlmTier())
        assertFalse(session(UserRole.NURSE).isOpenSlmTier())
        assertFalse(session(UserRole.COMPOUNDER).isOpenSlmTier())
        assertFalse(session(UserRole.ASHA_WORKER).isOpenSlmTier())
        assertFalse("a null session must be treated as worker tier", null.isOpenSlmTier())
    }

    /** §5.3: the doctor tier may ask the F7 question. The same input that is refused for every
     *  worker cadre reaches the engine here, which is what makes the tier split real rather than
     *  decorative. */
    @Test
    fun `a physician-tier viewer bypasses the input scope gate`() = runTest {
        val openAnswer = "Ibuprofen and lisinopril can interact and reduce the antihypertensive effect."
        val engine = RecordingSlmEngine(output = openAnswer)

        val result = useCase(session(UserRole.DOCTOR), engine)("case-1", "is ibuprofen safe with lisinopril")

        assertEquals(SlmReadbackResult.Answer(openAnswer, PROMPT_TEMPLATE_VERSION), result)
        assertEquals(1, engine.callCount)
    }

    /** Fail-closed in the pipeline, not only in the helper: no session gets the worker gates. */
    @Test
    fun `a null session is gated as a worker and never reaches the engine`() = runTest {
        val engine = RecordingSlmEngine(output = groundedOutput)

        val result = useCase(session = null, engine = engine)("case-1", "is ibuprofen safe with lisinopril")

        assertEquals(SlmReadbackResult.Refused(SlmRefusal.OUT_OF_SCOPE_PHRASING), result)
        assertEquals(0, engine.callCount)
    }

    // ----------------------------------------------------------------- output scope gate (§5.4)

    /**
     * Pass whole: a grounded generation is returned byte-for-byte as the engine produced it.
     * `assertSame` is deliberate - it proves the seam returned the engine's own string rather than
     * a reconstructed one, which is the strongest available statement of "no editing occurred"
     * (§6.2 forbids meaning-level rewriting, including of the hedges this model volunteers, F3).
     */
    @Test
    fun `a grounded output passes through untouched`() = runTest {
        val engine = RecordingSlmEngine(output = groundedOutput)

        val result = useCase(session(UserRole.ASHA_WORKER), engine)(
            "case-1", "Explain what the doctor prescribed in plain language",
        )

        val answer = result as SlmReadbackResult.Answer
        assertSame("the output was rebuilt rather than passed through", groundedOutput, answer.text)
        assertTrue("the model's own hedge was stripped", answer.text.contains("Ask the doctor"))
    }

    /**
     * Suppress whole: a generation naming a drug that is not on the record's medication lines is
     * suppressed entirely, not edited down to its compliant half. The assertion that matters is
     * that no part of the generated text survives into the result, which the type makes true and
     * this test states anyway.
     */
    @Test
    fun `an output introducing a drug absent from the record is wholly suppressed`() = runTest {
        val ungrounded = groundedOutput + " You can also give ibuprofen if the pain continues."
        val engine = RecordingSlmEngine(output = ungrounded)

        val result = useCase(session(UserRole.ASHA_WORKER), engine)(
            "case-1", "Explain what the doctor prescribed in plain language",
        )

        assertEquals(SlmReadbackResult.Refused(SlmRefusal.OUTPUT_NOT_GROUNDED), result)
        assertEquals("the engine did run - this is a post-model suppression", 1, engine.callCount)
        assertFalse("part of the generation survived the suppression", result.toString().contains("ibuprofen"))
        assertFalse("the grounded half survived the suppression", result.toString().contains("Amoxicillin"))
    }

    @Test
    fun `an output introducing a dosing numeral absent from the record is wholly suppressed`() = runTest {
        val engine = RecordingSlmEngine(output = "Take 2 capsules of Amoxicillin four times a day for 10 days.")

        val result = useCase(session(UserRole.ASHA_WORKER), engine)(
            "case-1", "Explain what the doctor prescribed in plain language",
        )

        assertEquals(SlmReadbackResult.Refused(SlmRefusal.OUTPUT_NOT_GROUNDED), result)
        assertEquals(1, engine.callCount)
    }

    /**
     * The gate has no editing mode to reach for. Called directly, it answers a yes/no question and
     * returns no text at all, so "suppress whole or pass whole" is a property of the API rather
     * than of the current call site.
     */
    @Test
    fun `the output gate returns a verdict and never a rewritten string`() {
        val gate = Class.forName("com.example.samdapp.domain.slm.SlmScopeGateKt")
            .declaredMethods.first { it.name.startsWith("outputIsGrounded") }

        assertEquals(Boolean::class.javaPrimitiveType, gate.returnType)
    }

    /** The H-17 interaction, stated as a test: a REJECTed case carries no medication lines for a
     *  worker, so any drug in the generation is ungrounded and the whole output is suppressed. */
    @Test
    fun `on a rejected case a worker-tier readback naming any drug is suppressed`() = runTest {
        val engine = RecordingSlmEngine(output = groundedOutput)

        val result = useCase(
            session(UserRole.ASHA_WORKER), engine, prescription = prescription(KernelDecision.REJECT),
        )("case-1", "Explain what the doctor prescribed in plain language")

        assertEquals(SlmReadbackResult.Refused(SlmRefusal.OUTPUT_NOT_GROUNDED), result)
        assertEquals(1, engine.callCount)
    }
}
