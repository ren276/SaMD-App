package com.example.samdapp.data.local.auth

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.samdapp.domain.auth.UserRole
import com.example.samdapp.domain.auth.UserSession
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

// Reuses the "auth_session" DataStore file MockAuthSession used: same store, richer key set,
// not a second file, so there is exactly one on-disk auth blob regardless of which AuthSession
// implementation is bound.
private val Context.authDataStore: DataStore<Preferences> by preferencesDataStore(name = "auth_session")

private object Keys {
    val DEVICE_ID = stringPreferencesKey("device_id")
    val ACCESS_TOKEN = stringPreferencesKey("access_token")
    val REFRESH_TOKEN = stringPreferencesKey("refresh_token")
    val WORKER_ID = stringPreferencesKey("worker_id")
    val NAME = stringPreferencesKey("name")
    val ROLE = stringPreferencesKey("role")
    val FACILITY_ID = stringPreferencesKey("facility_id")
    val FACILITY_NAME = stringPreferencesKey("facility_name")
    val MUST_CHANGE_PIN = booleanPreferencesKey("must_change_pin")
    val EXPIRES_AT_EPOCH_MS = longPreferencesKey("expires_at_epoch_ms")
}

/** Real, Preferences-DataStore-backed [AuthTokenStore]. See that interface's KDoc for why this is
 *  split out. */
@Singleton
class DataStoreAuthTokenStore @Inject constructor(
    @ApplicationContext context: Context,
) : AuthTokenStore {
    private val dataStore = context.authDataStore

    override val session: Flow<UserSession?> =
        dataStore.data
            .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
            .map { prefs ->
                val workerId = prefs[Keys.WORKER_ID] ?: return@map null
                val name = prefs[Keys.NAME] ?: return@map null
                val role = prefs[Keys.ROLE]?.let { raw -> runCatching { UserRole.valueOf(raw) }.getOrNull() } ?: return@map null
                UserSession(userId = workerId, name = name, role = role)
            }

    override val mustChangePin: Flow<Boolean> =
        dataStore.data
            .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
            .map { prefs -> prefs[Keys.MUST_CHANGE_PIN] ?: false }

    override suspend fun deviceId(): String {
        val existing = dataStore.data.first()[Keys.DEVICE_ID]
        if (existing != null) return existing
        val generated = UUID.randomUUID().toString()
        dataStore.edit { prefs -> prefs[Keys.DEVICE_ID] = generated }
        return generated
    }

    override suspend fun snapshot(): TokenSnapshot {
        val prefs = dataStore.data.first()
        return TokenSnapshot(
            deviceId = prefs[Keys.DEVICE_ID] ?: deviceId(),
            accessToken = prefs[Keys.ACCESS_TOKEN],
            refreshToken = prefs[Keys.REFRESH_TOKEN],
        )
    }

    override suspend fun saveLogin(
        accessToken: String,
        refreshToken: String,
        expiresInSeconds: Long,
        mustChangePin: Boolean,
        workerId: String,
        displayName: String,
        role: String,
        facilityId: String,
        facilityName: String,
    ) {
        dataStore.edit { prefs ->
            prefs[Keys.ACCESS_TOKEN] = accessToken
            prefs[Keys.REFRESH_TOKEN] = refreshToken
            prefs[Keys.EXPIRES_AT_EPOCH_MS] = System.currentTimeMillis() + expiresInSeconds * 1000
            prefs[Keys.MUST_CHANGE_PIN] = mustChangePin
            prefs[Keys.WORKER_ID] = workerId
            prefs[Keys.NAME] = displayName
            prefs[Keys.ROLE] = role
            prefs[Keys.FACILITY_ID] = facilityId
            prefs[Keys.FACILITY_NAME] = facilityName
        }
    }

    override suspend fun saveTokens(accessToken: String, refreshToken: String, expiresInSeconds: Long) {
        dataStore.edit { prefs ->
            prefs[Keys.ACCESS_TOKEN] = accessToken
            prefs[Keys.REFRESH_TOKEN] = refreshToken
            prefs[Keys.EXPIRES_AT_EPOCH_MS] = System.currentTimeMillis() + expiresInSeconds * 1000
        }
    }

    override suspend fun setMustChangePin(value: Boolean) {
        dataStore.edit { prefs -> prefs[Keys.MUST_CHANGE_PIN] = value }
    }

    override suspend fun clear() {
        dataStore.edit { prefs ->
            prefs.remove(Keys.ACCESS_TOKEN)
            prefs.remove(Keys.REFRESH_TOKEN)
            prefs.remove(Keys.EXPIRES_AT_EPOCH_MS)
            prefs.remove(Keys.MUST_CHANGE_PIN)
            prefs.remove(Keys.WORKER_ID)
            prefs.remove(Keys.NAME)
            prefs.remove(Keys.ROLE)
            prefs.remove(Keys.FACILITY_ID)
            prefs.remove(Keys.FACILITY_NAME)
        }
    }
}
