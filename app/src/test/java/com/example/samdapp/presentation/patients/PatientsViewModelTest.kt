package com.example.samdapp.presentation.patients

import com.example.samdapp.domain.usecase.GetRecentPatientsUseCase
import com.example.samdapp.testutil.FakePatientRepository
import com.example.samdapp.testutil.MainDispatcherRule
import com.example.samdapp.testutil.testPatient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PatientsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `unfiltered list reflects the recent-window roster`() = runTest(mainDispatcherRule.dispatcher) {
        val repo = FakePatientRepository(today = listOf(testPatient("p1", "Asha Devi"), testPatient("p2", "Ravi Kumar")))
        val viewModel = PatientsViewModel(GetRecentPatientsUseCase(repo))

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("p1", "p2"), state.patients.map { it.patient.id })
        assertFalse(state.isLoading)
    }

    @Test
    fun `query filters by name or id, case-insensitively`() = runTest(mainDispatcherRule.dispatcher) {
        val repo = FakePatientRepository(today = listOf(testPatient("p1", "Asha Devi"), testPatient("p2", "Ravi Kumar")))
        val viewModel = PatientsViewModel(GetRecentPatientsUseCase(repo))
        advanceUntilIdle()

        viewModel.onQueryChange("ravi")
        advanceUntilIdle()

        assertEquals(listOf("p2"), viewModel.uiState.value.patients.map { it.patient.id })
    }
}
