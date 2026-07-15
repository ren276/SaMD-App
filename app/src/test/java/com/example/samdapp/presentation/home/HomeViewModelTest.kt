package com.example.samdapp.presentation.home

import com.example.samdapp.domain.usecase.GetTodaysPatientsUseCase
import com.example.samdapp.testutil.FakePatientRepository
import com.example.samdapp.testutil.FakeSyncStatus
import com.example.samdapp.testutil.MainDispatcherRule
import com.example.samdapp.testutil.testPatient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

/** REQ-ROS-01, REQ-SYN-01: roster populated from the day-scoped use case; Sync now delegates. */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `roster reflects todays patients and stops loading`() = runTest(mainDispatcherRule.dispatcher) {
        val repo = FakePatientRepository(today = listOf(testPatient("p1"), testPatient("p2")))
        val viewModel = HomeViewModel(GetTodaysPatientsUseCase(repo), FakeSyncStatus())

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("p1", "p2"), state.todaysPatients.map { it.id })
        assertFalse(state.isLoadingRoster)
    }

    @Test
    fun `onSyncNow triggers a sync round`() = runTest(mainDispatcherRule.dispatcher) {
        val sync = FakeSyncStatus()
        val viewModel = HomeViewModel(GetTodaysPatientsUseCase(FakePatientRepository()), sync)

        viewModel.onSyncNow()
        advanceUntilIdle()

        assertEquals(1, sync.syncCalls)
    }
}
