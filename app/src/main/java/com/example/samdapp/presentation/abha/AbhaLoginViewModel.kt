package com.example.samdapp.presentation.abha

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

data class AbhaLoginUiState(val abhaId: String = "") {
    val canContinue: Boolean get() = abhaId.length == 14
}

@Stable
interface AbhaLoginActions {
    fun onAbhaIdChange(value: String)
}

/** Step 1 of mock ABHA login: just collects the ABHA id. Verification (mock OTP) happens on the
 *  next screen ([AbhaOtpViewModel]) — this ViewModel has nothing to verify, only to validate shape. */
@HiltViewModel
class AbhaLoginViewModel @Inject constructor() : ViewModel(), AbhaLoginActions {

    private val _uiState = MutableStateFlow(AbhaLoginUiState())
    val uiState: StateFlow<AbhaLoginUiState> = _uiState.asStateFlow()

    override fun onAbhaIdChange(value: String) {
        _uiState.update { it.copy(abhaId = value) }
    }
}
