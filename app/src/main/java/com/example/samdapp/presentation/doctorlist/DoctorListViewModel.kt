package com.example.samdapp.presentation.doctorlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.samdapp.domain.model.DoctorTrackerEntry
import com.example.samdapp.domain.repository.CaseRecordRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DoctorListUiState(
    val isLoading: Boolean = true,
    val entries: List<DoctorTrackerEntry> = emptyList(),
)

/** Read-only, cross-patient (Part B) — no sort-by-urgency, no triage badges, no clinical action.
 *  Tapping a Reviewed row reuses [com.example.samdapp.presentation.report.ReportScreen] read-only;
 *  this ViewModel has nothing else to do. */
@HiltViewModel
class DoctorListViewModel @Inject constructor(
    caseRecordRepository: CaseRecordRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DoctorListUiState())
    val uiState: StateFlow<DoctorListUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            caseRecordRepository.observeDoctorTrackerRows().collect { entries ->
                _uiState.update { it.copy(isLoading = false, entries = entries) }
            }
        }
    }
}
