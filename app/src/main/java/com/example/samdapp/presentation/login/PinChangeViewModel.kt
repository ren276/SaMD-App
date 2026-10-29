package com.example.samdapp.presentation.login

import androidx.annotation.StringRes
import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.samdapp.R
import com.example.samdapp.domain.auth.AuthSession
import com.example.samdapp.domain.auth.ChangePinResult
import com.example.samdapp.domain.auth.SignInFailureReason
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val PIN_MIN_LENGTH = 6
private const val PIN_MAX_LENGTH = 12

data class PinChangeUiState(
    val currentPin: String = "",
    val newPin: String = "",
    val confirmPin: String = "",
    val isSubmitting: Boolean = false,
    /** At most one error on screen. */
    val error: PinChangeError? = null,
) {
    val canSubmit: Boolean
        get() = currentPin.isNotBlank() &&
            newPin.length in PIN_MIN_LENGTH..PIN_MAX_LENGTH &&
            newPin == confirmPin &&
            newPin != currentPin &&
            !isSubmitting
}

/** Every way a PIN change can fail on screen (memo Q12, P2-R11). */
sealed interface PinChangeError {
    /** A server refusal: the server's own detail is shown. */
    data class ServerDetail(val detail: String) : PinChangeError

    /** A transport failure the app words itself: never exception text. */
    data class Worded(@StringRes val res: Int) : PinChangeError
}

/** The Open settings action belongs to the local-network case only (memo 5.3, P2-R11). */
internal fun PinChangeError?.offersOpenSettings(): Boolean =
    this is PinChangeError.Worded && res == R.string.pin_change_failed_local_network

/** The string for a PIN-change failure the app words itself, or null for a server refusal, whose
 *  own detail is shown. The same reasons as sign-in (one classifier), so no exception text reaches
 *  the screen. */
@StringRes
internal fun pinChangeFailureRes(reason: SignInFailureReason): Int? = when (reason) {
    SignInFailureReason.SERVER -> null
    SignInFailureReason.LOCAL_NETWORK_NOT_ALLOWED -> R.string.pin_change_failed_local_network
    SignInFailureReason.NO_CONNECTION -> R.string.pin_change_failed_no_connection
    SignInFailureReason.SECURE_CONNECTION_FAILED -> R.string.pin_change_failed_secure_connection
}

@Stable
interface PinChangeActions {
    fun onCurrentPinChange(value: String)
    fun onNewPinChange(value: String)
    fun onConfirmPinChange(value: String)
    fun onSubmit()
}

/**
 * Forced on first login (`must_change_pin`, api-contract.md §2.2/§2.6) and reachable voluntarily
 * from Profile later. On success the backend revokes every refresh chain for this worker, so
 * [AuthSession.changePin] clears the local session too: [state]'s consumer (AppNavHost) then
 * naturally routes to the sign-in gate because the session Flow it's already watching goes null.
 * This screen does not navigate itself.
 */
@HiltViewModel
class PinChangeViewModel @Inject constructor(
    private val authSession: AuthSession,
) : ViewModel(), PinChangeActions {

    private val _uiState = MutableStateFlow(PinChangeUiState())
    val uiState: StateFlow<PinChangeUiState> = _uiState.asStateFlow()

    override fun onCurrentPinChange(value: String) {
        if (value.length <= PIN_MAX_LENGTH && value.all { it.isDigit() }) {
            _uiState.update { it.copy(currentPin = value, error = null) }
        }
    }

    override fun onNewPinChange(value: String) {
        if (value.length <= PIN_MAX_LENGTH && value.all { it.isDigit() }) {
            _uiState.update { it.copy(newPin = value, error = null) }
        }
    }

    override fun onConfirmPinChange(value: String) {
        if (value.length <= PIN_MAX_LENGTH && value.all { it.isDigit() }) {
            _uiState.update { it.copy(confirmPin = value, error = null) }
        }
    }

    override fun onSubmit() {
        val current = _uiState.value
        if (!current.canSubmit) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, error = null) }
            when (val result = authSession.changePin(current.currentPin, current.newPin)) {
                is ChangePinResult.Success -> Unit // session clears; AppNavHost routes away.
                is ChangePinResult.Failure -> {
                    val res = pinChangeFailureRes(result.reason)
                    val error = if (res == null) PinChangeError.ServerDetail(result.message) else PinChangeError.Worded(res)
                    _uiState.update {
                        it.copy(isSubmitting = false, error = error, currentPin = "", newPin = "", confirmPin = "")
                    }
                }
            }
        }
    }
}
