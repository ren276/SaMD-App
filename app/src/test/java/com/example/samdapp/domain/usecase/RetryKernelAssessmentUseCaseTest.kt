package com.example.samdapp.domain.usecase

import com.example.samdapp.domain.model.CaseRecord
import com.example.samdapp.domain.model.CaseStatus
import com.example.samdapp.domain.model.Encounter
import com.example.samdapp.domain.model.InferenceSource
import com.example.samdapp.domain.kernel.KernelAssessmentResult
import com.example.samdapp.domain.kernel.RemoteKernelSource
import com.example.samdapp.domain.model.KernelPayload
import com.example.samdapp.testutil.FakeCaseRecordRepository
import com.example.samdapp.testutil.FakeConsultationRepository
import com.example.samdapp.testutil.FakeDeviceInfoProvider
import com.example.samdapp.testutil.FakeEncounterRepository
import com.example.samdapp.testutil.FakeKernelFallbackSource
import com.example.samdapp.testutil.FakeKernelReportRepository
import com.example.samdapp.testutil.FakePatientRepository
import com.example.samdapp.testutil.FakeVitalsRepository
import com.example.samdapp.testutil.testConsultation
import com.example.samdapp.testutil.testPatient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Kernel-mock production safety fix: the retry affordance on `KernelAssessmentScreen`'s
 *  UNAVAILABLE state re-runs the whole real-call pipeline purely from a case record id. */
class RetryKernelAssessmentUseCaseTest {

    private fun useCase(
        caseRecord: CaseRecord,
        encounter: Encounter,
        remoteKernelSource: RemoteKernelSource,
        kernelFallbackSource: FakeKernelFallbackSource = FakeKernelFallbackSource(),
    ): RetryKernelAssessmentUseCase {
        val patientRepo = FakePatientRepository().apply { registered = testPatient(encounter.patientId) }
        return RetryKernelAssessmentUseCase(
            caseRecordRepository = FakeCaseRecordRepository(initial = listOf(caseRecord)),
            vitalsRepository = FakeVitalsRepository(
                latestByEncounter = mapOf(
                    encounter.id to com.example.samdapp.domain.model.VitalsSnapshot(
                        encounterId = encounter.id,
                        patientId = encounter.patientId,
                        pulseBpm = 80,
                        recordedAt = Instant.EPOCH,
                    ),
                ),
            ),
            consultationRepository = FakeConsultationRepository(byEncounter = mapOf(encounter.id to testConsultation(encounter.id, encounter.patientId))),
            encounterRepository = FakeEncounterRepository(initialEncounters = listOf(encounter)),
            patientRepository = patientRepo,
            sendToKernelUseCase = SendToKernelUseCase(),
            generateKernelReportUseCase = GenerateKernelReportUseCase(
                FakeKernelReportRepository(), FakeDeviceInfoProvider(), remoteKernelSource, kernelFallbackSource,
            ),
        )
    }

    private fun caseRecord(id: String, encounterId: String) = CaseRecord(
        id = id, patientId = "p1", encounterId = encounterId, status = CaseStatus.SENT_TO_DOCTOR,
        assignedDoctorId = null, createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
    )

    private fun encounter(id: String) = Encounter(
        id = id, patientId = "p1", startedAt = Instant.EPOCH,
        createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH, followUpOfEncounterId = null,
    )

    private object AlwaysFailsKernelSource : RemoteKernelSource {
        override suspend fun assess(payload: KernelPayload, patientAge: Int, patientSex: String): KernelAssessmentResult =
            throw java.io.IOException("still unreachable")
    }

    private object AlwaysSucceedsKernelSource : RemoteKernelSource {
        override suspend fun assess(payload: KernelPayload, patientAge: Int, patientSex: String): KernelAssessmentResult =
            KernelAssessmentResult(
                predictedCondition = "Viral fever", confidenceScore = 0.9, triageUrgency = "ROUTINE",
                safetyScreenPassed = true, evidenceFor = emptyList(), evidenceAgainst = emptyList(),
                differentials = emptyList(), recommendedInvestigations = emptyList(), modelVersion = "v1",
            )
    }

    @Test
    fun `retry against a case record that no longer exists fails cleanly`() = runTest {
        val patientRepo = FakePatientRepository()
        val retry = RetryKernelAssessmentUseCase(
            caseRecordRepository = FakeCaseRecordRepository(),
            vitalsRepository = FakeVitalsRepository(),
            consultationRepository = FakeConsultationRepository(),
            encounterRepository = FakeEncounterRepository(),
            patientRepository = patientRepo,
            sendToKernelUseCase = SendToKernelUseCase(),
            generateKernelReportUseCase = GenerateKernelReportUseCase(
                FakeKernelReportRepository(), FakeDeviceInfoProvider(), AlwaysFailsKernelSource, FakeKernelFallbackSource(),
            ),
        )

        val result = retry("missing-case")

        assertTrue(result.isFailure)
    }

    @Test
    fun `retry that succeeds this time stamps REAL_INFERENCE`() = runTest {
        val enc = encounter("enc-1")
        val retry = useCase(caseRecord("case-1", "enc-1"), enc, AlwaysSucceedsKernelSource)

        val output = retry("case-1").getOrThrow()

        assertEquals(InferenceSource.REAL_INFERENCE, output.inferenceSource)
    }

    @Test
    fun `retry that still fails, still no fallback bound, stays honestly UNAVAILABLE`() = runTest {
        val enc = encounter("enc-1")
        val retry = useCase(caseRecord("case-1", "enc-1"), enc, AlwaysFailsKernelSource, FakeKernelFallbackSource(result = null))

        val output = retry("case-1").getOrThrow()

        assertEquals(InferenceSource.UNAVAILABLE, output.inferenceSource)
    }
}
