package com.example.samdapp.domain.slm

import com.example.samdapp.domain.auth.UserRole
import com.example.samdapp.domain.auth.UserSession
import com.example.samdapp.domain.model.CaseRecord
import com.example.samdapp.domain.model.CaseStatus
import com.example.samdapp.domain.model.KernelDecision
import com.example.samdapp.domain.model.MedicationLine
import com.example.samdapp.domain.model.Patient
import com.example.samdapp.domain.model.Prescription
import com.example.samdapp.domain.model.Visibility
import com.example.samdapp.domain.report.ClinicalReport
import com.example.samdapp.domain.report.ReportAudience
import com.example.samdapp.domain.report.ReportFormatter
import com.example.samdapp.domain.report.ReportPatientBlock
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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier
import java.time.Instant

/**
 * Stage 1 of the SLM build: the input contract only (design memo
 * `scratchpad/slm-guardrail-service-contract-memo.md` §4.1/§4.2/§10.2). Structure first, then the
 * three refusal paths, then the audience-derivation guard proved against assembled content rather
 * than against the enum alone.
 *
 * Independent sourcing, per CLAUDE.md: every behavioural test runs the REAL [ReportFormatter] and
 * the REAL [AssembleReportUseCase] over fake repositories, so what is asserted is the content the
 * production assembly path actually produces, not a hand-built stand-in. Expected medication text
 * is computed by calling [ReportFormatter.formatMedicationLine] independently, not copied from the
 * value under test. There is no write on this path, so the persisted-row rule has nothing to bite
 * on here; its sibling — assert the content, not the return code — is what the audience test does.
 */
class ApprovedRecordReaderTest {

    private val nameCanary = "Anita Kumari"
    private val mobileCanary = "9998887776"
    private val abhaCanary = "12345678901234"
    private val villageCanary = "Rampur"
    private val privateAilmentCanary = "PRIVATE-AILMENT-CANARY sensitive condition"

    private val hotPatient: Patient = testPatient(id = "p1", fullName = nameCanary).copy(
        mobileNumber = mobileCanary,
        abhaNumber = abhaCanary,
        village = villageCanary,
        aadhaarNumber = "123412341234",
        guardianOrSpouseName = "Guardian $nameCanary",
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

    private fun prescription(
        decision: KernelDecision?,
        medications: List<MedicationLine> = listOf(amoxicillin),
    ) = Prescription(
        id = "rx-1", patientId = "p1", encounterId = "e1", caseRecordId = "case-1", doctorId = "doc-1",
        diagnosis = "Acute bacterial pharyngitis", medications = medications,
        kernelDecision = decision, createdAt = Instant.EPOCH,
    )

    /** Builds the reader over the real formatter/assembler and fake repositories. */
    private suspend fun reader(
        session: UserSession?,
        cases: List<CaseRecord> = listOf(caseRecord),
        patient: Patient? = hotPatient,
        prescription: Prescription? = null,
        privateAilment: Boolean = false,
    ): ApprovedRecordReader {
        val caseRecords = FakeCaseRecordRepository(cases)
        val patients = FakePatientRepository()
        patient?.let { patients.register(it) }
        val prescriptions = FakePrescriptionRepository()
        prescription?.let { prescriptions.save(it) }
        val ailments = FakeAilmentRepository()
        ailments.addAilment(testAilmentEntry(id = "a-public", description = "Sore throat, 3 days"))
        if (privateAilment) {
            ailments.addAilment(
                testAilmentEntry(id = "a-private", visibility = Visibility.PRIVATE, description = privateAilmentCanary),
            )
        }
        return ApprovedRecordReader(
            authSession = FakeAuthSession(session),
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
        )
    }

    private fun session(role: UserRole) = UserSession(userId = "u-1", name = "Test User", role = role)

    // ---------------------------------------------------------------- structural (H-10 pattern)

    /**
     * The construction guarantee, not the convention: [ApprovedRecordSnapshot] has no field that
     * could hold patient identity. Mirrors the H-10 control on
     * [com.example.samdapp.domain.model.KernelPayload].
     */
    @Test
    fun `snapshot type structurally cannot carry patient identity`() {
        val identityTypes = setOf(
            Patient::class.java, ReportPatientBlock::class.java, ClinicalReport::class.java,
        )
        val identityNameFragments = listOf(
            "patient", "name", "abha", "mobile", "phone", "address", "aadhaar", "guardian", "village", "pincode",
        )

        // The Compose compiler adds a static `$stable` field to every class it sees; instance
        // fields are the ones that can carry data.
        val fields = ApprovedRecordSnapshot::class.java.declaredFields
            .filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) }
        assertEquals(
            "snapshot field set changed - re-justify every field against memo section 4.2 before updating this test",
            listOf("caseRecordId", "kernelDecision", "diagnosis", "medicationLines", "suggestsReferral"),
            fields.map { it.name },
        )
        fields.forEach { field ->
            assertFalse(
                "field ${field.name} is of an identity-carrying type ${field.type.simpleName}",
                field.type in identityTypes,
            )
            identityNameFragments.forEach { fragment ->
                assertFalse(
                    "field ${field.name} looks like an identity field ($fragment)",
                    field.name.lowercase().contains(fragment),
                )
            }
        }
    }

    /**
     * §4.2: the reader's signature takes a case record id, not a `Patient` — and §10.2: the
     * audience is never a caller-supplied parameter.
     */
    @Test
    fun `reader signature takes a caseRecordId and never a Patient or an audience`() {
        val forbidden = setOf(
            Patient::class.java, ReportPatientBlock::class.java, ReportAudience::class.java,
        )
        val invoke = ApprovedRecordReader::class.java.declaredMethods.single { it.name == "invoke" }

        // caseRecordId (String) + the suspend Continuation, and nothing else.
        assertEquals("String", invoke.parameterTypes.first().simpleName)
        assertEquals(2, invoke.parameterTypes.size)

        (ApprovedRecordReader::class.java.declaredMethods.flatMap { it.parameterTypes.toList() } +
            ApprovedRecordReader::class.java.declaredConstructors.flatMap { it.parameterTypes.toList() })
            .forEach { type ->
                assertFalse("forbidden parameter type ${type.simpleName} on the reader", type in forbidden)
            }
    }

    // ------------------------------------------------------------------------ refusal paths

    @Test
    fun `refuses with NOT_APPROVED when the case has no committed kernelDecision`() = runTest {
        // A prescription exists but the physician has not decided: the record is not approved.
        val result = reader(session(UserRole.ASHA_WORKER), prescription = prescription(decision = null))("case-1")

        assertEquals(ApprovedRecordResult.Refused(SnapshotRefusal.NOT_APPROVED), result)
    }

    @Test
    fun `refuses with NOT_APPROVED when there is no prescription at all`() = runTest {
        val result = reader(session(UserRole.ASHA_WORKER), prescription = null)("case-1")

        assertEquals(ApprovedRecordResult.Refused(SnapshotRefusal.NOT_APPROVED), result)
    }

    @Test
    fun `refuses with CASE_UNRESOLVABLE when the case record does not exist`() = runTest {
        val result = reader(session(UserRole.ASHA_WORKER), cases = emptyList())("case-1")

        assertEquals(ApprovedRecordResult.Refused(SnapshotRefusal.CASE_UNRESOLVABLE), result)
    }

    /**
     * Assembly failure via the real formatter: REQ-RX-02 makes
     * [ReportFormatter.formatMedicationLine] throw on a banned Latin abbreviation, which is a
     * thrown exception rather than a failed `Result`. It must land on a refusal, not propagate.
     */
    @Test
    fun `refuses with ASSEMBLY_FAILED when the formatter rejects a stored medication line`() = runTest {
        val banned = amoxicillin.copy(frequency = "BD")
        val result = reader(
            session(UserRole.ASHA_WORKER),
            prescription = prescription(KernelDecision.AGREE, medications = listOf(banned)),
        )("case-1")

        assertEquals(ApprovedRecordResult.Refused(SnapshotRefusal.ASSEMBLY_FAILED), result)
    }

    @Test
    fun `refuses with ASSEMBLY_FAILED when the case resolves but its patient row is missing`() = runTest {
        val result = reader(
            session(UserRole.ASHA_WORKER),
            patient = null,
            prescription = prescription(KernelDecision.AGREE),
        )("case-1")

        assertEquals(ApprovedRecordResult.Refused(SnapshotRefusal.ASSEMBLY_FAILED), result)
    }

    // ------------------------------------------------------- audience derivation (memo 10.2)

    @Test
    fun `audience is derived from the live cadre tier and is fail-closed on a null session`() {
        assertEquals(ReportAudience.PHYSICIAN, session(UserRole.DOCTOR).toReportAudience())
        assertEquals(ReportAudience.WORKER, session(UserRole.NURSE).toReportAudience())
        assertEquals(ReportAudience.WORKER, session(UserRole.COMPOUNDER).toReportAudience())
        assertEquals(ReportAudience.WORKER, session(UserRole.ASHA_WORKER).toReportAudience())
        assertEquals(ReportAudience.WORKER, null.toReportAudience())
    }

    /**
     * The content-level proof, not the enum-level one. A REJECTed case is the case where the
     * WORKER audience visibly changes the assembled record: the H-17 gate strips the medication
     * lines for a worker and leaves them for the physician. Reading the SAME case as both viewers
     * shows the derived audience actually reached assembly. The PRIVATE ailment canary is checked
     * over the whole snapshot at the same time — the snapshot carries no ailment field at all,
     * which is the stronger guarantee, and this asserts that stays true.
     */
    @Test
    fun `worker-tier viewer gets a WORKER-audience snapshot with no rejected-case medications and no private ailment text`() = runTest {
        val rejected = prescription(KernelDecision.REJECT)

        val worker = reader(session(UserRole.ASHA_WORKER), prescription = rejected, privateAilment = true)("case-1")
        val physician = reader(session(UserRole.DOCTOR), prescription = rejected, privateAilment = true)("case-1")

        val workerSnapshot = (worker as ApprovedRecordResult.Available).snapshot
        val physicianSnapshot = (physician as ApprovedRecordResult.Available).snapshot

        // WORKER audience reached the formatter: the gate stripped the medication lines.
        assertEquals(emptyList<String>(), workerSnapshot.medicationLines)
        // Same case, PHYSICIAN audience: the lines are there, so the difference is the audience,
        // not an empty prescription.
        assertEquals(
            listOf(ReportFormatter().formatMedicationLine(amoxicillin)),
            physicianSnapshot.medicationLines,
        )

        assertFalse(
            "PRIVATE ailment text reached the worker-tier snapshot",
            workerSnapshot.toString().contains(privateAilmentCanary),
        )
        listOf(nameCanary, mobileCanary, abhaCanary, villageCanary).forEach { canary ->
            assertFalse("identity value '$canary' reached the snapshot", workerSnapshot.toString().contains(canary))
            assertFalse("identity value '$canary' reached the snapshot", physicianSnapshot.toString().contains(canary))
        }
    }

    // ------------------------------------------------------------------------- approved path

    @Test
    fun `an approved case yields a snapshot carrying only the whitelisted readback fields`() = runTest {
        val result = reader(
            session(UserRole.ASHA_WORKER),
            prescription = prescription(KernelDecision.AGREE),
            privateAilment = true,
        )("case-1")

        val snapshot = (result as ApprovedRecordResult.Available).snapshot
        assertEquals("case-1", snapshot.caseRecordId)
        assertEquals(KernelDecision.AGREE, snapshot.kernelDecision)
        assertEquals("Acute bacterial pharyngitis", snapshot.diagnosis)
        assertEquals(listOf(ReportFormatter().formatMedicationLine(amoxicillin)), snapshot.medicationLines)
        assertFalse(snapshot.suggestsReferral)
        assertTrue(
            "medication line must be full-text per REQ-RX-02",
            snapshot.medicationLines.single().contains("three times a day"),
        )
    }
}
