package com.example.samdapp.presentation.login

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.samdapp.domain.auth.AuthSession
import com.example.samdapp.domain.auth.SignInResult
import com.example.samdapp.domain.auth.UserRole
import com.example.samdapp.presentation.common.isEmulator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LoginUiState(
    val name: String = "",
    val role: UserRole? = null,
    val pin: String = "",
    val isSubmitting: Boolean = false,
    val errorMessage: String? = null,
) {
    val canSubmit: Boolean get() = name.isNotBlank() && role != null && pin.length in PIN_MIN_LENGTH..PIN_MAX_LENGTH && !isSubmitting
}

// Mirrors backend/core/app/schemas/auth.py's PIN_MIN_LENGTH/PIN_MAX_LENGTH: early client-side
// feedback only, the server enforces this again (it is not a trust boundary here).
private const val PIN_MIN_LENGTH = 6
private const val PIN_MAX_LENGTH = 12

/** [RequestBiometricAuth] — tapping Sign in doesn't create a session directly; it asks the screen
 *  to run the system biometric/device-credential prompt first (REQ-SEC-03: verified, not just
 *  typed). The screen calls [LoginViewModel.onBiometricSucceeded]/[onBiometricFailed] with the
 *  result. */
sealed interface LoginEffect {
    data class RequestBiometricAuth(val subtitle: String) : LoginEffect
}

@Stable
interface LoginActions {
    fun onNameChange(value: String)
    fun onRoleSelect(role: UserRole)
    fun onPinChange(value: String)
    fun onSubmit()
}

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authSession: AuthSession,
) : ViewModel(), LoginActions {

    // Emulators can't satisfy the biometric prompt (see BiometricAuth.isEmulator), so prefill a
    // mock ASHA worker (Gayatri) here purely to save re-typing name+role on every emulator run.
    private val _uiState = MutableStateFlow(
        if (isEmulator()) LoginUiState(name = "Gayatri", role = UserRole.ASHA_WORKER) else LoginUiState(),
    )
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    private val _effects = Channel<LoginEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    override fun onNameChange(value: String) = _uiState.update { it.copy(name = value, errorMessage = null) }

    override fun onRoleSelect(role: UserRole) = _uiState.update { it.copy(role = role, errorMessage = null) }

    override fun onPinChange(value: String) {
        if (value.length <= PIN_MAX_LENGTH && value.all { it.isDigit() }) {
            _uiState.update { it.copy(pin = value, errorMessage = null) }
        }
    }

    override fun onSubmit() {
        val current = _uiState.value
        if (!current.canSubmit) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, errorMessage = null) }
            _effects.send(LoginEffect.RequestBiometricAuth(subtitle = current.name.trim()))
        }
    }

    fun onBiometricSucceeded() {
        val current = _uiState.value
        val role = current.role ?: return
        viewModelScope.launch {
            when (val result = authSession.signIn(current.name.trim(), role, current.pin)) {
                is SignInResult.Success ->
                    // Reset for the next sign-in: this ViewModel is Activity-scoped (obtained
                    // outside any NavEntry in AppNavHost), so it survives sign-out and would
                    // otherwise leave isSubmitting stuck true, and the previous user's
                    // name/role/pin prefilled, for whoever signs in next. Routing to
                    // Home vs. the PIN-change screen is AppNavHost's job, driven by
                    // AuthViewModel's session + must-change-pin state, not this ViewModel's.
                    _uiState.value = LoginUiState()
                is SignInResult.Failure ->
                    _uiState.update { it.copy(isSubmitting = false, errorMessage = result.message, pin = "") }
            }
        }
    }

    fun onBiometricFailed(message: String) {
        _uiState.update { it.copy(isSubmitting = false, errorMessage = "Can't sign in — $message") }
    }
}
