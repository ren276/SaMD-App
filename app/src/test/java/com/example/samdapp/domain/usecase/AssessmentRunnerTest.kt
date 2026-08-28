package com.example.samdapp.domain.usecase

import com.example.samdapp.domain.audit.AuditAction
import com.example.samdapp.domain.kernel.EvaluateKernelSource
import com.example.samdapp.domain.kernel.EvaluateResult
import com.example.samdapp.domain.kernel.KernelAssessmentResult
import com.example.samdapp.domain.kernel.RemoteKernelSource
import com.example.samdapp.domain.model.CaseRecord
import com.example.samdapp.domain.model.CaseStatus
import com.example.samdapp.domain.model.DoctorTrackerEntry
import com.example.samdapp.domain.model.Encounter
import com.example.samdapp.domain.model.EvaluateDiagnosticSummary
import com.example.samdapp.domain.model.EvaluateNlemTreatment
import com.example.samdapp.domain.model.EvaluateSafetyAndTriage
import com.example.samdapp.domain.model.InferenceSource
import com.example.samdapp.domain.model.KernelPayload
import com.example.samdapp.domain.repository.CaseRecordRepository
import com.example.samdapp.testutil.FakeAuditLogger
import com.example.samdapp.testutil.FakeCaseRecordRepository
import com.example.samdapp.testutil.FakeConsultationRepository
import com.example.samdapp.testutil.FakeDeviceInfoProvider
import com.example.samdapp.testutil.FakeEncounterRepository
import com.example.samdapp.testutil.FakeEvaluateReportRepository
import com.example.samdapp.testutil.FakeKernelFallbackSource
import com.example.samdapp.testutil.FakeKernelReportRepository
import com.example.samdapp.testutil.FakePatientRepository
import com.example.samdapp.testutil.FakeVitalsRepository
import com.example.samdapp.testutil.testConsultation
import com.example.samdapp.testutil.testPatient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The single orchestrator behind the async submission queue's first assessment AND every retry
 * (the deleted `RetryKernelAssessmentUseCase`'s replacement — see the queue-seams design memo's
 * Seam 1). Assertions read persisted state off the fake repositories, not `run`'s (`Unit`)
 * return value, per the project's rule that a write-survives-failure-path test must check the
 * row, not the caller-visible result.
 */
class AssessmentRunnerTest {

    private object AlwaysFailsKernelSource : RemoteKernelSource {
        override suspend fun assess(payload: KernelPayload, patientAge: Int, patientSex: String): KernelAssessmentResult =
            throw java.io.IOException("unreachable")
    }

    private object AlwaysSucceedsKernelSource : RemoteKernelSource {
        override suspend fun assess(payload: KernelPayload, patientAge: Int, patientSex: String): KernelAssessmentResult =
            KernelAssessmentResult(
                predictedCondition = "Viral fever", confidenceScore = 0.9, triageUrgency = "ROUTINE",
                safetyScreenPassed = true, evidenceFor = emptyList(), evidenceAgainst = emptyList(),
                differentials = emptyList(), recommendedInvestigations = emptyList(), modelVersion = "v1",
            )
    }

    private object AlwaysSucceedsEvaluateSource : EvaluateKernelSource {
        override suspend fun evaluate(payload: KernelPayload, patientAge: Int, patientSex: String): EvaluateResult =
            EvaluateResult(
                diagnosticSummary = EvaluateDiagnosticSummary(
                    primaryIcdCandidate = "J11", primaryAilmentName = "Viral fever", differential = emptyList(),
                ),
                nlemTreatment = EvaluateNlemTreatment(
                    recommendedDrug = "Paracetamol", levelOfHealthcare = listOf("PHC"), availableAtPHC = true,
                    dosageForms = listOf("Tablet"), pediatricDose = null, citation = null, confidence = null,
                    referralReason = null, matchedDisease = null,
                ),
                brandMapping = null,
                safetyAndTriage = EvaluateSafetyAndTriage(
                    vitalsTriage = null, requiresHumanReview = false, pediatricReferralFlag = false, failureReason = null,
                ),
            )
    }

    private object AlwaysFailsEvaluateSource : EvaluateKernelSource {
        override suspend fun evaluate(payload: KernelPayload, patientAge: Int, patientSex: String): EvaluateResult =
            throw java.io.IOException("evaluate unreachable")
    }

    /** Throws from the very first read - stands in for an unexpected resolve-stage exception
     *  (not a missing-data null, a genuine crash), so the class-2 catch has something to catch. */
    private object ThrowingCaseRecordRepository : CaseRecordRepository {
        override suspend fun createDraft(patientId: String, encounterId: String) = error("not used")
        override suspend fun markSavedLocally(caseRecordId: String) = error("not used")
        override suspend fun assignDoctor(caseRecordId: String, doctorId: String, isOnline: Boolean) = error("not used")
        override suspend fun sendAllPendingCases() = error("not used")
        override fun observePendingSyncCount(): Flow<Int> = error("not used")
        override suspend fun markPrescriptionReceived(caseRecordId: String) = error("not used")
        override fun observeCaseRecord(caseRecordId: String): Flow<CaseRecord?> = throw IllegalStateException("DB unavailable")
        override suspend fun getDayOrdinal(caseRecordId: String): Int? = error("not used")
        override fun observeLatestForPatient(patientId: String): Flow<CaseRecord?> = error("not used")
        override fun observeByEncounterId(encounterId: String): Flow<CaseRecord?> = error("not used")
        override fun observeResumableDraftForUser(userId: String): Flow<CaseRecord?> = error("not used")
        override fun observeOpenCaseCount(doctorId: String): Flow<Int> = error("not used")
        override fun observeDoctorTrackerRows(): Flow<List<DoctorTrackerEntry>> = error("not used")
    }

    private fun defaultCaseRecord() = CaseRecord(
        id = "case-1", patientId = "p1", encounterId = "enc-1", status = CaseStatus.SENT_TO_DOCTOR,
        assignedDoctorId = null, createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
    )

    private fun defaultEncounter() = Encounter(
        id = "enc-1", patientId = "p1", startedAt = Instant.EPOCH,
        createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH, followUpOfEncounterId = null,
    )

    private inner class Fixture(
        val kernelReportRepository: FakeKernelReportRepository = FakeKernelReportRepository(),
        val evaluateReportRepository: FakeEvaluateReportRepository = FakeEvaluateReportRepository(),
        val auditLogger: FakeAuditLogger = FakeAuditLogger(),
        caseRecordRepository: CaseRecordRepository = FakeCaseRecordRepository(initial = listOf(defaultCaseRecord())),
        vitalsRepository: FakeVitalsRepository = FakeVitalsRepository(
            latestByEncounter = mapOf(
                "enc-1" to com.example.samdapp.domain.model.VitalsSnapshot(
                    encounterId = "enc-1", patientId = "p1", pulseBpm = 80, recordedAt = Instant.EPOCH,
                ),
            ),
        ),
        consultationRepository: FakeConsultationRepository = FakeConsultationRepository(byEncounter = mapOf("enc-1" to testConsultation("enc-1"))),
        encounterRepository: FakeEncounterRepository = FakeEncounterRepository(initialEncounters = listOf(defaultEncounter())),
        patientRepository: FakePatientRepository = FakePatientRepository().apply { registered = testPatient("p1") },
        kernelSource: RemoteKernelSource = AlwaysSucceedsKernelSource,
        evaluateSource: EvaluateKernelSource = AlwaysSucceedsEvaluateSource,
    ) {
        val runner = AssessmentRunner(
            caseRecordRepository = caseRecordRepository,
            vitalsRepository = vitalsRepository,
            consultationRepository = consultationRepository,
            encounterRepository = encounterRepository,
            patientRepository = patientRepository,
            sendToKernelUseCase = SendToKernelUseCase(),
            generateKernelReportUseCase = GenerateKernelReportUseCase(
                kernelReportRepository, FakeDeviceInfoProvider(), kernelSource, FakeKernelFallbackSource(result = null), auditLogger,
            ),
            generateEvaluateReportUseCase = GenerateEvaluateReportUseCase(evaluateReportRepository, evaluateSource, com.example.samdapp.testutil.FakeBrandLookupSource()),
            auditLogger = auditLogger,
        )
    }

    @Test
    fun `happy path saves both a kernel and an evaluate report, and audits both`() = runTest {
        val fixture = Fixture()

        fixture.runner.run("case-1")

        val kernelOutput = fixture.kernelReportRepository.saved["case-1"]
        assertEquals(InferenceSource.REAL_INFERENCE, kernelOutput?.inferenceSource)
        val evaluateOutput = fixture.evaluateReportRepository.saved["case-1"]
        assertEquals("J11", evaluateOutput?.diagnosticSummary?.primaryIcdCandidate)
        assertTrue(fixture.auditLogger.logged.any { it.action == AuditAction.KERNEL_RESPONSE_RECEIVED.value })
        assertTrue(fixture.auditLogger.logged.any { it.action == AuditAction.EVALUATE_RESPONSE_RECEIVED.value })
    }

    @Test
    fun `missing vitals collapses to a written UNAVAILABLE row, not a silent no-op`() = runTest {
        val fixture = Fixture(vitalsRepository = FakeVitalsRepository())

        fixture.runner.run("case-1")

        val saved = fixture.kernelReportRepository.saved["case-1"]
        assertEquals(InferenceSource.UNAVAILABLE, saved?.inferenceSource)
        assertNull(fixture.evaluateReportRepository.saved["case-1"])
    }

    @Test
    fun `missing consultation collapses to a written UNAVAILABLE row, not a silent no-op`() = runTest {
        val fixture = Fixture(consultationRepository = FakeConsultationRepository())

        fixture.runner.run("case-1")

        val saved = fixture.kernelReportRepository.saved["case-1"]
        assertEquals(InferenceSource.UNAVAILABLE, saved?.inferenceSource)
    }

    @Test
    fun `unexpected exception during resolve is caught and collapses to the same UNAVAILABLE row`() = runTest {
        val fixture = Fixture(caseRecordRepository = ThrowingCaseRecordRepository)

        fixture.runner.run("case-1")

        val saved = fixture.kernelReportRepository.saved["case-1"]
        assertEquals(InferenceSource.UNAVAILABLE, saved?.inferenceSource)
    }

    @Test
    fun `stage 3 kernel failure is not swallowed by the class-2 catch - it is not reachable, kernel never throws`() = runTest {
        // GenerateKernelReportUseCase never throws (it falls through to its own UNAVAILABLE
        // state internally) - this asserts that shape holds through the runner too: a
        // kernel-source failure still yields a real REAL_INFERENCE-absent, honest row, from
        // GenerateKernelReportUseCase's own fallback path, not from AssessmentRunner's catch.
        val fixture = Fixture(kernelSource = AlwaysFailsKernelSource)

        fixture.runner.run("case-1")

        val saved = fixture.kernelReportRepository.saved["case-1"]
        assertEquals(InferenceSource.UNAVAILABLE, saved?.inferenceSource)
    }

    @Test
    fun `stage 4 evaluate failure is audited and does not fail the run - the kernel row still saves`() = runTest {
        val fixture = Fixture(evaluateSource = AlwaysFailsEvaluateSource)

        fixture.runner.run("case-1")

        val kernelOutput = fixture.kernelReportRepository.saved["case-1"]
        assertEquals(InferenceSource.REAL_INFERENCE, kernelOutput?.inferenceSource)
        assertNull(fixture.evaluateReportRepository.saved["case-1"])
        assertTrue(fixture.auditLogger.logged.any { it.action == AuditAction.EVALUATE_RESPONSE_FAILED.value })
        assertTrue(fixture.auditLogger.logged.any { it.action == AuditAction.KERNEL_RESPONSE_RECEIVED.value })
    }

    @Test
    fun `no case record at all collapses to a written UNAVAILABLE row`() = runTest {
        val fixture = Fixture(caseRecordRepository = FakeCaseRecordRepository())

        fixture.runner.run("no-such-case")

        val saved = fixture.kernelReportRepository.saved["no-such-case"]
        assertEquals(InferenceSource.UNAVAILABLE, saved?.inferenceSource)
    }
}
