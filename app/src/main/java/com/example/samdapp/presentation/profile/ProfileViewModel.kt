package com.example.samdapp.presentation.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.samdapp.domain.audit.AuditLogEntry
import com.example.samdapp.domain.repository.AuditLogRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProfileUiState(
    val recentActions: List<AuditLogEntry> = emptyList(),
    val isLoadingAudit: Boolean = true,
)

@HiltViewModel(assistedFactory = ProfileViewModel.Factory::class)
class ProfileViewModel @AssistedInject constructor(
    @Assisted private val userId: String,
    private val auditLogRepository: AuditLogRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            auditLogRepository.observeRecentForUser(userId).collect { entries ->
                _uiState.update { it.copy(recentActions = entries, isLoadingAudit = false) }
            }
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(userId: String): ProfileViewModel
    }
}
