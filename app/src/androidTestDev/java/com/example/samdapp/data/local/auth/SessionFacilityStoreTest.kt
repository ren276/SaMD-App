package com.example.samdapp.data.local.auth

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * `DataStoreAuthTokenStore.currentFacility()` against a real Preferences DataStore on an isolated
 * temp file (on-device SLM memo 7.1, Y-21'). The app's own `auth_session` file is never touched:
 * the store is built through its internal DataStore constructor. Mock values only.
 */
class SessionFacilityStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var store: DataStoreAuthTokenStore

    @Before
    fun setUp() {
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "facility_test.preferences_pb") }
        store = DataStoreAuthTokenStore(dataStore)
    }

    @After
    fun tearDown() = scope.cancel()

    private suspend fun signIn(facilityId: String = "fac-1", facilityName: String = "PHC Mock") = store.saveLogin(
        accessToken = "mock-access", refreshToken = "mock-refresh", expiresInSeconds = 3600, mustChangePin = false,
        workerId = "w-1", displayName = "Mock Worker", role = "DOCTOR", facilityId = facilityId, facilityName = facilityName,
    )

    @Test
    fun signedOutIsNull() = runBlocking {
        assertNull(store.currentFacility())
    }

    @Test
    fun signedInWithAFacilityReturnsIdAndName() = runBlocking {
        signIn(facilityId = "fac-7", facilityName = "PHC Rampur")

        val facility = store.currentFacility()!!

        assertEquals("fac-7", facility.facilityId)
        assertEquals("PHC Rampur", facility.facilityName)
    }

    @Test
    fun aBlankFacilityIdIsNoFacility() = runBlocking {
        signIn(facilityId = "  ")
        assertNull(store.currentFacility())
    }

    @Test
    fun aFacilityIdWithoutASignedInWorkerIsNoFacility() = runBlocking {
        dataStore.edit { it[stringPreferencesKey("facility_id")] = "fac-leftover" }
        assertNull(store.currentFacility())
    }

    @Test
    fun clearingTheSessionClearsTheFacility() = runBlocking {
        signIn()
        assertEquals("fac-1", store.currentFacility()!!.facilityId)

        store.clear()

        assertNull(store.currentFacility())
    }

    @Test
    fun aRefreshLeavesTheFacilityAndANewLoginReplacesIt() = runBlocking {
        signIn(facilityId = "fac-1")
        store.saveTokens("mock-access-2", "mock-refresh-2", 3600)
        assertEquals("fac-1", store.currentFacility()!!.facilityId)

        signIn(facilityId = "fac-2")
        assertEquals("fac-2", store.currentFacility()!!.facilityId)
    }
}
