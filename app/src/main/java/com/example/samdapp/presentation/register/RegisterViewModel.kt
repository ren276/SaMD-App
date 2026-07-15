package com.example.samdapp.presentation.register

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.samdapp.domain.audit.AuditLogger
import com.example.samdapp.domain.audit.auditPayload
import com.example.samdapp.domain.usecase.RegisterPatientUseCase
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

enum class RegisterField {
    FULL_NAME, DATE_OF_BIRTH, AGE, MOBILE_NUMBER, GUARDIAN_OR_SPOUSE_NAME, AADHAAR_NUMBER, ABHA_NUMBER,
    VILLAGE, BLOCK, DISTRICT, STATE, PINCODE, CATEGORY, MARITAL_STATUS, BLOOD_GROUP, EMERGENCY_CONTACT,
    PRIMARY_CARE_CLINIC_NAME, REFERRING_PHYSICIAN_NAME,
}

data class RegisterUiState(
    val fields: Map<RegisterField, String> = emptyMap(),
    val biologicalSex: String = "Female",
    val isSubmitting: Boolean = false,
    val errorMessage: String? = null,
) {
    val canSubmit: Boolean
        get() {
            val fullName = fields[RegisterField.FULL_NAME].orEmpty()
            val hasContact = fields[RegisterField.MOBILE_NUMBER].orEmpty().isNotBlank() ||
                fields[RegisterField.VILLAGE].orEmpty().isNotBlank() ||
                fields[RegisterField.DISTRICT].orEmpty().isNotBlank()
            return fullName.isNotBlank() && hasContact && !isSubmitting
        }
}

sealed interface RegisterEffect {
    data class Registered(val patientId: String) : RegisterEffect
}

@Stable
interface RegisterActions {
    fun onFieldChange(field: RegisterField, value: String)
    fun onBiologicalSexChange(sex: String)
    fun onSubmit()
}

@HiltViewModel
class RegisterViewModel @Inject constructor(
    private val registerPatientUseCase: RegisterPatientUseCase,
    private val auditLogger: AuditLogger,
) : ViewModel(), RegisterActions {

    private val _uiState = MutableStateFlow(RegisterUiState())
    val uiState: StateFlow<RegisterUiState> = _uiState.asStateFlow()

    private val _effects = Channel<RegisterEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    override fun onFieldChange(field: RegisterField, value: String) {
        _uiState.update { it.copy(fields = it.fields + (field to value)) }
    }

    override fun onBiologicalSexChange(sex: String) {
        _uiState.update { it.copy(biologicalSex = sex) }
    }

    override fun onSubmit() {
        val current = _uiState.value
        if (!current.canSubmit) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, errorMessage = null) }
            val fields = current.fields
            val result = registerPatientUseCase(
                fullName = fields[RegisterField.FULL_NAME].orEmpty(),
                dateOfBirth = fields[RegisterField.DATE_OF_BIRTH]?.takeIf { it.isNotBlank() }
                    ?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
                age = fields[RegisterField.AGE]?.toIntOrNull(),
                biologicalSex = current.biologicalSex,
                mobileNumber = fields[RegisterField.MOBILE_NUMBER]?.takeIf { it.isNotBlank() },
                village = fields[RegisterField.VILLAGE]?.takeIf { it.isNotBlank() },
                guardianOrSpouseName = fields[RegisterField.GUARDIAN_OR_SPOUSE_NAME]?.takeIf { it.isNotBlank() },
                aadhaarNumber = fields[RegisterField.AADHAAR_NUMBER]?.takeIf { it.isNotBlank() },
                abhaNumber = fields[RegisterField.ABHA_NUMBER]?.takeIf { it.isNotBlank() },
                block = fields[RegisterField.BLOCK]?.takeIf { it.isNotBlank() },
                district = fields[RegisterField.DISTRICT]?.takeIf { it.isNotBlank() },
                state = fields[RegisterField.STATE]?.takeIf { it.isNotBlank() },
                pincode = fields[RegisterField.PINCODE]?.takeIf { it.isNotBlank() },
                category = fields[RegisterField.CATEGORY]?.takeIf { it.isNotBlank() },
                maritalStatus = fields[RegisterField.MARITAL_STATUS]?.takeIf { it.isNotBlank() },
                bloodGroup = fields[RegisterField.BLOOD_GROUP]?.takeIf { it.isNotBlank() },
                emergencyContact = fields[RegisterField.EMERGENCY_CONTACT]?.takeIf { it.isNotBlank() },
                primaryCareClinicName = fields[RegisterField.PRIMARY_CARE_CLINIC_NAME]?.takeIf { it.isNotBlank() },
                referringPhysicianName = fields[RegisterField.REFERRING_PHYSICIAN_NAME]?.takeIf { it.isNotBlank() },
            )
            result.fold(
                onSuccess = { patient ->
                    _uiState.update { it.copy(isSubmitting = false) }
                    auditLogger.log(
                        action = "patient_registered",
                        patientId = patient.id,
                        payload = auditPayload("fullName" to patient.fullName, "biologicalSex" to current.biologicalSex),
                    )
                    _effects.send(RegisterEffect.Registered(patient.id))
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(isSubmitting = false, errorMessage = error.message ?: "Registration failed")
                    }
                },
            )
        }
    }
}
