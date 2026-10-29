package com.example.samdapp.presentation.login

import android.Manifest
import com.example.samdapp.di.DevNearbyDevicesModule
import com.example.samdapp.di.NearbyDevicesModule
import com.example.samdapp.domain.connectivity.ACCESS_LOCAL_NETWORK_PERMISSION
import com.example.samdapp.domain.connectivity.BackendHost
import com.example.samdapp.domain.connectivity.LocalNetworkGate
import com.example.samdapp.testutil.FakeAuthSession
import com.example.samdapp.testutil.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Memo P2-R2a on the dev flavor's real member set: the dev build declares BLUETOOTH_CONNECT, and a
 *  worker who granted it for a BLE Start gets the local-network request with no rationale. */
@OptIn(ExperimentalCoroutinesApi::class)
class DevLoginNearbyDevicesTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `in dev a granted BLUETOOTH_CONNECT means a direct request`() = runTest(mainDispatcherRule.dispatcher) {
        val check = SignInLocalNetworkCheck(
            LocalNetworkGate(sdkInt = 37) { false },
            BackendHost { "10.203.2.52" },
            LoginLocalNetworkFlowTest.Permissions(
                requested = setOf(ACCESS_LOCAL_NETWORK_PERMISSION, Manifest.permission.BLUETOOTH_CONNECT),
                granted = setOf(Manifest.permission.BLUETOOTH_CONNECT),
            ),
            NearbyDevicesModule.provideMainNearbyDevicesMembers() + DevNearbyDevicesModule.provideBluetoothNearbyDevicesMembers(),
        )
        val vm = LoginViewModel(FakeAuthSession(), check)
        vm.onNameChange("Asha")
        vm.onRoleSelect(com.example.samdapp.domain.auth.UserRole.ASHA_WORKER)
        vm.onPinChange("482915")
        vm.onSubmit()
        advanceUntilIdle()
        vm.onBiometricSucceeded()
        advanceUntilIdle()

        assertEquals(LocalNetworkStep.REQUEST, vm.uiState.value.localNetworkStep)
    }
}
