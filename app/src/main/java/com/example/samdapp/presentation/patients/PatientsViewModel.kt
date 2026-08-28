package com.example.samdapp.presentation.patients

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.samdapp.domain.model.PatientDirectoryEntry
import com.example.samdapp.domain.usecase.GetRecentPatientsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PatientsUiState(
    val query: String = "",
    val patients: List<PatientDirectoryEntry> = emptyList(),
    val isLoading: Boolean = true,
)

@HiltViewModel
class PatientsViewModel @Inject constructor(
    getRecentPatientsUseCase: GetRecentPatientsUseCase,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    private val _uiState = MutableStateFlow(PatientsUiState())
    val uiState: StateFlow<PatientsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(getRecentPatientsUseCase(), _query) { entries, query ->
                val filtered = if (query.isBlank()) {
                    entries
                } else {
                    entries.filter {
                        it.patient.fullName.contains(query, ignoreCase = true) ||
                            it.patient.id.contains(query, ignoreCase = true)
                    }
                }
                PatientsUiState(query = query, patients = filtered, isLoading = false)
            }.collect { _uiState.value = it }
        }
    }

    fun onQueryChange(value: String) {
        _query.value = value
    }
}
