package com.example.samdapp.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.samdapp.data.local.entity.CaseRecordEntity
import com.example.samdapp.data.local.entity.EncounterEntity
import com.example.samdapp.data.local.entity.PatientEntity
import com.example.samdapp.domain.model.AssessGateSnapshot
import com.example.samdapp.domain.model.CaseStatus
import com.example.samdapp.domain.model.SyncChainRow
import com.example.samdapp.domain.model.SyncState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * The assess gate's one joined read against real SQLite: a case record LEFT JOINed to its
 * encounter and patient. The parent columns are nullable, and Room only warns on a nullable
 * projection alias that matches no field, so a misspelt parent alias would build and silently
 * read null. Only running the query proves every column lands where the gate reads it.
 */
class AssessGateSnapshotQueryTest {

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

    private fun patient(state: SyncState, serverVersion: Int?, code: String?, message: String?) = PatientEntity(
        id = "pat-1", fullName = "Asha Devi", dateOfBirth = null, age = 30, biologicalSex = "Female",
        guardianOrSpouseName = null, guardianRelation = null, mobileNumber = null,
        aadhaarNumber = null, abhaNumber = null, village = null, block = null, district = null,
        state = null, pincode = null, category = null, maritalStatus = null, bloodGroup = null,
        emergencyContact = null, primaryCareClinicName = null, referringPhysicianName = null,
        createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH, localModifiedAt = Instant.EPOCH,
        syncState = state, serverVersion = serverVersion, syncErrorCode = code, syncErrorMessage = message,
    )

    private fun case(state: SyncState, serverVersion: Int?) = CaseRecordEntity(
        id = "case-1", patientId = "pat-1", encounterId = "enc-1", status = CaseStatus.SENT_TO_DOCTOR,
        assignedDoctorId = null, createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
        localModifiedAt = Instant.EPOCH, syncState = state, serverVersion = serverVersion,
    )

    @Test
    fun everyColumnOfTheChainLandsWhereTheGateReadsIt() = runBlocking {
        db.patientDao().insert(patient(SyncState.FAILED, 2, "SAMD-SYNC-6003", "this record already exists with different data."))
        db.encounterDao().insert(
            EncounterEntity(
                id = "enc-1", patientId = "pat-1", startedAt = Instant.EPOCH, createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH, followUpOfEncounterId = null, localModifiedAt = Instant.EPOCH,
                syncState = SyncState.CONFLICT, serverVersion = 5, syncErrorCode = "enc-code", syncErrorMessage = "enc-message",
            ),
        )
        db.caseRecordDao().insert(case(SyncState.RETRYABLE, 7).copy(syncErrorCode = "case-code", syncErrorMessage = "case-message"))

        val snapshot = db.caseRecordDao().getAssessGateRow("case-1")?.toSnapshot()

        assertEquals(
            AssessGateSnapshot(
                caseRecord = SyncChainRow(SyncState.RETRYABLE, 7, "case-code", "case-message"),
                encounter = SyncChainRow(SyncState.CONFLICT, 5, "enc-code", "enc-message"),
                patient = SyncChainRow(SyncState.FAILED, 2, "SAMD-SYNC-6003", "this record already exists with different data."),
            ),
            snapshot,
        )
    }

    @Test
    fun missingParentsReadAsNull_andTheCaseStillComesBack() = runBlocking {
        db.caseRecordDao().insert(case(SyncState.PENDING, null))

        val snapshot = db.caseRecordDao().getAssessGateRow("case-1")?.toSnapshot()

        assertEquals(SyncChainRow(SyncState.PENDING, null, null, null), snapshot?.caseRecord)
        assertNull(snapshot?.encounter)
        assertNull(snapshot?.patient)
    }

    @Test
    fun noCaseRecordReadsAsNull() = runBlocking {
        assertNull(db.caseRecordDao().getAssessGateRow("case-missing"))
    }
}
