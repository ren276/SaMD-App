package com.example.samdapp.presentation.compounder

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.samdapp.domain.model.ObservationSource
import com.example.samdapp.domain.model.VitalsSnapshot
import com.example.samdapp.domain.model.toSnapshot
import com.example.samdapp.domain.repository.ConsultationRepository
import com.example.samdapp.domain.usecase.AddSymptomUseCase
import com.example.samdapp.domain.usecase.GetVitalsPrefillUseCase
import com.example.samdapp.domain.usecase.RecordVitalsUseCase
import com.example.samdapp.domain.usecase.StartCaseUseCase
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
import java.time.Instant

data class CompounderUiState(
    val encounterId: String? = null,
    val caseRecordId: String? = null,
    val isLoadingPrefill: Boolean = true,
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    val pulseBpm: String = "",
    val bpSystolic: String = "",
    val bpDiastolic: String = "",
    val spo2Percent: String = "",
    val temperatureCelsius: String = "",
    val respiratoryRate: String = "",
    val weightKg: String = "",
    val heightCm: String = "",
    val painScore: String = "",
    val showPointOfCareTests: Boolean = false,
    val bloodGlucoseMgDl: String = "",
    val urinalysisResult: String = "",
    val chiefComplaint: String = "",
    val newSymptomText: String = "",
    val symptoms: List<String> = emptyList(),
    val source: ObservationSource = ObservationSource.MANUAL,
) {
    val bmi: Double?
        get() {
            val w = weightKg.toDoubleOrNull() ?: return null
            val h = heightCm.toDoubleOrNull() ?: return null
            if (h <= 0) return null
            val meters = h / 100.0
            return (Math.round((w / (meters * meters)) * 10.0)) / 10.0
        }

    val canContinue: Boolean get() = !isLoadingPrefill && !isSaving && chiefComplaint.isNotBlank() && encounterId != null
}

sealed interface CompounderEffect {
    data class Continue(
        val patientId: String,
        val encounterId: String,
        val caseRecordId: String,
        val chiefComplaint: String,
    ) : CompounderEffect
}

@Stable
interface CompounderActions {
    fun onPulseChange(value: String)
    fun onBpSystolicChange(value: String)
    fun onBpDiastolicChange(value: String)
    fun onSpo2Change(value: String)
    fun onTemperatureChange(value: String)
    fun onRespiratoryRateChange(value: String)
    fun onWeightChange(value: String)
    fun onHeightChange(value: String)
    fun onPainScoreChange(value: String)
    fun onTogglePointOfCareTests()
    fun onBloodGlucoseChange(value: String)
    fun onUrinalysisChange(value: String)
    fun onChiefComplaintChange(value: String)
    fun onNewSymptomTextChange(value: String)
    fun onAddSymptom()
    fun onContinue()
}

@HiltViewModel(assistedFactory = CompounderViewModel.Factory::class)
class CompounderViewModel @AssistedInject constructor(
    @Assisted private val patientId: String,
    private val startCaseUseCase: StartCaseUseCase,
    private val getVitalsPrefillUseCase: GetVitalsPrefillUseCase,
    private val recordVitalsUseCase: RecordVitalsUseCase,
    private val addSymptomUseCase: AddSymptomUseCase,
    private val consultationRepository: ConsultationRepository,
) : ViewModel(), CompounderActions {

    @AssistedFactory
    interface Factory {
        fun create(patientId: String): CompounderViewModel
    }

    private val _uiState = MutableStateFlow(CompounderUiState())
    val uiState: StateFlow<CompounderUiState> = _uiState.asStateFlow()

    private val _effects = Channel<CompounderEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    init {
        viewModelScope.launch {
            val started = startCaseUseCase(patientId).getOrElse {
                _uiState.update { state -> state.copy(isLoadingPrefill = false, errorMessage = "Could not start visit") }
                return@launch
            }
            _uiState.update { it.copy(encounterId = started.encounter.id, caseRecordId = started.caseRecord.id) }
            launch {
                consultationRepository.observeSymptoms(started.encounter.id).collect { symptoms ->
                    _uiState.update { it.copy(symptoms = symptoms.map(com.example.samdapp.domain.model.Symptom::description)) }
                }
            }
            val prefill = getVitalsPrefillUseCase()
            val snapshot = prefill.toSnapshot(started.encounter.id, patientId, Instant.now())
            _uiState.update {
                it.copy(
                    isLoadingPrefill = false,
                    pulseBpm = snapshot.pulseBpm?.toString().orEmpty(),
                    bpSystolic = snapshot.bpSystolic?.toString().orEmpty(),
                    bpDiastolic = snapshot.bpDiastolic?.toString().orEmpty(),
                    spo2Percent = snapshot.spo2Percent?.toString().orEmpty(),
                    temperatureCelsius = snapshot.temperatureCelsius?.toString().orEmpty(),
                    respiratoryRate = snapshot.respiratoryRate?.toString().orEmpty(),
                    weightKg = snapshot.weightKg?.toString().orEmpty(),
                    heightCm = snapshot.heightCm?.toString().orEmpty(),
                    bloodGlucoseMgDl = snapshot.bloodGlucoseMgDl?.toString().orEmpty(),
                    source = ObservationSource.DEVICE,
                )
            }
        }
    }

    override fun onPulseChange(value: String) = _uiState.update { it.copy(pulseBpm = value) }
    override fun onBpSystolicChange(value: String) = _uiState.update { it.copy(bpSystolic = value) }
    override fun onBpDiastolicChange(value: String) = _uiState.update { it.copy(bpDiastolic = value) }
    override fun onSpo2Change(value: String) = _uiState.update { it.copy(spo2Percent = value) }
    override fun onTemperatureChange(value: String) = _uiState.update { it.copy(temperatureCelsius = value) }
    override fun onRespiratoryRateChange(value: String) = _uiState.update { it.copy(respiratoryRate = value) }
    override fun onWeightChange(value: String) = _uiState.update { it.copy(weightKg = value) }
    override fun onHeightChange(value: String) = _uiState.update { it.copy(heightCm = value) }
    override fun onPainScoreChange(value: String) = _uiState.update { it.copy(painScore = value) }
    override fun onTogglePointOfCareTests() = _uiState.update { it.copy(showPointOfCareTests = !it.showPointOfCareTests) }
    override fun onBloodGlucoseChange(value: String) = _uiState.update { it.copy(bloodGlucoseMgDl = value) }
    override fun onUrinalysisChange(value: String) = _uiState.update { it.copy(urinalysisResult = value) }
    override fun onChiefComplaintChange(value: String) = _uiState.update { it.copy(chiefComplaint = value) }
    override fun onNewSymptomTextChange(value: String) = _uiState.update { it.copy(newSymptomText = value) }

    override fun onAddSymptom() {
        val current = _uiState.value
        val encounterId = current.encounterId ?: return
        if (current.newSymptomText.isBlank()) return
        viewModelScope.launch {
            addSymptomUseCase(patientId, encounterId, current.newSymptomText)
            _uiState.update { it.copy(newSymptomText = "") }
        }
    }

    override fun onContinue() {
        val current = _uiState.value
        val encounterId = current.encounterId ?: return
        val caseRecordId = current.caseRecordId ?: return
        if (!current.canContinue) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            val snapshot = VitalsSnapshot(
                encounterId = encounterId,
                patientId = patientId,
                pulseBpm = current.pulseBpm.toIntOrNull(),
                bpSystolic = current.bpSystolic.toIntOrNull(),
                bpDiastolic = current.bpDiastolic.toIntOrNull(),
                spo2Percent = current.spo2Percent.toIntOrNull(),
                temperatureCelsius = current.temperatureCelsius.toDoubleOrNull(),
                respiratoryRate = current.respiratoryRate.toIntOrNull(),
                weightKg = current.weightKg.toDoubleOrNull(),
                heightCm = current.heightCm.toDoubleOrNull(),
                bloodGlucoseMgDl = current.bloodGlucoseMgDl.toIntOrNull(),
                painScore = current.painScore.toIntOrNull(),
                urinalysisResult = current.urinalysisResult.ifBlank { null },
                source = current.source,
                recordedAt = Instant.now(),
            )
            recordVitalsUseCase(snapshot).fold(
                onSuccess = {
                    _uiState.update { it.copy(isSaving = false) }
                    _effects.send(CompounderEffect.Continue(patientId, encounterId, caseRecordId, current.chiefComplaint))
                },
                onFailure = { error ->
                    _uiState.update { it.copy(isSaving = false, errorMessage = error.message ?: "Could not save vitals") }
                },
            )
        }
    }
}
