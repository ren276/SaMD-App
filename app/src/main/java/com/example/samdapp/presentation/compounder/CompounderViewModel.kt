package com.example.samdapp.presentation.compounder

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.samdapp.data.mock.DemoPatientProfile
import com.example.samdapp.domain.audit.AuditAction
import com.example.samdapp.domain.audit.AuditLogger
import com.example.samdapp.domain.audit.auditPayload
import com.example.samdapp.domain.media.AilmentAudioRecorder
import com.example.samdapp.domain.model.AilmentEntry
import com.example.samdapp.domain.model.MeasurementType
import com.example.samdapp.domain.model.ObservationSource
import com.example.samdapp.domain.model.Visibility
import com.example.samdapp.domain.model.VitalsCaptureMethod
import com.example.samdapp.domain.model.VitalsSnapshot
import com.example.samdapp.domain.model.toSnapshot
import com.example.samdapp.domain.repository.AilmentRepository
import com.example.samdapp.domain.usecase.AddAilmentUseCase
import com.example.samdapp.domain.usecase.CheckEmergencyThresholdsUseCase
import com.example.samdapp.domain.usecase.DeleteAilmentUseCase
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

/**
 * Worker-facing projection of [AilmentEntry]. For a [Visibility.PRIVATE] entry, [description],
 * [measuredValue]/[measuredUnit], [severity], [duration], and [onset] are all `null` here — not
 * merely hidden by the UI, genuinely absent from this state, satisfying REQ-AIL-02's "never render
 * private text into worker-facing UI state, full stop." [audioUriForDelete] is a file handle for
 * the delete affordance only; there is no playback code path anywhere that could read it back.
 *
 * The clinical kernel (Phase 4) reads from [AilmentRepository.observeForEncounter] directly, not
 * through this projection — it still receives every entry regardless of visibility (REQ-AIL-04).
 */
data class AilmentListItem(
    val id: String,
    val visibility: Visibility,
    val measurementType: MeasurementType,
    val description: String?,
    val measuredValue: Double?,
    val measuredUnit: String?,
    val severity: Int?,
    val duration: String?,
    val onset: String?,
    val hasAudio: Boolean,
    val audioUriForDelete: String?,
)

internal fun AilmentEntry.toListItem(): AilmentListItem {
    val isPrivate = visibility == Visibility.PRIVATE
    return AilmentListItem(
        id = id,
        visibility = visibility,
        measurementType = measurementType,
        description = if (isPrivate) null else description,
        measuredValue = if (isPrivate) null else measuredValue,
        measuredUnit = if (isPrivate) null else measuredUnit,
        severity = if (isPrivate) null else severity,
        duration = if (isPrivate) null else duration,
        onset = if (isPrivate) null else onset,
        hasAudio = audioLocalUri != null,
        audioUriForDelete = audioLocalUri,
    )
}

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
    val ailments: List<AilmentListItem> = emptyList(),
    val newAilmentDescription: String = "",
    val newAilmentMeasurementType: MeasurementType = MeasurementType.NON_MEASURABLE,
    val newAilmentMeasuredValue: String = "",
    val newAilmentMeasuredUnit: String = "",
    val newAilmentSeverity: String = "",
    val newAilmentDuration: String = "",
    val newAilmentOnset: String = "",
    val newAilmentQualifiers: String = "",
    val newAilmentVisibility: Visibility = Visibility.PUBLIC,
    val showPrivateHandoffInterstitial: Boolean = false,
    val isRecordingAilmentAudio: Boolean = false,
    val pendingAilmentAudioUri: String? = null,
    val source: ObservationSource = ObservationSource.MANUAL,
    val captureMethod: VitalsCaptureMethod? = null,
) {
    val canAddAilment: Boolean
        get() = newAilmentDescription.isNotBlank() &&
            (newAilmentMeasurementType == MeasurementType.NON_MEASURABLE || newAilmentMeasuredValue.isNotBlank())

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

    /** Short-circuits past Consultation/Sending entirely (REQ-TRS-02) — see EmergencyOverrideScreen. */
    data class EmergencyOverride(val reasons: List<String>) : CompounderEffect
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
    fun onCaptureMethodChange(method: VitalsCaptureMethod)
    fun onTogglePointOfCareTests()
    fun onBloodGlucoseChange(value: String)
    fun onUrinalysisChange(value: String)
    fun onChiefComplaintChange(value: String)
    fun onAilmentDescriptionChange(value: String)
    fun onAilmentMeasurementTypeChange(type: MeasurementType)
    fun onAilmentMeasuredValueChange(value: String)
    fun onAilmentMeasuredUnitChange(value: String)
    fun onAilmentSeverityChange(value: String)
    fun onAilmentDurationChange(value: String)
    fun onAilmentOnsetChange(value: String)
    fun onAilmentQualifiersChange(value: String)
    fun onAilmentVisibilityToggle()
    fun onPrivateHandoffAcknowledged()
    fun onPrivateHandoffCancelled()
    fun onStartAilmentAudioRecording()
    fun onStopAilmentAudioRecording()
    fun onAddAilment()
    fun onDeleteAilment(id: String, audioUri: String?)
    fun onContinue()
    /** Pre-fills main concern, vitals and the ailment form from [DemoPatientProfile] — demo only. */
    fun fillDemoData()
}

@HiltViewModel(assistedFactory = CompounderViewModel.Factory::class)
class CompounderViewModel @AssistedInject constructor(
    @Assisted("patientId") private val patientId: String,
    @Assisted("followUpOfEncounterId") private val followUpOfEncounterId: String?,
    @Assisted("resumeEncounterId") private val resumeEncounterId: String?,
    @Assisted("resumeCaseRecordId") private val resumeCaseRecordId: String?,
    private val startCaseUseCase: StartCaseUseCase,
    private val getVitalsPrefillUseCase: GetVitalsPrefillUseCase,
    private val recordVitalsUseCase: RecordVitalsUseCase,
    private val addAilmentUseCase: AddAilmentUseCase,
    private val deleteAilmentUseCase: DeleteAilmentUseCase,
    private val ailmentRepository: AilmentRepository,
    private val ailmentAudioRecorder: AilmentAudioRecorder,
    private val checkEmergencyThresholdsUseCase: CheckEmergencyThresholdsUseCase,
    private val auditLogger: AuditLogger,
) : ViewModel(), CompounderActions {

    @AssistedFactory
    interface Factory {
        fun create(
            @Assisted("patientId") patientId: String,
            @Assisted("followUpOfEncounterId") followUpOfEncounterId: String?,
            @Assisted("resumeEncounterId") resumeEncounterId: String?,
            @Assisted("resumeCaseRecordId") resumeCaseRecordId: String?,
        ): CompounderViewModel
    }

    private val _uiState = MutableStateFlow(CompounderUiState())
    val uiState: StateFlow<CompounderUiState> = _uiState.asStateFlow()

    private val _effects = Channel<CompounderEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    init {
        viewModelScope.launch {
            val (encounterId, caseRecordId) = if (resumeEncounterId != null && resumeCaseRecordId != null) {
                auditLogger.log(
                    action = AuditAction.ENCOUNTER_RESUMED,
                    patientId = patientId,
                    caseRecordId = resumeCaseRecordId,
                    payload = auditPayload("encounterId" to resumeEncounterId),
                )
                resumeEncounterId to resumeCaseRecordId
            } else {
                val started = startCaseUseCase(patientId, followUpOfEncounterId).getOrElse {
                    _uiState.update { state -> state.copy(isLoadingPrefill = false, errorMessage = "Could not start visit") }
                    return@launch
                }
                auditLogger.log(
                    action = AuditAction.ENCOUNTER_STARTED,
                    patientId = patientId,
                    caseRecordId = started.caseRecord.id,
                    payload = auditPayload("encounterId" to started.encounter.id),
                )
                started.encounter.id to started.caseRecord.id
            }
            _uiState.update { it.copy(encounterId = encounterId, caseRecordId = caseRecordId) }
            launch {
                // Full AilmentEntry list, unfiltered — the visibility-aware drop to AilmentListItem
                // happens right here, in the mapping into this UI state, and nowhere else. The
                // kernel path (Phase 4) reads AilmentRepository directly, bypassing this projection.
                ailmentRepository.observeForEncounter(encounterId).collect { ailments ->
                    _uiState.update { it.copy(ailments = ailments.map { entry -> entry.toListItem() }) }
                }
            }
            val prefill = getVitalsPrefillUseCase()
            val snapshot = prefill.toSnapshot(encounterId, patientId, Instant.now())
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
    override fun onCaptureMethodChange(method: VitalsCaptureMethod) = _uiState.update { it.copy(captureMethod = method) }
    override fun onTogglePointOfCareTests() = _uiState.update { it.copy(showPointOfCareTests = !it.showPointOfCareTests) }
    override fun onBloodGlucoseChange(value: String) = _uiState.update { it.copy(bloodGlucoseMgDl = value) }
    override fun onUrinalysisChange(value: String) = _uiState.update { it.copy(urinalysisResult = value) }
    override fun onChiefComplaintChange(value: String) = _uiState.update { it.copy(chiefComplaint = value) }
    override fun onAilmentDescriptionChange(value: String) = _uiState.update { it.copy(newAilmentDescription = value) }
    override fun onAilmentMeasurementTypeChange(type: MeasurementType) =
        _uiState.update { it.copy(newAilmentMeasurementType = type) }
    override fun onAilmentMeasuredValueChange(value: String) = _uiState.update { it.copy(newAilmentMeasuredValue = value) }
    override fun onAilmentMeasuredUnitChange(value: String) = _uiState.update { it.copy(newAilmentMeasuredUnit = value) }
    override fun onAilmentSeverityChange(value: String) = _uiState.update { it.copy(newAilmentSeverity = value) }
    override fun onAilmentDurationChange(value: String) = _uiState.update { it.copy(newAilmentDuration = value) }
    override fun onAilmentOnsetChange(value: String) = _uiState.update { it.copy(newAilmentOnset = value) }
    override fun onAilmentQualifiersChange(value: String) = _uiState.update { it.copy(newAilmentQualifiers = value) }

    /** Investor-demo shortcut: fills main concern, vitals and ailment form in one tap. */
    override fun fillDemoData() {
        _uiState.update { state ->
            state.copy(
                chiefComplaint = DemoPatientProfile.MAIN_CONCERN,
                pulseBpm = DemoPatientProfile.PULSE_BPM,
                bpSystolic = DemoPatientProfile.BP_SYSTOLIC,
                bpDiastolic = DemoPatientProfile.BP_DIASTOLIC,
                spo2Percent = DemoPatientProfile.SPO2_PERCENT,
                temperatureCelsius = DemoPatientProfile.TEMPERATURE_CELSIUS,
                respiratoryRate = DemoPatientProfile.RESPIRATORY_RATE,
                weightKg = DemoPatientProfile.WEIGHT_KG,
                heightCm = DemoPatientProfile.HEIGHT_CM,
                painScore = DemoPatientProfile.PAIN_SCORE,
                captureMethod = VitalsCaptureMethod.DIGITAL_MONITOR,
                newAilmentDescription = DemoPatientProfile.AILMENT.description,
                newAilmentSeverity = DemoPatientProfile.AILMENT.severity,
                newAilmentDuration = DemoPatientProfile.AILMENT.duration,
            )
        }
    }


    /** Toggling to PRIVATE surfaces the "hand the device to the patient" interstitial
     *  (REQ-AIL-02) — toggling back to PUBLIC needs no such handoff cue. */
    override fun onAilmentVisibilityToggle() {
        _uiState.update { state ->
            if (state.newAilmentVisibility == Visibility.PUBLIC) {
                state.copy(newAilmentVisibility = Visibility.PRIVATE, showPrivateHandoffInterstitial = true)
            } else {
                state.copy(newAilmentVisibility = Visibility.PUBLIC, showPrivateHandoffInterstitial = false)
            }
        }
    }

    override fun onPrivateHandoffAcknowledged() {
        _uiState.update { it.copy(showPrivateHandoffInterstitial = false) }
    }

    /** Backing out of the interstitial reverts to PUBLIC — there is no "private, but I didn't
     *  actually hand the device over" state. */
    override fun onPrivateHandoffCancelled() {
        _uiState.update { it.copy(showPrivateHandoffInterstitial = false, newAilmentVisibility = Visibility.PUBLIC) }
    }

    override fun onStartAilmentAudioRecording() {
        val result = ailmentAudioRecorder.startRecording()
        result.fold(
            onSuccess = { uri -> _uiState.update { it.copy(isRecordingAilmentAudio = true, pendingAilmentAudioUri = uri) } },
            onFailure = { error -> _uiState.update { it.copy(errorMessage = error.message ?: "Could not start recording") } },
        )
    }

    override fun onStopAilmentAudioRecording() {
        ailmentAudioRecorder.stopRecording()
        _uiState.update { it.copy(isRecordingAilmentAudio = false) }
    }

    override fun onAddAilment() {
        val current = _uiState.value
        val encounterId = current.encounterId ?: return
        if (!current.canAddAilment) return
        viewModelScope.launch {
            addAilmentUseCase(
                patientId = patientId,
                encounterId = encounterId,
                description = current.newAilmentDescription,
                measurementType = current.newAilmentMeasurementType,
                visibility = current.newAilmentVisibility,
                measuredValue = current.newAilmentMeasuredValue.toDoubleOrNull(),
                measuredUnit = current.newAilmentMeasuredUnit.takeIf { it.isNotBlank() },
                severity = current.newAilmentSeverity.toIntOrNull(),
                onset = current.newAilmentOnset.takeIf { it.isNotBlank() },
                duration = current.newAilmentDuration.takeIf { it.isNotBlank() },
                qualifiers = current.newAilmentQualifiers.takeIf { it.isNotBlank() },
                audioLocalUri = current.pendingAilmentAudioUri,
            ).onSuccess {
                // Audit payload never carries the private description/value — only that a
                // private ailment was captured, same posture as REQ-AIL-02 in the UI itself.
                auditLogger.log(
                    action = AuditAction.AILMENT_CAPTURED,
                    patientId = patientId,
                    caseRecordId = current.caseRecordId,
                    payload = auditPayload(
                        "measurementType" to current.newAilmentMeasurementType.name,
                        "visibility" to current.newAilmentVisibility.name,
                        "description" to if (current.newAilmentVisibility == Visibility.PRIVATE) null else current.newAilmentDescription,
                    ),
                )
            }
            _uiState.update {
                it.copy(
                    newAilmentDescription = "",
                    newAilmentMeasuredValue = "",
                    newAilmentMeasuredUnit = "",
                    newAilmentSeverity = "",
                    newAilmentDuration = "",
                    newAilmentOnset = "",
                    newAilmentQualifiers = "",
                    newAilmentVisibility = Visibility.PUBLIC,
                    pendingAilmentAudioUri = null,
                )
            }
        }
    }

    override fun onDeleteAilment(id: String, audioUri: String?) {
        viewModelScope.launch {
            deleteAilmentUseCase(id).onSuccess {
                audioUri?.let(ailmentAudioRecorder::deleteRecording)
                auditLogger.log(
                    action = AuditAction.AILMENT_DELETED,
                    patientId = patientId,
                    caseRecordId = _uiState.value.caseRecordId,
                    payload = auditPayload("ailmentId" to id),
                )
            }
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
                captureMethod = current.captureMethod,
                recordedAt = Instant.now(),
            )
            recordVitalsUseCase(snapshot).fold(
                onSuccess = {
                    _uiState.update { it.copy(isSaving = false) }
                    auditLogger.log(
                        action = AuditAction.VITALS_RECORDED,
                        patientId = patientId,
                        caseRecordId = caseRecordId,
                        payload = auditPayload("pulseBpm" to current.pulseBpm, "bpSystolic" to current.bpSystolic),
                    )
                    // REQ-TRS-02: this check runs BEFORE Consultation/Sending are ever reached —
                    // an emergency case must never enter the offline-sync queue.
                    val emergency = checkEmergencyThresholdsUseCase(
                        spo2Percent = snapshot.spo2Percent,
                        bpSystolic = snapshot.bpSystolic,
                        bpDiastolic = snapshot.bpDiastolic,
                    )
                    if (emergency.triggered) {
                        auditLogger.log(
                            action = AuditAction.EMERGENCY_OVERRIDE,
                            patientId = patientId,
                            caseRecordId = caseRecordId,
                            payload = auditPayload("reasons" to emergency.reasons.joinToString("; ")),
                        )
                        _effects.send(CompounderEffect.EmergencyOverride(emergency.reasons))
                    } else {
                        _effects.send(CompounderEffect.Continue(patientId, encounterId, caseRecordId, current.chiefComplaint))
                    }
                },
                onFailure = { error ->
                    _uiState.update { it.copy(isSaving = false, errorMessage = error.message ?: "Could not save vitals") }
                },
            )
        }
    }
}
