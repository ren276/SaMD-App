package com.example.samdapp.presentation.abha

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.samdapp.domain.abha.AbhaEnrolResult
import com.example.samdapp.domain.usecase.RequestAbhaOtpUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** [aadhaarNumber] lives here for exactly as long as this screen does. It is never copied into an
 *  effect, an audit payload, or a Room entity — [RequestAbhaOtpUseCase] takes it as an argument
 *  and the backend does the rest. */
data class AbhaAadhaarEntryUiState(
    val aadhaarNumber: String = "",
    val consentGiven: Boolean = false,
    val isSubmitting: Boolean = false,
    val errorMessage: String? = null,
    val errorRetryable: Boolean = false,
) {
    val canSubmit: Boolean
        get() = aadhaarNumber.length == AADHAAR_LENGTH && consentGiven && !isSubmitting
}

const val AADHAAR_LENGTH = 12

sealed interface AbhaAadhaarEntryEffect {
    /** [maskedMobile] is ABDM's masked value and is carried purely so the OTP screen can tell the
     *  worker which phone to check. [sessionId] is the backend's local transaction id, the only
     *  handle this flow carries across steps. */
    data class OtpRequested(val sessionId: String, val maskedMobile: String?) : AbhaAadhaarEntryEffect
}

@Stable
interface AbhaAadhaarEntryActions {
    fun onAadhaarNumberChange(value: String)
    fun onConsentChange(value: Boolean)
    fun onSubmit()
}

/**
 * First step of the real ABHA create flow. Collects the Aadhaar number and the explicit consent
 * that must precede sending it, then asks the backend to request an Aadhaar OTP.
 *
 * Nothing here is logged. There is deliberately no audit write on this screen: the auditable event
 * is the profile that comes out the far end ([AbhaCreateOtpViewModel] writes
 * [com.example.samdapp.domain.audit.AuditAction.ABHA_PROFILE_CREATED]), and an audit row for
 * "an Aadhaar number was typed" would be a record of the very value this flow exists to not keep.
 */
@HiltViewModel
class AbhaAadhaarEntryViewModel @Inject constructor(
    private val requestAbhaOtpUseCase: RequestAbhaOtpUseCase,
) : ViewModel(), AbhaAadhaarEntryActions {

    private val _uiState = MutableStateFlow(AbhaAadhaarEntryUiState())
    val uiState: StateFlow<AbhaAadhaarEntryUiState> = _uiState.asStateFlow()

    private val _effects = Channel<AbhaAadhaarEntryEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    override fun onAadhaarNumberChange(value: String) {
        _uiState.update { it.copy(aadhaarNumber = value, errorMessage = null) }
    }

    override fun onConsentChange(value: Boolean) {
        _uiState.update { it.copy(consentGiven = value, errorMessage = null) }
    }

    override fun onSubmit() {
        val current = _uiState.value
        if (!current.canSubmit) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, errorMessage = null, errorRetryable = false) }
            when (val result = requestAbhaOtpUseCase(current.aadhaarNumber, current.consentGiven)) {
                is AbhaEnrolResult.Success -> {
                    _uiState.update { it.copy(isSubmitting = false) }
                    _effects.send(
                        AbhaAadhaarEntryEffect.OtpRequested(
                            sessionId = result.data.sessionId,
                            maskedMobile = result.data.maskedMobile,
                        ),
                    )
                }
                is AbhaEnrolResult.Error -> _uiState.update {
                    it.copy(
                        isSubmitting = false,
                        errorMessage = result.message,
                        errorRetryable = result.retryable,
                    )
                }
            }
        }
    }
}
