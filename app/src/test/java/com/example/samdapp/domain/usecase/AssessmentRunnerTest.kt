package com.example.samdapp.domain.usecase

import com.example.samdapp.domain.audit.AuditAction
import com.example.samdapp.domain.kernel.EvaluateKernelSource
import com.example.samdapp.domain.kernel.EvaluateResult
import com.example.samdapp.domain.kernel.KernelApiResult
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
        override suspend fun assess(
            payload: KernelPayload,
            patientAge: Int,
            patientSex: String,
        ): KernelApiResult<KernelAssessmentResult> =
            KernelApiResult.Unreachable(java.io.IOException("unreachable"))
    }

    private object AlwaysSucceedsKernelSource : RemoteKernelSource {
        override suspend fun assess(
            payload: KernelPayload,
            patientAge: Int,
            patientSex: String,
        ): KernelApiResult<KernelAssessmentResult> = KernelApiResult.Success(
            KernelAssessmentResult(
                predictedCondition = "Viral fever", confidenceScore = 0.9, triageUrgency = "ROUTINE",
                safetyScreenPassed = true, evidenceFor = emptyList(), evidenceAgainst = emptyList(),
                differentials = emptyList(), recommendedInvestigations = emptyList(), modelVersion = "v1",
            ),
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
        // Server-present, so the gate passes and resolve is still what throws.
        override suspend fun assessGateSnapshot(caseRecordId: String) = com.example.samdapp.domain.model.AssessGateSnapshot(
            caseRecord = com.example.samdapp.domain.model.SyncChainRow(com.example.samdapp.domain.model.SyncState.SYNCED, 1, null, null),
            encounter = null, patient = null,
        )
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
        val syncStatus: com.example.samdapp.domain.sync.SyncStatus = com.example.samdapp.testutil.FakeSyncStatus(),
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
            syncStatus = syncStatus,
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

    /** The regression guard for SAMD-ENC-4002. Both kernel legs are backend proxies that resolve
     *  the case record server-side, and a device-created case exists only locally until the
     *  outbox drains — so the push has to have happened by the time the kernel call goes out. */
    @Test
    fun `the case is pushed before the kernel call runs`() = runTest {
        val syncStatus = com.example.samdapp.testutil.FakeSyncStatus()
        var syncCallsSeenByKernel = -1
        val recordingKernel = object : RemoteKernelSource {
            override suspend fun assess(
                payload: KernelPayload,
                patientAge: Int,
                patientSex: String,
            ): KernelApiResult<KernelAssessmentResult> {
                syncCallsSeenByKernel = syncStatus.syncInProcessCalls
                return AlwaysSucceedsKernelSource.assess(payload, patientAge, patientSex)
            }
        }
        val fixture = Fixture(kernelSource = recordingKernel, syncStatus = syncStatus)

        fixture.runner.run("case-1")

        assertEquals(1, syncCallsSeenByKernel)
    }

    /** The runner drains in this process. Going through the WorkManager request answered "did not
     *  succeed" for a drain that was only in its retry backoff, which is how a case that was
     *  seconds from the server read as not sent (live check 2, 2026-10-06). */
    @Test
    fun `the pre-assessment push is the in-process one, never the WorkManager one`() = runTest {
        val syncStatus = com.example.samdapp.testutil.FakeSyncStatus()
        val fixture = Fixture(syncStatus = syncStatus)

        fixture.runner.run("case-1")

        assertEquals(1, syncStatus.syncInProcessCalls)
        assertEquals(0, syncStatus.syncCalls)
    }

    /** A push that fails (offline, backend down) must not abort the assessment. The kernel call
     *  then fails on its own and lands in the UNAVAILABLE state that already exists; swallowing
     *  the assessment here would lose that honest outcome. */
    @Test
    fun `a failed pre-assessment sync still lets the assessment run`() = runTest {
        val fixture = Fixture(syncStatus = FailingSyncStatus)

        fixture.runner.run("case-1")

        assertEquals(InferenceSource.REAL_INFERENCE, fixture.kernelReportRepository.saved["case-1"]?.inferenceSource)
    }

    /** Nothing to assess means nothing worth pushing for: the unavailable row is written without
     *  a network round trip. */
    @Test
    fun `a case that cannot be resolved is never pushed`() = runTest {
        val syncStatus = com.example.samdapp.testutil.FakeSyncStatus()
        val fixture = Fixture(vitalsRepository = FakeVitalsRepository(), syncStatus = syncStatus)

        fixture.runner.run("case-1")

        assertEquals(InferenceSource.UNAVAILABLE, fixture.kernelReportRepository.saved["case-1"]?.inferenceSource)
        assertEquals(0, syncStatus.syncInProcessCalls)
    }

    private object FailingSyncStatus : com.example.samdapp.domain.sync.SyncStatus {
        override val state = kotlinx.coroutines.flow.flowOf(com.example.samdapp.domain.sync.SyncState())
        override suspend fun syncNow(): Result<Unit> = Result.failure(IllegalStateException("offline"))
        override suspend fun syncNowInProcess(): Result<Unit> = Result.failure(IllegalStateException("offline"))
        override suspend fun stateNow() = com.example.samdapp.domain.sync.SyncState()
        override suspend fun failedRecords(): List<com.example.samdapp.domain.sync.FailedSyncRecord> = emptyList()
        override suspend fun sendFailedRecordAgain(record: com.example.samdapp.domain.sync.FailedSyncRecord) = Unit
    }

    // ── Gate on this case's own server presence (memo section 3.2) ──────────────

    private class CountingKernelSource : RemoteKernelSource {
        var calls = 0
        override suspend fun assess(payload: KernelPayload, patientAge: Int, patientSex: String) =
            AlwaysSucceedsKernelSource.assess(payload, patientAge, patientSex).also { calls++ }
    }

    private fun chain(state: com.example.samdapp.domain.model.SyncState, serverVersion: Int? = null, code: String? = null, message: String? = null) =
        com.example.samdapp.domain.model.SyncChainRow(state, serverVersion, code, message)

    @Test
    fun `a duplicate-ABHA patient stops the assessment before any call, whatever syncNow returned`() = runTest {
        // syncNow() succeeds (a 200 batch can still reject the patient), so a gate on the global
        // Result would proceed into a 404. The gate reads this case's own chain instead.
        val caseRecords = FakeCaseRecordRepository(initial = listOf(defaultCaseRecord())).apply {
            gateSnapshots["case-1"] = com.example.samdapp.domain.model.AssessGateSnapshot(
                caseRecord = chain(com.example.samdapp.domain.model.SyncState.RETRYABLE),
                encounter = chain(com.example.samdapp.domain.model.SyncState.RETRYABLE),
                patient = chain(
                    com.example.samdapp.domain.model.SyncState.FAILED,
                    code = com.example.samdapp.domain.model.SYNC_RECORD_INVALID_CODE,
                    message = com.example.samdapp.domain.model.BackendConstraintMessages.UNIQUE_VIOLATION,
                ),
            )
        }
        val kernel = CountingKernelSource()
        val fixture = Fixture(caseRecordRepository = caseRecords, kernelSource = kernel)

        fixture.runner.run("case-1")

        assertEquals("no /assess call for a case the server cannot hold", 0, kernel.calls)
        val saved = fixture.kernelReportRepository.saved["case-1"]
        assertEquals(InferenceSource.UNAVAILABLE, saved?.inferenceSource)
        assertEquals(com.example.samdapp.domain.kernel.KernelFailure.PATIENT_DUPLICATE, saved?.failureCode)
        assertEquals("PATIENT_DUPLICATE", fixture.evaluateReportRepository.failures["case-1"])
        val audit = fixture.auditLogger.logged.single { it.action == AuditAction.KERNEL_RESPONSE_RECEIVED.value }
        assertTrue(audit.payload, audit.payload.contains("PATIENT_DUPLICATE") && audit.payload.contains("UNAVAILABLE"))
    }

    @Test
    fun `a case the server holds is assessed even after a local edit reset it to PENDING`() = runTest {
        val caseRecords = FakeCaseRecordRepository(initial = listOf(defaultCaseRecord())).apply {
            gateSnapshots["case-1"] = com.example.samdapp.domain.model.AssessGateSnapshot(
                caseRecord = chain(com.example.samdapp.domain.model.SyncState.PENDING, serverVersion = 4),
                encounter = chain(com.example.samdapp.domain.model.SyncState.PENDING, serverVersion = 2),
                patient = chain(com.example.samdapp.domain.model.SyncState.SYNCED, serverVersion = 1),
            )
        }
        val kernel = CountingKernelSource()
        val fixture = Fixture(caseRecordRepository = caseRecords, kernelSource = kernel)

        fixture.runner.run("case-1")

        assertEquals(1, kernel.calls)
        assertEquals(InferenceSource.REAL_INFERENCE, fixture.kernelReportRepository.saved["case-1"]?.inferenceSource)
    }

    // ── Bounded re-run while the visit is still on its way (memo section 12.3) ─────

    private class CountingEvaluateSource : EvaluateKernelSource {
        var calls = 0
        override suspend fun evaluate(payload: KernelPayload, patientAge: Int, patientSex: String): EvaluateResult =
            AlwaysSucceedsEvaluateSource.evaluate(payload, patientAge, patientSex).also { calls++ }
    }

    private fun notSentYetSnapshot(
        patient: com.example.samdapp.domain.model.SyncChainRow = chain(com.example.samdapp.domain.model.SyncState.PENDING),
    ) = com.example.samdapp.domain.model.AssessGateSnapshot(
        caseRecord = chain(com.example.samdapp.domain.model.SyncState.PENDING),
        encounter = chain(com.example.samdapp.domain.model.SyncState.PENDING),
        patient = patient,
    )

    private fun fixtureWithGate(
        snapshot: com.example.samdapp.domain.model.AssessGateSnapshot,
        kernel: RemoteKernelSource = AlwaysSucceedsKernelSource,
        evaluate: EvaluateKernelSource = AlwaysSucceedsEvaluateSource,
    ): Fixture {
        val caseRecords = FakeCaseRecordRepository(initial = listOf(defaultCaseRecord())).apply { gateSnapshots["case-1"] = snapshot }
        return Fixture(caseRecordRepository = caseRecords, kernelSource = kernel, evaluateSource = evaluate)
    }

    @Test
    fun `a case that is not sent yet asks to be retried and persists nothing, before the last attempt`() = runTest {
        val kernel = CountingKernelSource()
        val evaluate = CountingEvaluateSource()
        val fixture = fixtureWithGate(notSentYetSnapshot(), kernel, evaluate)

        for (attempt in 0 until AssessmentRunner.MAX_ASSESS_ATTEMPTS - 1) {
            assertEquals("attempt $attempt", AssessmentOutcome.RetryLater, fixture.runner.run("case-1", runAttemptCount = attempt))
        }

        assertEquals(0, kernel.calls)
        assertEquals(0, evaluate.calls)
        assertTrue("no kernel row", fixture.kernelReportRepository.saved.isEmpty())
        assertTrue("no evaluate failure marker", fixture.evaluateReportRepository.failures.isEmpty())
        assertTrue("no audit entry", fixture.auditLogger.logged.none { it.action == AuditAction.KERNEL_RESPONSE_RECEIVED.value })
    }

    @Test
    fun `the last attempt records not sent yet, with its audit entry, and asks for no more`() = runTest {
        val fixture = fixtureWithGate(notSentYetSnapshot())

        val outcome = fixture.runner.run("case-1", runAttemptCount = AssessmentRunner.MAX_ASSESS_ATTEMPTS - 1)

        assertEquals(AssessmentOutcome.Done, outcome)
        val saved = fixture.kernelReportRepository.saved["case-1"]
        assertEquals(com.example.samdapp.domain.kernel.KernelFailure.CASE_NOT_SENT_YET, saved?.failureCode)
        assertEquals("CASE_NOT_SENT_YET", fixture.evaluateReportRepository.failures["case-1"])
        val audit = fixture.auditLogger.logged.single { it.action == AuditAction.KERNEL_RESPONSE_RECEIVED.value }
        assertTrue(audit.payload, audit.payload.contains("CASE_NOT_SENT_YET"))
    }

    @Test
    fun `a duplicate patient or a failed chain is recorded at once, never retried`() = runTest {
        val duplicate = notSentYetSnapshot(
            patient = chain(
                com.example.samdapp.domain.model.SyncState.FAILED,
                code = com.example.samdapp.domain.model.SYNC_RECORD_INVALID_CODE,
                message = com.example.samdapp.domain.model.BackendConstraintMessages.UNIQUE_VIOLATION,
            ),
        )
        val blocked = notSentYetSnapshot(patient = chain(com.example.samdapp.domain.model.SyncState.FAILED, code = "SAMD-SYNC-RETRY-EXHAUSTED"))
        listOf(duplicate to "PATIENT_DUPLICATE", blocked to "CASE_SYNC_BLOCKED").forEach { (snapshot, name) ->
            val fixture = fixtureWithGate(snapshot)

            val outcome = fixture.runner.run("case-1", runAttemptCount = 0)

            assertEquals(name, AssessmentOutcome.Done, outcome)
            assertEquals(name, fixture.kernelReportRepository.saved["case-1"]?.failureCode?.name)
        }
    }

    @Test
    fun `a CONFLICT patient is retried like any other chain that is still on its way`() = runTest {
        val fixture = fixtureWithGate(notSentYetSnapshot(patient = chain(com.example.samdapp.domain.model.SyncState.CONFLICT)))

        assertEquals(AssessmentOutcome.RetryLater, fixture.runner.run("case-1", runAttemptCount = 0))
        assertTrue(fixture.kernelReportRepository.saved.isEmpty())
    }

    @Test
    fun `retry, retry, then on the server calls the kernel and evaluate exactly once each`() = runTest {
        val kernel = CountingKernelSource()
        val evaluate = CountingEvaluateSource()
        val caseRecords = FakeCaseRecordRepository(initial = listOf(defaultCaseRecord())).apply { gateSnapshots["case-1"] = notSentYetSnapshot() }
        val fixture = Fixture(caseRecordRepository = caseRecords, kernelSource = kernel, evaluateSource = evaluate)

        assertEquals(AssessmentOutcome.RetryLater, fixture.runner.run("case-1", runAttemptCount = 0))
        assertEquals(AssessmentOutcome.RetryLater, fixture.runner.run("case-1", runAttemptCount = 1))
        caseRecords.gateSnapshots["case-1"] = com.example.samdapp.domain.model.AssessGateSnapshot(
            caseRecord = chain(com.example.samdapp.domain.model.SyncState.SYNCED, serverVersion = 1),
            encounter = chain(com.example.samdapp.domain.model.SyncState.SYNCED, serverVersion = 1),
            patient = chain(com.example.samdapp.domain.model.SyncState.SYNCED, serverVersion = 1),
        )
        assertEquals(AssessmentOutcome.Done, fixture.runner.run("case-1", runAttemptCount = 2))

        assertEquals(1, kernel.calls)
        assertEquals(1, evaluate.calls)
        assertEquals(InferenceSource.REAL_INFERENCE, fixture.kernelReportRepository.saved["case-1"]?.inferenceSource)
    }

    @Test
    fun `missing local data is recorded as RECORD_INCOMPLETE and audited`() = runTest {
        val fixture = Fixture(vitalsRepository = FakeVitalsRepository())

        fixture.runner.run("case-1")

        val saved = fixture.kernelReportRepository.saved["case-1"]
        assertEquals(com.example.samdapp.domain.kernel.KernelFailure.RECORD_INCOMPLETE, saved?.failureCode)
        val audit = fixture.auditLogger.logged.single { it.action == AuditAction.KERNEL_RESPONSE_RECEIVED.value }
        assertTrue(audit.payload, audit.payload.contains("RECORD_INCOMPLETE"))
    }

}
