package com.example.samdapp.data.local.auth

import com.example.samdapp.domain.auth.UserSession
import kotlinx.coroutines.flow.Flow

/** Everything a synchronous OkHttp `Interceptor`/`Authenticator` needs to decide what to attach
 *  or whether to refresh, read in one round trip instead of five. */
data class TokenSnapshot(
    val deviceId: String,
    val accessToken: String?,
    val refreshToken: String?,
)

/**
 * Backs [com.example.samdapp.data.local.auth.BackendAuthSession] and, directly, the bearer
 * `Interceptor` and `Authenticator` in `data/remote/`: both need read access to the current
 * access/refresh token outside of the `AuthSession` domain interface, which exposes only
 * [UserSession], not raw tokens (tokens are a data-layer/network concern, not a domain one).
 *
 * An interface, not a concrete class, so `TokenAuthenticator`/`BearerInterceptor` tests can use an
 * in-memory fake instead of a real DataStore, same shape as
 * [com.example.samdapp.data.local.dao.AuditLogDao] / `FakeAuditLogDao` elsewhere in this
 * codebase. [DataStoreAuthTokenStore] is the real,
 * DataStore-backed implementation, bound in `di/RepositoryModule.kt`.
 */
interface AuthTokenStore {
    val session: Flow<UserSession?>
    val mustChangePin: Flow<Boolean>

    /** Generated once, lazily, on first call and persisted: not `ANDROID_ID`, not any hardware
     *  identifier (api-contract.md §2.2). */
    suspend fun deviceId(): String

    suspend fun snapshot(): TokenSnapshot

    suspend fun saveLogin(
        accessToken: String,
        refreshToken: String,
        expiresInSeconds: Long,
        mustChangePin: Boolean,
        workerId: String,
        displayName: String,
        role: String,
        facilityId: String,
        facilityName: String,
    )

    /** Called after a successful token refresh. Leaves worker/session fields untouched. */
    suspend fun saveTokens(accessToken: String, refreshToken: String, expiresInSeconds: Long)

    suspend fun setMustChangePin(value: Boolean)

    /** Clears session and tokens. Preserves `device_id`: a signed-out device is still the same
     *  device for the next login. */
    suspend fun clear()
}
