package com.example.samdapp.presentation.abha

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.samdapp.domain.audit.AuditAction
import com.example.samdapp.domain.audit.AuditLogger
import com.example.samdapp.domain.audit.auditPayload
import com.example.samdapp.domain.usecase.CreateAbhaProfileUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

enum class AbhaSignUpStage { FORM, REDIRECTING }

data class AbhaSignUpUiState(
    val stage: AbhaSignUpStage = AbhaSignUpStage.FORM,
    val name: String = "",
    val dateOfBirth: String = "",
    val gender: String = "Female",
    val mobileNumber: String = "",
    val errorMessage: String? = null,
) {
    val canSubmit: Boolean
        get() = name.isNotBlank() && dateOfBirth.isNotBlank() && mobileNumber.length == 10 &&
            stage == AbhaSignUpStage.FORM
}

sealed interface AbhaSignUpEffect {
    data class Created(val abhaId: String) : AbhaSignUpEffect
}

@Stable
interface AbhaSignUpActions {
    fun onNameChange(value: String)
    fun onDateOfBirthChange(value: String)
    fun onGenderChange(value: String)
    fun onMobileNumberChange(value: String)
    fun onSubmit()
}

/**
 * Mock "Create ABHA ID" flow. [AbhaSignUpUiState.stage] REDIRECTING is shown for the whole
 * duration of [CreateAbhaProfileUseCase]'s simulated portal delay — that's the "Redirecting to
 * ABHA Portal (simulated)" screen the brief asks for; no separate delay is added here.
 */
@HiltViewModel
class AbhaSignUpViewModel @Inject constructor(
    private val createAbhaProfileUseCase: CreateAbhaProfileUseCase,
    private val auditLogger: AuditLogger,
) : ViewModel(), AbhaSignUpActions {

    private val _uiState = MutableStateFlow(AbhaSignUpUiState())
    val uiState: StateFlow<AbhaSignUpUiState> = _uiState.asStateFlow()

    private val _effects = Channel<AbhaSignUpEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    override fun onNameChange(value: String) {
        _uiState.update { it.copy(name = value) }
    }

    override fun onDateOfBirthChange(value: String) {
        _uiState.update { it.copy(dateOfBirth = value) }
    }

    override fun onGenderChange(value: String) {
        _uiState.update { it.copy(gender = value) }
    }

    override fun onMobileNumberChange(value: String) {
        _uiState.update { it.copy(mobileNumber = value) }
    }

    override fun onSubmit() {
        val current = _uiState.value
        if (!current.canSubmit) return
        viewModelScope.launch {
            _uiState.update { it.copy(stage = AbhaSignUpStage.REDIRECTING, errorMessage = null) }
            val result = createAbhaProfileUseCase(
                name = current.name,
                dateOfBirth = runCatching { LocalDate.parse(current.dateOfBirth) }.getOrNull(),
                gender = current.gender,
                mobileNumber = current.mobileNumber,
            )
            result.fold(
                onSuccess = { profile ->
                    auditLogger.log(
                        action = AuditAction.ABHA_PROFILE_CREATED,
                        payload = auditPayload("abhaId" to profile.abhaId),
                    )
                    _effects.send(AbhaSignUpEffect.Created(profile.abhaId))
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(stage = AbhaSignUpStage.FORM, errorMessage = error.message ?: "Could not create ABHA ID")
                    }
                },
            )
        }
    }
}
