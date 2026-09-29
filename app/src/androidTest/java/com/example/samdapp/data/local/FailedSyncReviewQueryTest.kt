package com.example.samdapp.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.samdapp.data.local.entity.AbhaProfileEntity
import com.example.samdapp.data.local.entity.CaseRecordEntity
import com.example.samdapp.data.local.entity.KernelReportEntity
import com.example.samdapp.data.local.entity.PatientEntity
import com.example.samdapp.data.local.entity.SocialHistoryEntity
import com.example.samdapp.domain.model.CaseStatus
import com.example.samdapp.domain.model.InferenceSource
import com.example.samdapp.domain.model.RiskCategory
import com.example.samdapp.domain.model.SyncState
import com.example.samdapp.domain.model.UrgencyLevel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * S-3's twenty `getFailedForReview()` statements, against real SQLite.
 *
 * **Five tables, not twenty, and that is deliberate.** The twenty statements are the same
 * statement in four structural shapes, and only the shape can be wrong:
 *
 * 1. `patients` — the direct-column shape, and the one table whose id IS the patient id.
 *    Fourteen of the twenty are this shape.
 * 2. `kernel_reports` — the one-hop join shape, reaching its patient through `case_records`.
 *    Five of the twenty are this shape (`attachments`, the three report tables,
 *    `medication_lines`), differing only in which parent table they join.
 * 3. `abha_profiles` — the no-patient shape. `CAST(NULL AS TEXT)`, which is the one expression
 *    in the set that SQLite could plausibly disagree with Room about.
 * 4. `social_histories` — the aliased-primary-key shape, where `recordId` is `patientId`,
 *    because that is the id `requeueFailed` dispatches on for this table.
 *
 * Writing all twenty out would be four assertions and sixteen copies of them, and the sixteen
 * would each need a fully-populated entity for a table whose shape is already covered. What the
 * remaining sixteen DO need is a guarantee that they say the same thing, and that is
 * `SyncDaoSqlContractTest`, which reads all twenty as text in the JVM suite.
 *
 * **NOT RUN.** No device or emulator is attached in this environment, the same status as
 * `MigrationTest19To20` (S-4) and `MigrationTest20To21` (S-2). Written and compiled.
 */
class FailedSyncReviewQueryTest {

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

    private fun patient(id: String, name: String) = PatientEntity(
        id = id, fullName = name, dateOfBirth = null, age = 30, biologicalSex = "Female",
        guardianOrSpouseName = null, guardianRelation = null, mobileNumber = null,
        aadhaarNumber = null, abhaNumber = null, village = null, block = null, district = null,
        state = null, pincode = null, category = null, maritalStatus = null, bloodGroup = null,
        emergencyContact = null, primaryCareClinicName = null, referringPhysicianName = null,
        createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH, localModifiedAt = Instant.EPOCH,
    )

    @Test
    fun patientsDao_returnsOnlyFailedRows_withItsOwnIdAsThePatient() = runBlocking {
        val dao = db.patientDao()
        dao.insert(patient("pat-failed", "Asha Devi").copy(
            syncState = SyncState.FAILED,
            syncErrorCode = "SAMD-SYNC-6003",
            syncErrorMessage = "this record already exists with different data.",
            localModifiedAt = Instant.ofEpochMilli(9000),
        ))
        dao.insert(patient("pat-pending", "B").copy(syncState = SyncState.PENDING))
        dao.insert(patient("pat-synced", "C").copy(syncState = SyncState.SYNCED))
        // The decision this row exists to pin: a RETRYABLE row is still being worked on by the
        // device and must not reach a worker. If it appears here, the count on the Home card and
        // the list it opens disagree, and the worker is shown a row that needs nothing from them.
        dao.insert(patient("pat-retryable", "D").copy(syncState = SyncState.RETRYABLE))

        val rows = dao.getFailedForReview()

        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals("patients", row.tableName)
        assertEquals("pat-failed", row.recordId)
        assertEquals("the patients row's own id is the patient id", "pat-failed", row.patientId)
        assertEquals(Instant.ofEpochMilli(9000), row.recordedAt)
        assertEquals("SAMD-SYNC-6003", row.syncErrorCode)
        assertEquals("this record already exists with different data.", row.syncErrorMessage)
    }

    @Test
    fun kernelReportsDao_reachesThePatientThroughItsCaseRecord() = runBlocking {
        db.patientDao().insert(patient("pat-1", "Sunita Devi"))
        db.caseRecordDao().insert(
            CaseRecordEntity(
                id = "case-1", patientId = "pat-1", encounterId = "enc-1", status = CaseStatus.DRAFT,
                assignedDoctorId = null, createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
                localModifiedAt = Instant.EPOCH,
            ),
        )
        // kernel_reports has a UNIQUE index on caseRecordId and upsert is REPLACE, so a second
        // report for case-1 would delete kr-1. The PENDING report belongs to its own case.
        db.caseRecordDao().insert(
            CaseRecordEntity(
                id = "case-2", patientId = "pat-1", encounterId = "enc-2", status = CaseStatus.DRAFT,
                assignedDoctorId = null, createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
                localModifiedAt = Instant.EPOCH,
            ),
        )
        db.kernelReportDao().upsert(kernelReport("kr-1", "case-1", SyncState.FAILED))
        db.kernelReportDao().upsert(kernelReport("kr-2", "case-2", SyncState.PENDING))

        val rows = db.kernelReportDao().getFailedForReview()

        assertEquals(1, rows.size)
        assertEquals("kernel_reports", rows.single().tableName)
        assertEquals("kr-1", rows.single().recordId)
        assertEquals("the join must resolve the patient one hop up", "pat-1", rows.single().patientId)
    }

    @Test
    fun kernelReportsDao_stillReturnsTheRowWhenItsCaseRecordIsMissing() = runBlocking {
        // A LEFT JOIN, not a JOIN, and this is why: an orphaned report is exactly the kind of
        // record that fails to sync, and an inner join would drop it from the list that exists
        // to show it.
        db.kernelReportDao().upsert(kernelReport("kr-1", "case-gone", SyncState.FAILED))

        val rows = db.kernelReportDao().getFailedForReview()

        assertEquals(1, rows.size)
        assertNull(rows.single().patientId)
    }

    @Test
    fun abhaProfilesDao_returnsANullPatientWithoutDroppingTheRow() = runBlocking {
        db.abhaProfileDao().upsert(
            AbhaProfileEntity(
                abhaId = "abha-1", abhaAddress = null, name = "Sunita Devi", dateOfBirth = null,
                gender = "FEMALE", address = null, district = null, state = null, pincode = null,
                mobileNumber = null, emailAddress = null, photoUrlMock = null, kycVerified = true,
                createdAt = Instant.EPOCH, syncState = SyncState.FAILED,
                syncErrorCode = "SAMD-SYNC-RETRY-EXHAUSTED", localModifiedAt = Instant.EPOCH,
            ),
        )

        val rows = db.abhaProfileDao().getFailedForReview()

        assertEquals(1, rows.size)
        assertEquals("abha_profiles", rows.single().tableName)
        assertEquals("this table's primary key is abhaId, not id", "abha-1", rows.single().recordId)
        assertNull("abha_profiles has no patient; CAST(NULL AS TEXT) must survive", rows.single().patientId)
    }

    @Test
    fun socialHistoriesDao_usesThePatientIdAsTheRecordId() = runBlocking {
        // patientId IS this table's primary key, so it is also the id requeueFailed dispatches
        // on. A recordId that did not match would make "Send again" a silent no-op.
        db.socialHistoryDao().upsert(
            SocialHistoryEntity(
                patientId = "pat-1", occupation = "Farmer", tobaccoUse = "NEVER", alcoholUse = "NEVER",
                recreationalDrugUse = null, environmentalExposure = null, recentTravel = null,
                updatedAt = Instant.EPOCH, syncState = SyncState.FAILED, localModifiedAt = Instant.EPOCH,
            ),
        )

        val rows = db.socialHistoryDao().getFailedForReview()

        assertEquals(1, rows.size)
        assertEquals("pat-1", rows.single().recordId)
        assertEquals("pat-1", rows.single().patientId)

        db.socialHistoryDao().requeueFailed(rows.single().recordId)
        assertTrue(
            "the recordId the list carries must be the one requeueFailed accepts",
            db.socialHistoryDao().getFailedForReview().isEmpty(),
        )
    }

    private fun kernelReport(id: String, caseRecordId: String, syncState: SyncState) = KernelReportEntity(
        id = id, caseRecordId = caseRecordId, predictedCondition = "cond", confidenceScore = 0.8,
        differentials = emptyList(), reasoningSummary = "reasoning", evidenceFor = emptyList(),
        evidenceAgainst = emptyList(), modelVersion = "v1", icdCode = null, deviceId = "dev-1",
        softwareVersion = "1.0", dataQualityScore = null, uncertaintyScore = null,
        riskCategory = RiskCategory.MODERATE, urgencyLevel = UrgencyLevel.ROUTINE,
        inferenceStartedAt = Instant.EPOCH, inferenceEndedAt = Instant.EPOCH,
        requiredHumanVerification = false, inferenceSource = InferenceSource.MOCK_FALLBACK,
        syncState = syncState, localModifiedAt = Instant.EPOCH,
    )
}
