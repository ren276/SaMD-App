package com.example.samdapp.domain.connectivity

import kotlinx.coroutines.flow.Flow

/** Real device connectivity, not mocked — detecting whether a network is reachable is a
 * platform capability, not a backend call, so it doesn't conflict with "no real network calls." */
interface NetworkMonitor {
    val isNetworkAvailable: Flow<Boolean>
}
