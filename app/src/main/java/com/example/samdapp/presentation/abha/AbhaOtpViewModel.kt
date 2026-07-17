package com.example.samdapp.presentation.abha

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.samdapp.domain.audit.AuditAction
import com.example.samdapp.domain.audit.AuditLogger
import com.example.samdapp.domain.audit.auditPayload
import com.example.samdapp.domain.usecase.VerifyAbhaLoginUseCase
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

/** [otp] pre-fills "123456" and is labeled a mock in the UI — [VerifyAbhaLoginUseCase] accepts any
 *  6-digit code, it doesn't special-case that value. */
data class AbhaOtpUiState(
    val abhaId: String,
    val otp: String = "123456",
    val isVerifying: Boolean = false,
    val errorMessage: String? = null,
) {
    val canVerify: Boolean get() = otp.length == 6 && !isVerifying
}

sealed interface AbhaOtpEffect {
    data class Verified(val abhaId: String) : AbhaOtpEffect
}

@Stable
interface AbhaOtpActions {
    fun onOtpChange(value: String)
    fun onVerify()
}

@HiltViewModel(assistedFactory = AbhaOtpViewModel.Factory::class)
class AbhaOtpViewModel @AssistedInject constructor(
    @Assisted private val abhaId: String,
    private val verifyAbhaLoginUseCase: VerifyAbhaLoginUseCase,
    private val auditLogger: AuditLogger,
) : ViewModel(), AbhaOtpActions {

    @AssistedFactory
    interface Factory {
        fun create(abhaId: String): AbhaOtpViewModel
    }

    private val _uiState = MutableStateFlow(AbhaOtpUiState(abhaId = abhaId))
    val uiState: StateFlow<AbhaOtpUiState> = _uiState.asStateFlow()

    private val _effects = Channel<AbhaOtpEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    override fun onOtpChange(value: String) {
        _uiState.update { it.copy(otp = value) }
    }

    override fun onVerify() {
        val current = _uiState.value
        if (!current.canVerify) return
        viewModelScope.launch {
            _uiState.update { it.copy(isVerifying = true, errorMessage = null) }
            val result = verifyAbhaLoginUseCase(abhaId = abhaId, otp = current.otp)
            result.fold(
                onSuccess = { profile ->
                    auditLogger.log(
                        action = AuditAction.ABHA_LOGIN_VERIFIED,
                        payload = auditPayload("abhaId" to profile.abhaId),
                    )
                    _uiState.update { it.copy(isVerifying = false) }
                    _effects.send(AbhaOtpEffect.Verified(profile.abhaId))
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(isVerifying = false, errorMessage = error.message ?: "Verification failed")
                    }
                },
            )
        }
    }
}
