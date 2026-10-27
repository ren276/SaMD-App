package com.example.samdapp.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.samdapp.data.local.entity.AilmentEntity
import com.example.samdapp.data.local.entity.AuditLogEntity
import com.example.samdapp.data.local.entity.CaseRecordEntity
import com.example.samdapp.data.local.entity.EncounterEntity
import com.example.samdapp.data.local.entity.EvaluateReportEntity
import com.example.samdapp.data.local.entity.PatientEntity
import com.example.samdapp.data.sync.RoomSyncOutboxRepository
import com.example.samdapp.domain.model.CaseStatus
import com.example.samdapp.domain.model.MeasurementType
import com.example.samdapp.domain.model.SyncState
import com.example.samdapp.domain.model.Visibility
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Duration
import java.time.Instant

/**
 * FS-1(1): "everything sent" at sign-in (facility-scope memo 3.2) against real SQLite and the
 * drain's own collect queries, in the style of [CountDrainConsistencyTest]. Every row the drain
 * would still send blocks; a row nothing will resend on its own (FAILED, CONFLICT, held) blocks;
 * a SYNCED row does not; and a local-only evaluate marker (H-14, `SyncSql.EVALUATE_SYNCABLE`) is
 * never collected and never blocks.
 *
 * Shapes: a root (`patients`), a child that can be held (`ailments`), `audit_log`, and
 * `evaluate_reports`, the one table whose drain query adds a predicate of its own. The other
 * tables share these statement shapes, pinned as text by `SyncDaoSqlContractTest`. An in-memory
 * database: never the app's real one.
 */
class SignInOutboxConsistencyTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: RoomSyncOutboxRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
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

    private val now: Instant = Instant.now()
    private val afterInterval: Instant = now.plus(Duration.ofDays(1))

    private enum class Kind { PENDING, RETRY_INSIDE, HELD, FAILED, CONFLICT, SYNCED }

    private fun stateOf(kind: Kind) = when (kind) {
        Kind.PENDING, Kind.HELD -> SyncState.PENDING
        Kind.RETRY_INSIDE -> SyncState.RETRYABLE
        Kind.FAILED -> SyncState.FAILED
        Kind.CONFLICT -> SyncState.CONFLICT
        Kind.SYNCED -> SyncState.SYNCED
    }

    private fun attemptOf(kind: Kind): Instant? = if (kind == Kind.RETRY_INSIDE) now else null
    private fun versionOf(kind: Kind): Int? = if (kind == Kind.SYNCED) 1 else null

    // A healthy chain, plus with [refused] a patient FAILED and never on the server. The refused
    // patient blocks on its own, so only the held case seeds it.
    private suspend fun ancestors(refused: Boolean = false) {
        db.patientDao().insert(patient("pat-ok", SyncState.SYNCED, 1))
        if (refused) db.patientDao().insert(patient("pat-bad", SyncState.FAILED, null))
        db.encounterDao().insert(encounter("enc-ok", "pat-ok"))
    }

    private suspend fun everythingSent() = repository.readSignInOutbox().everythingSent

    @Test
    fun patients_onlyASyncedRowLeavesEverythingSent() = runBlocking {
        Kind.values().filter { it != Kind.HELD }.forEach { kind ->
            db.clearAllTables()
            db.patientDao().insert(patient("p", stateOf(kind), versionOf(kind), attemptOf(kind)))
            val collected = db.patientDao().getPendingForSync(afterInterval).isNotEmpty()
            assertEquals("patients $kind: the drain would send it", kind == Kind.PENDING || kind == Kind.RETRY_INSIDE, collected)
            assertEquals("patients $kind", kind == Kind.SYNCED, everythingSent())
        }
    }

    @Test
    fun ailments_aHeldRowIsNotCollectedButStillBlocks() = runBlocking {
        Kind.values().forEach { kind ->
            db.clearAllTables()
            ancestors(refused = kind == Kind.HELD)
            val patientId = if (kind == Kind.HELD) "pat-bad" else "pat-ok"
            db.ailmentDao().insert(ailment("a", patientId, stateOf(kind), versionOf(kind), attemptOf(kind)))
            val collected = db.ailmentDao().getPendingForSync(afterInterval).isNotEmpty()
            assertEquals("ailments $kind: the drain would send it", kind == Kind.PENDING || kind == Kind.RETRY_INSIDE, collected)
            assertEquals("ailments $kind", kind == Kind.SYNCED, everythingSent())
            if (kind == Kind.HELD) assertEquals("a held row is counted as held", 1, repository.readSignInOutbox().heldClinical)
        }
    }

    @Test
    fun auditLog_anUnsentOrRefusedAuditRowBlocks() = runBlocking {
        Kind.values().filter { it != Kind.HELD }.forEach { kind ->
            db.clearAllTables()
            db.auditLogDao().insert(audit("au", stateOf(kind), versionOf(kind), attemptOf(kind)))
            val collected = db.auditLogDao().getPendingForSync(afterInterval).isNotEmpty()
            assertEquals("audit_log $kind: the drain would send it", kind == Kind.PENDING || kind == Kind.RETRY_INSIDE, collected)
            assertEquals("audit_log $kind", kind == Kind.SYNCED, everythingSent())
        }
    }

    @Test
    fun evaluateReports_localOnlyMarkersNeverBlock_aRealReportDoes() = runBlocking {
        ancestors()
        listOf(SyncState.PENDING, SyncState.RETRYABLE, SyncState.FAILED, SyncState.CONFLICT).forEach { state ->
            val id = "marker-${state.name.lowercase()}"
            db.caseRecordDao().insert(caseRecord("case-$id", "pat-ok"))
            db.evaluateReportDao().upsert(evaluateReport(id, "case-$id", state, failureCode = "CASE_NOT_SENT_YET"))
        }
        assertTrue("the drain never collects a marker", db.evaluateReportDao().getPendingForSync(afterInterval).isEmpty())
        assertTrue("local-only markers are facility data, but never block", everythingSent())

        db.caseRecordDao().insert(caseRecord("case-real", "pat-ok"))
        db.evaluateReportDao().upsert(evaluateReport("real", "case-real", SyncState.PENDING, failureCode = null))
        assertEquals(listOf("real"), db.evaluateReportDao().getPendingForSync(afterInterval).map { it.id })
        assertFalse("a real report the drain would send blocks", everythingSent())
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────

    private fun patient(id: String, state: SyncState, serverVersion: Int?, lastAttempt: Instant? = null) = PatientEntity(
        id = id, fullName = "Asha Devi", dateOfBirth = null, age = 30, biologicalSex = "Female",
        guardianOrSpouseName = null, guardianRelation = null, mobileNumber = null,
        aadhaarNumber = null, abhaNumber = null, village = null, block = null, district = null,
        state = null, pincode = null, category = null, maritalStatus = null, bloodGroup = null,
        emergencyContact = null, primaryCareClinicName = null, referringPhysicianName = null,
        createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH, localModifiedAt = Instant.EPOCH,
        syncState = state, serverVersion = serverVersion, lastSyncAttemptAt = lastAttempt,
    )

    private fun encounter(id: String, patientId: String) = EncounterEntity(
        id = id, patientId = patientId, startedAt = Instant.EPOCH, createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH, followUpOfEncounterId = null, localModifiedAt = Instant.EPOCH,
        syncState = SyncState.SYNCED, serverVersion = 1,
    )

    private fun caseRecord(id: String, patientId: String) = CaseRecordEntity(
        id = id, patientId = patientId, encounterId = "enc-ok", status = CaseStatus.DRAFT,
        assignedDoctorId = null, createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
        localModifiedAt = Instant.EPOCH, syncState = SyncState.SYNCED, serverVersion = 1,
    )

    private fun ailment(id: String, patientId: String, state: SyncState, serverVersion: Int?, lastAttempt: Instant?) = AilmentEntity(
        id = id, patientId = patientId, encounterId = "enc-ok", description = "cough",
        measurementType = MeasurementType.NON_MEASURABLE, visibility = Visibility.PUBLIC,
        measuredValue = null, measuredUnit = null, severity = null, onset = null, duration = null,
        qualifiers = null, audioLocalUri = null, capturedAtOffline = Instant.EPOCH, syncedToCloudAt = null,
        deletedAt = null, createdAt = Instant.EPOCH, localModifiedAt = Instant.EPOCH,
        syncState = state, serverVersion = serverVersion, lastSyncAttemptAt = lastAttempt,
    )

    private fun audit(id: String, state: SyncState, serverVersion: Int?, lastAttempt: Instant?) = AuditLogEntity(
        id = id, timestamp = Instant.EPOCH, userId = "u1", patientId = null, caseRecordId = null,
        action = "login", payload = "{}", syncState = state, serverVersion = serverVersion,
        lastSyncAttemptAt = lastAttempt, localModifiedAt = Instant.EPOCH,
    )

    private fun evaluateReport(id: String, caseRecordId: String, state: SyncState, failureCode: String?) = EvaluateReportEntity(
        id = id, caseRecordId = caseRecordId, payloadJson = "{}", inferenceStartedAt = Instant.EPOCH,
        inferenceEndedAt = Instant.EPOCH, failureCode = failureCode, syncState = state,
        serverVersion = null, lastSyncAttemptAt = if (state == SyncState.RETRYABLE) now.minus(Duration.ofHours(1)) else null,
        localModifiedAt = Instant.EPOCH,
    )
}
