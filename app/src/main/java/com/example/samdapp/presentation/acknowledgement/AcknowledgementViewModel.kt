package com.example.samdapp.presentation.acknowledgement

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.samdapp.domain.usecase.AcknowledgeCaseUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AcknowledgementUiState(val isSaving: Boolean = true, val errorMessage: String? = null)

@HiltViewModel(assistedFactory = AcknowledgementViewModel.Factory::class)
class AcknowledgementViewModel @AssistedInject constructor(
    @Assisted val caseRecordId: String,
    private val acknowledgeCaseUseCase: AcknowledgeCaseUseCase,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(caseRecordId: String): AcknowledgementViewModel
    }

    private val _uiState = MutableStateFlow(AcknowledgementUiState())
    val uiState: StateFlow<AcknowledgementUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            acknowledgeCaseUseCase(caseRecordId).fold(
                onSuccess = { _uiState.update { it.copy(isSaving = false) } },
                onFailure = { error -> _uiState.update { it.copy(isSaving = false, errorMessage = error.message) } },
            )
        }
    }
}
