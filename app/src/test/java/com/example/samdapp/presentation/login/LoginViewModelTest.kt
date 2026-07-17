package com.example.samdapp.presentation.login

import com.example.samdapp.domain.auth.UserRole
import com.example.samdapp.testutil.FakeAuthSession
import com.example.samdapp.testutil.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** REQ-SEC-03: sign-in must be gated by a device biometric/credential check, not just typed name+role. */
@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `submit requests biometric auth instead of signing in directly`() = runTest(mainDispatcherRule.dispatcher) {
        val authSession = FakeAuthSession()
        val viewModel = LoginViewModel(authSession)
        val effects = mutableListOf<LoginEffect>()
        val collectJob = launch { viewModel.effects.toList(effects) }

        viewModel.onNameChange("Asha")
        viewModel.onRoleSelect(UserRole.ASHA_WORKER)
        viewModel.onSubmit()
        advanceUntilIdle()

        assertNull(authSession.currentUser().first())
        val effect = effects.single() as LoginEffect.RequestBiometricAuth
        assertEquals("Asha", effect.subtitle)

        collectJob.cancel()
    }

    @Test
    fun `biometric success completes the sign-in and resets the form`() = runTest(mainDispatcherRule.dispatcher) {
        val authSession = FakeAuthSession()
        val viewModel = LoginViewModel(authSession)

        viewModel.onNameChange("Asha")
        viewModel.onRoleSelect(UserRole.ASHA_WORKER)
        viewModel.onSubmit()
        advanceUntilIdle()
        viewModel.onBiometricSucceeded()
        advanceUntilIdle()

        assertEquals("Asha", authSession.currentUser().first()?.name)
        assertEquals(LoginUiState(), viewModel.uiState.value)
    }

    @Test
    fun `biometric failure does not sign in and surfaces an error, keeping the form filled`() =
        runTest(mainDispatcherRule.dispatcher) {
            val authSession = FakeAuthSession()
            val viewModel = LoginViewModel(authSession)

            viewModel.onNameChange("Asha")
            viewModel.onRoleSelect(UserRole.ASHA_WORKER)
            viewModel.onSubmit()
            advanceUntilIdle()
            viewModel.onBiometricFailed("fingerprint did not match")
            advanceUntilIdle()

            assertNull(authSession.currentUser().first())
            assertTrue(viewModel.uiState.value.errorMessage!!.contains("fingerprint did not match"))
            assertEquals("Asha", viewModel.uiState.value.name)
            assertTrue(!viewModel.uiState.value.isSubmitting)
        }
}
