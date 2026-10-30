package com.example.samdapp.domain.connectivity

import javax.inject.Inject

/** Whether the gate blocks the host every backend call reaches (memo P2-R5). The host is read on
 *  each call, never kept: in dev it can change at runtime. */
class BackendLocalNetwork @Inject constructor(
    private val gate: LocalNetworkGate,
    private val backendHost: BackendHost,
) {
    fun blocked(): Boolean = gate.blocksHost(backendHost.host())
}
