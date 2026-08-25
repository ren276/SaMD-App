package com.example.samdapp.presentation.abha

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.samdapp.domain.model.AbhaProfile
import com.example.samdapp.domain.repository.AbhaProfileRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * [profile] is null once loading finishes and no row was found — the honest empty state for
 * `patient.abhaNumber != null` but the [AbhaProfile] row never landed (deleted, or the device
 * that enrolled it never synced). The linkage at the [com.example.samdapp.domain.model.Patient]
 * level still holds; the screen says so rather than hiding the entry point.
 */
data class AbhaProfileUiState(
    val isLoading: Boolean = true,
    val profile: AbhaProfile? = null,
)

@HiltViewModel(assistedFactory = AbhaProfileViewModel.Factory::class)
class AbhaProfileViewModel @AssistedInject constructor(
    @Assisted private val abhaId: String,
    private val abhaProfileRepository: AbhaProfileRepository,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(abhaId: String): AbhaProfileViewModel
    }

    private val _uiState = MutableStateFlow(AbhaProfileUiState())
    val uiState: StateFlow<AbhaProfileUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val profile = abhaProfileRepository.getProfile(abhaId)
            _uiState.update { it.copy(isLoading = false, profile = profile) }
        }
    }
}
