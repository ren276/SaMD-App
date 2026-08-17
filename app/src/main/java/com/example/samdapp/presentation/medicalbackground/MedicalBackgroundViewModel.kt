package com.example.samdapp.presentation.medicalbackground

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.samdapp.data.mock.DemoPatientProfile
import com.example.samdapp.domain.audit.AuditAction
import com.example.samdapp.domain.audit.AuditLogger
import com.example.samdapp.domain.audit.auditPayload
import com.example.samdapp.domain.model.Allergy
import com.example.samdapp.domain.model.AllergyCategory
import com.example.samdapp.domain.model.FamilyHistoryEntry
import com.example.samdapp.domain.model.MedicalHistoryCategory
import com.example.samdapp.domain.model.MedicalHistoryItem
import com.example.samdapp.domain.model.MedicationEntry
import com.example.samdapp.domain.model.MedicationKind
import com.example.samdapp.domain.model.SocialHistory
import com.example.samdapp.domain.repository.MedicalBackgroundRepository
import com.example.samdapp.domain.usecase.AddAllergyUseCase
import com.example.samdapp.domain.usecase.AddFamilyHistoryEntryUseCase
import com.example.samdapp.domain.usecase.AddMedicalHistoryItemUseCase
import com.example.samdapp.domain.usecase.AddMedicationUseCase
import com.example.samdapp.domain.usecase.SaveSocialHistoryUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MedicalBackgroundUiState(
    val medicalHistoryItems: List<MedicalHistoryItem> = emptyList(),
    val medications: List<MedicationEntry> = emptyList(),
    val allergies: List<Allergy> = emptyList(),
    val familyHistory: List<FamilyHistoryEntry> = emptyList(),
    val socialHistory: SocialHistory? = null,
)

@Stable
interface MedicalBackgroundActions {
    fun onAddMedicalHistoryItem(category: MedicalHistoryCategory, description: String, yearOrDate: String?)
    fun onAddMedication(kind: MedicationKind, name: String, dosage: String?, frequency: String?)
    fun onAddAllergy(category: AllergyCategory, allergen: String, reactionType: String?)
    fun onAddFamilyHistoryEntry(condition: String, relation: String?)
    fun onSaveSocialHistory(
        occupation: String?,
        tobaccoUse: String?,
        alcoholUse: String?,
        recreationalDrugUse: String?,
        environmentalExposure: String?,
        recentTravel: String?,
    )
    /** Bulk-loads all demo data for investor presentation — calls the individual add* functions. */
    fun fillDemoData()
}

@HiltViewModel(assistedFactory = MedicalBackgroundViewModel.Factory::class)
class MedicalBackgroundViewModel @AssistedInject constructor(
    @Assisted private val patientId: String,
    private val repository: MedicalBackgroundRepository,
    private val addMedicalHistoryItemUseCase: AddMedicalHistoryItemUseCase,
    private val addMedicationUseCase: AddMedicationUseCase,
    private val addAllergyUseCase: AddAllergyUseCase,
    private val addFamilyHistoryEntryUseCase: AddFamilyHistoryEntryUseCase,
    private val saveSocialHistoryUseCase: SaveSocialHistoryUseCase,
    private val auditLogger: AuditLogger,
) : ViewModel(), MedicalBackgroundActions {

    @AssistedFactory
    interface Factory {
        fun create(patientId: String): MedicalBackgroundViewModel
    }

    private val _uiState = MutableStateFlow(MedicalBackgroundUiState())
    val uiState: StateFlow<MedicalBackgroundUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                repository.observeMedicalHistory(patientId),
                repository.observeMedications(patientId),
                repository.observeAllergies(patientId),
                repository.observeFamilyHistory(patientId),
                repository.observeSocialHistory(patientId),
            ) { history, medications, allergies, family, social ->
                MedicalBackgroundUiState(history, medications, allergies, family, social)
            }.collect { snapshot -> _uiState.update { snapshot } }
        }
    }

    override fun onAddMedicalHistoryItem(category: MedicalHistoryCategory, description: String, yearOrDate: String?) {
        viewModelScope.launch {
            addMedicalHistoryItemUseCase(patientId, category, description, yearOrDate).onSuccess {
                auditLogger.log(
                    action = AuditAction.MEDICAL_HISTORY_ITEM_ADDED,
                    patientId = patientId,
                    payload = auditPayload("category" to category.name, "description" to description),
                )
            }
        }
    }

    override fun onAddMedication(kind: MedicationKind, name: String, dosage: String?, frequency: String?) {
        viewModelScope.launch {
            addMedicationUseCase(patientId, encounterId = null, kind, name, dosage, frequency).onSuccess {
                auditLogger.log(
                    action = AuditAction.MEDICATION_ADDED,
                    patientId = patientId,
                    payload = auditPayload("kind" to kind.name, "name" to name, "dosage" to dosage),
                )
            }
        }
    }

    override fun onAddAllergy(category: AllergyCategory, allergen: String, reactionType: String?) {
        viewModelScope.launch {
            addAllergyUseCase(patientId, category, allergen, reactionType).onSuccess {
                auditLogger.log(
                    action = AuditAction.ALLERGY_ADDED,
                    patientId = patientId,
                    payload = auditPayload("category" to category.name, "allergen" to allergen),
                )
            }
        }
    }

    override fun onAddFamilyHistoryEntry(condition: String, relation: String?) {
        viewModelScope.launch {
            addFamilyHistoryEntryUseCase(patientId, condition, relation).onSuccess {
                auditLogger.log(
                    action = AuditAction.FAMILY_HISTORY_ADDED,
                    patientId = patientId,
                    payload = auditPayload("condition" to condition, "relation" to relation),
                )
            }
        }
    }

    override fun onSaveSocialHistory(
        occupation: String?,
        tobaccoUse: String?,
        alcoholUse: String?,
        recreationalDrugUse: String?,
        environmentalExposure: String?,
        recentTravel: String?,
    ) {
        viewModelScope.launch {
            saveSocialHistoryUseCase(
                patientId, occupation, tobaccoUse, alcoholUse, recreationalDrugUse, environmentalExposure, recentTravel,
            ).onSuccess {
                auditLogger.log(
                    action = AuditAction.SOCIAL_HISTORY_SAVED,
                    patientId = patientId,
                    payload = auditPayload("occupation" to occupation, "tobaccoUse" to tobaccoUse),
                )
            }
        }
    }

    /** Investor-demo shortcut: bulk-inserts all mock clinical background data for [patientId]. */
    override fun fillDemoData() {
        DemoPatientProfile.MEDICAL_HISTORY.forEach { item ->
            onAddMedicalHistoryItem(item.category, item.description, item.yearOrDate)
        }
        DemoPatientProfile.MEDICATIONS.forEach { med ->
            onAddMedication(med.kind, med.name, med.dosage, med.frequency)
        }
        DemoPatientProfile.ALLERGIES.forEach { allergy ->
            onAddAllergy(allergy.category, allergy.allergen, allergy.reactionType)
        }
        DemoPatientProfile.FAMILY_HISTORY.forEach { entry ->
            onAddFamilyHistoryEntry(entry.condition, entry.relation)
        }
        val sh = DemoPatientProfile.SOCIAL_HISTORY
        onSaveSocialHistory(sh.occupation, sh.tobaccoUse, sh.alcoholUse, sh.recreationalDrugUse, sh.environmentalExposure, sh.recentTravel)
    }
}
