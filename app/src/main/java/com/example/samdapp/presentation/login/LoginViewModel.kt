package com.example.samdapp.presentation.login

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.samdapp.domain.auth.AuthSession
import com.example.samdapp.domain.auth.UserRole
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LoginUiState(
    val name: String = "",
    val role: UserRole? = null,
    val isSubmitting: Boolean = false,
) {
    val canSubmit: Boolean get() = name.isNotBlank() && role != null && !isSubmitting
}

@Stable
interface LoginActions {
    fun onNameChange(value: String)
    fun onRoleSelect(role: UserRole)
    fun onSubmit()
}

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authSession: AuthSession,
) : ViewModel(), LoginActions {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    override fun onNameChange(value: String) = _uiState.update { it.copy(name = value) }

    override fun onRoleSelect(role: UserRole) = _uiState.update { it.copy(role = role) }

    override fun onSubmit() {
        val current = _uiState.value
        val role = current.role ?: return
        if (!current.canSubmit) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true) }
            authSession.signIn(current.name.trim(), role)
            // No effect to emit — AppNavHost observes AuthSession.currentUser() directly and
            // swaps to the main nav host reactively once this sign-in completes.
        }
    }
}
