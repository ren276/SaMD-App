package com.example.samdapp.presentation.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Activity-scoped (one instance for the app's lifetime, same "shared instance obtained outside
 * any NavEntry" pattern as [com.example.samdapp.presentation.connectivity.ConnectivityViewModel]) —
 * drives the idle auto-lock (item 2, privacy hardening): a shared/unattended tablet re-verifies
 * the current worker's presence after [IDLE_TIMEOUT_MS] of no touch input, without ending their
 * session. [MainActivity][com.example.samdapp.MainActivity] feeds [onUserInteraction] from
 * `Activity.onUserInteraction()` (fired on every touch/key gesture dispatched to the activity —
 * the standard Android idiom for idle-timeout detection, no custom pointer-input plumbing needed).
 */
@HiltViewModel
class IdleLockViewModel @Inject constructor() : ViewModel() {

    private val _isLocked = MutableStateFlow(false)
    val isLocked: StateFlow<Boolean> = _isLocked.asStateFlow()

    private var idleJob: Job? = null

    fun onUserInteraction() {
        if (_isLocked.value) return // ignore taps on the lock screen itself — only biometric success unlocks
        scheduleLock()
    }

    fun onUnlocked() {
        _isLocked.value = false
        scheduleLock()
    }

    /** Called once per fresh sign-in (see [com.example.samdapp.presentation.navigation.AppNavHost])
     *  so idling on Login/ABHA screens before a worker actually signs in never carries an
     *  already-tripped lock into the new session. */
    fun reset() {
        _isLocked.value = false
        scheduleLock()
    }

    private fun scheduleLock() {
        idleJob?.cancel()
        idleJob = viewModelScope.launch {
            delay(IDLE_TIMEOUT_MS)
            _isLocked.value = true
        }
    }

    companion object {
        private const val IDLE_TIMEOUT_MS = 75_000L
    }
}
