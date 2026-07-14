package com.example.samdapp.presentation.connectivity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.samdapp.domain.connectivity.NetworkMonitor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/** One instance for the whole app (obtained at the nav-host level, outside any per-screen
 * scope) so the online/offline status is shared and persistent across every screen.
 *
 * Two cases, per the ask: the user can manually flip [desiredOnline]; separately, if the
 * device genuinely has no network, [effectiveOnline] auto-switches to offline regardless of
 * what the user asked for — and switches back the moment the network returns, since it's a
 * live combine of both signals rather than a one-shot check. */
@HiltViewModel
class ConnectivityViewModel @Inject constructor(
    networkMonitor: NetworkMonitor,
) : ViewModel() {

    private val desiredOnline = MutableStateFlow(true)

    val effectiveOnline: StateFlow<Boolean> = combine(desiredOnline, networkMonitor.isNetworkAvailable) { desired, hasNetwork ->
        desired && hasNetwork
    }.stateIn(viewModelScope, SharingStarted.Eagerly, initialValue = true)

    val isManuallyOnline: StateFlow<Boolean> = desiredOnline

    fun toggle() {
        desiredOnline.update { !it }
    }
}
