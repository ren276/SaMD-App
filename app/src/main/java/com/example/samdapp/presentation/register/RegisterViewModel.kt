package com.example.samdapp.presentation.register

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.samdapp.data.mock.DemoPatientProfile
import com.example.samdapp.domain.audit.AuditLogger
import com.example.samdapp.domain.audit.auditPayload
import com.example.samdapp.domain.repository.AbhaProfileRepository
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

/** Fields where the DB expects a fixed-length, digits-only value — enforced here so a
 * malformed mobile/pincode/Aadhaar/ABHA number can never reach Room (and later, the cloud). */
private val DIGIT_LENGTH_RULES: Map<RegisterField, Int> = mapOf(
    RegisterField.MOBILE_NUMBER to 10,
    RegisterField.EMERGENCY_CONTACT to 10,
    RegisterField.PINCODE to 6,
    RegisterField.AADHAAR_NUMBER to 12,
    RegisterField.ABHA_NUMBER to 14,
)

data class RegisterUiState(
    val fields: Map<RegisterField, String> = emptyMap(),
    val biologicalSex: String = "Female",
    val isSubmitting: Boolean = false,
    val errorMessage: String? = null,
    /** Non-null once an ABHA profile has been loaded (REQ-ABH-02) — the fields it populated are
     *  in [autofilledFields], shown visually tagged "from ABHA" so the provenance is legible. */
    val abhaId: String? = null,
    val autofilledFields: Set<RegisterField> = emptySet(),
    val sexAutofilledFromAbha: Boolean = false,
) {
    fun fieldError(field: RegisterField): String? {
        val expectedLength = DIGIT_LENGTH_RULES[field] ?: return null
        val value = fields[field].orEmpty()
        if (value.isBlank()) return null
        if (value.length != expectedLength || !value.all(Char::isDigit)) {
            return "Must be $expectedLength digits"
        }
        return null
    }

    private val hasValidationErrors: Boolean
        get() = RegisterField.entries.any { fieldError(it) != null }

    val canSubmit: Boolean
        get() {
            val fullName = fields[RegisterField.FULL_NAME].orEmpty()
            val hasContact = fields[RegisterField.MOBILE_NUMBER].orEmpty().isNotBlank() ||
                fields[RegisterField.VILLAGE].orEmpty().isNotBlank() ||
                fields[RegisterField.DISTRICT].orEmpty().isNotBlank()
            return fullName.isNotBlank() && hasContact && !isSubmitting && !hasValidationErrors
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
    /** Pre-fills every field from [DemoPatientProfile] — investor-demo shortcut only. */
    fun fillDemoData()
}

@HiltViewModel
class RegisterViewModel @Inject constructor(
    private val registerPatientUseCase: RegisterPatientUseCase,
    private val auditLogger: AuditLogger,
    private val abhaProfileRepository: AbhaProfileRepository,
) : ViewModel(), RegisterActions {

    private val _uiState = MutableStateFlow(RegisterUiState())
    val uiState: StateFlow<RegisterUiState> = _uiState.asStateFlow()

    private val _effects = Channel<RegisterEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    /**
     * Loads the stored [com.example.samdapp.domain.model.AbhaProfile] and autofills registration
     * fields from it (REQ-ABH-02) — called once from [com.example.samdapp.presentation.register.RegisterScreen]
     * via `LaunchedEffect(abhaId)` rather than taken as a constructor param, since [abhaId] is
     * optional (null on the manual/no-ABHA path) and not needed before the first frame.
     */
    fun loadAbhaProfile(abhaId: String) {
        if (_uiState.value.abhaId == abhaId) return
        viewModelScope.launch {
            val profile = abhaProfileRepository.getProfile(abhaId) ?: return@launch
            val autofilled = mutableSetOf<RegisterField>()
            fun autofill(fields: Map<RegisterField, String>, field: RegisterField, value: String?): Map<RegisterField, String> {
                if (value.isNullOrBlank()) return fields
                autofilled += field
                return fields + (field to value)
            }
            _uiState.update { state ->
                var fields = state.fields
                fields = autofill(fields, RegisterField.FULL_NAME, profile.name)
                fields = autofill(fields, RegisterField.MOBILE_NUMBER, profile.mobileNumber)
                fields = autofill(fields, RegisterField.VILLAGE, profile.address)
                fields = autofill(fields, RegisterField.DISTRICT, profile.district)
                fields = autofill(fields, RegisterField.STATE, profile.state)
                fields = autofill(fields, RegisterField.PINCODE, profile.pincode)
                fields = autofill(fields, RegisterField.ABHA_NUMBER, profile.abhaId)
                fields = autofill(fields, RegisterField.DATE_OF_BIRTH, profile.dateOfBirth?.toString())
                val sexAutofilled = profile.gender in listOf("Female", "Male", "Other")
                state.copy(
                    fields = fields,
                    biologicalSex = if (sexAutofilled) profile.gender else state.biologicalSex,
                    abhaId = abhaId,
                    autofilledFields = autofilled,
                    sexAutofilledFromAbha = sexAutofilled,
                )
            }
        }
    }

    override fun onFieldChange(field: RegisterField, value: String) {
        // A manual edit overrides the ABHA-sourced value — drop the "from ABHA" tag so the UI
        // never shows a stale provenance claim for text the worker just typed over.
        _uiState.update { it.copy(fields = it.fields + (field to value), autofilledFields = it.autofilledFields - field) }
    }

    override fun onBiologicalSexChange(sex: String) {
        _uiState.update { it.copy(biologicalSex = sex, sexAutofilledFromAbha = false) }
    }

    /** Investor-demo shortcut: fills every field from [DemoPatientProfile] in one tap. */
    override fun fillDemoData() {
        _uiState.update { state ->
            state.copy(
                biologicalSex = DemoPatientProfile.BIOLOGICAL_SEX,
                fields = state.fields + mapOf(
                    RegisterField.FULL_NAME to DemoPatientProfile.FULL_NAME,
                    RegisterField.DATE_OF_BIRTH to DemoPatientProfile.DATE_OF_BIRTH,
                    RegisterField.MOBILE_NUMBER to DemoPatientProfile.MOBILE_NUMBER,
                    RegisterField.EMERGENCY_CONTACT to DemoPatientProfile.EMERGENCY_CONTACT,
                    RegisterField.GUARDIAN_OR_SPOUSE_NAME to DemoPatientProfile.GUARDIAN_OR_SPOUSE_NAME,
                    RegisterField.VILLAGE to DemoPatientProfile.VILLAGE,
                    RegisterField.BLOCK to DemoPatientProfile.BLOCK,
                    RegisterField.DISTRICT to DemoPatientProfile.DISTRICT,
                    RegisterField.STATE to DemoPatientProfile.STATE,
                    RegisterField.PINCODE to DemoPatientProfile.PINCODE,
                    RegisterField.CATEGORY to DemoPatientProfile.CATEGORY,
                    RegisterField.MARITAL_STATUS to DemoPatientProfile.MARITAL_STATUS,
                    RegisterField.BLOOD_GROUP to DemoPatientProfile.BLOOD_GROUP,
                    RegisterField.AADHAAR_NUMBER to DemoPatientProfile.AADHAAR_NUMBER,
                    RegisterField.ABHA_NUMBER to DemoPatientProfile.ABHA_NUMBER,
                    RegisterField.PRIMARY_CARE_CLINIC_NAME to DemoPatientProfile.PRIMARY_CARE_CLINIC_NAME,
                    RegisterField.REFERRING_PHYSICIAN_NAME to DemoPatientProfile.REFERRING_PHYSICIAN_NAME,
                ),
            )
        }
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
