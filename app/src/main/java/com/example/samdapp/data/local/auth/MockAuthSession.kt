package com.example.samdapp.data.local.auth

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.samdapp.domain.auth.AuthSession
import com.example.samdapp.domain.auth.ChangePinResult
import com.example.samdapp.domain.auth.SignInResult
import com.example.samdapp.domain.auth.UserRole
import com.example.samdapp.domain.auth.UserSession
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

// A DIFFERENT file name than AuthTokenStore's "auth_session": androidx DataStore throws
// IllegalStateException if two DataStore<Preferences> instances are ever opened against the same
// file in one process, and MockAuthSession, though unbound, still has an @Inject constructor:
// an accidental future injection of both must not be able to crash the app.
private val Context.mockAuthDataStore: DataStore<Preferences> by preferencesDataStore(name = "mock_auth_session_unused")

private object MockKeys {
    val USER_ID = stringPreferencesKey("user_id")
    val NAME = stringPreferencesKey("name")
    val ROLE = stringPreferencesKey("role")
}

/**
 * Mock — no credential verification, [signIn] simply stores whatever name/role the worker
 * entered. Preferences DataStore, not Room: this is one small key-value blob (id/name/role)
 * with no relational shape and no queries — Room would be relational overkill for a single row.
 * Survives app restart (DataStore persists to disk); cleared on [signOut].
 *
 * userId is deterministically derived from the entered name + role (SHA-256 hash, truncated) —
 * not a fresh random id per [signIn]. Same name+role typed again yields the same userId, so the
 * audit trail (REQ-AUD-01, H-06/H-07) can tell repeated sign-ins by the same worker apart from
 * different workers. This is NOT identity verification: there is still no credential check, so
 * anyone typing "AshaDevi" gets AshaDevi's userId regardless of who they actually are.
 *
 * As of Phase 6a this is no longer bound in [com.example.samdapp.di.RepositoryModule]:
 * [com.example.samdapp.data.local.auth.BackendAuthSession] is the real [AuthSession]
 * implementation now (REQ-SEC-03 closed). Kept in the tree, not deleted: there is no existing
 * flavor/DI seam in this codebase that swaps implementations per build variant (unlike, say, a
 * dev-only fake), so there is nothing to wire this behind; it remains only as a reference for the
 * `stableUserId` derivation that [BackendAuthSession] and the backend's `worker_id` provisioning
 * both still have to match exactly for audit continuity across the mock-to-real cutover.
 */
@Singleton
class MockAuthSession @Inject constructor(
    @ApplicationContext context: Context,
) : AuthSession {

    private val dataStore = context.mockAuthDataStore

    override fun currentUser(): Flow<UserSession?> =
        dataStore.data
            .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
            .map { prefs ->
                val userId = prefs[MockKeys.USER_ID] ?: return@map null
                val name = prefs[MockKeys.NAME] ?: return@map null
                val role = prefs[MockKeys.ROLE]?.let { raw -> runCatching { UserRole.valueOf(raw) }.getOrNull() } ?: return@map null
                UserSession(userId = userId, name = name, role = role)
            }

    override fun mustChangePin(): Flow<Boolean> = flowOf(false)

    override suspend fun signIn(name: String, role: UserRole, pin: String): SignInResult {
        dataStore.edit { prefs ->
            prefs[MockKeys.USER_ID] = stableUserId(name, role)
            prefs[MockKeys.NAME] = name
            prefs[MockKeys.ROLE] = role.name
        }
        return SignInResult.Success(mustChangePin = false)
    }

    override suspend fun changePin(currentPin: String, newPin: String): ChangePinResult = ChangePinResult.Success

    override suspend fun signOut() {
        dataStore.edit { it.clear() }
    }
}
