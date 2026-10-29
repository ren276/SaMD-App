package com.example.samdapp.presentation.login

import com.example.samdapp.domain.connectivity.AppPermissions
import com.example.samdapp.domain.connectivity.BackendHost
import com.example.samdapp.domain.connectivity.LocalNetworkGate
import com.example.samdapp.domain.connectivity.NearbyDevicesMembers
import com.example.samdapp.domain.connectivity.nearbyDevicesMemberGranted
import javax.inject.Inject

/** What login asks before the Sign in call (memo 5.1, P2-R1, P2-R2). Both answers are read at
 *  call time: the grant can change in Settings between two taps. */
class SignInLocalNetworkCheck @Inject constructor(
    private val gate: LocalNetworkGate,
    private val backendHost: BackendHost,
    private val permissions: AppPermissions,
    @NearbyDevicesMembers private val nearbyDevicesMembers: Set<@JvmSuppressWildcards String>,
) {
    /** The gate's own decision for the host the Sign in call will reach. False below SDK 37 and
     *  for a public host, whatever the permission state. */
    fun backendBlocked(): Boolean = gate.blocksHost(backendHost.host())

    fun nearbyDevicesMemberGranted(): Boolean = nearbyDevicesMemberGranted(nearbyDevicesMembers, permissions)
}
