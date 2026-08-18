package com.example.samdapp.data.local.auth

import com.example.samdapp.data.remote.AuthApiResult
import com.example.samdapp.data.remote.RetrofitAuthService
import com.example.samdapp.domain.auth.AuthSession
import com.example.samdapp.domain.auth.ChangePinResult
import com.example.samdapp.domain.auth.SignInResult
import com.example.samdapp.domain.auth.UserRole
import com.example.samdapp.domain.auth.UserSession
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real [AuthSession] implementation (Phase 6a, REQ-SEC-03), replaces [MockAuthSession] in
 * [com.example.samdapp.di.RepositoryModule]. `worker_id` is derived locally with the exact same
 * [stableUserId] formula the mock used, so a worker's audit identity does not discontinue at this
 * cutover; the backend provisions `user_accounts.worker_id` to match (api-contract.md §2.1).
 */
@Singleton
class BackendAuthSession @Inject constructor(
    private val authApiService: RetrofitAuthService,
    private val tokenStore: AuthTokenStore,
) : AuthSession {

    override fun currentUser(): Flow<UserSession?> = tokenStore.session

    override fun mustChangePin(): Flow<Boolean> = tokenStore.mustChangePin

    override suspend fun signIn(name: String, role: UserRole, pin: String): SignInResult {
        val workerId = stableUserId(name, role)
        val deviceId = tokenStore.deviceId()

        return when (val result = authApiService.login(workerId, pin, deviceId)) {
            is AuthApiResult.Success -> {
                val login = result.data
                tokenStore.saveLogin(
                    accessToken = login.accessToken,
                    refreshToken = login.refreshToken,
                    expiresInSeconds = login.expiresIn,
                    mustChangePin = login.mustChangePin,
                    workerId = login.worker.workerId,
                    displayName = login.worker.displayName,
                    role = login.worker.role,
                    facilityId = login.worker.facilityId,
                    facilityName = login.worker.facilityName,
                )
                SignInResult.Success(mustChangePin = login.mustChangePin)
            }
            is AuthApiResult.Failure -> SignInResult.Failure(result.message)
        }
    }

    override suspend fun changePin(currentPin: String, newPin: String): ChangePinResult {
        // Changing the PIN revokes every refresh chain for this worker, on every device
        // (api-contract.md §2.6): no new token pair is returned, so the client must sign in
        // again. Clearing here rather than leaving stale, now-revoked tokens in the store.
        return when (val result = authApiService.changePin(currentPin, newPin)) {
            is AuthApiResult.Success -> {
                tokenStore.clear()
                ChangePinResult.Success
            }
            is AuthApiResult.Failure -> ChangePinResult.Failure(result.message)
        }
    }

    override suspend fun signOut() {
        val snapshot = tokenStore.snapshot()
        // Best-effort: logout is idempotent server side and the local session is cleared either
        // way, so a network failure here must not block sign-out.
        snapshot.refreshToken?.let { runCatching { authApiService.logout(it) } }
        tokenStore.clear()
    }
}
