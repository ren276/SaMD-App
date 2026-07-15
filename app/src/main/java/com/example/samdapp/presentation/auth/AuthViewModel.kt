package com.example.samdapp.presentation.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.samdapp.domain.auth.AuthSession
import com.example.samdapp.domain.auth.UserSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface AuthUiState {
    data object Loading : AuthUiState
    data class SignedIn(val session: UserSession) : AuthUiState
    data object SignedOut : AuthUiState
}

/**
 * One instance for the whole app lifetime (obtained at the nav-host level, outside any
 * NavEntry) — mirrors [com.example.samdapp.presentation.connectivity.ConnectivityViewModel]'s
 * shape. Drives both the sign-in gate in AppNavHost and the "signed in as X" display + sign-out
 * action on Home, so there's exactly one session read, not one per consumer.
 */
@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authSession: AuthSession,
) : ViewModel() {

    val state: StateFlow<AuthUiState> = authSession.currentUser()
        .map { session -> if (session != null) AuthUiState.SignedIn(session) else AuthUiState.SignedOut }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AuthUiState.Loading)

    fun signOut() {
        viewModelScope.launch { authSession.signOut() }
    }
}
