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
import java.util.UUID
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
 * A fresh opaque userId is minted on every [signIn] — there's no real account system to resolve
 * "same person, signing in again" against, and that resolution is exactly what real auth
 * (REQ-SEC-03) will own later.
 */
@Singleton
class MockAuthSession @Inject constructor(
    @ApplicationContext context: Context,
) : AuthSession {

    private val dataStore = context.authDataStore

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
            prefs[Keys.USER_ID] = UUID.randomUUID().toString()
            prefs[Keys.NAME] = name
            prefs[Keys.ROLE] = role.name
        }
    }

    override suspend fun signOut() {
        dataStore.edit { it.clear() }
    }
}
