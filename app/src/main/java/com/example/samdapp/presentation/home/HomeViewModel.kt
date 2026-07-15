package com.example.samdapp.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.samdapp.domain.model.Patient
import com.example.samdapp.domain.sync.SyncState
import com.example.samdapp.domain.sync.SyncStatus
import com.example.samdapp.domain.usecase.GetTodaysPatientsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val todaysPatients: List<Patient> = emptyList(),
    val isLoadingRoster: Boolean = true,
    val sync: SyncState = SyncState(),
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    getTodaysPatientsUseCase: GetTodaysPatientsUseCase,
    private val syncStatus: SyncStatus,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        // Day-scoped roster only — the repository never exposes the full patient table.
        viewModelScope.launch {
            getTodaysPatientsUseCase().collect { patients ->
                _uiState.update { it.copy(todaysPatients = patients, isLoadingRoster = false) }
            }
        }
        viewModelScope.launch {
            syncStatus.state.collect { syncState -> _uiState.update { it.copy(sync = syncState) } }
        }
    }

    fun onSyncNow() {
        viewModelScope.launch { syncStatus.syncNow() }
    }
}
