package com.example.samdapp.presentation.doctorassignment

import com.example.samdapp.domain.connectivity.ConnectivityController
import com.example.samdapp.domain.model.CaseRecord
import com.example.samdapp.domain.model.CaseStatus
import com.example.samdapp.domain.model.Doctor
import com.example.samdapp.domain.model.Encounter
import com.example.samdapp.domain.usecase.AssignDoctorUseCase
import com.example.samdapp.domain.usecase.ResolveDoctorAssignmentUseCase
import com.example.samdapp.testutil.FakeAuditLogger
import com.example.samdapp.testutil.FakeCaseRecordRepository
import com.example.samdapp.testutil.FakeDoctorRepository
import com.example.samdapp.testutil.FakeEncounterRepository
import com.example.samdapp.testutil.FakeEvaluateReportRepository
import com.example.samdapp.testutil.FakeKernelReportRepository
import com.example.samdapp.testutil.FakeNetworkMonitor
import com.example.samdapp.testutil.MainDispatcherRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

/**
 * The restore-time guard for the doctor-assignment confirm screen, and the enum trap under it.
 *
 * A saved back stack can be restored after the case it points at has moved on. Restoring the
 * confirm screen for a case that already has a doctor offers a second assignment for one case,
 * which is the consult-sent-to-two-doctors family. The ViewModel now checks the case's status
 * before resolving anything and navigates away instead of drawing the screen.
 *
 * The trap is the status check itself. `CaseStatus` is declared
 * `DRAFT, SAVED_LOCALLY, PENDING_SYNC, SENT_TO_DOCTOR, PRESCRIPTION_RECEIVED, ABANDONED`, so the
 * obvious `status.ordinal >= SENT_TO_DOCTOR.ordinal` is wrong twice: it sweeps in `ABANDONED`,
 * which has no doctor, and it will silently adopt any future enum value declared after it. The
 * `ABANDONED` case below is the one that would fail on an ordinal implementation, and it is why
 * this test exists rather than a comment.
 */
class DoctorAssignmentConfirmViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val doctor = Doctor(
        id = "doc-1",
        name = "Dr Test",
        specialty = "General Medicine",
        available = true,
        facilityName = "PHC Test",
        registrationNumber = "REG-1",
    )

    private fun caseRecord(status: CaseStatus, assignedDoctorId: String? = null) = CaseRecord(
        id = CASE,
        patientId = "pat-1",
        encounterId = ENCOUNTER,
        status = status,
        assignedDoctorId = assignedDoctorId,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    private fun viewModel(
        status: CaseStatus,
        assignedDoctorId: String? = null,
    ): DoctorAssignmentConfirmViewModel {
        val cases = FakeCaseRecordRepository(initial = listOf(caseRecord(status, assignedDoctorId)))
        // The resolver bails out if the encounter row is missing, so the control cases below would
        // fail for that reason rather than for the status check they exist to exercise.
        val encounters = FakeEncounterRepository(
            initialEncounters = listOf(
                Encounter(
                    id = ENCOUNTER, patientId = "pat-1", startedAt = Instant.EPOCH,
                    createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH, followUpOfEncounterId = null,
                ),
            ),
        )
        return DoctorAssignmentConfirmViewModel(
            caseRecordId = CASE,
            caseRecordRepository = cases,
            resolveDoctorAssignmentUseCase = ResolveDoctorAssignmentUseCase(
                encounterRepository = encounters,
                caseRecordRepository = cases,
                doctorRepository = FakeDoctorRepository(listOf(doctor)),
                kernelReportRepository = FakeKernelReportRepository(),
                evaluateReportRepository = FakeEvaluateReportRepository(),
            ),
            assignDoctorUseCase = AssignDoctorUseCase(cases),
            auditLogger = FakeAuditLogger(),
            connectivityController = ConnectivityController(FakeNetworkMonitor(initial = true)),
        )
    }

    private companion object {
        const val CASE = "case-1"
        const val ENCOUNTER = "enc-1"
    }

    /**
     * The guard. A case already sent to a doctor must navigate away rather than offer a second
     * assignment, and it must do so without ever populating the screen: a proposal that flashes on
     * its way out is still a proposal the worker could tap.
     */
    @Test
    fun `a case already sent to a doctor navigates away instead of confirming`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = viewModel(CaseStatus.SENT_TO_DOCTOR)

        assertEquals(DoctorAssignmentConfirmEffect.AlreadyAssigned, viewModel.effects.first())
        assertNull("No doctor may be proposed for a case that has one", viewModel.uiState.value.selectedDoctor)
    }

    /** A case whose prescription has come back is further along still, and equally must not be
     *  offered for assignment. */
    @Test
    fun `a case with a prescription received navigates away too`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = viewModel(CaseStatus.PRESCRIPTION_RECEIVED)

        assertEquals(DoctorAssignmentConfirmEffect.AlreadyAssigned, viewModel.effects.first())
        assertNull(viewModel.uiState.value.selectedDoctor)
    }

    /**
     * THE ORDINAL TRAP, pinned. `ABANDONED` is declared AFTER `SENT_TO_DOCTOR`, so an ordinal
     * threshold would treat an abandoned case as already assigned and send the worker to Home. It
     * has no doctor, so it must restore like any other unassigned case. This test is the reason the
     * check is a set.
     */
    @Test
    fun `an ABANDONED case restores normally rather than being treated as assigned`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = viewModel(CaseStatus.ABANDONED)

        advanceUntilIdle()

        assertTrue("An abandoned case must not be sent away as already assigned", !viewModel.uiState.value.isLoading)
        assertEquals(doctor, viewModel.uiState.value.selectedDoctor)
    }

    /** The ordinary forward path, so the guard cannot pass by rejecting everything. */
    @Test
    fun `a case before assignment proposes a doctor as usual`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = viewModel(CaseStatus.SAVED_LOCALLY)

        advanceUntilIdle()

        assertEquals(doctor, viewModel.uiState.value.selectedDoctor)
        assertTrue(!viewModel.uiState.value.isLoading)
    }

    @Test
    fun `a draft case proposes a doctor as usual`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = viewModel(CaseStatus.DRAFT)

        advanceUntilIdle()

        assertEquals(doctor, viewModel.uiState.value.selectedDoctor)
    }

    /**
     * PR 57 review finding, confirmed against `CaseRecordRepositoryImpl.assignDoctor`: an OFFLINE
     * confirmation writes `PENDING_SYNC` together with `assignedDoctorId`, so such a case already
     * has a doctor and is merely waiting to be pushed. The first version of this guard omitted
     * `PENDING_SYNC`, so a restored screen proposed a doctor again and confirming would have
     * overwritten the persisted one before it ever synced.
     *
     * Note this is also why the guard cannot be an ordinal threshold in the other direction:
     * `PENDING_SYNC` sorts BEFORE `SENT_TO_DOCTOR`, so `>=` would have missed it too.
     */
    @Test
    fun `an offline-assigned case pending sync navigates away instead of re-proposing`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = viewModel(CaseStatus.PENDING_SYNC, assignedDoctorId = "doc-9")

        assertEquals(DoctorAssignmentConfirmEffect.AlreadyAssigned, viewModel.effects.first())
        assertNull("An offline-assigned case must not be offered a new doctor", viewModel.uiState.value.selectedDoctor)
    }

    /** The doctor id is checked directly as well as the status, because `markPrescriptionReceived`
     *  updates status alone: a case assigned on another device and arrived by sync can carry a
     *  doctor without this device having seen the status transition that set it. */
    @Test
    fun `a case carrying a doctor id navigates away whatever its status says`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = viewModel(CaseStatus.SAVED_LOCALLY, assignedDoctorId = "doc-9")

        assertEquals(DoctorAssignmentConfirmEffect.AlreadyAssigned, viewModel.effects.first())
        assertNull(viewModel.uiState.value.selectedDoctor)
    }

    /** Pins the status SET specifically. The two assertions above would also pass on the
     *  `assignedDoctorId` half of the check alone, so without this nothing would fail if
     *  `PENDING_SYNC` were dropped from `ALREADY_ASSIGNED_STATUSES` again. */
    @Test
    fun `PENDING_SYNC alone is enough to navigate away, without a local doctor id`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = viewModel(CaseStatus.PENDING_SYNC, assignedDoctorId = null)

        assertEquals(DoctorAssignmentConfirmEffect.AlreadyAssigned, viewModel.effects.first())
    }
}
