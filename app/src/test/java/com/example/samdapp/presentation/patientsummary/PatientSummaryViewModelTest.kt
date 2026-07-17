package com.example.samdapp.presentation.patientsummary

import com.example.samdapp.domain.model.CaseStatus
import com.example.samdapp.domain.model.ConsultationHistoryEntry
import com.example.samdapp.domain.usecase.ReceiveDoctorPrescriptionUseCase
import com.example.samdapp.testutil.FakeCaseRecordRepository
import com.example.samdapp.testutil.FakeDoctorPrescriptionInbox
import com.example.samdapp.testutil.FakeEncounterRepository
import com.example.samdapp.testutil.FakePatientRepository
import com.example.samdapp.testutil.FakePrescriptionRepository
import com.example.samdapp.testutil.MainDispatcherRule
import com.example.samdapp.testutil.testPatient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

/** Part C: consultation history — the empty-state case (new patient, zero prior encounters) and
 *  the populated case (multiple encounters, most recent first per the repository contract). */
@OptIn(ExperimentalCoroutinesApi::class)
class PatientSummaryViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun viewModel(
        patientId: String = "p1",
        history: List<ConsultationHistoryEntry> = emptyList(),
    ): PatientSummaryViewModel {
        val patientRepo = FakePatientRepository().apply { registered = testPatient(patientId) }
        return PatientSummaryViewModel(
            patientId = patientId,
            patientRepository = patientRepo,
            caseRecordRepository = FakeCaseRecordRepository(),
            encounterRepository = FakeEncounterRepository(history = history),
            receiveDoctorPrescriptionUseCase = ReceiveDoctorPrescriptionUseCase(
                inbox = FakeDoctorPrescriptionInbox(),
                caseRecordRepository = FakeCaseRecordRepository(),
                prescriptionRepository = FakePrescriptionRepository(),
            ),
        )
    }

    @Test
    fun `new patient with zero prior encounters shows an empty history, not a stuck loading state`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel(history = emptyList())

        advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(state.history.isEmpty())
        assertFalse(state.isLoadingHistory)
    }

    @Test
    fun `returning patient's history reflects every prior encounter, most recent first`() = runTest(mainDispatcherRule.dispatcher) {
        val history = listOf(
            ConsultationHistoryEntry(
                encounterId = "enc-2", visitDate = Instant.EPOCH.plusSeconds(200), chiefComplaint = "Cough",
                caseRecordId = "case-2", caseStatus = CaseStatus.SENT_TO_DOCTOR, followUpOfEncounterId = "enc-1",
                doctorName = null, doctorSpecialty = null,
            ),
            ConsultationHistoryEntry(
                encounterId = "enc-1", visitDate = Instant.EPOCH.plusSeconds(100), chiefComplaint = "Fever",
                caseRecordId = "case-1", caseStatus = CaseStatus.PRESCRIPTION_RECEIVED, followUpOfEncounterId = null,
                doctorName = null, doctorSpecialty = null,
            ),
        )
        val vm = viewModel(history = history)

        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(listOf("enc-2", "enc-1"), state.history.map { it.encounterId })
        // enc-2 follows up enc-1, so both collapse into one chain, represented by the latest (enc-2).
        assertEquals(1, state.chains.size)
        assertEquals("enc-1", state.chains.single().rootEncounterId)
        assertEquals("enc-2", state.chains.single().latest.encounterId)
        assertEquals(2, state.chains.single().visitCount)
        assertFalse(state.isLoadingHistory)
    }
}
