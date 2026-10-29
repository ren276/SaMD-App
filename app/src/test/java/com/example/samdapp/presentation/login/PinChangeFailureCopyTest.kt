package com.example.samdapp.presentation.login

import com.example.samdapp.R
import com.example.samdapp.data.local.LocalClinicalData
import com.example.samdapp.data.local.LocalTransaction
import com.example.samdapp.data.local.auth.BackendAuthSession
import com.example.samdapp.data.local.auth.FacilitySignInGate
import com.example.samdapp.data.local.dao.DeviceDataOwnerDao
import com.example.samdapp.data.local.entity.DeviceDataOwnerEntity
import com.example.samdapp.data.remote.RetrofitAuthService
import com.example.samdapp.data.remote.api.AuthApiService
import com.example.samdapp.data.remote.dto.ApiEnvelopeDto
import com.example.samdapp.data.remote.dto.ChangePinRequestDto
import com.example.samdapp.data.remote.dto.ChangePinResponseDto
import com.example.samdapp.data.remote.dto.LoginRequestDto
import com.example.samdapp.data.remote.dto.LoginResponseDto
import com.example.samdapp.data.remote.dto.LogoutRequestDto
import com.example.samdapp.data.remote.dto.LogoutResponseDto
import com.example.samdapp.data.remote.dto.MeResponseDto
import com.example.samdapp.data.remote.dto.RefreshRequestDto
import com.example.samdapp.data.remote.dto.TokenPairDto
import com.example.samdapp.data.sync.MemoryOwnerMismatchHold
import com.example.samdapp.domain.auth.SignInFailureReason
import com.example.samdapp.domain.connectivity.LocalNetworkNotAllowedException
import com.example.samdapp.testutil.FakeAuthTokenStore
import com.example.samdapp.testutil.FakeSyncOutboxRepository
import com.example.samdapp.testutil.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import retrofit2.Response
import java.io.IOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

/**
 * Memo Q12 (B6), P2-R11: a PIN change that got no answer shows a string resource, never the
 * exception's text (which quotes LAN addresses and ports); a server refusal keeps its detail; the
 * session is kept on every failure (Q10a). Runs the real BackendAuthSession and RetrofitAuthService
 * over an API fake that throws, so the whole path is exercised.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PinChangeFailureCopyTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val rawText = "failed to connect to /10.203.2.52 (port 8080) from /10.203.7.114 (port 53786) after 10000ms"

    private class ThrowingApi(private val behaviour: () -> Response<ApiEnvelopeDto<ChangePinResponseDto>>) : AuthApiService {
        override suspend fun changePin(request: ChangePinRequestDto): Response<ApiEnvelopeDto<ChangePinResponseDto>> = behaviour()
        override suspend fun login(request: LoginRequestDto): Response<ApiEnvelopeDto<LoginResponseDto>> = error("unused")
        override suspend fun logout(request: LogoutRequestDto): Response<ApiEnvelopeDto<LogoutResponseDto>> = error("unused")
        override suspend fun refresh(request: RefreshRequestDto): Response<ApiEnvelopeDto<TokenPairDto>> = error("unused")
        override suspend fun me(): Response<ApiEnvelopeDto<MeResponseDto>> = error("unused")
    }

    private class Setup(behaviour: () -> Response<ApiEnvelopeDto<ChangePinResponseDto>>) {
        val tokens = FakeAuthTokenStore(accessToken = "access-1", refreshToken = "refresh-1")
        private val api = RetrofitAuthService(ThrowingApi(behaviour))
        private val facilityGate = FacilitySignInGate(
            object : DeviceDataOwnerDao {
                override suspend fun insertIfAbsent(owner: DeviceDataOwnerEntity): Long = error("not used")
                override suspend fun read(): DeviceDataOwnerEntity? = error("not used")
            },
            object : LocalClinicalData { override suspend fun present(): Boolean = error("not used") },
            FakeSyncOutboxRepository(),
            object : LocalTransaction { override suspend fun <T> run(block: suspend () -> T): T = error("not used") },
            api,
        ) { error("not used") }
        val viewModel = PinChangeViewModel(BackendAuthSession(api, tokens, facilityGate, MemoryOwnerMismatchHold()))
    }

    private fun kotlinx.coroutines.test.TestScope.change(setup: Setup): PinChangeUiState {
        setup.viewModel.onCurrentPinChange("482915")
        setup.viewModel.onNewPinChange("739104")
        setup.viewModel.onConfirmPinChange("739104")
        setup.viewModel.onSubmit()
        advanceUntilIdle()
        return setup.viewModel.uiState.value
    }

    private suspend fun assertSessionKept(setup: Setup, label: String) {
        val snapshot = setup.tokens.snapshot()
        assertEquals("$label: access token kept", "access-1", snapshot.accessToken)
        assertEquals("$label: refresh token kept", "refresh-1", snapshot.refreshToken)
    }

    @Test
    fun `the reasons map to their own PIN change strings, and a server refusal to none`() {
        assertEquals(R.string.pin_change_failed_local_network, pinChangeFailureRes(SignInFailureReason.LOCAL_NETWORK_NOT_ALLOWED))
        assertEquals(R.string.pin_change_failed_no_connection, pinChangeFailureRes(SignInFailureReason.NO_CONNECTION))
        assertEquals(R.string.pin_change_failed_secure_connection, pinChangeFailureRes(SignInFailureReason.SECURE_CONNECTION_FAILED))
        assertNull(pinChangeFailureRes(SignInFailureReason.SERVER))
    }

    @Test
    fun `LocalNetworkNotAllowedException gives the local-network string and Open settings`() = runTest(mainDispatcherRule.dispatcher) {
        val setup = Setup { throw LocalNetworkNotAllowedException() }
        val state = change(setup)

        assertEquals(PinChangeError.Worded(R.string.pin_change_failed_local_network), state.error)
        assertTrue(state.error.offersOpenSettings())
        assertSessionKept(setup, "local network")
    }

    @Test
    fun `a generic IOException gives the no-connection string and no Open settings`() = runTest(mainDispatcherRule.dispatcher) {
        val setup = Setup { throw IOException(rawText) }
        val state = change(setup)

        assertEquals(PinChangeError.Worded(R.string.pin_change_failed_no_connection), state.error)
        assertFalse(state.error.offersOpenSettings())
        assertSessionKept(setup, "io")
    }

    @Test
    fun `an SSLException gives the secure-connection string`() = runTest(mainDispatcherRule.dispatcher) {
        for (failure in listOf<IOException>(SSLException(rawText), SSLHandshakeException(rawText))) {
            val setup = Setup { throw failure }
            val state = change(setup)

            assertEquals("$failure", PinChangeError.Worded(R.string.pin_change_failed_secure_connection), state.error)
            assertFalse(state.error.offersOpenSettings())
            assertSessionKept(setup, "$failure")
        }
    }

    @Test
    fun `a SocketTimeoutException gives the no-connection string`() = runTest(mainDispatcherRule.dispatcher) {
        val setup = Setup { throw SocketTimeoutException(rawText) }
        val state = change(setup)

        assertEquals(PinChangeError.Worded(R.string.pin_change_failed_no_connection), state.error)
        assertFalse(state.error.offersOpenSettings())
        assertSessionKept(setup, "timeout")
    }

    @Test
    fun `a server refusal keeps the server's detail, without Open settings, and keeps the session`() = runTest(mainDispatcherRule.dispatcher) {
        val body = """{"detail":"Current PIN is incorrect.","code":"SAMD-AUTH-1001"}""".toResponseBody()
        val setup = Setup { Response.error(400, body) }
        val state = change(setup)

        assertEquals(PinChangeError.ServerDetail("Current PIN is incorrect."), state.error)
        assertFalse(state.error.offersOpenSettings())
        assertSessionKept(setup, "server refusal")
    }

    @Test
    fun `no exception text appears anywhere in the UI state`() = runTest(mainDispatcherRule.dispatcher) {
        val failures = listOf<IOException>(
            IOException(rawText), SocketTimeoutException(rawText), SSLException(rawText),
        )
        for (failure in failures) {
            val state = change(Setup { throw failure })
            val shown = state.toString()
            assertFalse("$failure: address leaked into $shown", "10.203" in shown || "port 8080" in shown || rawText in shown)
        }
        // The gate's own message is generic, but it is still not shown.
        val gate = change(Setup { throw LocalNetworkNotAllowedException() }).toString()
        assertFalse("gate text leaked: $gate", LocalNetworkNotAllowedException().message!! in gate)
    }

    @Test
    fun `editing the form clears the error`() = runTest(mainDispatcherRule.dispatcher) {
        val setup = Setup { throw LocalNetworkNotAllowedException() }
        change(setup)
        setup.viewModel.onCurrentPinChange("1")

        assertNull(setup.viewModel.uiState.value.error)
    }
}
