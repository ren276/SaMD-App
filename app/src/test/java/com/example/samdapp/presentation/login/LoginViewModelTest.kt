package com.example.samdapp.presentation.login

import com.example.samdapp.domain.auth.AuthSession
import com.example.samdapp.domain.auth.SignInResult
import com.example.samdapp.domain.auth.UserRole
import com.example.samdapp.testutil.FakeAuthSession
import com.example.samdapp.testutil.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
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
        val viewModel = LoginViewModel(authSession, publicBackendCheck())
        val effects = mutableListOf<LoginEffect>()
        val collectJob = launch { viewModel.effects.toList(effects) }

        viewModel.onNameChange("Asha")
        viewModel.onRoleSelect(UserRole.ASHA_WORKER)
        viewModel.onPinChange("482915")
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
        val viewModel = LoginViewModel(authSession, publicBackendCheck())

        viewModel.onNameChange("Asha")
        viewModel.onRoleSelect(UserRole.ASHA_WORKER)
        viewModel.onPinChange("482915")
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
            val viewModel = LoginViewModel(authSession, publicBackendCheck())

            viewModel.onNameChange("Asha")
            viewModel.onRoleSelect(UserRole.ASHA_WORKER)
            viewModel.onPinChange("482915")
            viewModel.onSubmit()
            advanceUntilIdle()
            viewModel.onBiometricFailed("fingerprint did not match")
            advanceUntilIdle()

            assertNull(authSession.currentUser().first())
            assertTrue((viewModel.uiState.value.error as LoginError.Biometric).message.contains("fingerprint did not match"))
            assertEquals("Asha", viewModel.uiState.value.name)
            assertTrue(!viewModel.uiState.value.isSubmitting)
        }

    private class RefusingAuthSession(private val result: SignInResult) : AuthSession by FakeAuthSession() {
        override suspend fun signIn(name: String, role: UserRole, pin: String): SignInResult = result
    }

    private fun TestScope.signInWith(result: SignInResult): LoginViewModel {
        val viewModel = LoginViewModel(RefusingAuthSession(result), publicBackendCheck())
        viewModel.onNameChange("Asha")
        viewModel.onRoleSelect(UserRole.ASHA_WORKER)
        viewModel.onPinChange("482915")
        viewModel.onSubmit()
        advanceUntilIdle()
        viewModel.onBiometricSucceeded()
        advanceUntilIdle()
        return viewModel
    }

    @Test
    fun `a sign-in blocked by the facility check names the owner facility and clears the PIN`() = runTest(mainDispatcherRule.dispatcher) {
        val state = signInWith(SignInResult.FacilityBlocked("PHC Alpha")).uiState.value

        assertEquals(SignInRefusal.FacilityBlocked("PHC Alpha"), (state.error as? LoginError.Refusal)?.refusal)
        assertNull("no raw message alongside the string resource", (state.error as? LoginError.ServerDetail)?.detail)
        assertEquals("", state.pin)
        assertTrue(!state.isSubmitting)
    }

    @Test
    fun `a sign-in with no facility is refused with its own message`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = signInWith(SignInResult.NoFacility)

        assertEquals(SignInRefusal.NoFacility, (viewModel.uiState.value.error as? LoginError.Refusal)?.refusal)
        assertEquals("", viewModel.uiState.value.pin)

        viewModel.onPinChange("1")
        assertNull("editing the form clears the refusal", (viewModel.uiState.value.error as? LoginError.Refusal)?.refusal)
    }

    @Test
    fun `a sign-in the facility check could not complete is refused with the generic message`() = runTest(mainDispatcherRule.dispatcher) {
        val state = signInWith(SignInResult.Unavailable).uiState.value

        assertEquals(SignInRefusal.Unavailable, (state.error as? LoginError.Refusal)?.refusal)
        assertEquals("", state.pin)
        assertTrue(!state.isSubmitting)
    }
}
