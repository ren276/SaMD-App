package com.example.samdapp.data.local.auth

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.samdapp.domain.auth.AuthSession
import com.example.samdapp.domain.auth.UserRole
import com.example.samdapp.domain.auth.UserSession
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

private val Context.authDataStore: DataStore<Preferences> by preferencesDataStore(name = "auth_session")

private object Keys {
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
 * anyone typing "AshaDevi" gets AshaDevi's userId regardless of who they actually are. Real
 * account resolution is still REQ-SEC-03, PLANNED.
 */
@Singleton
class MockAuthSession @Inject constructor(
    @ApplicationContext context: Context,
) : AuthSession {

    private val dataStore = context.authDataStore

    private fun stableUserId(name: String, role: UserRole): String {
        val normalized = "${name.trim().lowercase()}|${role.name}"
        val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    override fun currentUser(): Flow<UserSession?> =
        dataStore.data
            .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
            .map { prefs ->
                val userId = prefs[Keys.USER_ID] ?: return@map null
                val name = prefs[Keys.NAME] ?: return@map null
                val role = prefs[Keys.ROLE]?.let { raw -> runCatching { UserRole.valueOf(raw) }.getOrNull() } ?: return@map null
                UserSession(userId = userId, name = name, role = role)
            }

    override suspend fun signIn(name: String, role: UserRole) {
        dataStore.edit { prefs ->
            prefs[Keys.USER_ID] = stableUserId(name, role)
            prefs[Keys.NAME] = name
            prefs[Keys.ROLE] = role.name
        }
    }

    override suspend fun signOut() {
        dataStore.edit { it.clear() }
    }
}
