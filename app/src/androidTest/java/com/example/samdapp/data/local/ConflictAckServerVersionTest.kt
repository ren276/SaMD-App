package com.example.samdapp.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.samdapp.data.local.entity.PatientEntity
import com.example.samdapp.data.remote.dto.SyncResultDto
import com.example.samdapp.data.sync.RoomSyncOutboxRepository
import com.example.samdapp.domain.model.SyncState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * A `conflict` ack must never move a row's `serverVersion`, read back from real SQLite.
 *
 * The row keeps the `base_version` it was written on. If a conflict ack ever carried a
 * `server_version` and the device adopted it, any later resend of that row would carry a
 * matching `base_version`, pass rule 4 of `_resolve_write` (api-contract.md section 6.1) and
 * overwrite the newer server data the conflict was protecting. Today the backend's conflict
 * result happens to carry no `server_version`, so the DAO's `COALESCE` keeps the old value by
 * accident of wire shape; this test pins the device's own guarantee instead.
 *
 * Driven through [RoomSyncOutboxRepository.applyAck], the single place every ack is mapped, so
 * the guarantee holds for all twenty tables at once. `patients` stands in for them: every
 * `applySyncResult` is the same statement, pinned by `SyncDaoSqlContractTest`.
 */
class ConflictAckServerVersionTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: RoomSyncOutboxRepository

    private val sentAt = Instant.ofEpochMilli(5_000)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).build()
        repository = RoomSyncOutboxRepository(
            patientDao = db.patientDao(), encounterDao = db.encounterDao(),
            consultationDao = db.consultationDao(), attachmentDao = db.attachmentDao(),
            observationDao = db.observationDao(), ailmentDao = db.ailmentDao(),
            medicalHistoryItemDao = db.medicalHistoryItemDao(), allergyDao = db.allergyDao(),
            familyHistoryEntryDao = db.familyHistoryEntryDao(), socialHistoryDao = db.socialHistoryDao(),
            medicationEntryDao = db.medicationEntryDao(), caseRecordDao = db.caseRecordDao(),
            kernelReportDao = db.kernelReportDao(), evaluateReportDao = db.evaluateReportDao(),
            diagnosisFeedbackDao = db.diagnosisFeedbackDao(), prescriptionDao = db.prescriptionDao(),
            referralDao = db.referralDao(), abhaProfileDao = db.abhaProfileDao(),
            auditLogDao = db.auditLogDao(),
        )
    }

    @After
    fun tearDown() = db.close()

    private suspend fun insertPatient(state: SyncState, serverVersion: Int?) = db.patientDao().insert(
        PatientEntity(
            id = "pat-1", fullName = "Asha Devi", dateOfBirth = null, age = 30, biologicalSex = "Female",
            guardianOrSpouseName = null, guardianRelation = null, mobileNumber = null,
            aadhaarNumber = null, abhaNumber = null, village = null, block = null, district = null,
            state = null, pincode = null, category = null, maritalStatus = null, bloodGroup = null,
            emergencyContact = null, primaryCareClinicName = null, referringPhysicianName = null,
            createdAt = Instant.EPOCH, updatedAt = sentAt, localModifiedAt = sentAt,
            syncState = state, serverVersion = serverVersion,
        ),
    )

    private suspend fun stored() = db.patientDao().observeById("pat-1").first()!!

    @Test
    fun conflictAck_carryingAServerVersion_leavesTheStoredVersionUntouched() = runBlocking {
        insertPatient(SyncState.PENDING, serverVersion = 3)

        repository.applyAck(
            SyncResultDto(table = "patients", id = "pat-1", status = "conflict", serverVersion = 7),
            sentLocalModifiedAt = sentAt,
        )

        val row = stored()
        assertEquals(SyncState.CONFLICT, row.syncState)
        assertEquals("a conflict ack must never arm rule 4 by adopting the server's version", 3, row.serverVersion)
    }

    @Test
    fun appliedAck_stillAdoptsTheServerVersion() = runBlocking {
        // Control: the guard is specific to CONFLICT and must not stop a normal ack.
        insertPatient(SyncState.PENDING, serverVersion = 3)

        repository.applyAck(
            SyncResultDto(table = "patients", id = "pat-1", status = "applied", serverVersion = 7),
            sentLocalModifiedAt = sentAt,
        )

        val row = stored()
        assertEquals(SyncState.SYNCED, row.syncState)
        assertEquals(7, row.serverVersion)
    }

    @Test
    fun requeueFailed_onAConflictRow_changesNothing() = runBlocking {
        // Characterization of the existing FAILED-only guard: "Send again" cannot pull a
        // CONFLICT row back into the queue with its stale base_version.
        insertPatient(SyncState.CONFLICT, serverVersion = 3)

        repository.requeueFailed("patients", "pat-1")

        val row = stored()
        assertEquals(SyncState.CONFLICT, row.syncState)
        assertEquals(3, row.serverVersion)
    }
}
