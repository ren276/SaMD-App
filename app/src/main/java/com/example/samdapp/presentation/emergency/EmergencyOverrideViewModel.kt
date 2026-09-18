package com.example.samdapp.presentation.emergency

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.samdapp.domain.repository.VitalsRepository
import com.example.samdapp.domain.usecase.CheckEmergencyThresholdsUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Re-derives the tripped threshold reasons from the vitals already persisted for this encounter,
 * so [EmergencyOverrideRoute][com.example.samdapp.presentation.navigation.EmergencyOverrideRoute]
 * can carry ids only instead of a `List<String>` of clinical text through the saved-state Bundle.
 *
 * The text is identical, not approximate, and that is load-bearing for a red-flag screen:
 * [CheckEmergencyThresholdsUseCase] is a pure function of `spo2Percent`, `bpSystolic` and
 * `bpDiastolic` that builds its strings inline, and `CompounderViewModel` persists the vitals
 * snapshot BEFORE running the check on those same three values. Re-running the same function over
 * the same persisted values therefore reproduces the same strings.
 *
 * Keyed on `encounterId`, not `caseRecordId`: vitals are read with
 * [VitalsRepository.observeLatestForEncounter] and
 * [com.example.samdapp.domain.model.VitalsSnapshot] carries no case id at all.
 */
@HiltViewModel(assistedFactory = EmergencyOverrideViewModel.Factory::class)
class EmergencyOverrideViewModel @AssistedInject constructor(
    @Assisted private val encounterId: String,
    private val vitalsRepository: VitalsRepository,
    private val checkEmergencyThresholdsUseCase: CheckEmergencyThresholdsUseCase,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(encounterId: String): EmergencyOverrideViewModel
    }

    private val _reasons = MutableStateFlow<List<String>>(emptyList())

    /**
     * Empty until the vitals row is read, and empty forever if no row exists. The screen's
     * standing instruction (refer to a physical hospital now, do not wait for sync) does not
     * depend on this list, so an emergency screen with no bullets is still a correct and complete
     * emergency screen. It must never be a reason to not show the screen.
     */
    val reasons: StateFlow<List<String>> = _reasons.asStateFlow()

    init {
        viewModelScope.launch {
            val snapshot = vitalsRepository.observeLatestForEncounter(encounterId).first() ?: return@launch
            _reasons.value = checkEmergencyThresholdsUseCase(
                spo2Percent = snapshot.spo2Percent,
                bpSystolic = snapshot.bpSystolic,
                bpDiastolic = snapshot.bpDiastolic,
            ).reasons
        }
    }
}
