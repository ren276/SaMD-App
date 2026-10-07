package com.example.samdapp.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.samdapp.data.local.entity.CaseRecordEntity
import com.example.samdapp.data.local.entity.EncounterEntity
import com.example.samdapp.data.local.entity.PatientEntity
import com.example.samdapp.domain.model.CaseStatus
import com.example.samdapp.domain.model.SyncState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * The case record's outbox facts on the two list queries that render "with the doctor": the
 * cross-patient tracker and one patient's visit history. Both feed `isServerPresent`, which
 * decides between "Awaiting doctor's review" and "Queued for doctor, not yet on the server". The
 * history columns are nullable (LEFT JOIN), and Room only warns on a nullable alias that matches no
 * field, so only running the queries proves the values land.
 */
class CaseServerPresenceQueryTest {

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

    private suspend fun seed() {
        db.patientDao().insert(
            PatientEntity(
                id = "pat-1", fullName = "Asha Devi", dateOfBirth = null, age = 30, biologicalSex = "Female",
                guardianOrSpouseName = null, guardianRelation = null, mobileNumber = null,
                aadhaarNumber = null, abhaNumber = null, village = null, block = null, district = null,
                state = null, pincode = null, category = null, maritalStatus = null, bloodGroup = null,
                emergencyContact = null, primaryCareClinicName = null, referringPhysicianName = null,
                createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH, localModifiedAt = Instant.EPOCH,
            ),
        )
        listOf("enc-1" to 2_000L, "enc-2" to 1_000L).forEach { (id, at) ->
            db.encounterDao().insert(
                EncounterEntity(
                    id = id, patientId = "pat-1", startedAt = Instant.ofEpochMilli(at), createdAt = Instant.EPOCH,
                    updatedAt = Instant.EPOCH, followUpOfEncounterId = null, localModifiedAt = Instant.EPOCH,
                ),
            )
        }
        db.caseRecordDao().insert(
            CaseRecordEntity(
                id = "case-1", patientId = "pat-1", encounterId = "enc-1", status = CaseStatus.SENT_TO_DOCTOR,
                assignedDoctorId = null, createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
                localModifiedAt = Instant.EPOCH, syncState = SyncState.PENDING, serverVersion = 3,
            ),
        )
    }

    @Test
    fun trackerRow_carriesTheCaseSyncStateAndServerVersion() = runBlocking {
        seed()

        val row = db.caseRecordDao().observeDoctorTrackerRows().first().single()

        assertEquals(SyncState.PENDING, row.caseSyncState)
        assertEquals(3, row.caseServerVersion)
    }

    @Test
    fun historyRow_carriesThemToo_andReadsNullWithNoCaseRecord() = runBlocking {
        seed()

        val rows = db.encounterDao().observeHistoryForPatient("pat-1").first()

        val withCase = rows.single { it.encounterId == "enc-1" }
        assertEquals(SyncState.PENDING, withCase.caseSyncState)
        assertEquals(3, withCase.caseServerVersion)
        val noCase = rows.single { it.encounterId == "enc-2" }
        assertNull(noCase.caseSyncState)
        assertNull(noCase.caseServerVersion)
    }
}
