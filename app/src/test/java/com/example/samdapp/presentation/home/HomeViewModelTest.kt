package com.example.samdapp.presentation.home

import com.example.samdapp.domain.auth.UserRole
import com.example.samdapp.domain.auth.UserSession
import com.example.samdapp.domain.model.CaseRecord
import com.example.samdapp.domain.model.CaseStatus
import com.example.samdapp.domain.usecase.GetTodaysPatientsUseCase
import com.example.samdapp.testutil.FakeAuthSession
import com.example.samdapp.testutil.FakeCaseRecordRepository
import com.example.samdapp.testutil.FakePatientRepository
import com.example.samdapp.testutil.FakeSyncStatus
import com.example.samdapp.testutil.MainDispatcherRule
import com.example.samdapp.testutil.testPatient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import java.time.Instant

/** REQ-ROS-01, REQ-SYN-01: roster populated from the day-scoped use case; Sync now delegates. */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `roster reflects todays patients and stops loading`() = runTest(mainDispatcherRule.dispatcher) {
        val repo = FakePatientRepository(today = listOf(testPatient("p1"), testPatient("p2")))
        val viewModel = HomeViewModel(
            GetTodaysPatientsUseCase(repo), FakeSyncStatus(), FakeAuthSession(), FakeCaseRecordRepository(), repo,
        )

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("p1", "p2"), state.todaysPatients.map { it.id })
        assertFalse(state.isLoadingRoster)
    }

    @Test
    fun `onSyncNow triggers a sync round`() = runTest(mainDispatcherRule.dispatcher) {
        val sync = FakeSyncStatus()
        val viewModel = HomeViewModel(
            GetTodaysPatientsUseCase(FakePatientRepository()), sync, FakeAuthSession(), FakeCaseRecordRepository(), FakePatientRepository(),
        )

        viewModel.onSyncNow()
        advanceUntilIdle()

        assertEquals(1, sync.syncCalls)
    }

    @Test
    fun `surfaces a signed-in worker's incomplete draft as resumable`() = runTest(mainDispatcherRule.dispatcher) {
        val patientRepo = FakePatientRepository()
        patientRepo.register(testPatient("p1", fullName = "Asha Devi"))
        val draft = CaseRecord(
            id = "case-1", patientId = "p1", encounterId = "enc-1", status = CaseStatus.DRAFT,
            assignedDoctorId = null, createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
        )
        val session = UserSession(userId = "u1", name = "Asha", role = UserRole.ASHA_WORKER)
        val viewModel = HomeViewModel(
            GetTodaysPatientsUseCase(patientRepo),
            FakeSyncStatus(),
            FakeAuthSession(initialSession = session),
            FakeCaseRecordRepository(initial = listOf(draft)),
            patientRepo,
        )

        advanceUntilIdle()

        val resumable = viewModel.uiState.value.resumableEncounter
        assertEquals("p1", resumable?.patientId)
        assertEquals("enc-1", resumable?.encounterId)
        assertEquals("case-1", resumable?.caseRecordId)
        assertEquals("Asha Devi", resumable?.patientName)
    }

    @Test
    fun `no resumable encounter when signed out`() = runTest(mainDispatcherRule.dispatcher) {
        val draft = CaseRecord(
            id = "case-1", patientId = "p1", encounterId = "enc-1", status = CaseStatus.DRAFT,
            assignedDoctorId = null, createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
        )
        val viewModel = HomeViewModel(
            GetTodaysPatientsUseCase(FakePatientRepository()),
            FakeSyncStatus(),
            FakeAuthSession(initialSession = null),
            FakeCaseRecordRepository(initial = listOf(draft)),
            FakePatientRepository(),
        )

        advanceUntilIdle()

        assertNull(viewModel.uiState.value.resumableEncounter)
    }
}
