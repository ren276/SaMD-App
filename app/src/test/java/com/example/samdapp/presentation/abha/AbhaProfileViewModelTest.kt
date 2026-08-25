package com.example.samdapp.presentation.abha

import com.example.samdapp.testutil.FakeAbhaProfileRepository
import com.example.samdapp.testutil.MainDispatcherRule
import com.example.samdapp.testutil.testAbhaProfile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * The empty-state case this class exists to guard: `patient.abhaNumber != null` (the row was
 * shown) but [com.example.samdapp.domain.repository.AbhaProfileRepository.getProfile] returns
 * null for it — the row was never saved on this device, or was deleted. Design memo §(d)/open
 * question 2 decided the screen must render an honest "not on device" empty state here, not
 * crash and not silently disappear. This test is what stops a future refactor (e.g. a `!!` on
 * the repository result) from turning that empty state into a crash.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AbhaProfileViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `a profile missing on device leaves uiState with a null profile, not a crash`() = runTest {
        val viewModel = AbhaProfileViewModel(abhaId = "12345678901234", abhaProfileRepository = FakeAbhaProfileRepository())

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertNull(state.profile)
    }

    @Test
    fun `a profile present on device is loaded into uiState`() = runTest {
        val profile = testAbhaProfile(abhaId = "12345678901234")
        val repository = FakeAbhaProfileRepository(initialProfiles = listOf(profile))

        val viewModel = AbhaProfileViewModel(abhaId = "12345678901234", abhaProfileRepository = repository)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals(profile, state.profile)
    }
}
