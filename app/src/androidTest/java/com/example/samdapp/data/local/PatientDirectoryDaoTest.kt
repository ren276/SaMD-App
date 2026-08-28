package com.example.samdapp.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.samdapp.data.local.entity.EncounterEntity
import com.example.samdapp.data.local.entity.PatientEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * Patients tab directory read (observeRegisteredOrSeenBetween). Companion to
 * TodaysPatientsDaoTest, which stays untouched and covers the encounter-required Home query.
 * This class exists specifically to guard the HAVING-vs-WHERE trap: a WHERE predicate on
 * e.startedAt would silently degrade the LEFT JOIN back into an INNER JOIN and reintroduce the
 * bug this query fixes.
 */
class PatientDirectoryDaoTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).build()
    }

    @After
    fun tearDown() = db.close()

    private fun patient(id: String, createdAtMillis: Long) = PatientEntity(
        id = id, fullName = "P-$id", dateOfBirth = null, age = 30, biologicalSex = "Female",
        guardianOrSpouseName = null, guardianRelation = null, mobileNumber = null, aadhaarNumber = null, abhaNumber = null,
        village = null, block = null, district = null, state = null, pincode = null, category = null,
        maritalStatus = null, bloodGroup = null, emergencyContact = null, primaryCareClinicName = null,
        referringPhysicianName = null,
        createdAt = Instant.ofEpochMilli(createdAtMillis), updatedAt = Instant.ofEpochMilli(createdAtMillis),
        localModifiedAt = Instant.ofEpochMilli(createdAtMillis),
    )

    private fun encounter(id: String, patientId: String, startedAtMillis: Long) = EncounterEntity(
        id = id,
        patientId = patientId,
        startedAt = Instant.ofEpochMilli(startedAtMillis),
        createdAt = Instant.ofEpochMilli(startedAtMillis),
        updatedAt = Instant.ofEpochMilli(startedAtMillis),
        followUpOfEncounterId = null,
        localModifiedAt = Instant.ofEpochMilli(startedAtMillis),
    )

    @Test
    fun registeredWithNoEncounter_insideWindow_appears() = runBlocking {
        val dao = db.patientDao()
        dao.insert(patient("never", createdAtMillis = 1_500))

        val result = dao.observeRegisteredOrSeenBetween(1_000, 2_000).first()

        assertEquals(listOf("never"), result.map { it.patient.id })
        assertNull(result.single().lastSeenAt)
    }

    @Test
    fun registeredWithNoEncounter_outsideWindow_excluded() = runBlocking {
        val dao = db.patientDao()
        dao.insert(patient("old", createdAtMillis = 500))

        val result = dao.observeRegisteredOrSeenBetween(1_000, 2_000).first()

        assertEquals(emptyList<String>(), result.map { it.patient.id })
    }

    @Test
    fun onlyEncounterIsOutsideWindow_registeredOutsideWindowToo_excluded() = runBlocking {
        // An encounter's startedAt can never precede its own patient's createdAt (an encounter
        // requires the patient row to already exist) - so a patient's fallback createdAt is
        // never "more recent" than an encounter they already have. This test pins that: when a
        // patient's one encounter is outside the window, COALESCE resolves to that encounter
        // time (not createdAt), and the row is correctly excluded, exactly like the old
        // INNER JOIN behaviour for this case.
        val dao = db.patientDao()
        val enc = db.encounterDao()
        dao.insert(patient("p1", createdAtMillis = 200))
        enc.insert(encounter("e1", "p1", startedAtMillis = 500)) // encounter after registration, still before the window

        val result = dao.observeRegisteredOrSeenBetween(1_000, 2_000).first()

        assertEquals(emptyList<String>(), result.map { it.patient.id })
    }

    @Test
    fun registeredInsideWindow_withOnlyAnEarlierOutOfWindowEncounter_includedOnRegistrationTime() = runBlocking {
        // Regression guard: a plain COALESCE(MAX(e.startedAt), p.createdAt) picks the encounter
        // time whenever any encounter exists at all, even one earlier than registration, which
        // would wrongly exclude this patient (registered inside the window) because their only
        // encounter happens to be outside it. The query uses the later of the two specifically
        // so this case is included and ordered on the registration time, not the stale encounter.
        val dao = db.patientDao()
        val enc = db.encounterDao()
        dao.insert(patient("p1", createdAtMillis = 1_500))
        enc.insert(encounter("e1", "p1", startedAtMillis = 200))

        val result = dao.observeRegisteredOrSeenBetween(1_000, 2_000).first()

        assertEquals(listOf("p1"), result.map { it.patient.id })
    }

    @Test
    fun oldEncounterOutsideWindow_newerEncounterInsideWindow_includedOnNewestOnly() = runBlocking {
        // MAX(e.startedAt) must pick the newest encounter, not any encounter - a stale visit
        // from before the window must not by itself keep a patient out of, or force them into,
        // the window; only their most recent activity (or createdAt, if none) decides it.
        val dao = db.patientDao()
        val enc = db.encounterDao()
        dao.insert(patient("p1", createdAtMillis = 100))
        enc.insert(encounter("e_old", "p1", startedAtMillis = 300)) // before the window
        enc.insert(encounter("e_new", "p1", startedAtMillis = 1_600)) // inside the window

        val result = dao.observeRegisteredOrSeenBetween(1_000, 2_000).first()

        assertEquals(listOf("p1"), result.map { it.patient.id })
        assertEquals(Instant.ofEpochMilli(1_600), result.single().lastSeenAt)
    }

    @Test
    fun encounterInsideWindow_reportsLastSeenAt_notNull() = runBlocking {
        val dao = db.patientDao()
        val enc = db.encounterDao()
        dao.insert(patient("seen", createdAtMillis = 100)) // registered well before the window
        enc.insert(encounter("e1", "seen", startedAtMillis = 1_500))

        val result = dao.observeRegisteredOrSeenBetween(1_000, 2_000).first()

        assertEquals(listOf("seen"), result.map { it.patient.id })
        assertEquals(Instant.ofEpochMilli(1_500), result.single().lastSeenAt)
    }

    @Test
    fun dedupesMultipleEncounters_ordersByMostRecentThenId() = runBlocking {
        val dao = db.patientDao()
        val enc = db.encounterDao()
        dao.insert(patient("a", createdAtMillis = 100))
        dao.insert(patient("b", createdAtMillis = 100))
        enc.insert(encounter("e1", "a", startedAtMillis = 1_200))
        enc.insert(encounter("e2", "a", startedAtMillis = 1_800))
        enc.insert(encounter("e3", "b", startedAtMillis = 1_800))

        val result = dao.observeRegisteredOrSeenBetween(1_000, 2_000).first()

        // "a" and "b" tie on lastSeenAt (both 1_800) -> tiebreak by id ascending.
        assertEquals(listOf("a", "b"), result.map { it.patient.id })
    }
}
