package com.example.samdapp.presentation.register

import com.example.samdapp.domain.usecase.RegisterPatientUseCase
import com.example.samdapp.testutil.FakeAuditLogger
import com.example.samdapp.testutil.FakePatientRepository
import com.example.samdapp.testutil.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** REQ-REG-01, REQ-AUD-01: a successful registration emits Registered and logs patient_registered. */
@OptIn(ExperimentalCoroutinesApi::class)
class RegisterViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `successful submit registers, logs audit, and emits Registered`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repo = FakePatientRepository()
            val audit = FakeAuditLogger()
            val viewModel = RegisterViewModel(RegisterPatientUseCase(repo), audit)

            val effects = mutableListOf<RegisterEffect>()
            val collectJob = launch { viewModel.effects.toList(effects) }

            viewModel.onFieldChange(RegisterField.FULL_NAME, "Asha")
            viewModel.onFieldChange(RegisterField.MOBILE_NUMBER, "9998887776")
            viewModel.onSubmit()
            advanceUntilIdle()

            assertEquals("Asha", repo.registered?.fullName)
            assertTrue(audit.logged.any { it.action == "patient_registered" })
            assertTrue(effects.firstOrNull() is RegisterEffect.Registered)

            collectJob.cancel()
        }

    @Test
    fun `submit is ignored when required fields are missing`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repo = FakePatientRepository()
            val audit = FakeAuditLogger()
            val viewModel = RegisterViewModel(RegisterPatientUseCase(repo), audit)

            viewModel.onFieldChange(RegisterField.FULL_NAME, "Asha") // no contact method
            viewModel.onSubmit()
            advanceUntilIdle()

            assertEquals(null, repo.registered)
            assertTrue(audit.logged.isEmpty())
        }
}
