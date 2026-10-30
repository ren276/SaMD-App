package com.example.samdapp.presentation.login

import androidx.annotation.StringRes
import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.samdapp.R
import com.example.samdapp.domain.auth.AuthSession
import com.example.samdapp.domain.auth.SignInFailureReason
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
    /** At most one error on screen (memo P2-R6). */
    val error: LoginError? = null,
    /** The local-network step this sign-in is waiting on (memo 5.2, P2-R2), or null. */
    val localNetworkStep: LocalNetworkStep? = null,
) {
    val canSubmit: Boolean get() = name.isNotBlank() && role != null && pin.length in PIN_MIN_LENGTH..PIN_MAX_LENGTH && !isSubmitting
}

/** Every way a sign-in can fail on screen, one at a time (memo P2-R6). */
sealed interface LoginError {
    /** A server refusal: the server's own `detail` is shown. */
    data class ServerDetail(val detail: String) : LoginError

    /** A failure the app words itself (Q4): never exception text. */
    data class Worded(@StringRes val res: Int) : LoginError

    /** A sign-in the server accepted but this phone refused (facility-scope memo 3.2). */
    data class Refusal(val refusal: SignInRefusal) : LoginError

    /** The biometric or device-credential step did not pass. */
    data class Biometric(val message: String) : LoginError
}

/** The memo 5.3 denied state: [R.string.lnp_denied_login] with an Open settings button. The
 *  pre-check denial and the gate's own refusal (the P2-R1 safety net) both land here. */
internal fun LoginError?.offersOpenSettings(): Boolean = this is LoginError.Worded && res == R.string.lnp_denied_login

enum class LocalNetworkStep {
    /** Show the memo 5.2 rationale; Continue moves to [REQUEST]. */
    RATIONALE,

    /** Launch the system request; the screen acknowledges with [LoginViewModel.onLocalNetworkRequestLaunched]. */
    REQUEST,
}

sealed interface SignInRefusal {
    /** The phone belongs to [ownerFacilityName]'s facility. */
    data class FacilityBlocked(val ownerFacilityName: String) : SignInRefusal

    /** The account is not linked to a facility. */
    data object NoFacility : SignInRefusal

    /** The facility check could not complete safely (FS-R5). */
    data object Unavailable : SignInRefusal
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
    fun onRationaleContinue()
    fun onRationaleNotNow()
}

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authSession: AuthSession,
    private val localNetwork: SignInLocalNetworkCheck,
) : ViewModel(), LoginActions {

    // Emulators can't satisfy the biometric prompt (see BiometricAuth.isEmulator), so prefill a
    // mock ASHA worker (Gayatri) here purely to save re-typing name+role on every emulator run.
    private val _uiState = MutableStateFlow(
        if (isEmulator()) LoginUiState(name = "Gayatri", role = UserRole.ASHA_WORKER) else LoginUiState(),
    )
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    private val _effects = Channel<LoginEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    override fun onNameChange(value: String) = _uiState.update { it.copy(name = value, error = null) }

    override fun onRoleSelect(role: UserRole) = _uiState.update { it.copy(role = role, error = null) }

    override fun onPinChange(value: String) {
        if (value.length <= PIN_MAX_LENGTH && value.all { it.isDigit() }) {
            _uiState.update { it.copy(pin = value, error = null) }
        }
    }

    override fun onSubmit() {
        val current = _uiState.value
        if (!current.canSubmit) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, error = null) }
            _effects.send(LoginEffect.RequestBiometricAuth(subtitle = current.name.trim()))
        }
    }

    // In memory only, never saved state: a launcher result after process death finds this false
    // and is ignored. Set when the screen launches the request, cleared by its result.
    private var awaitingLocalNetworkResult = false

    // A denial in this session with shouldShowRequestPermissionRationale false (memo 5.4): the
    // next Sign in skips the rationale and leads with Settings. Never persisted (P2-R2).
    private var localNetworkDeniedForGood = false

    fun onBiometricSucceeded() {
        if (_uiState.value.role == null) return
        // Memo 5.1, P2-R1: ask before the call, with the gate's own decision for the real host.
        if (localNetwork.backendBlocked()) {
            when {
                localNetwork.nearbyDevicesMemberGranted() -> _uiState.update { it.copy(localNetworkStep = LocalNetworkStep.REQUEST) }
                localNetworkDeniedForGood -> showLocalNetworkDenied()
                else -> _uiState.update { it.copy(localNetworkStep = LocalNetworkStep.RATIONALE) }
            }
            return
        }
        signIn()
    }

    override fun onRationaleContinue() {
        if (_uiState.value.localNetworkStep != LocalNetworkStep.RATIONALE) return
        _uiState.update { it.copy(localNetworkStep = LocalNetworkStep.REQUEST) }
    }

    /** Back to the form: no error, no request (P2-R2). */
    override fun onRationaleNotNow() {
        if (_uiState.value.localNetworkStep != LocalNetworkStep.RATIONALE) return
        _uiState.update { it.copy(localNetworkStep = null, isSubmitting = false) }
    }

    fun onLocalNetworkRequestLaunched() {
        if (_uiState.value.localNetworkStep != LocalNetworkStep.REQUEST) return
        awaitingLocalNetworkResult = true
        _uiState.update { it.copy(localNetworkStep = null) }
    }

    /** [showRationale] is `shouldShowRequestPermissionRationale` read after the result; it only
     *  matters on a denial. A grant signs in once; nothing here re-submits a second time. */
    fun onLocalNetworkPermissionResult(granted: Boolean, showRationale: Boolean) {
        if (!awaitingLocalNetworkResult) return
        awaitingLocalNetworkResult = false
        if (granted) {
            signIn()
        } else {
            if (!showRationale) localNetworkDeniedForGood = true
            showLocalNetworkDenied()
        }
    }

    private fun showLocalNetworkDenied() = _uiState.update {
        it.copy(isSubmitting = false, localNetworkStep = null, error = LoginError.Worded(R.string.lnp_denied_login), pin = "")
    }

    private fun signIn() {
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
                is SignInResult.Failure -> {
                    val res = signInFailureRes(result.reason)
                    _uiState.update {
                        it.copy(
                            isSubmitting = false,
                            error = if (res == null) LoginError.ServerDetail(result.message) else LoginError.Worded(res),
                            pin = "",
                        )
                    }
                }
                is SignInResult.FacilityBlocked -> _uiState.update {
                    it.copy(isSubmitting = false, error = LoginError.Refusal(SignInRefusal.FacilityBlocked(result.ownerFacilityName)), pin = "")
                }
                SignInResult.NoFacility ->
                    _uiState.update { it.copy(isSubmitting = false, error = LoginError.Refusal(SignInRefusal.NoFacility), pin = "") }
                SignInResult.Unavailable ->
                    _uiState.update { it.copy(isSubmitting = false, error = LoginError.Refusal(SignInRefusal.Unavailable), pin = "") }
            }
        }
    }

    fun onBiometricFailed(message: String) {
        _uiState.update { it.copy(isSubmitting = false, error = LoginError.Biometric("Can't sign in — $message")) }
    }
}

/** The string for a sign-in failure the app words itself, or null for a server refusal, whose own
 *  `detail` is shown. Every transport failure lands here, so no exception text reaches the screen. */
@StringRes
internal fun signInFailureRes(reason: SignInFailureReason): Int? = when (reason) {
    SignInFailureReason.SERVER -> null
    SignInFailureReason.LOCAL_NETWORK_NOT_ALLOWED -> R.string.lnp_denied_login
    SignInFailureReason.NO_CONNECTION -> R.string.login_failed_no_connection
    SignInFailureReason.SECURE_CONNECTION_FAILED -> R.string.login_failed_secure_connection
}
