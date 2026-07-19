package com.example.samdapp.domain.connectivity

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/** App-wide online/offline signal — the single source of truth every screen and use case checks
 *  before doing anything that would leave the device (sending a case to a doctor, running a real
 *  sync round), not just the status-bar toggle. Two independent switches can each force offline,
 *  matching the two real-world cases this exists for: [isManuallyOnline] is the worker's own
 *  toggle (e.g. pre-empting a known outage or a chaotic government-protocol-push window), and
 *  [isOnline] additionally folds in whether the device actually has a network — either one being
 *  off is enough to go offline, and it recovers the moment both are on again. */
@Singleton
class ConnectivityController @Inject constructor(
    networkMonitor: NetworkMonitor,
) {
    private val desiredOnline = MutableStateFlow(true)

    val isManuallyOnline: StateFlow<Boolean> = desiredOnline.asStateFlow()

    val isOnline: Flow<Boolean> = combine(desiredOnline, networkMonitor.isNetworkAvailable) { desired, hasNetwork ->
        desired && hasNetwork
    }

    fun toggle() {
        desiredOnline.update { !it }
    }
}
