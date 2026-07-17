package com.example.samdapp.presentation.referrals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.samdapp.domain.model.ReferralRequest
import com.example.samdapp.domain.repository.ReferralRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ReferralsUiState(
    val referrals: List<ReferralRequest> = emptyList(),
    val isLoading: Boolean = true,
)

@HiltViewModel
class ReferralsViewModel @Inject constructor(
    referralRepository: ReferralRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReferralsUiState())
    val uiState: StateFlow<ReferralsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            referralRepository.observeAll().collect { referrals ->
                _uiState.update { it.copy(referrals = referrals, isLoading = false) }
            }
        }
    }
}
