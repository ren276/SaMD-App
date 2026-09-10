package com.example.samdapp.presentation.compounder

import android.os.Build
import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.samdapp.data.mock.DemoPatientProfile
import com.example.samdapp.domain.audit.AuditAction
import com.example.samdapp.domain.audit.AuditLogger
import com.example.samdapp.domain.audit.auditPayload
import com.example.samdapp.domain.connectivity.LocalNetworkFailure
import com.example.samdapp.domain.connectivity.UNREACHABLE_OR_BLOCKED_MESSAGE
import com.example.samdapp.domain.connectivity.classifyLocalNetworkFailure
import com.example.samdapp.domain.media.AilmentAudioRecorder
import com.example.samdapp.domain.model.AilmentEntry
import com.example.samdapp.domain.model.MeasurementType
import com.example.samdapp.domain.model.ObservationSource
import com.example.samdapp.domain.model.Visibility
import com.example.samdapp.domain.model.VitalsCaptureMethod
import com.example.samdapp.domain.model.VitalsSnapshot
import com.example.samdapp.domain.model.toSnapshot
import com.example.samdapp.domain.repository.AilmentRepository
import com.example.samdapp.domain.usecase.AcquireDeviceVitalsUseCase
import com.example.samdapp.domain.usecase.AddAilmentUseCase
import com.example.samdapp.domain.usecase.CheckEmergencyThresholdsUseCase
import com.example.samdapp.domain.usecase.DeleteAilmentUseCase
import com.example.samdapp.domain.usecase.GetVitalsPrefillUseCase
import com.example.samdapp.domain.usecase.RecordVitalsUseCase
import com.example.samdapp.domain.usecase.StartCaseUseCase
import com.example.samdapp.domain.usecase.StopDeviceAcquisitionUseCase
import com.example.samdapp.domain.vitalssource.AcquisitionRequest
import com.example.samdapp.domain.vitalssource.AcquisitionResult
import com.example.samdapp.domain.vitalssource.Instrument
import com.example.samdapp.domain.vitalssource.RejectReason
import com.example.samdapp.domain.vitalssource.Scenario
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
    val captureMethod: VitalsCaptureMethod? = null,
    /** Null when idle. The in-flight guard, mirroring [isSaving] exactly: set before the suspend
     *  call, cleared in both the accepted and the rejected branch. An [Instrument] rather than a
     *  Boolean because it also names which field group shows the acquiring indicator. */
    val acquiringInstrument: Instrument? = null,
    val selectedInstrument: Instrument = Instrument.BP,
    val selectedScenario: Scenario = Scenario.NORMAL,
    /** Held apart from [errorMessage] so an instrument failure never overwrites a save failure and
     *  a save failure never overwrites an instrument one. */
    val acquisitionError: String? = null,
    val activeSessionId: String? = null,
    val fieldProvenance: Map<VitalsField, VitalsFieldProvenance> = emptyMap(),
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

    /**
     * The one place [ObservationSource] is decided, with [fieldProvenance] as its only input.
     *
     * This does not compete with PR1's `VitalsReading.derivedSource()`; it is the same rule reached
     * from the other side. Prefill marks DEVICE for exactly the fields a non-null reading
     * populated, which is precisely what `hasAnyValue()` tests, so a prefilled form and a
     * device-acquired form agree by construction. What per-field state adds is the demotion: an
     * edited field leaves the DEVICE set, so a form where a human typed over every instrument
     * number rolls up to MANUAL, which no whole-snapshot rule could see.
     */
    val source: ObservationSource
        get() = if (fieldProvenance.containsValue(VitalsFieldProvenance.DEVICE)) {
            ObservationSource.DEVICE
        } else {
            ObservationSource.MANUAL
        }

    /** A second Start while one is in flight is a no-op, and Start is unavailable mid-save. */
    val canStartAcquisition: Boolean get() = acquiringInstrument == null && !isSaving

    /** Field-level, never screen-level: the form stays visible and editable while a reading is
     *  in flight, so the worker keeps sight of what they have already typed. */
    fun isAcquiring(field: VitalsField): Boolean =
        acquiringInstrument?.writtenFields()?.contains(field) == true
}

/**
 * User-facing copy for a failure that could be the local network. Routed through
 * [classifyLocalNetworkFailure] rather than written inline, so the pre-enforcement third state
 * (a vendor-level local-network toggle that `checkSelfPermission` cannot see) reaches the worker
 * with both causes named instead of a confident wrong one.
 *
 * [sdkInt] is a parameter rather than a direct `Build.VERSION.SDK_INT` read so the mapping is
 * testable on the host JVM at each enforcement level.
 */
internal fun localNetworkFailureMessage(reason: RejectReason, sdkInt: Int): String =
    when (classifyLocalNetworkFailure(permissionGranted = reason != RejectReason.PERMISSION_DENIED, sdkInt = sdkInt)) {
        LocalNetworkFailure.PERMISSION_DENIED ->
            "Local network access is off for this app. Allow it in system settings to reach the device gateway."
        LocalNetworkFailure.UNREACHABLE ->
            "Cannot reach the device gateway. Check it is powered on and on the same Wi-Fi."
        LocalNetworkFailure.UNREACHABLE_OR_BLOCKED -> UNREACHABLE_OR_BLOCKED_MESSAGE
    }

/** Why a reading was refused, in the worker's terms. Never carries a measured value. */
internal fun rejectionMessage(reason: RejectReason, sdkInt: Int = Build.VERSION.SDK_INT): String = when (reason) {
    RejectReason.UNREACHABLE, RejectReason.PERMISSION_DENIED -> localNetworkFailureMessage(reason, sdkInt)
    RejectReason.TIMEOUT -> "The device gateway did not answer in time. Try again."
    RejectReason.NO_MEASUREMENT -> "The instrument has not produced a reading yet. Try again."
    RejectReason.QUALITY_STATUS_NOT_OK ->
        "The instrument reported a fault reading, so nothing was filled in. Check the instrument and try again."
    RejectReason.SESSION_ID_MISMATCH, RejectReason.DEVICE_TYPE_MISMATCH ->
        "That reading did not match this measurement, so nothing was filled in. Try again."
    RejectReason.MALFORMED -> "The device gateway sent something this app could not read."
    RejectReason.NOT_SUPPORTED -> "No instrument gateway is available in this build."
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
    fun onAilmentAudioPermissionDenied()
    fun onStopAilmentAudioRecording()
    fun onAddAilment()
    fun onDeleteAilment(id: String, audioUri: String?)
    fun onContinue()
    fun onInstrumentSelected(instrument: Instrument)
    fun onScenarioSelected(scenario: Scenario)
    /** Worker-triggered. Nothing reaches the instrument gateway until this is called. */
    fun onStartAcquisition()
    fun onStopAcquisition()
    /** A declined local-network prompt must say so. Without this the Start button would simply
     *  stop responding, with no explanation and no way back. */
    fun onLocalNetworkPermissionDenied()
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
    private val acquireDeviceVitalsUseCase: AcquireDeviceVitalsUseCase,
    private val stopDeviceAcquisitionUseCase: StopDeviceAcquisitionUseCase,
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

    private var acquisitionJob: Job? = null

    /** [viewModelScope] is already cancelled by the time [onCleared] runs, so the closing
     *  `/session/stop` cannot ride on it. This scope exists for that one best-effort call and
     *  cancels itself as soon as the call returns. */
    private val teardownScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

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
                    // Marks DEVICE for exactly the fields the reading populated, which is the same
                    // test `snapshot.source` applies to the reading as a whole. Feeding the map
                    // rather than copying the enum keeps one source computation, not two.
                    fieldProvenance = deviceMarksFor(snapshot),
                )
            }
        }
    }

    override fun onPulseChange(value: String) =
        _uiState.update { it.copy(pulseBpm = value, fieldProvenance = it.markEdited(VitalsField.PULSE_BPM)) }
    override fun onBpSystolicChange(value: String) =
        _uiState.update { it.copy(bpSystolic = value, fieldProvenance = it.markEdited(VitalsField.BP_SYSTOLIC)) }
    override fun onBpDiastolicChange(value: String) =
        _uiState.update { it.copy(bpDiastolic = value, fieldProvenance = it.markEdited(VitalsField.BP_DIASTOLIC)) }
    override fun onSpo2Change(value: String) =
        _uiState.update { it.copy(spo2Percent = value, fieldProvenance = it.markEdited(VitalsField.SPO2_PERCENT)) }
    override fun onTemperatureChange(value: String) =
        _uiState.update { it.copy(temperatureCelsius = value, fieldProvenance = it.markEdited(VitalsField.TEMPERATURE_CELSIUS)) }
    override fun onRespiratoryRateChange(value: String) =
        _uiState.update { it.copy(respiratoryRate = value, fieldProvenance = it.markEdited(VitalsField.RESPIRATORY_RATE)) }
    override fun onWeightChange(value: String) =
        _uiState.update { it.copy(weightKg = value, fieldProvenance = it.markEdited(VitalsField.WEIGHT_KG)) }
    override fun onHeightChange(value: String) =
        _uiState.update { it.copy(heightCm = value, fieldProvenance = it.markEdited(VitalsField.HEIGHT_CM)) }
    override fun onPainScoreChange(value: String) = _uiState.update { it.copy(painScore = value) }
    override fun onCaptureMethodChange(method: VitalsCaptureMethod) = _uiState.update { it.copy(captureMethod = method) }
    override fun onTogglePointOfCareTests() = _uiState.update { it.copy(showPointOfCareTests = !it.showPointOfCareTests) }
    override fun onBloodGlucoseChange(value: String) =
        _uiState.update { it.copy(bloodGlucoseMgDl = value, fieldProvenance = it.markEdited(VitalsField.BLOOD_GLUCOSE_MG_DL)) }
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
                // A human pressed a button and chose these numbers, so they are MANUAL. Dropping
                // any DEVICE mark left by an earlier acquisition keeps the roll-up honest rather
                // than letting demo values inherit an instrument's provenance.
                fieldProvenance = state.fieldProvenance - DEMO_WRITTEN_FIELDS,
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

    override fun onAilmentAudioPermissionDenied() {
        _uiState.update { it.copy(errorMessage = "Microphone permission was declined. Allow it to record, or type the ailment.") }
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

    override fun onInstrumentSelected(instrument: Instrument) =
        _uiState.update { it.copy(selectedInstrument = instrument, acquisitionError = null) }

    override fun onScenarioSelected(scenario: Scenario) =
        _uiState.update { it.copy(selectedScenario = scenario, acquisitionError = null) }

    /**
     * RC-4 lives here by absence: this path mutates [CompounderUiState] and nothing else. It never
     * calls [RecordVitalsUseCase], never touches the repository and never logs `VITALS_RECORDED`.
     * The instrument cannot write to the record; only [onContinue] can.
     */
    override fun onStartAcquisition() {
        val current = _uiState.value
        // The in-flight guard. A second Start while one is running is a no-op, not a queued second
        // acquisition that would race the first one's field writes.
        if (!current.canStartAcquisition) return
        val request = AcquisitionRequest(current.selectedInstrument, current.selectedScenario)
        _uiState.update { it.copy(acquiringInstrument = request.instrument, acquisitionError = null) }
        acquisitionJob = viewModelScope.launch {
            when (val result = acquireDeviceVitalsUseCase(request)) {
                is AcquisitionResult.Accepted -> {
                    val writtenFields = applyAcceptedReading(result)
                    auditLogger.log(
                        action = AuditAction.VITALS_DEVICE_READING_RECEIVED,
                        patientId = patientId,
                        caseRecordId = current.caseRecordId,
                        payload = auditPayload(
                            "sessionId" to result.sessionId,
                            "deviceType" to result.deviceType,
                            "instrument" to result.instrument.name,
                            "synthetic" to result.synthetic?.toString(),
                            "measuredAt" to result.measuredAt,
                            "fieldsPopulated" to writtenFields.joinToString(",") { it.name },
                            "fieldProvenance" to writtenFields.joinToString(",") {
                                "${it.name}:${VitalsFieldProvenance.DEVICE.name}"
                            },
                        ),
                    )
                }

                is AcquisitionResult.Rejected -> {
                    // No field is written on any rejection path. A refused reading leaves the form
                    // exactly as the worker left it.
                    _uiState.update {
                        it.copy(acquiringInstrument = null, acquisitionError = rejectionMessage(result.reason))
                    }
                    auditLogger.log(
                        action = AuditAction.VITALS_DEVICE_READING_FAILED,
                        patientId = patientId,
                        caseRecordId = current.caseRecordId,
                        payload = auditPayload(
                            "sessionId" to current.activeSessionId,
                            "instrumentRequested" to request.instrument.name,
                            "rejectReason" to result.reason.name,
                        ),
                    )
                }
            }
        }
    }

    override fun onLocalNetworkPermissionDenied() = _uiState.update {
        it.copy(acquiringInstrument = null, acquisitionError = rejectionMessage(RejectReason.PERMISSION_DENIED))
    }

    /** Stop closes the session on the gateway. It deliberately does NOT clear any field the worker
     *  can see: stopping the instrument is not undoing the reading it already gave. */
    override fun onStopAcquisition() {
        acquisitionJob?.cancel()
        acquisitionJob = null
        _uiState.update { it.copy(acquiringInstrument = null, activeSessionId = null) }
        viewModelScope.launch { stopDeviceAcquisitionUseCase() }
    }

    /** Widened to public so a unit test can drive screen exit; [ViewModel.onCleared] is otherwise
     *  protected and this is the only seam the teardown behaviour can be asserted through. */
    public override fun onCleared() {
        acquisitionJob?.cancel()
        acquisitionJob = null
        val hadSession = _uiState.value.run { activeSessionId != null || acquiringInstrument != null }
        if (hadSession) {
            teardownScope.launch {
                try {
                    stopDeviceAcquisitionUseCase()
                } finally {
                    teardownScope.cancel()
                }
            }
        } else {
            teardownScope.cancel()
        }
        super.onCleared()
    }

    /**
     * Writes only the fields this reading actually carried. A null value is not written, so an
     * absent secondary or tertiary never overwrites something the worker typed and never lands as a
     * zero. Every written field is marked DEVICE, replacing any earlier DEVICE_EDITED mark, because
     * the new instrument value has replaced whatever was there.
     */
    /** Returns the fields this reading actually populated, as [VitalsField] names only, so the
     *  caller can log which fields arrived without this function knowing anything about audit
     *  payloads and without the caller re-deriving the same null checks a second time. */
    private fun applyAcceptedReading(accepted: AcquisitionResult.Accepted): Set<VitalsField> {
        val reading = accepted.reading
        val written = buildMap {
            reading.pulseBpm?.let { put(VitalsField.PULSE_BPM, it.toString()) }
            reading.bpSystolic?.let { put(VitalsField.BP_SYSTOLIC, it.toString()) }
            reading.bpDiastolic?.let { put(VitalsField.BP_DIASTOLIC, it.toString()) }
            reading.spo2Percent?.let { put(VitalsField.SPO2_PERCENT, it.toString()) }
            reading.temperatureCelsius?.let { put(VitalsField.TEMPERATURE_CELSIUS, it.toString()) }
            reading.respiratoryRate?.let { put(VitalsField.RESPIRATORY_RATE, it.toString()) }
            reading.weightKg?.let { put(VitalsField.WEIGHT_KG, it.toString()) }
            reading.heightCm?.let { put(VitalsField.HEIGHT_CM, it.toString()) }
            reading.bloodGlucoseMgDl?.let { put(VitalsField.BLOOD_GLUCOSE_MG_DL, it.toString()) }
        }
        _uiState.update { state ->
            state.copy(
                acquiringInstrument = null,
                activeSessionId = accepted.sessionId,
                acquisitionError = null,
                pulseBpm = written[VitalsField.PULSE_BPM] ?: state.pulseBpm,
                bpSystolic = written[VitalsField.BP_SYSTOLIC] ?: state.bpSystolic,
                bpDiastolic = written[VitalsField.BP_DIASTOLIC] ?: state.bpDiastolic,
                spo2Percent = written[VitalsField.SPO2_PERCENT] ?: state.spo2Percent,
                temperatureCelsius = written[VitalsField.TEMPERATURE_CELSIUS] ?: state.temperatureCelsius,
                respiratoryRate = written[VitalsField.RESPIRATORY_RATE] ?: state.respiratoryRate,
                weightKg = written[VitalsField.WEIGHT_KG] ?: state.weightKg,
                heightCm = written[VitalsField.HEIGHT_CM] ?: state.heightCm,
                bloodGlucoseMgDl = written[VitalsField.BLOOD_GLUCOSE_MG_DL] ?: state.bloodGlucoseMgDl,
                // A glucometer reading writes a field inside a collapsed section, which would be a
                // silent write. Open it. One-way only: never collapse, because collapsing could
                // hide a value the worker has already edited. The manual toggle stays theirs.
                showPointOfCareTests = state.showPointOfCareTests || written.containsKey(VitalsField.BLOOD_GLUCOSE_MG_DL),
                fieldProvenance = state.fieldProvenance + written.keys.associateWith { VitalsFieldProvenance.DEVICE },
            )
        }
        return written.keys
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

/**
 * The DEVICE to DEVICE_EDITED flip, one call from each existing `onXChange`. A MANUAL field stays
 * MANUAL and an already DEVICE_EDITED field stays DEVICE_EDITED, so this is a one-way demotion.
 *
 * It fires on any callback from the text field, including one where the worker retypes the same
 * character. That is deliberate and it is the safe direction: a field marked DEVICE_EDITED that was
 * not really edited understates device provenance, while the reverse would claim an instrument
 * measured a number a human had changed.
 */
internal fun CompounderUiState.markEdited(field: VitalsField): Map<VitalsField, VitalsFieldProvenance> =
    if (fieldProvenance[field] == VitalsFieldProvenance.DEVICE) {
        fieldProvenance + (field to VitalsFieldProvenance.DEVICE_EDITED)
    } else {
        fieldProvenance
    }

private val DEMO_WRITTEN_FIELDS = setOf(
    VitalsField.PULSE_BPM,
    VitalsField.BP_SYSTOLIC,
    VitalsField.BP_DIASTOLIC,
    VitalsField.SPO2_PERCENT,
    VitalsField.TEMPERATURE_CELSIUS,
    VitalsField.RESPIRATORY_RATE,
    VitalsField.WEIGHT_KG,
    VitalsField.HEIGHT_CM,
)

/** Seeds provenance from a prefill snapshot: DEVICE for every field the source actually supplied. */
internal fun deviceMarksFor(snapshot: VitalsSnapshot): Map<VitalsField, VitalsFieldProvenance> = buildMap {
    snapshot.pulseBpm?.let { put(VitalsField.PULSE_BPM, VitalsFieldProvenance.DEVICE) }
    snapshot.bpSystolic?.let { put(VitalsField.BP_SYSTOLIC, VitalsFieldProvenance.DEVICE) }
    snapshot.bpDiastolic?.let { put(VitalsField.BP_DIASTOLIC, VitalsFieldProvenance.DEVICE) }
    snapshot.spo2Percent?.let { put(VitalsField.SPO2_PERCENT, VitalsFieldProvenance.DEVICE) }
    snapshot.temperatureCelsius?.let { put(VitalsField.TEMPERATURE_CELSIUS, VitalsFieldProvenance.DEVICE) }
    snapshot.respiratoryRate?.let { put(VitalsField.RESPIRATORY_RATE, VitalsFieldProvenance.DEVICE) }
    snapshot.weightKg?.let { put(VitalsField.WEIGHT_KG, VitalsFieldProvenance.DEVICE) }
    snapshot.heightCm?.let { put(VitalsField.HEIGHT_CM, VitalsFieldProvenance.DEVICE) }
    snapshot.bloodGlucoseMgDl?.let { put(VitalsField.BLOOD_GLUCOSE_MG_DL, VitalsFieldProvenance.DEVICE) }
}
