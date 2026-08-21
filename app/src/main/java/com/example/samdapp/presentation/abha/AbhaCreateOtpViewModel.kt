package com.example.samdapp.presentation.abha

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.samdapp.domain.abha.AbhaEnrolResult
import com.example.samdapp.domain.audit.AuditAction
import com.example.samdapp.domain.audit.AuditLogger
import com.example.samdapp.domain.audit.auditPayload
import com.example.samdapp.domain.usecase.AbhaEnrolOutcome
import com.example.samdapp.domain.usecase.EnrolAbhaUseCase
import com.example.samdapp.presentation.common.OTP_LENGTH
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Which OTP this screen is currently asking for. [AADHAAR] is always first and is the round that
 * actually enrols the account. [COMMUNICATION_MOBILE] is entered only when the backend reports
 * `MOBILE_VERIFICATION_REQUIRED`, and it is a sub-state of this same screen rather than a separate
 * destination: same session, same composable, one extra round.
 */
enum class AbhaOtpRound { AADHAAR, COMMUNICATION_MOBILE }

/**
 * [mobileNumber] is the ABHA communication mobile the worker types. It is held only for as long as
 * this screen is on top of the back stack, is passed to [EnrolAbhaUseCase] as an argument, and is
 * never written to Room or to an audit payload — matching the backend's own `REDACTED_KEYS`
 * treatment of `mobile_number`. It is a different value from [maskedMobile], which is ABDM's masked
 * rendering of the Aadhaar-linked number and is display-only.
 *
 * [sessionId] is intentionally absent from this state: it is a constructor parameter, so it cannot
 * be rendered by a composable reading this object.
 */
data class AbhaCreateOtpUiState(
    val maskedMobile: String?,
    val round: AbhaOtpRound = AbhaOtpRound.AADHAAR,
    val otp: String = "",
    val mobileNumber: String = "",
    val isVerifying: Boolean = false,
    val errorMessage: String? = null,
    val errorRetryable: Boolean = false,
) {
    val canVerify: Boolean
        get() = otp.length == OTP_LENGTH && !isVerifying &&
            (round == AbhaOtpRound.COMMUNICATION_MOBILE || mobileNumber.length == MOBILE_LENGTH)
}

const val MOBILE_LENGTH = 10

sealed interface AbhaCreateOtpEffect {
    data class Enrolled(val abhaId: String) : AbhaCreateOtpEffect
}

@Stable
interface AbhaCreateOtpActions {
    fun onOtpChange(value: String)
    fun onMobileNumberChange(value: String)
    fun onVerify()
}

/**
 * Second step of the real ABHA create flow: verify the Aadhaar OTP, answer the conditional
 * mobile-OTP round if ABDM asks for one, and hand the resulting ABHA number to registration.
 *
 * Separate from [AbhaOtpViewModel], which drives the mock login OTP screen, rather than sharing it
 * behind a mode flag: the two have different inputs, different backends, and different
 * post-conditions, and the only genuinely common piece is the field itself
 * ([com.example.samdapp.presentation.common.OtpInputField]).
 */
@HiltViewModel(assistedFactory = AbhaCreateOtpViewModel.Factory::class)
class AbhaCreateOtpViewModel @AssistedInject constructor(
    // Both assisted parameters erase to java.lang.String, so Dagger needs the identifiers to tell
    // them apart; nullability is not part of what it matches on.
    @Assisted("sessionId") private val sessionId: String,
    @Assisted("maskedMobile") private val maskedMobile: String?,
    private val enrolAbhaUseCase: EnrolAbhaUseCase,
    private val auditLogger: AuditLogger,
) : ViewModel(), AbhaCreateOtpActions {

    @AssistedFactory
    interface Factory {
        fun create(
            @Assisted("sessionId") sessionId: String,
            @Assisted("maskedMobile") maskedMobile: String?,
        ): AbhaCreateOtpViewModel
    }

    private val _uiState = MutableStateFlow(AbhaCreateOtpUiState(maskedMobile = maskedMobile))
    val uiState: StateFlow<AbhaCreateOtpUiState> = _uiState.asStateFlow()

    private val _effects = Channel<AbhaCreateOtpEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    override fun onOtpChange(value: String) {
        _uiState.update { it.copy(otp = value, errorMessage = null) }
    }

    override fun onMobileNumberChange(value: String) {
        _uiState.update { it.copy(mobileNumber = value, errorMessage = null) }
    }

    override fun onVerify() {
        val current = _uiState.value
        if (!current.canVerify) return
        viewModelScope.launch {
            _uiState.update { it.copy(isVerifying = true, errorMessage = null, errorRetryable = false) }

            val result = when (current.round) {
                AbhaOtpRound.AADHAAR -> enrolAbhaUseCase(
                    sessionId = sessionId,
                    otp = current.otp,
                    mobileNumber = current.mobileNumber,
                )
                AbhaOtpRound.COMMUNICATION_MOBILE -> enrolAbhaUseCase.verifyCommunicationMobile(
                    sessionId = sessionId,
                    otp = current.otp,
                )
            }

            when (result) {
                is AbhaEnrolResult.Success -> onOutcome(result.data)
                is AbhaEnrolResult.Error -> _uiState.update {
                    it.copy(
                        isVerifying = false,
                        errorMessage = result.message,
                        errorRetryable = result.retryable,
                    )
                }
            }
        }
    }

    private suspend fun onOutcome(outcome: AbhaEnrolOutcome) {
        when (outcome) {
            // The OTP is cleared, not kept: the second round needs a different code, and leaving
            // the spent one in the field would let a double-tap submit it again.
            AbhaEnrolOutcome.MobileVerificationRequired -> _uiState.update {
                it.copy(isVerifying = false, round = AbhaOtpRound.COMMUNICATION_MOBILE, otp = "")
            }

            is AbhaEnrolOutcome.Enrolled -> {
                // Payload carries the ABHA number only. Not the session id, not the mobile number,
                // and certainly not the Aadhaar number or either OTP.
                auditLogger.log(
                    action = AuditAction.ABHA_PROFILE_CREATED,
                    payload = auditPayload("abhaId" to outcome.profile.abhaId),
                )
                _uiState.update { it.copy(isVerifying = false) }
                _effects.send(AbhaCreateOtpEffect.Enrolled(outcome.profile.abhaId))
            }
        }
    }
}
