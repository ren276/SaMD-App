package com.example.samdapp.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.samdapp.data.local.entity.EncounterEntity
import com.example.samdapp.data.local.entity.PatientEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * REQ-ROS-01/02: the only patient-list query is date-bounded, dedupes a patient with multiple
 * encounters, orders by most-recent encounter, and excludes out-of-window / encounter-less
 * patients. This is the data-minimisation guarantee — verify it stays true.
 */
class TodaysPatientsDaoTest {

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

    private fun patient(id: String) = PatientEntity(
        id = id, fullName = "P-$id", dateOfBirth = null, age = 30, biologicalSex = "Female",
        guardianOrSpouseName = null, guardianRelation = null, mobileNumber = null, aadhaarNumber = null, abhaNumber = null,
        village = null, block = null, district = null, state = null, pincode = null, category = null,
        maritalStatus = null, bloodGroup = null, emergencyContact = null, primaryCareClinicName = null,
        referringPhysicianName = null, createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
        localModifiedAt = Instant.EPOCH,
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
    fun onlyPatientsWithEncounterInWindow_dedupedAndOrderedByMostRecent() = runBlocking {
        val dao = db.patientDao()
        val enc = db.encounterDao()
        listOf("today", "twice", "yesterday", "never").forEach { dao.insert(patient(it)) }

        enc.insert(encounter("e1", "today", 1_500))     // in [1000, 2000)
        enc.insert(encounter("e2", "twice", 1_200))     // in window (older)
        enc.insert(encounter("e3", "twice", 1_800))     // in window (newer) -> dedupe, orders "twice" first
        enc.insert(encounter("e4", "yesterday", 500))   // before window
        enc.insert(encounter("e5", "today", 2_000))     // == end -> excluded (half-open)
        // "never" has no encounter

        val result = dao.observePatientsWithEncounterBetween(1_000, 2_000).first().map { it.id }

        assertEquals(listOf("twice", "today"), result)
    }

    @Test
    fun emptyWhenNoEncountersInWindow() = runBlocking {
        val dao = db.patientDao()
        dao.insert(patient("p1"))
        db.encounterDao().insert(encounter("e1", "p1", 500))

        val result = dao.observePatientsWithEncounterBetween(1_000, 2_000).first()

        assertEquals(emptyList<PatientEntity>(), result)
    }
}
