package com.example.samdapp.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.samdapp.data.local.dao.SyncStateCount
import com.example.samdapp.data.local.entity.AuditLogEntity
import com.example.samdapp.data.local.entity.PatientEntity
import com.example.samdapp.data.sync.OutboxCounts
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
 * The grouped state counter against real SQLite, and the fold Home reads, end to end.
 *
 * `patients` stands in for the twenty counters: they are one statement shape, pinned as text by
 * `SyncDaoSqlContractTest`. What only SQLite can show is that the GROUP BY returns one row per
 * non-SYNCED state with the right count, that SYNCED is excluded, and that Room maps the
 * projection onto [SyncStateCount] (a misnamed alias on a non-null field fails the build, a
 * wrong count does not).
 */
class OutboxCountQueryTest {

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

    private fun patient(id: String, state: SyncState) = PatientEntity(
        id = id, fullName = "P $id", dateOfBirth = null, age = 30, biologicalSex = "Female",
        guardianOrSpouseName = null, guardianRelation = null, mobileNumber = null,
        aadhaarNumber = null, abhaNumber = null, village = null, block = null, district = null,
        state = null, pincode = null, category = null, maritalStatus = null, bloodGroup = null,
        emergencyContact = null, primaryCareClinicName = null, referringPhysicianName = null,
        createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH, localModifiedAt = Instant.EPOCH,
        syncState = state,
    )

    private suspend fun seed() {
        listOf(
            "p1" to SyncState.PENDING, "p2" to SyncState.PENDING, "p3" to SyncState.RETRYABLE,
            "p4" to SyncState.SYNCED, "p5" to SyncState.SYNCED, "p6" to SyncState.FAILED,
            "p7" to SyncState.CONFLICT, "p8" to SyncState.CONFLICT,
        ).forEach { (id, state) -> db.patientDao().insert(patient(id, state)) }
    }

    @Test
    fun groupedCounter_returnsOneRowPerUnsyncedState_andExcludesSynced() = runBlocking {
        seed()

        val counts = db.patientDao().observeSyncStateCounts().first().toSet()

        assertEquals(
            setOf(
                SyncStateCount(SyncState.PENDING, held = false, rowCount = 2),
                SyncStateCount(SyncState.RETRYABLE, held = false, rowCount = 1),
                SyncStateCount(SyncState.FAILED, held = false, rowCount = 1),
                SyncStateCount(SyncState.CONFLICT, held = false, rowCount = 2),
            ),
            counts,
        )
    }

    @Test
    fun oneShotCounter_matchesTheObservedCounter() = runBlocking {
        seed()

        assertEquals(
            db.patientDao().observeSyncStateCounts().first().toSet(),
            db.patientDao().getSyncStateCounts().toSet(),
        )
    }

    @Test
    fun repository_foldsClinicalAndAuditCountsForHome() = runBlocking {
        seed()
        db.auditLogDao().insert(
            AuditLogEntity(
                id = "a1", timestamp = Instant.EPOCH, userId = "u1", patientId = null, caseRecordId = null,
                action = "login", payload = "{}", localModifiedAt = Instant.EPOCH,
            ),
        )
        val repository = RoomSyncOutboxRepository(
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

        assertEquals(
            OutboxCounts(pendingClinical = 3, pendingAudit = 1, needsReview = 3),
            repository.observeOutboxCounts().first(),
        )
        assertEquals(
            "the one-shot read the Sync now message uses must agree with the Flow",
            OutboxCounts(pendingClinical = 3, pendingAudit = 1, needsReview = 3),
            repository.readOutboxCounts(),
        )
    }
}
