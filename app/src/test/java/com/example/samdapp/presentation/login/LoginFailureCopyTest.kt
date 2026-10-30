package com.example.samdapp.presentation.login

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
import com.example.samdapp.domain.auth.ChangePinResult
import com.example.samdapp.domain.auth.SignInFailureReason
import com.example.samdapp.domain.auth.SignInResult
import com.example.samdapp.domain.auth.UserRole
import com.example.samdapp.domain.auth.UserSession
import com.example.samdapp.domain.auth.signInFailureReasonFor
import com.example.samdapp.domain.connectivity.LocalNetworkGate
import com.example.samdapp.domain.connectivity.LocalNetworkNotAllowedException
import com.example.samdapp.testutil.FakeAuthTokenStore
import com.example.samdapp.testutil.FakeSyncOutboxRepository
import com.example.samdapp.testutil.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException

/** Q4, memo 4.1 and 11.4: a sign-in that got no answer shows a string resource, never the
 *  exception's text (which quotes LAN addresses and ports); a server refusal keeps its detail. */
@OptIn(ExperimentalCoroutinesApi::class)
class LoginFailureCopyTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class FailingAuthSession(private val failure: SignInResult.Failure) : AuthSession {
        override fun currentUser(): Flow<UserSession?> = flowOf(null)
        override fun mustChangePin(): Flow<Boolean> = flowOf(false)
        override suspend fun signIn(name: String, role: UserRole, pin: String): SignInResult = failure
        override suspend fun changePin(currentPin: String, newPin: String): ChangePinResult = ChangePinResult.Success
        override suspend fun signOut() = Unit
    }

    private val rawText = "failed to connect to /10.203.2.52 (port 8080) from /10.203.7.114 (port 53786) after 10000ms"

    @Test
    fun `transport failures are classified by type`() {
        assertEquals(SignInFailureReason.SERVER, signInFailureReasonFor(null))
        assertEquals(SignInFailureReason.LOCAL_NETWORK_NOT_ALLOWED, signInFailureReasonFor(LocalNetworkNotAllowedException()))
        assertEquals(SignInFailureReason.NO_CONNECTION, signInFailureReasonFor(SocketTimeoutException(rawText)))
        assertEquals(SignInFailureReason.NO_CONNECTION, signInFailureReasonFor(IOException(rawText)))
        assertEquals(SignInFailureReason.SECURE_CONNECTION_FAILED, signInFailureReasonFor(SSLHandshakeException("x")))
    }

    @Test
    fun `every reason the app words has its own string, and a server refusal has none`() {
        assertEquals(R.string.lnp_denied_login, signInFailureRes(SignInFailureReason.LOCAL_NETWORK_NOT_ALLOWED))
        assertEquals(R.string.login_failed_no_connection, signInFailureRes(SignInFailureReason.NO_CONNECTION))
        assertEquals(R.string.login_failed_secure_connection, signInFailureRes(SignInFailureReason.SECURE_CONNECTION_FAILED))
        assertNull(signInFailureRes(SignInFailureReason.SERVER))
    }

    @Test
    fun `a transport failure shows the string and never the exception text`() = runTest(mainDispatcherRule.dispatcher) {
        for ((reason, res) in listOf(
            SignInFailureReason.LOCAL_NETWORK_NOT_ALLOWED to R.string.lnp_denied_login,
            SignInFailureReason.NO_CONNECTION to R.string.login_failed_no_connection,
        )) {
            val viewModel = LoginViewModel(FailingAuthSession(SignInResult.Failure(rawText, reason)), publicBackendCheck())
            viewModel.onNameChange("Asha")
            viewModel.onRoleSelect(UserRole.ASHA_WORKER)
            viewModel.onPinChange("482915")
            viewModel.onSubmit()
            advanceUntilIdle()
            viewModel.onBiometricSucceeded()
            advanceUntilIdle()
            assertEquals("$reason", res, (viewModel.uiState.value.error as? LoginError.Worded)?.res)
            assertNull("$reason must not show exception text", (viewModel.uiState.value.error as? LoginError.ServerDetail)?.detail)
        }
    }

    @Test
    fun `a server refusal keeps the server's detail`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = LoginViewModel(FailingAuthSession(SignInResult.Failure("Invalid credentials.")), publicBackendCheck())
        viewModel.onNameChange("Asha")
        viewModel.onRoleSelect(UserRole.ASHA_WORKER)
        viewModel.onPinChange("482915")
        viewModel.onSubmit()
        advanceUntilIdle()
        viewModel.onBiometricSucceeded()
        advanceUntilIdle()
        assertEquals("Invalid credentials.", (viewModel.uiState.value.error as? LoginError.ServerDetail)?.detail)
        assertNull((viewModel.uiState.value.error as? LoginError.Worded)?.res)
    }

    @Test
    fun `a sign-in stopped by the gate reaches the session as LOCAL_NETWORK_NOT_ALLOWED`() {
        val api = Retrofit.Builder()
            .baseUrl("http://10.203.2.52:8080/")
            .client(
                OkHttpClient.Builder()
                    .addInterceptor(LocalNetworkGateInterceptor(LocalNetworkGate(sdkInt = 37) { false }))
                    .build(),
            )
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(AuthApiService::class.java)
        // The login fails at the gate interceptor, so the facility gate must never run: every
        // dependency it would touch throws (master's signature, a semantic merge fix).
        val gate = FacilitySignInGate(
            object : DeviceDataOwnerDao {
                override suspend fun insertIfAbsent(owner: DeviceDataOwnerEntity): Long = error("gate must not run")
                override suspend fun read(): DeviceDataOwnerEntity? = error("gate must not run")
            },
            object : LocalClinicalData { override suspend fun present(): Boolean = error("gate must not run") },
            FakeSyncOutboxRepository(),
            object : LocalTransaction { override suspend fun <T> run(block: suspend () -> T): T = error("gate must not run") },
            RetrofitAuthService(api),
        ) { error("gate must not run") }
        val session = BackendAuthSession(RetrofitAuthService(api), FakeAuthTokenStore(), gate, MemoryOwnerMismatchHold())

        val result = runBlocking { session.signIn("Asha", UserRole.ASHA_WORKER, "482915") }

        assertEquals(SignInFailureReason.LOCAL_NETWORK_NOT_ALLOWED, (result as SignInResult.Failure).reason)
    }
}
