package com.example.samdapp.presentation.login

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.samdapp.domain.auth.AuthSession
import com.example.samdapp.domain.auth.ChangePinResult
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
    val errorMessage: String? = null,
) {
    val canSubmit: Boolean
        get() = currentPin.isNotBlank() &&
            newPin.length in PIN_MIN_LENGTH..PIN_MAX_LENGTH &&
            newPin == confirmPin &&
            newPin != currentPin &&
            !isSubmitting
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
            _uiState.update { it.copy(currentPin = value, errorMessage = null) }
        }
    }

    override fun onNewPinChange(value: String) {
        if (value.length <= PIN_MAX_LENGTH && value.all { it.isDigit() }) {
            _uiState.update { it.copy(newPin = value, errorMessage = null) }
        }
    }

    override fun onConfirmPinChange(value: String) {
        if (value.length <= PIN_MAX_LENGTH && value.all { it.isDigit() }) {
            _uiState.update { it.copy(confirmPin = value, errorMessage = null) }
        }
    }

    override fun onSubmit() {
        val current = _uiState.value
        if (!current.canSubmit) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, errorMessage = null) }
            when (val result = authSession.changePin(current.currentPin, current.newPin)) {
                is ChangePinResult.Success -> Unit // session clears; AppNavHost routes away.
                is ChangePinResult.Failure ->
                    _uiState.update {
                        it.copy(isSubmitting = false, errorMessage = result.message, currentPin = "", newPin = "", confirmPin = "")
                    }
            }
        }
    }
}
