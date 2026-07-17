package com.example.samdapp.presentation.consultationchain

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.samdapp.domain.model.ConsultationHistoryEntry
import com.example.samdapp.domain.model.groupIntoChains
import com.example.samdapp.domain.repository.EncounterRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ConsultationChainUiState(
    val isLoading: Boolean = true,
    val visits: List<ConsultationHistoryEntry> = emptyList(),
)

/** Backs the chain-detail screen: the visits of one follow-up chain (identified by its root
 *  encounter), newest first. Reuses the same [groupIntoChains] grouping PatientSummary uses, then
 *  picks the matching chain — so the two views can never disagree on what belongs to a chain. */
@HiltViewModel(assistedFactory = ConsultationChainViewModel.Factory::class)
class ConsultationChainViewModel @AssistedInject constructor(
    @Assisted("patientId") private val patientId: String,
    @Assisted("rootEncounterId") private val rootEncounterId: String,
    encounterRepository: EncounterRepository,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(
            @Assisted("patientId") patientId: String,
            @Assisted("rootEncounterId") rootEncounterId: String,
        ): ConsultationChainViewModel
    }

    private val _uiState = MutableStateFlow(ConsultationChainUiState())
    val uiState: StateFlow<ConsultationChainUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            encounterRepository.observeHistoryForPatient(patientId).collect { history ->
                val visits = history.groupIntoChains()
                    .firstOrNull { it.rootEncounterId == rootEncounterId }
                    ?.visits
                    .orEmpty()
                _uiState.update { it.copy(isLoading = false, visits = visits) }
            }
        }
    }
}
