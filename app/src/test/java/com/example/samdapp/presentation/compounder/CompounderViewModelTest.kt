package com.example.samdapp.presentation.compounder

import com.example.samdapp.domain.audit.AuditAction
import com.example.samdapp.domain.connectivity.UNREACHABLE_OR_BLOCKED_MESSAGE
import com.example.samdapp.domain.media.AilmentAudioRecorder
import com.example.samdapp.domain.model.ObservationSource
import com.example.samdapp.domain.model.VitalsReading
import com.example.samdapp.domain.model.VitalsSnapshot
import com.example.samdapp.domain.repository.VitalsRepository
import com.example.samdapp.domain.usecase.AcquireDeviceVitalsUseCase
import com.example.samdapp.domain.usecase.AddAilmentUseCase
import com.example.samdapp.domain.usecase.CheckEmergencyThresholdsUseCase
import com.example.samdapp.domain.usecase.DeleteAilmentUseCase
import com.example.samdapp.domain.usecase.GetVitalsPrefillUseCase
import com.example.samdapp.domain.usecase.RecordVitalsUseCase
import com.example.samdapp.domain.usecase.StartCaseUseCase
import com.example.samdapp.domain.usecase.StopDeviceAcquisitionUseCase
import com.example.samdapp.domain.vitalssource.AcquisitionRequest
import com.example.samdapp.domain.vitalssource.AcquisitionResult
import com.example.samdapp.domain.vitalssource.Instrument
import com.example.samdapp.domain.vitalssource.RejectReason
import com.example.samdapp.domain.vitalssource.Scenario
import com.example.samdapp.domain.vitalssource.VitalsSource
import com.example.samdapp.testutil.FakeAilmentRepository
import com.example.samdapp.testutil.FakeAuditLogger
import com.example.samdapp.testutil.FakeCaseRecordRepository
import com.example.samdapp.testutil.FakeEncounterRepository
import com.example.samdapp.testutil.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

private class FakeVitalsSource(private val prefill: VitalsReading = VitalsReading()) : VitalsSource {
    var nextResult: AcquisitionResult = AcquisitionResult.Rejected(RejectReason.NOT_SUPPORTED)
    val requests = mutableListOf<AcquisitionRequest>()
    var stopCount = 0

    /** Set to hold an acquisition in flight so a test can observe the Acquiring state. */
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun readVitals(): VitalsReading = prefill

    override suspend fun startAcquisition(request: AcquisitionRequest): AcquisitionResult {
        requests += request
        gate?.await()
        return nextResult
    }

    override suspend fun stopAcquisition() {
        stopCount++
    }
}

/** [com.example.samdapp.testutil.FakeVitalsRepository] cannot see what was saved; RC-4 needs to
 *  assert that nothing was, so this one records every call. */
private class RecordingVitalsRepository : VitalsRepository {
    val saved = mutableListOf<VitalsSnapshot>()
    override suspend fun saveVitals(snapshot: VitalsSnapshot): Result<Unit> {
        saved += snapshot
        return Result.success(Unit)
    }

    override fun observeLatestForEncounter(encounterId: String): Flow<VitalsSnapshot?> = flowOf(null)
}

private class NoopAudioRecorder : AilmentAudioRecorder {
    override fun startRecording(): Result<String> = Result.success("file://noop")
    override fun stopRecording(): Result<Unit> = Result.success(Unit)
    override fun deleteRecording(uri: String) = Unit
}

/**
 * The provenance transition rules and the two UI-level risk controls.
 *
 * RC-4 is the one to read first: driving an acquisition must never reach [VitalsRepository] and
 * must never log `VITALS_RECORDED`. The instrument cannot write to the patient record; only a human
 * pressing Continue can. It is proved here by absence, which is why the repository fake counts.
 */
class CompounderViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val audit = FakeAuditLogger()
    private val vitalsRepository = RecordingVitalsRepository()

    private fun viewModel(source: FakeVitalsSource): CompounderViewModel {
        val ailments = FakeAilmentRepository()
        return CompounderViewModel(
            patientId = "patient-1",
            followUpOfEncounterId = null,
            resumeEncounterId = null,
            resumeCaseRecordId = null,
            startCaseUseCase = StartCaseUseCase(FakeEncounterRepository(), FakeCaseRecordRepository()),
            getVitalsPrefillUseCase = GetVitalsPrefillUseCase(source),
            acquireDeviceVitalsUseCase = AcquireDeviceVitalsUseCase(source),
            stopDeviceAcquisitionUseCase = StopDeviceAcquisitionUseCase(source),
            recordVitalsUseCase = RecordVitalsUseCase(vitalsRepository),
            addAilmentUseCase = AddAilmentUseCase(ailments),
            deleteAilmentUseCase = DeleteAilmentUseCase(ailments),
            ailmentRepository = ailments,
            ailmentAudioRecorder = NoopAudioRecorder(),
            checkEmergencyThresholdsUseCase = CheckEmergencyThresholdsUseCase(),
            auditLogger = audit,
        )
    }

    private fun bpAccepted(systolic: Int = 174, diastolic: Int = 105, pulse: Int = 112) =
        AcquisitionResult.Accepted(
            reading = VitalsReading(bpSystolic = systolic, bpDiastolic = diastolic, pulseBpm = pulse),
            instrument = Instrument.BP,
            sessionId = "session-A",
            deviceType = "BLOOD_PRESSURE",
        )

    private fun glucoseAccepted(value: Int = 95) = AcquisitionResult.Accepted(
        reading = VitalsReading(bloodGlucoseMgDl = value),
        instrument = Instrument.GLUCOMETER,
        sessionId = "session-B",
        deviceType = "GLUCOMETER",
    )

    // --- Provenance transitions ---------------------------------------------------------------

    @Test
    fun `an accepted reading marks only the fields it wrote as DEVICE`() = runTest(mainDispatcherRule.dispatcher) {
        val source = FakeVitalsSource().apply { nextResult = bpAccepted() }
        val vm = viewModel(source)

        vm.onStartAcquisition()

        val state = vm.uiState.value
        assertEquals("174", state.bpSystolic)
        assertEquals(VitalsFieldProvenance.DEVICE, state.fieldProvenance[VitalsField.BP_SYSTOLIC])
        assertEquals(VitalsFieldProvenance.DEVICE, state.fieldProvenance[VitalsField.BP_DIASTOLIC])
        assertEquals(VitalsFieldProvenance.DEVICE, state.fieldProvenance[VitalsField.PULSE_BPM])
        // Never touched by this instrument, so it carries no mark at all, which reads as MANUAL.
        assertNull(state.fieldProvenance[VitalsField.SPO2_PERCENT])
    }

    @Test
    fun `editing a DEVICE field flips it to DEVICE_EDITED and it stays there`() =
        runTest(mainDispatcherRule.dispatcher) {
            val source = FakeVitalsSource().apply { nextResult = bpAccepted() }
            val vm = viewModel(source)
            vm.onStartAcquisition()

            vm.onBpSystolicChange("160")
            assertEquals(VitalsFieldProvenance.DEVICE_EDITED, vm.uiState.value.fieldProvenance[VitalsField.BP_SYSTOLIC])

            vm.onBpSystolicChange("161")
            assertEquals(VitalsFieldProvenance.DEVICE_EDITED, vm.uiState.value.fieldProvenance[VitalsField.BP_SYSTOLIC])
        }

    @Test
    fun `a field a device never wrote stays MANUAL however much it is typed in`() =
        runTest(mainDispatcherRule.dispatcher) {
            val vm = viewModel(FakeVitalsSource())

            vm.onRespiratoryRateChange("18")

            assertNull(vm.uiState.value.fieldProvenance[VitalsField.RESPIRATORY_RATE])
            assertEquals(ObservationSource.MANUAL, vm.uiState.value.source)
        }

    @Test
    fun `a fresh accepted reading resets an edited field back to DEVICE`() =
        runTest(mainDispatcherRule.dispatcher) {
            val source = FakeVitalsSource().apply { nextResult = bpAccepted() }
            val vm = viewModel(source)
            vm.onStartAcquisition()
            vm.onBpSystolicChange("160")
            assertEquals(VitalsFieldProvenance.DEVICE_EDITED, vm.uiState.value.fieldProvenance[VitalsField.BP_SYSTOLIC])

            source.nextResult = bpAccepted(systolic = 132)
            vm.onStartAcquisition()

            assertEquals("132", vm.uiState.value.bpSystolic)
            assertEquals(VitalsFieldProvenance.DEVICE, vm.uiState.value.fieldProvenance[VitalsField.BP_SYSTOLIC])
        }

    // --- Roll-up -------------------------------------------------------------------------------

    @Test
    fun `at least one DEVICE field rolls the snapshot up to DEVICE`() = runTest(mainDispatcherRule.dispatcher) {
        val source = FakeVitalsSource().apply { nextResult = bpAccepted() }
        val vm = viewModel(source)
        vm.onStartAcquisition()

        vm.onBpSystolicChange("160")

        // systolic is DEVICE_EDITED now, but diastolic and pulse are still DEVICE.
        assertEquals(ObservationSource.DEVICE, vm.uiState.value.source)
    }

    @Test
    fun `every device field edited rolls the snapshot back down to MANUAL`() =
        runTest(mainDispatcherRule.dispatcher) {
            val source = FakeVitalsSource().apply { nextResult = bpAccepted() }
            val vm = viewModel(source)
            vm.onStartAcquisition()

            vm.onBpSystolicChange("160")
            vm.onBpDiastolicChange("95")
            vm.onPulseChange("80")

            assertEquals(ObservationSource.MANUAL, vm.uiState.value.source)
        }

    @Test
    fun `no acquisition at all rolls up to MANUAL`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel(FakeVitalsSource())

        assertEquals(ObservationSource.MANUAL, vm.uiState.value.source)
    }

    /** The PR1 composition check: a prefill that carried values still reports DEVICE, reached
     *  through the per-field map rather than a second independent computation. */
    @Test
    fun `a prefill carrying values still rolls up to DEVICE through the per-field map`() =
        runTest(mainDispatcherRule.dispatcher) {
            val vm = viewModel(FakeVitalsSource(prefill = VitalsReading(pulseBpm = 72)))

            assertEquals(VitalsFieldProvenance.DEVICE, vm.uiState.value.fieldProvenance[VitalsField.PULSE_BPM])
            assertEquals(ObservationSource.DEVICE, vm.uiState.value.source)
        }

    @Test
    fun `the saved snapshot carries the rolled-up source`() = runTest(mainDispatcherRule.dispatcher) {
        val source = FakeVitalsSource().apply { nextResult = bpAccepted() }
        val vm = viewModel(source)
        vm.onStartAcquisition()
        vm.onChiefComplaintChange("Headache")

        vm.onContinue()

        assertEquals(1, vitalsRepository.saved.size)
        assertEquals(ObservationSource.DEVICE, vitalsRepository.saved.single().source)
        assertEquals(174, vitalsRepository.saved.single().bpSystolic)
    }

    // --- RC-4, the sole persistence gate -------------------------------------------------------

    @Test
    fun `acquisition never persists and never logs VITALS_RECORDED, Continue does both once`() =
        runTest(mainDispatcherRule.dispatcher) {
            val source = FakeVitalsSource().apply { nextResult = bpAccepted() }
            val vm = viewModel(source)

            vm.onStartAcquisition()

            assertTrue("acquisition must not reach the repository", vitalsRepository.saved.isEmpty())
            assertTrue(
                "acquisition must not log VITALS_RECORDED",
                audit.logged.none { it.action == AuditAction.VITALS_RECORDED.value },
            )

            vm.onChiefComplaintChange("Headache")
            vm.onContinue()

            assertEquals(1, vitalsRepository.saved.size)
            assertEquals(1, audit.logged.count { it.action == AuditAction.VITALS_RECORDED.value })
        }

    // --- PR5: VITALS_DEVICE_READING_RECEIVED / _FAILED ------------------------------------------

    @Test
    fun `an accepted acquisition logs exactly one VITALS_DEVICE_READING_RECEIVED, never VITALS_RECORDED`() =
        runTest(mainDispatcherRule.dispatcher) {
            val source = FakeVitalsSource().apply { nextResult = bpAccepted() }
            val vm = viewModel(source)

            vm.onStartAcquisition()

            assertEquals(
                1,
                audit.logged.count { it.action == AuditAction.VITALS_DEVICE_READING_RECEIVED.value },
            )
            assertTrue(audit.logged.none { it.action == AuditAction.VITALS_RECORDED.value })
        }

    @Test
    fun `no measured vital value string appears in the RECEIVED payload`() =
        runTest(mainDispatcherRule.dispatcher) {
            // A distinctive value unlikely to collide with any provenance/control-evidence field
            // (session id, device type, field names) that legitimately belongs in the payload.
            val source = FakeVitalsSource().apply { nextResult = bpAccepted(systolic = 173) }
            val vm = viewModel(source)

            vm.onStartAcquisition()

            val entry = audit.logged.single { it.action == AuditAction.VITALS_DEVICE_READING_RECEIVED.value }
            assertTrue("payload must never carry the measured value", "173" !in entry.payload)
        }

    @Test
    fun `every rejection reason logs exactly one VITALS_DEVICE_READING_FAILED with the matching reason`() =
        runTest(mainDispatcherRule.dispatcher) {
            for (reason in RejectReason.entries) {
                val source = FakeVitalsSource().apply { nextResult = AcquisitionResult.Rejected(reason) }
                val vm = viewModel(source)
                val sizeBefore = audit.logged.size

                vm.onStartAcquisition()

                val newEntries = audit.logged.drop(sizeBefore)
                val failures = newEntries.filter { it.action == AuditAction.VITALS_DEVICE_READING_FAILED.value }
                assertEquals("reason $reason", 1, failures.size)
                assertTrue("reason $reason missing from payload", reason.name in failures.single().payload)
            }
        }

    @Test
    fun `no measured vital value string appears in the FAILED payload`() =
        runTest(mainDispatcherRule.dispatcher) {
            val source = FakeVitalsSource().apply { nextResult = bpAccepted() }
            val vm = viewModel(source)
            vm.onStartAcquisition()
            source.nextResult = AcquisitionResult.Rejected(RejectReason.QUALITY_STATUS_NOT_OK)

            vm.onStartAcquisition()

            val entry = audit.logged.last { it.action == AuditAction.VITALS_DEVICE_READING_FAILED.value }
            assertTrue("payload must never carry the measured value", "174" !in entry.payload)
        }

    // --- Rejection paths -----------------------------------------------------------------------

    @Test
    fun `an unreachable gateway sets an acquisition error routed through the classifier`() =
        runTest(mainDispatcherRule.dispatcher) {
            val source = FakeVitalsSource().apply {
                nextResult = AcquisitionResult.Rejected(RejectReason.UNREACHABLE)
            }
            val vm = viewModel(source)

            vm.onStartAcquisition()

            val state = vm.uiState.value
            // Build.VERSION.SDK_INT reads 0 on the host JVM, which is below the enforcement level,
            // so the classifier's third state is the correct answer here: this app cannot tell a
            // genuinely unreachable gateway from a vendor-level local-network block.
            assertEquals(UNREACHABLE_OR_BLOCKED_MESSAGE, state.acquisitionError)
            assertNull(state.acquiringInstrument)
            // No field written, and errorMessage (the save channel) untouched.
            assertEquals("", state.bpSystolic)
            assertTrue(state.fieldProvenance.isEmpty())
            assertNull(state.errorMessage)
        }

    @Test
    fun `every reject reason maps to a message and none leaks a measured value`() {
        for (reason in RejectReason.entries) {
            val message = rejectionMessage(reason, sdkInt = 37)
            assertTrue("$reason produced no message", message.isNotBlank())
        }
        assertEquals(
            "Local network access is off for this app. Allow it in system settings to reach the device gateway.",
            rejectionMessage(RejectReason.PERMISSION_DENIED, sdkInt = 37),
        )
        assertEquals(
            "Cannot reach the device gateway. Check it is powered on and on the same Wi-Fi.",
            rejectionMessage(RejectReason.UNREACHABLE, sdkInt = 37),
        )
        assertEquals(
            UNREACHABLE_OR_BLOCKED_MESSAGE,
            rejectionMessage(RejectReason.UNREACHABLE, sdkInt = 36),
        )
    }

    // --- The in-flight guard and teardown ------------------------------------------------------

    @Test
    fun `a second Start while one is in flight is a no-op`() = runTest(mainDispatcherRule.dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val source = FakeVitalsSource().apply {
            this.gate = gate
            nextResult = bpAccepted()
        }
        val vm = viewModel(source)

        vm.onStartAcquisition()
        assertEquals(Instrument.BP, vm.uiState.value.acquiringInstrument)

        vm.onStartAcquisition()
        vm.onStartAcquisition()

        assertEquals(1, source.requests.size)
        gate.complete(Unit)
        assertNull(vm.uiState.value.acquiringInstrument)
    }

    @Test
    fun `screen exit cancels the in-flight job, posts stop, and writes no field afterwards`() =
        runTest(mainDispatcherRule.dispatcher) {
            val gate = CompletableDeferred<Unit>()
            val source = FakeVitalsSource().apply {
                this.gate = gate
                nextResult = bpAccepted()
            }
            val vm = viewModel(source)
            vm.onStartAcquisition()

            vm.onCleared()

            assertEquals(1, source.stopCount)
            // Releasing the gate after teardown must not resurrect the write.
            gate.complete(Unit)
            assertEquals("", vm.uiState.value.bpSystolic)
            assertTrue(vm.uiState.value.fieldProvenance.isEmpty())
        }

    @Test
    fun `Stop posts stop, clears the session, and never clears a visible field`() =
        runTest(mainDispatcherRule.dispatcher) {
            val source = FakeVitalsSource().apply { nextResult = bpAccepted() }
            val vm = viewModel(source)
            vm.onStartAcquisition()
            assertEquals("174", vm.uiState.value.bpSystolic)

            vm.onStopAcquisition()

            assertEquals(1, source.stopCount)
            assertNull(vm.uiState.value.activeSessionId)
            assertEquals("174", vm.uiState.value.bpSystolic)
            assertEquals(VitalsFieldProvenance.DEVICE, vm.uiState.value.fieldProvenance[VitalsField.BP_SYSTOLIC])
        }

    // --- Glucometer section expansion ----------------------------------------------------------

    @Test
    fun `an accepted glucometer reading opens the point-of-care section`() =
        runTest(mainDispatcherRule.dispatcher) {
            val source = FakeVitalsSource().apply { nextResult = glucoseAccepted() }
            val vm = viewModel(source)
            assertTrue(!vm.uiState.value.showPointOfCareTests)

            vm.onInstrumentSelected(Instrument.GLUCOMETER)
            vm.onStartAcquisition()

            assertTrue("a glucose write into a collapsed section is a silent write", vm.uiState.value.showPointOfCareTests)
            assertEquals("95", vm.uiState.value.bloodGlucoseMgDl)
        }

    @Test
    fun `an accepted BP reading does not collapse an already open section`() =
        runTest(mainDispatcherRule.dispatcher) {
            val source = FakeVitalsSource().apply { nextResult = bpAccepted() }
            val vm = viewModel(source)
            vm.onTogglePointOfCareTests()
            assertTrue(vm.uiState.value.showPointOfCareTests)

            vm.onStartAcquisition()

            assertTrue(vm.uiState.value.showPointOfCareTests)
        }

    // --- Selectors -----------------------------------------------------------------------------

    @Test
    fun `the selected instrument and scenario are what the request carries`() =
        runTest(mainDispatcherRule.dispatcher) {
            val source = FakeVitalsSource().apply { nextResult = glucoseAccepted() }
            val vm = viewModel(source)

            vm.onInstrumentSelected(Instrument.GLUCOMETER)
            vm.onScenarioSelected(Scenario.LOW)
            vm.onStartAcquisition()

            assertEquals(AcquisitionRequest(Instrument.GLUCOMETER, Scenario.LOW), source.requests.single())
        }

    @Test
    fun `the demo fill leaves no device provenance behind`() = runTest(mainDispatcherRule.dispatcher) {
        val source = FakeVitalsSource().apply { nextResult = bpAccepted() }
        val vm = viewModel(source)
        vm.onStartAcquisition()
        assertEquals(ObservationSource.DEVICE, vm.uiState.value.source)

        vm.fillDemoData()

        assertEquals(ObservationSource.MANUAL, vm.uiState.value.source)
    }
}
