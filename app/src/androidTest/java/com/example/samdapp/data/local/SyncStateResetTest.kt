package com.example.samdapp.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.samdapp.data.local.entity.AbhaProfileEntity
import com.example.samdapp.data.local.entity.AilmentEntity
import com.example.samdapp.data.local.entity.CaseRecordEntity
import com.example.samdapp.data.local.entity.ConsultationEntity
import com.example.samdapp.data.local.entity.DiagnosisFeedbackEntity
import com.example.samdapp.data.local.entity.EvaluateReportEntity
import com.example.samdapp.data.local.entity.KernelReportEntity
import com.example.samdapp.data.local.entity.ReferralEntity
import com.example.samdapp.data.local.entity.SocialHistoryEntity
import com.example.samdapp.domain.model.CaseStatus
import com.example.samdapp.domain.model.InferenceSource
import com.example.samdapp.domain.model.MeasurementType
import com.example.samdapp.domain.model.PhysicianDecision
import com.example.samdapp.domain.model.ReferralStatus
import com.example.samdapp.domain.model.RiskCategory
import com.example.samdapp.domain.model.SyncState
import com.example.samdapp.domain.model.UrgencyLevel
import com.example.samdapp.domain.model.Visibility
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * `syncstate-reset` session: proves every syncable clinical mutation found by the S1 audit
 * (PROGRESS.md) resets `syncState` back to `PENDING` on an already-`SYNCED` row, in the same
 * statement that bumps `localModifiedAt`, while leaving `serverVersion` untouched — a re-edited
 * row must re-drain (6b's outbox only ever selects `syncState = 'PENDING'`) without looking
 * never-synced to the backend's conflict logic. Also proves the mirror image: 6b's own
 * `applySyncResult` (the sync-machinery ack path) never sets `PENDING`, so a future edit to these
 * DAOs can't accidentally wire the two together into an infinite drain.
 */
class SyncStateResetTest {

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

    private suspend fun <T> firstValue(observe: (String) -> Flow<T>, key: String): T = observe(key).first()

    private suspend fun <T> firstList(observe: (String) -> Flow<List<T>>, key: String): List<T> = observe(key).first()

    // --- CaseRecordDao: updateStatus, assignDoctor, abandonDraftsForPatient, sendAllPendingSync ---

    private fun caseRecord(
        id: String,
        status: CaseStatus = CaseStatus.DRAFT,
        assignedDoctorId: String? = null,
        patientId: String = "pat-1",
    ) = CaseRecordEntity(
        id = id, patientId = patientId, encounterId = "enc-1", status = status,
        assignedDoctorId = assignedDoctorId, createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
        syncState = SyncState.SYNCED, serverVersion = 7, localModifiedAt = Instant.EPOCH,
    )

    @Test
    fun caseRecordDao_updateStatus_resetsSyncStateAndPreservesServerVersion() = runBlocking {
        val dao = db.caseRecordDao()
        dao.insert(caseRecord("case-1"))

        dao.updateStatus("case-1", CaseStatus.SENT_TO_DOCTOR, Instant.ofEpochMilli(5000))

        val updated = requireNotNull(firstValue(dao::observeById, "case-1"))
        assertEquals(SyncState.PENDING, updated.syncState)
        assertEquals(Instant.ofEpochMilli(5000), updated.localModifiedAt)
        assertEquals(7, updated.serverVersion)
        assertEquals(CaseStatus.SENT_TO_DOCTOR, updated.status)
    }

    @Test
    fun caseRecordDao_assignDoctor_resetsSyncStateAndPreservesServerVersion() = runBlocking {
        val dao = db.caseRecordDao()
        dao.insert(caseRecord("case-1"))

        dao.assignDoctor("case-1", "doc-1", CaseStatus.SENT_TO_DOCTOR, Instant.ofEpochMilli(5000))

        val updated = requireNotNull(firstValue(dao::observeById, "case-1"))
        assertEquals(SyncState.PENDING, updated.syncState)
        assertEquals(Instant.ofEpochMilli(5000), updated.localModifiedAt)
        assertEquals(7, updated.serverVersion)
        assertEquals("doc-1", updated.assignedDoctorId)
    }

    @Test
    fun caseRecordDao_abandonDraftsForPatient_resetsSyncStateAndPreservesServerVersion() = runBlocking {
        val dao = db.caseRecordDao()
        dao.insert(caseRecord("case-1", status = CaseStatus.DRAFT, patientId = "pat-1"))

        dao.abandonDraftsForPatient("pat-1", Instant.ofEpochMilli(5000))

        val updated = requireNotNull(firstValue(dao::observeById, "case-1"))
        assertEquals(SyncState.PENDING, updated.syncState)
        assertEquals(Instant.ofEpochMilli(5000), updated.localModifiedAt)
        assertEquals(7, updated.serverVersion)
        assertEquals(CaseStatus.ABANDONED, updated.status)
    }

    @Test
    fun caseRecordDao_sendAllPendingSync_resetsSyncStateAndPreservesServerVersion() = runBlocking {
        val dao = db.caseRecordDao()
        dao.insert(caseRecord("case-1", status = CaseStatus.PENDING_SYNC))

        dao.sendAllPendingSync(Instant.ofEpochMilli(5000))

        val updated = requireNotNull(firstValue(dao::observeById, "case-1"))
        assertEquals(SyncState.PENDING, updated.syncState)
        assertEquals(Instant.ofEpochMilli(5000), updated.localModifiedAt)
        assertEquals(7, updated.serverVersion)
        assertEquals(CaseStatus.SENT_TO_DOCTOR, updated.status)
    }

    @Test
    fun caseRecordDao_applySyncResult_neverSetsPending_guardAgainstTheInfiniteDrainBug() = runBlocking {
        val dao = db.caseRecordDao()
        dao.insert(caseRecord("case-1").copy(syncState = SyncState.PENDING, localModifiedAt = Instant.ofEpochMilli(1000)))

        dao.applySyncResult(
            "case-1", SyncState.SYNCED, serverVersion = 1, syncErrorCode = null,
            attemptAt = Instant.now(), sentLocalModifiedAt = Instant.ofEpochMilli(1000),
        )

        val updated = requireNotNull(firstValue(dao::observeById, "case-1"))
        assertEquals(SyncState.SYNCED, updated.syncState)
        assertTrue("applySyncResult must never leave a row PENDING", updated.syncState != SyncState.PENDING)
    }

    /** The composition this whole session exists for: [CaseRecordDao.updateStatus] (syncState-
     *  reset) and [CaseRecordDao.applySyncResult]'s revision guard (PR#6 review fix, CodeRabbit
     *  finding #1) must work together, not just individually. A row already `SYNCED` is
     *  clinically re-edited (resets to `PENDING` with a fresh `localModifiedAt`) *before* the
     *  in-flight batch that sent the OLD content gets acked. That late ack still carries the OLD
     *  `sentLocalModifiedAt` (the revision as of when it was packed, per InFlightBatchMember).
     *  If the two mechanisms didn't compose, this ack would stamp the row `SYNCED` for content
     *  the server never received, and the outbox (which only drains `PENDING`) would never send
     *  the edit — exactly the record-integrity gap this session and PR#6 exist to close, from two
     *  different directions at once. */
    @Test
    fun aClinicalEditDuringAnInFlightSync_isNeverLost_lateAckForTheStaleRevisionLeavesRowPending() = runBlocking {
        val dao = db.caseRecordDao()
        val sentAt = Instant.ofEpochMilli(1000)
        dao.insert(caseRecord("case-1").copy(syncState = SyncState.PENDING, serverVersion = null, localModifiedAt = sentAt))

        // Outbox packs and sends the row at localModifiedAt = sentAt (its ack is still in flight).
        // Before that ack lands, a worker edits the case clinically -- syncState resets to
        // PENDING, localModifiedAt advances past what was actually sent.
        val editedAt = Instant.ofEpochMilli(2000)
        dao.updateStatus("case-1", CaseStatus.SENT_TO_DOCTOR, editedAt)
        val afterEdit = requireNotNull(firstValue(dao::observeById, "case-1"))
        assertEquals(SyncState.PENDING, afterEdit.syncState)
        assertEquals(editedAt, afterEdit.localModifiedAt)

        // The late ack for the pre-edit send arrives, guarded on the OLD sentLocalModifiedAt.
        dao.applySyncResult(
            "case-1", SyncState.SYNCED, serverVersion = 1, syncErrorCode = null,
            attemptAt = Instant.now(), sentLocalModifiedAt = sentAt,
        )

        val afterLateAck = requireNotNull(firstValue(dao::observeById, "case-1"))
        assertEquals(
            "a late ack for a stale revision must not stamp the row SYNCED once it has been re-edited",
            SyncState.PENDING,
            afterLateAck.syncState,
        )
        assertEquals(editedAt, afterLateAck.localModifiedAt)
        assertEquals(CaseStatus.SENT_TO_DOCTOR, afterLateAck.status)
    }

    // --- ConsultationDao.updateTranscription ---

    @Test
    fun consultationDao_updateTranscription_resetsSyncStateAndPreservesServerVersion() = runBlocking {
        val dao = db.consultationDao()
        dao.insert(
            ConsultationEntity(
                id = "cons-1", patientId = "pat-1", encounterId = "enc-1", chiefComplaint = "Fever",
                onset = null, durationBucket = null, severityScore = null, aggravatingFactors = null,
                relievingFactors = null, impactOnDailyActivities = null, relevantHistory = null,
                transcription = "original", createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
                syncState = SyncState.SYNCED, serverVersion = 3, localModifiedAt = Instant.EPOCH,
            ),
        )

        dao.updateTranscription("cons-1", "edited after first sync", Instant.ofEpochMilli(5000))

        val updated = requireNotNull(firstValue(dao::observeForEncounter, "enc-1"))
        assertEquals(SyncState.PENDING, updated.syncState)
        assertEquals(Instant.ofEpochMilli(5000), updated.localModifiedAt)
        assertEquals(3, updated.serverVersion)
        assertEquals("edited after first sync", updated.transcription)
    }

    // --- AilmentDao.markDeleted ---

    @Test
    fun ailmentDao_markDeleted_resetsSyncStateAndPreservesServerVersion() = runBlocking {
        val dao = db.ailmentDao()
        dao.insert(
            AilmentEntity(
                id = "ail-1", patientId = "pat-1", encounterId = "enc-1", description = "Headache",
                measurementType = MeasurementType.NON_MEASURABLE, visibility = Visibility.PRIVATE,
                measuredValue = null, measuredUnit = null, severity = null, onset = null, duration = null,
                qualifiers = null, audioLocalUri = null, capturedAtOffline = Instant.EPOCH,
                syncedToCloudAt = null, deletedAt = null, createdAt = Instant.EPOCH,
                syncState = SyncState.SYNCED, serverVersion = 2, localModifiedAt = Instant.EPOCH,
            ),
        )

        dao.markDeleted("ail-1", Instant.ofEpochMilli(5000))

        // observeForEncounter excludes soft-deleted rows by design (its own KDoc); read the row
        // back via getPendingForSync instead, which conveniently also proves the PENDING reset.
        val updated = dao.getPendingForSync().single { it.id == "ail-1" }
        assertEquals(SyncState.PENDING, updated.syncState)
        assertEquals(Instant.ofEpochMilli(5000), updated.localModifiedAt)
        assertEquals(2, updated.serverVersion)
        assertEquals(Instant.ofEpochMilli(5000), updated.deletedAt)
    }

    // --- ReferralDao.updateStatus ---

    @Test
    fun referralDao_updateStatus_resetsSyncStateAndPreservesServerVersion() = runBlocking {
        val dao = db.referralDao()
        dao.insert(
            ReferralEntity(
                id = "ref-1", patientUid = "pat-1", caseRecordId = "case-1",
                urgencyLevel = UrgencyLevel.ROUTINE, reason = "Specialist opinion",
                sendingPhcId = "PHC-001", status = ReferralStatus.QUEUED, timestamp = Instant.EPOCH,
                syncState = SyncState.SYNCED, serverVersion = 4, localModifiedAt = Instant.EPOCH,
            ),
        )

        dao.updateStatus("ref-1", ReferralStatus.SENT, Instant.ofEpochMilli(5000))

        val updated = firstList(dao::observeForCase, "case-1").single()
        assertEquals(SyncState.PENDING, updated.syncState)
        assertEquals(Instant.ofEpochMilli(5000), updated.localModifiedAt)
        assertEquals(4, updated.serverVersion)
        assertEquals(ReferralStatus.SENT, updated.status)
    }

    // --- REPLACE-upsert paths: serverVersion preserved via DAO's getServerVersion + repository read ---

    @Test
    fun socialHistoryDao_replaceUpsert_defaultsToPending_andServerVersionMustBeReadFirst() = runBlocking {
        val dao = db.socialHistoryDao()
        dao.upsert(
            SocialHistoryEntity(
                patientId = "pat-1", occupation = "Farmer", tobaccoUse = "NEVER", alcoholUse = "NEVER",
                recreationalDrugUse = null, environmentalExposure = null, recentTravel = null,
                updatedAt = Instant.EPOCH, syncState = SyncState.SYNCED, serverVersion = 9,
                localModifiedAt = Instant.EPOCH,
            ),
        )

        // Simulates MedicalBackgroundRepositoryImpl.upsertSocialHistory: read serverVersion first,
        // thread it through the replacement (mirrors the real fix, proves the DAO seam works).
        val existingServerVersion = dao.getServerVersion("pat-1")
        assertEquals(9, existingServerVersion)
        dao.upsert(
            SocialHistoryEntity(
                patientId = "pat-1", occupation = "Farmer", tobaccoUse = "FORMER", alcoholUse = "NEVER",
                recreationalDrugUse = null, environmentalExposure = null, recentTravel = null,
                updatedAt = Instant.ofEpochMilli(5000), localModifiedAt = Instant.ofEpochMilli(5000),
                serverVersion = existingServerVersion,
            ),
        )

        val updated = requireNotNull(firstValue(dao::observeForPatient, "pat-1"))
        assertEquals(SyncState.PENDING, updated.syncState) // REPLACE's fresh entity default
        assertEquals(Instant.ofEpochMilli(5000), updated.localModifiedAt)
        assertEquals(9, updated.serverVersion) // preserved via the getServerVersion read
        assertEquals("FORMER", updated.tobaccoUse)
    }

    @Test
    fun kernelReportDao_replaceUpsert_defaultsToPending_andServerVersionMustBeReadFirst() = runBlocking {
        val dao = db.kernelReportDao()
        dao.upsert(kernelReport("kr-1", syncState = SyncState.SYNCED, serverVersion = 6))

        val existingServerVersion = dao.getServerVersion("kr-1")
        assertEquals(6, existingServerVersion)
        dao.upsert(kernelReport("kr-1", predictedCondition = "Revised diagnosis", serverVersion = existingServerVersion))

        val updated = requireNotNull(firstValue(dao::observeForCase, "case-1"))
        assertEquals(SyncState.PENDING, updated.syncState)
        assertEquals(6, updated.serverVersion)
        assertEquals("Revised diagnosis", updated.predictedCondition)
    }

    private fun kernelReport(
        id: String,
        predictedCondition: String = "Viral fever",
        syncState: SyncState = SyncState.PENDING,
        serverVersion: Int? = null,
    ) = KernelReportEntity(
        id = id, caseRecordId = "case-1", predictedCondition = predictedCondition, confidenceScore = 0.8,
        differentials = emptyList(), reasoningSummary = "reasoning", evidenceFor = emptyList(),
        evidenceAgainst = emptyList(), modelVersion = "v1", icdCode = null, deviceId = "dev-1",
        softwareVersion = "1.0", dataQualityScore = null, uncertaintyScore = null,
        riskCategory = RiskCategory.MODERATE, urgencyLevel = UrgencyLevel.ROUTINE,
        inferenceStartedAt = Instant.EPOCH, inferenceEndedAt = Instant.EPOCH,
        requiredHumanVerification = false, inferenceSource = InferenceSource.MOCK_FALLBACK,
        syncState = syncState, serverVersion = serverVersion, localModifiedAt = Instant.ofEpochMilli(5000),
    )

    @Test
    fun evaluateReportDao_replaceUpsert_defaultsToPending_andServerVersionMustBeReadFirst() = runBlocking {
        val dao = db.evaluateReportDao()
        dao.upsert(
            EvaluateReportEntity(
                id = "ev-1", caseRecordId = "case-1", payloadJson = "{}", inferenceStartedAt = Instant.EPOCH,
                inferenceEndedAt = Instant.EPOCH, syncState = SyncState.SYNCED, serverVersion = 11,
                localModifiedAt = Instant.EPOCH,
            ),
        )

        val existingServerVersion = dao.getServerVersion("ev-1")
        assertEquals(11, existingServerVersion)
        dao.upsert(
            EvaluateReportEntity(
                id = "ev-1", caseRecordId = "case-1", payloadJson = "{\"revised\":true}",
                inferenceStartedAt = Instant.EPOCH, inferenceEndedAt = Instant.EPOCH,
                localModifiedAt = Instant.ofEpochMilli(5000), serverVersion = existingServerVersion,
            ),
        )

        val updated = requireNotNull(firstValue(dao::observeForCase, "case-1"))
        assertEquals(SyncState.PENDING, updated.syncState)
        assertEquals(11, updated.serverVersion)
        assertEquals("{\"revised\":true}", updated.payloadJson)
    }

    @Test
    fun diagnosisFeedbackDao_replaceUpsert_defaultsToPending_andServerVersionMustBeReadFirst() = runBlocking {
        val dao = db.diagnosisFeedbackDao()
        dao.upsert(
            DiagnosisFeedbackEntity(
                id = "df-1", caseRecordId = "case-1", icdCandidate = "J11",
                physicianDecision = PhysicianDecision.AGREE, physicianFinalDiagnosis = null,
                clinicalNote = null, createdAt = Instant.EPOCH, syncState = SyncState.SYNCED,
                serverVersion = 2, localModifiedAt = Instant.EPOCH,
            ),
        )

        val existingServerVersion = dao.getServerVersion("df-1")
        assertEquals(2, existingServerVersion)
        dao.upsert(
            DiagnosisFeedbackEntity(
                id = "df-1", caseRecordId = "case-1", icdCandidate = "J11",
                physicianDecision = PhysicianDecision.MODIFY, physicianFinalDiagnosis = "J12",
                clinicalNote = "revised", createdAt = Instant.EPOCH,
                localModifiedAt = Instant.ofEpochMilli(5000), serverVersion = existingServerVersion,
            ),
        )

        val updated = requireNotNull(firstValue(dao::observeForCase, "case-1"))
        assertEquals(SyncState.PENDING, updated.syncState)
        assertEquals(2, updated.serverVersion)
        assertEquals(PhysicianDecision.MODIFY, updated.physicianDecision)
    }

    @Test
    fun abhaProfileDao_replaceUpsert_defaultsToPending_andServerVersionMustBeReadFirst() = runBlocking {
        val dao = db.abhaProfileDao()
        dao.upsert(
            AbhaProfileEntity(
                abhaId = "abha-1", abhaAddress = null, name = "Sunita Devi", dateOfBirth = null,
                gender = "FEMALE", address = null, district = "Sitapur", state = "UP", pincode = "261001",
                mobileNumber = null, emailAddress = null, photoUrlMock = null, kycVerified = true,
                createdAt = Instant.EPOCH, syncState = SyncState.SYNCED, serverVersion = 5,
                localModifiedAt = Instant.EPOCH,
            ),
        )

        val existingServerVersion = dao.getByAbhaId("abha-1")?.serverVersion
        assertEquals(5, existingServerVersion)
        dao.upsert(
            AbhaProfileEntity(
                abhaId = "abha-1", abhaAddress = "sunita@abdm", name = "Sunita Devi", dateOfBirth = null,
                gender = "FEMALE", address = null, district = "Sitapur", state = "UP", pincode = "261001",
                mobileNumber = null, emailAddress = null, photoUrlMock = null, kycVerified = true,
                createdAt = Instant.EPOCH, localModifiedAt = Instant.ofEpochMilli(5000),
                serverVersion = existingServerVersion,
            ),
        )

        val updated = requireNotNull(dao.getByAbhaId("abha-1"))
        assertEquals(SyncState.PENDING, updated.syncState)
        assertEquals(5, updated.serverVersion)
        assertEquals("sunita@abdm", updated.abhaAddress)
    }
}
