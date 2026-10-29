package com.example.samdapp.presentation.login

import android.Manifest
import com.example.samdapp.R
import com.example.samdapp.data.local.LocalClinicalData
import com.example.samdapp.data.local.LocalTransaction
import com.example.samdapp.data.local.auth.BackendAuthSession
import com.example.samdapp.data.local.auth.FacilitySignInGate
import com.example.samdapp.data.local.dao.DeviceDataOwnerDao
import com.example.samdapp.data.local.entity.DeviceDataOwnerEntity
import com.example.samdapp.data.remote.LocalNetworkGateInterceptor
import com.example.samdapp.data.remote.RetrofitAuthService
import com.example.samdapp.data.remote.api.AuthApiService
import com.example.samdapp.data.sync.MemoryOwnerMismatchHold
import com.example.samdapp.domain.auth.AuthSession
import com.example.samdapp.domain.auth.SignInFailureReason
import com.example.samdapp.domain.auth.SignInResult
import com.example.samdapp.domain.auth.UserRole
import com.example.samdapp.domain.connectivity.ACCESS_LOCAL_NETWORK_PERMISSION
import com.example.samdapp.domain.connectivity.AppPermissions
import com.example.samdapp.domain.connectivity.BackendHost
import com.example.samdapp.domain.connectivity.LocalNetworkGate
import com.example.samdapp.domain.connectivity.nearbyDevicesMemberGranted
import com.example.samdapp.testutil.FakeAuthSession
import com.example.samdapp.testutil.FakeAuthTokenStore
import com.example.samdapp.testutil.FakeSyncOutboxRepository
import com.example.samdapp.testutil.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/** A staging or prod shape: a public backend host, so the gate never blocks and login runs as before. */
internal fun publicBackendCheck(): SignInLocalNetworkCheck = SignInLocalNetworkCheck(
    LocalNetworkGate(sdkInt = 37) { false },
    BackendHost { "api.samd.example.com" },
    LoginLocalNetworkFlowTest.Permissions(),
    LoginLocalNetworkFlowTest.DEV_SHAPE_MEMBERS,
)

/**
 * Memo 5.1 to 5.4 and P2-R1 to P2-R3, P2-R6 at login: the gate's own decision for the backend host
 * is asked after the biometric step and before the Sign in call; the rationale shows only when no
 * declared NEARBY_DEVICES member is granted; every denial is lnp_denied_login with Open settings.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LoginLocalNetworkFlowTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    companion object {
        /** The dev flavor's member set (P2-R2a), written out so these cases run in every flavor;
         *  `DevNearbyDevicesSetTest` pins that dev's real set is exactly this. */
        val DEV_SHAPE_MEMBERS = setOf(
            Manifest.permission.NEARBY_WIFI_DEVICES,
            Manifest.permission.UWB_RANGING,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
        )
    }

    class Permissions(
        private val requested: Set<String> = setOf(ACCESS_LOCAL_NETWORK_PERMISSION, Manifest.permission.INTERNET),
        private val granted: Set<String> = emptySet(),
    ) : AppPermissions {
        override fun requested() = requested
        override fun isGranted(permission: String) = permission in granted
    }

    private class CountingSession(private val result: SignInResult = SignInResult.Success(mustChangePin = false)) :
        AuthSession by FakeAuthSession() {
        var calls = 0
        override suspend fun signIn(name: String, role: UserRole, pin: String): SignInResult {
            calls++
            return result
        }
    }

    private val lanHost = "10.203.2.52"

    private fun viewModel(
        session: AuthSession,
        sdkInt: Int = 37,
        lnpGranted: () -> Boolean = { false },
        host: String = lanHost,
        permissions: AppPermissions = Permissions(),
        members: Set<String> = DEV_SHAPE_MEMBERS,
    ) = LoginViewModel(session, SignInLocalNetworkCheck(LocalNetworkGate(sdkInt, lnpGranted), BackendHost { host }, permissions, members))

    private fun TestScope.tapSignIn(viewModel: LoginViewModel) {
        viewModel.onNameChange("Asha")
        viewModel.onRoleSelect(UserRole.ASHA_WORKER)
        viewModel.onPinChange("482915")
        viewModel.onSubmit()
        advanceUntilIdle()
        viewModel.onBiometricSucceeded()
        advanceUntilIdle()
    }

    /** What the screen does on a REQUEST step, then the system's answer. */
    private fun TestScope.answerRequest(viewModel: LoginViewModel, granted: Boolean, showRationale: Boolean = true) {
        assertEquals(LocalNetworkStep.REQUEST, viewModel.uiState.value.localNetworkStep)
        viewModel.onLocalNetworkRequestLaunched()
        viewModel.onLocalNetworkPermissionResult(granted, showRationale)
        advanceUntilIdle()
    }

    @Test
    fun `a local host shows the rationale after the biometric step and before any login call`() = runTest(mainDispatcherRule.dispatcher) {
        val session = CountingSession()
        val vm = viewModel(session)
        vm.onNameChange("Asha")
        vm.onRoleSelect(UserRole.ASHA_WORKER)
        vm.onPinChange("482915")
        vm.onSubmit()
        advanceUntilIdle()
        assertNull("nothing is asked before the biometric step", vm.uiState.value.localNetworkStep)

        vm.onBiometricSucceeded()
        advanceUntilIdle()

        assertEquals(LocalNetworkStep.RATIONALE, vm.uiState.value.localNetworkStep)
        assertEquals("no login call before the permission result", 0, session.calls)
    }

    @Test
    fun `a granted NEARBY_DEVICES member means a direct request with no rationale`() = runTest(mainDispatcherRule.dispatcher) {
        val session = CountingSession()
        val permissions = Permissions(
            requested = setOf(ACCESS_LOCAL_NETWORK_PERMISSION, Manifest.permission.BLUETOOTH_CONNECT),
            granted = setOf(Manifest.permission.BLUETOOTH_CONNECT),
        )
        val vm = viewModel(session, permissions = permissions)
        tapSignIn(vm)

        assertEquals(LocalNetworkStep.REQUEST, vm.uiState.value.localNetworkStep)
        assertEquals(0, session.calls)
    }

    @Test
    fun `a package that declares no Bluetooth permission shows the rationale for a local host`() = runTest(mainDispatcherRule.dispatcher) {
        val session = CountingSession()
        val vm = viewModel(session, permissions = Permissions(requested = setOf(ACCESS_LOCAL_NETWORK_PERMISSION, Manifest.permission.INTERNET)))
        tapSignIn(vm)

        assertEquals(LocalNetworkStep.RATIONALE, vm.uiState.value.localNetworkStep)
        assertEquals(0, session.calls)
    }

    @Test
    fun `Bluetooth permissions declared but not granted show the rationale`() = runTest(mainDispatcherRule.dispatcher) {
        val session = CountingSession()
        val permissions = Permissions(
            requested = setOf(ACCESS_LOCAL_NETWORK_PERMISSION, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN),
        )
        val vm = viewModel(session, permissions = permissions)
        tapSignIn(vm)

        assertEquals(LocalNetworkStep.RATIONALE, vm.uiState.value.localNetworkStep)
    }

    @Test
    fun `a public backend host, staging or prod, asks nothing and signs in`() = runTest(mainDispatcherRule.dispatcher) {
        for (host in listOf("staging.samd.example.com", "api.samd.example.com")) {
            val session = CountingSession()
            val vm = viewModel(session, host = host)
            tapSignIn(vm)

            assertNull(host, vm.uiState.value.localNetworkStep)
            assertEquals(host, 1, session.calls)
        }
    }

    @Test
    fun `below SDK 37 a local host asks nothing and signs in`() = runTest(mainDispatcherRule.dispatcher) {
        val session = CountingSession()
        val vm = viewModel(session, sdkInt = 36)
        tapSignIn(vm)

        assertNull(vm.uiState.value.localNetworkStep)
        assertEquals(1, session.calls)
    }

    @Test
    fun `a grant signs in exactly once and the result takes the normal facility path`() = runTest(mainDispatcherRule.dispatcher) {
        val session = CountingSession(SignInResult.FacilityBlocked("PHC Alpha"))
        val vm = viewModel(session)
        tapSignIn(vm)
        vm.onRationaleContinue()
        answerRequest(vm, granted = true)

        assertEquals(1, session.calls)
        assertEquals(LoginError.Refusal(SignInRefusal.FacilityBlocked("PHC Alpha")), vm.uiState.value.error)
    }

    @Test
    fun `a denial shows lnp_denied_login with Open settings and calls nothing`() = runTest(mainDispatcherRule.dispatcher) {
        val session = CountingSession()
        val vm = viewModel(session)
        tapSignIn(vm)
        vm.onRationaleContinue()
        answerRequest(vm, granted = false)

        val state = vm.uiState.value
        assertEquals(LoginError.Worded(R.string.lnp_denied_login), state.error)
        assertTrue("Open settings is offered", state.error.offersOpenSettings())
        assertFalse(state.isSubmitting)
        assertEquals(0, session.calls)
    }

    @Test
    fun `after a denial with the rationale flag false the next Sign in leads with Settings`() = runTest(mainDispatcherRule.dispatcher) {
        val session = CountingSession()
        val vm = viewModel(session)
        tapSignIn(vm)
        vm.onRationaleContinue()
        answerRequest(vm, granted = false, showRationale = false)

        tapSignIn(vm)

        assertNull("no rationale and no request", vm.uiState.value.localNetworkStep)
        assertEquals(LoginError.Worded(R.string.lnp_denied_login), vm.uiState.value.error)
        assertTrue(vm.uiState.value.error.offersOpenSettings())
        assertEquals(0, session.calls)
    }

    @Test
    fun `after a denial that may be asked again the next Sign in shows the rationale`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel(CountingSession())
        tapSignIn(vm)
        vm.onRationaleContinue()
        answerRequest(vm, granted = false, showRationale = true)

        tapSignIn(vm)

        assertEquals(LocalNetworkStep.RATIONALE, vm.uiState.value.localNetworkStep)
    }

    @Test
    fun `Not now goes back to the form with no request, no login and no error`() = runTest(mainDispatcherRule.dispatcher) {
        val session = CountingSession()
        val vm = viewModel(session)
        tapSignIn(vm)
        vm.onRationaleNotNow()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertNull(state.localNetworkStep)
        assertNull(state.error)
        assertFalse(state.isSubmitting)
        // A late result with no request outstanding is ignored.
        vm.onLocalNetworkPermissionResult(granted = true, showRationale = true)
        advanceUntilIdle()
        assertEquals(0, session.calls)
    }

    @Test
    fun `a launcher result with no request outstanding does nothing`() = runTest(mainDispatcherRule.dispatcher) {
        val session = CountingSession()
        val vm = viewModel(session)
        vm.onLocalNetworkPermissionResult(granted = true, showRationale = true)
        advanceUntilIdle()

        assertEquals(0, session.calls)
        assertEquals(LoginUiState(), vm.uiState.value)
    }

    @Test
    fun `the gate refusing after a grant shows the denied state with exactly one login call`() = runTest(mainDispatcherRule.dispatcher) {
        val session = CountingSession(SignInResult.Failure("raw", SignInFailureReason.LOCAL_NETWORK_NOT_ALLOWED))
        val vm = viewModel(session)
        tapSignIn(vm)
        vm.onRationaleContinue()
        answerRequest(vm, granted = true)
        advanceUntilIdle()

        assertEquals(1, session.calls)
        assertEquals(LoginError.Worded(R.string.lnp_denied_login), vm.uiState.value.error)
        assertTrue(vm.uiState.value.error.offersOpenSettings())
        assertNull(vm.uiState.value.localNetworkStep)
    }

    @Test
    fun `the error field holds one error at a time`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel(CountingSession())
        tapSignIn(vm)
        vm.onRationaleContinue()
        answerRequest(vm, granted = false)
        assertEquals(LoginError.Worded(R.string.lnp_denied_login), vm.uiState.value.error)

        vm.onPinChange("482915")
        vm.onSubmit()
        advanceUntilIdle()
        vm.onBiometricFailed("fingerprint did not match")

        val error = vm.uiState.value.error
        assertTrue("the biometric error replaced the denial: $error", error is LoginError.Biometric)
        assertFalse(error.offersOpenSettings())
    }

    @Test
    fun `ACCESS_LOCAL_NETWORK itself never counts as a granted NEARBY_DEVICES member`() {
        val onlyLocalNetwork = Permissions(
            requested = setOf(ACCESS_LOCAL_NETWORK_PERMISSION),
            granted = setOf(ACCESS_LOCAL_NETWORK_PERMISSION),
        )
        assertFalse(nearbyDevicesMemberGranted(DEV_SHAPE_MEMBERS + ACCESS_LOCAL_NETWORK_PERMISSION, onlyLocalNetwork))
        val undeclared = Permissions(requested = emptySet(), granted = setOf(Manifest.permission.BLUETOOTH_CONNECT))
        assertFalse("a member the build does not declare does not count", nearbyDevicesMemberGranted(DEV_SHAPE_MEMBERS, undeclared))
    }

    @Test
    fun `a sign-in the gate refuses never reaches the facility gate`() {
        val api = Retrofit.Builder()
            .baseUrl("http://10.203.2.52:8080/")
            .client(OkHttpClient.Builder().addInterceptor(LocalNetworkGateInterceptor(LocalNetworkGate(sdkInt = 37) { false })).build())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(AuthApiService::class.java)
        var facilityTouches = 0
        val gate = FacilitySignInGate(
            object : DeviceDataOwnerDao {
                override suspend fun insertIfAbsent(owner: DeviceDataOwnerEntity): Long = 0L.also { facilityTouches++ }
                override suspend fun read(): DeviceDataOwnerEntity? = null.also { facilityTouches++ }
            },
            object : LocalClinicalData { override suspend fun present(): Boolean = false.also { facilityTouches++ } },
            FakeSyncOutboxRepository(),
            object : LocalTransaction { override suspend fun <T> run(block: suspend () -> T): T = block().also { facilityTouches++ } },
            RetrofitAuthService(api),
        ) { facilityTouches++; error("no audit expected") }
        val tokens = FakeAuthTokenStore()
        val session = BackendAuthSession(RetrofitAuthService(api), tokens, gate, MemoryOwnerMismatchHold())

        val result = runBlocking { session.signIn("Asha", UserRole.ASHA_WORKER, "482915") }

        assertEquals(SignInFailureReason.LOCAL_NETWORK_NOT_ALLOWED, (result as SignInResult.Failure).reason)
        assertEquals("the facility gate is never consulted", 0, facilityTouches)
        assertNull("nothing is saved", runBlocking { tokens.snapshot().accessToken })
    }
}
