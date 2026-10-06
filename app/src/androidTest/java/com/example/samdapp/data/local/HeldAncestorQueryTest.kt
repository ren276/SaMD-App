package com.example.samdapp.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.samdapp.data.local.dao.SyncStateCount
import com.example.samdapp.data.local.entity.AilmentEntity
import com.example.samdapp.data.local.entity.AttachmentEntity
import com.example.samdapp.data.local.entity.CaseRecordEntity
import com.example.samdapp.data.local.entity.ConsultationEntity
import com.example.samdapp.data.local.entity.EncounterEntity
import com.example.samdapp.data.local.entity.KernelReportEntity
import com.example.samdapp.data.local.entity.MedicationLineEntity
import com.example.samdapp.data.local.entity.PatientEntity
import com.example.samdapp.data.local.entity.PrescriptionEntity
import com.example.samdapp.data.local.entity.ReferralEntity
import com.example.samdapp.domain.model.AttachmentType
import com.example.samdapp.domain.model.CaseStatus
import com.example.samdapp.domain.model.InferenceSource
import com.example.samdapp.domain.model.MeasurementType
import com.example.samdapp.domain.model.ReferralStatus
import com.example.samdapp.domain.model.RiskCategory
import com.example.samdapp.domain.model.SyncState
import com.example.samdapp.domain.model.UrgencyLevel
import com.example.samdapp.domain.model.Visibility
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * Memo section 12.4: a row is HELD when an ancestor in its chain is FAILED and has no
 * `serverVersion`. A held PENDING or RETRYABLE row is not collected by the drain, is counted
 * `held = 1` apart from the pending count, and is returned by the review query so the fold can
 * place it under its holder. A CONFLICT ancestor, or a FAILED one the server already holds,
 * holds nothing.
 *
 * One test group per representative shape, as Part A did, because the 68 statements are four
 * statements over seventeen fragments and only the fragment's shape can be wrong:
 *
 * - S1 direct patient and encounter: `ailments`.
 * - S1 with the patient under another column name: `referrals` (`patientUid`), and the case.
 * - S1 with a follow-up self reference: `encounters`.
 * - S2 through one parent: `kernel_reports` (case), `attachments` (consultation).
 * - S2 through two hops: `medication_lines` (prescription, then its case, patient, encounter).
 *
 * `SyncDaoSqlContractTest` pins in the JVM suite that every one of the seventeen tables uses its
 * own fragment in each of its four statements.
 */
class HeldAncestorQueryTest {

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

    // The drain cutoff: well after every row's lastSyncAttemptAt, so a RETRYABLE row's own
    // interval never explains an absence. Only the held fragment can.
    private val cutoff: Instant = Instant.now()

    private fun patient(id: String, state: SyncState, serverVersion: Int? = null) = PatientEntity(
        id = id, fullName = "Asha Devi", dateOfBirth = null, age = 30, biologicalSex = "Female",
        guardianOrSpouseName = null, guardianRelation = null, mobileNumber = null,
        aadhaarNumber = null, abhaNumber = null, village = null, block = null, district = null,
        state = null, pincode = null, category = null, maritalStatus = null, bloodGroup = null,
        emergencyContact = null, primaryCareClinicName = null, referringPhysicianName = null,
        createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH, localModifiedAt = Instant.EPOCH,
        syncState = state, serverVersion = serverVersion,
    )

    private fun encounter(id: String, patientId: String, state: SyncState = SyncState.PENDING, serverVersion: Int? = null, followUpOf: String? = null) =
        EncounterEntity(
            id = id, patientId = patientId, startedAt = Instant.EPOCH, createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH, followUpOfEncounterId = followUpOf, localModifiedAt = Instant.EPOCH,
            syncState = state, serverVersion = serverVersion,
        )

    private fun caseRecord(id: String, patientId: String, encounterId: String, state: SyncState = SyncState.PENDING, serverVersion: Int? = null) =
        CaseRecordEntity(
            id = id, patientId = patientId, encounterId = encounterId, status = CaseStatus.DRAFT,
            assignedDoctorId = null, createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
            localModifiedAt = Instant.EPOCH, syncState = state, serverVersion = serverVersion,
        )

    private fun ailment(id: String, patientId: String, encounterId: String, state: SyncState = SyncState.PENDING) = AilmentEntity(
        id = id, patientId = patientId, encounterId = encounterId, description = "cough",
        measurementType = MeasurementType.NON_MEASURABLE, visibility = Visibility.PUBLIC,
        measuredValue = null, measuredUnit = null, severity = null, onset = null, duration = null,
        qualifiers = null, audioLocalUri = null, capturedAtOffline = Instant.EPOCH, syncedToCloudAt = null,
        deletedAt = null, createdAt = Instant.EPOCH, localModifiedAt = Instant.EPOCH, syncState = state,
    )

    private fun referral(id: String, patientUid: String, caseRecordId: String, state: SyncState = SyncState.PENDING) = ReferralEntity(
        id = id, patientUid = patientUid, caseRecordId = caseRecordId, urgencyLevel = UrgencyLevel.ROUTINE,
        reason = "r", sendingPhcId = "phc-1", status = ReferralStatus.QUEUED, timestamp = Instant.EPOCH,
        localModifiedAt = Instant.EPOCH, syncState = state,
    )

    private fun consultation(id: String, patientId: String, encounterId: String) = ConsultationEntity(
        id = id, patientId = patientId, encounterId = encounterId, chiefComplaint = "cc", onset = null,
        durationBucket = null, severityScore = null, aggravatingFactors = null, relievingFactors = null,
        impactOnDailyActivities = null, impactOnDailyActivitiesProvenance = null, relevantHistory = null,
        transcription = null, createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH, localModifiedAt = Instant.EPOCH,
    )

    private fun attachment(id: String, consultationId: String, state: SyncState = SyncState.PENDING) = AttachmentEntity(
        id = id, consultationId = consultationId, type = AttachmentType.IMAGE, uri = "file://x",
        createdAt = Instant.EPOCH, localModifiedAt = Instant.EPOCH, syncState = state,
    )

    private fun kernelReport(id: String, caseRecordId: String, state: SyncState = SyncState.PENDING) = KernelReportEntity(
        id = id, caseRecordId = caseRecordId, predictedCondition = "cond", confidenceScore = 0.8,
        differentials = emptyList(), reasoningSummary = "reasoning", evidenceFor = emptyList(),
        evidenceAgainst = emptyList(), modelVersion = "v1", icdCode = null, deviceId = "dev-1",
        softwareVersion = "1.0", dataQualityScore = null, uncertaintyScore = null,
        riskCategory = RiskCategory.MODERATE, urgencyLevel = UrgencyLevel.ROUTINE,
        inferenceStartedAt = Instant.EPOCH, inferenceEndedAt = Instant.EPOCH,
        requiredHumanVerification = false, inferenceSource = InferenceSource.REAL_INFERENCE,
        syncState = state, localModifiedAt = Instant.EPOCH,
    )

    private fun prescription(id: String, patientId: String, encounterId: String, caseRecordId: String) = PrescriptionEntity(
        id = id, patientId = patientId, encounterId = encounterId, caseRecordId = caseRecordId,
        doctorId = "doc-1", diagnosis = "dx", createdAt = Instant.EPOCH, localModifiedAt = Instant.EPOCH,
    )

    private fun medicationLine(id: String, prescriptionId: String, state: SyncState = SyncState.PENDING) = MedicationLineEntity(
        id = id, prescriptionId = prescriptionId, position = 0, genericName = "Paracetamol", brandName = null,
        strength = "500 mg", dosage = "1", frequency = "TDS", route = "oral", duration = "3 days", quantity = "9",
        foodRelation = null, instructions = null, localModifiedAt = Instant.EPOCH, syncState = state,
    )

    private suspend fun rootPatient(state: SyncState, serverVersion: Int? = null) {
        db.patientDao().insert(patient("pat-1", state, serverVersion))
    }

    private fun List<SyncStateCount>.heldCount() = filter { it.held }.sumOf { it.rowCount }
    private fun List<SyncStateCount>.notHeldCount() = filter { !it.held }.sumOf { it.rowCount }

    // ── S1: ailments, patient and encounter ancestors ────────────────────────────

    private suspend fun ailmentsFixture(patientState: SyncState, patientVersion: Int? = null) {
        rootPatient(patientState, patientVersion)
        db.encounterDao().insert(encounter("enc-1", "pat-1"))
        db.ailmentDao().insert(ailment("a-1", "pat-1", "enc-1"))
    }

    private suspend fun assertAilmentHeld(held: Boolean, why: String) {
        val dao = db.ailmentDao()
        assertEquals("$why: drain collects it only when not held", !held, dao.getPendingForSync(cutoff).any { it.id == "a-1" })
        val counts = dao.getSyncStateCounts()
        assertEquals("$why: held count", if (held) 1 else 0, counts.heldCount())
        assertEquals("$why: pending count", if (held) 0 else 1, counts.notHeldCount())
        assertEquals("$why: the review query lists a held row", held, dao.getFailedForReview().any { it.recordId == "a-1" })
    }

    @Test
    fun ailments_patientFailedAndNeverOnServer_holdsTheRow() = runBlocking {
        ailmentsFixture(SyncState.FAILED)
        assertAilmentHeld(true, "patient FAILED, no server version")
    }

    @Test
    fun ailments_patientFailedButOnServer_holdsNothing() = runBlocking {
        ailmentsFixture(SyncState.FAILED, patientVersion = 3)
        assertAilmentHeld(false, "patient FAILED with server version 3")
    }

    @Test
    fun ailments_patientConflict_holdsNothing() = runBlocking {
        ailmentsFixture(SyncState.CONFLICT)
        assertAilmentHeld(false, "patient CONFLICT (operator ruling Q2)")
    }

    @Test
    fun ailments_encounterFailedAndNeverOnServer_holdsTheRow() = runBlocking {
        rootPatient(SyncState.PENDING)
        db.encounterDao().insert(encounter("enc-1", "pat-1", state = SyncState.FAILED))
        db.ailmentDao().insert(ailment("a-1", "pat-1", "enc-1"))
        assertAilmentHeld(true, "encounter FAILED, no server version")
    }

    @Test
    fun ailments_requeueingTheHolderReleasesTheRow() = runBlocking {
        ailmentsFixture(SyncState.FAILED)
        assertAilmentHeld(true, "before")

        db.patientDao().requeueFailed("pat-1")

        assertAilmentHeld(false, "after the patient is sent again")
    }

    @Test
    fun ailments_aRetryableHeldRowStaysExcludedWhateverItsInterval() = runBlocking {
        rootPatient(SyncState.FAILED)
        db.encounterDao().insert(encounter("enc-1", "pat-1"))
        db.ailmentDao().insert(ailment("a-1", "pat-1", "enc-1", state = SyncState.RETRYABLE))

        assertFalse(db.ailmentDao().getPendingForSync(cutoff).any { it.id == "a-1" })
        assertEquals(1, db.ailmentDao().getSyncStateCounts().heldCount())
        assertTrue(db.ailmentDao().getFailedForReview().any { it.recordId == "a-1" })
    }

    @Test
    fun ailments_aFailedRowIsNotHeldAndKeepsItsOwnReviewEntry() = runBlocking {
        ailmentsFixture(SyncState.FAILED)
        db.ailmentDao().insert(ailment("a-2", "pat-1", "enc-1", state = SyncState.FAILED))

        val review = db.ailmentDao().getFailedForReview().single { it.recordId == "a-2" }

        assertEquals(SyncState.FAILED, review.syncState)
        assertEquals("pat-1", review.patientId)
        assertEquals("enc-1", review.encounterId)
        assertEquals(null, review.serverVersion)
    }

    // ── S1: referrals, a differently named patient column, and the case ──────────

    @Test
    fun referrals_areHeldByTheirFailedCase() = runBlocking {
        rootPatient(SyncState.PENDING)
        db.encounterDao().insert(encounter("enc-1", "pat-1"))
        db.caseRecordDao().insert(caseRecord("case-1", "pat-1", "enc-1", state = SyncState.FAILED))
        db.referralDao().insert(referral("ref-1", "pat-1", "case-1"))

        assertFalse(db.referralDao().getPendingForSync(cutoff).any { it.id == "ref-1" })
        assertEquals(1, db.referralDao().getSyncStateCounts().heldCount())
        val review = db.referralDao().getFailedForReview().single()
        assertEquals("pat-1", review.patientId)
        assertEquals("case-1", review.caseRecordId)
    }

    @Test
    fun referrals_areHeldByTheirFailedPatientThroughPatientUid() = runBlocking {
        rootPatient(SyncState.FAILED)
        db.encounterDao().insert(encounter("enc-1", "pat-1"))
        db.caseRecordDao().insert(caseRecord("case-1", "pat-1", "enc-1"))
        db.referralDao().insert(referral("ref-1", "pat-1", "case-1"))

        assertFalse(db.referralDao().getPendingForSync(cutoff).any { it.id == "ref-1" })
        assertEquals(1, db.referralDao().getSyncStateCounts().heldCount())
    }

    @Test
    fun referrals_areNotHeldWhenNothingInTheirChainFailed() = runBlocking {
        rootPatient(SyncState.PENDING)
        db.encounterDao().insert(encounter("enc-1", "pat-1"))
        db.caseRecordDao().insert(caseRecord("case-1", "pat-1", "enc-1"))
        db.referralDao().insert(referral("ref-1", "pat-1", "case-1"))

        assertTrue(db.referralDao().getPendingForSync(cutoff).any { it.id == "ref-1" })
        assertEquals(0, db.referralDao().getSyncStateCounts().heldCount())
    }

    // ── S1: encounters, the follow-up self reference ─────────────────────────────

    @Test
    fun encounters_aFollowUpIsHeldByItsFailedPredecessor() = runBlocking {
        rootPatient(SyncState.PENDING)
        db.encounterDao().insert(encounter("enc-1", "pat-1", state = SyncState.FAILED))
        db.encounterDao().insert(encounter("enc-2", "pat-1", followUpOf = "enc-1"))

        val collected = db.encounterDao().getPendingForSync(cutoff).map { it.id }

        assertEquals("the failed predecessor is FAILED, not pending, and the follow-up is held", emptyList<String>(), collected)
        assertEquals(1, db.encounterDao().getSyncStateCounts().heldCount())
    }

    // ── S2: kernel_reports through its case ──────────────────────────────────────

    @Test
    fun kernelReports_areHeldWhenTheirCasesPatientIsRefused() = runBlocking {
        rootPatient(SyncState.FAILED)
        db.encounterDao().insert(encounter("enc-1", "pat-1"))
        db.caseRecordDao().insert(caseRecord("case-1", "pat-1", "enc-1"))
        db.kernelReportDao().upsert(kernelReport("kr-1", "case-1"))

        assertFalse(db.kernelReportDao().getPendingForSync(cutoff).any { it.id == "kr-1" })
        assertEquals(1, db.kernelReportDao().getSyncStateCounts().heldCount())
        val review = db.kernelReportDao().getFailedForReview().single()
        assertEquals("pat-1", review.patientId)
        assertEquals("enc-1", review.encounterId)
        assertEquals("case-1", review.caseRecordId)
    }

    @Test
    fun kernelReports_areNotHeldByAConflictPatient() = runBlocking {
        rootPatient(SyncState.CONFLICT)
        db.encounterDao().insert(encounter("enc-1", "pat-1"))
        db.caseRecordDao().insert(caseRecord("case-1", "pat-1", "enc-1"))
        db.kernelReportDao().upsert(kernelReport("kr-1", "case-1"))

        assertTrue(db.kernelReportDao().getPendingForSync(cutoff).any { it.id == "kr-1" })
        assertEquals(0, db.kernelReportDao().getSyncStateCounts().heldCount())
    }

    // ── S2: attachments through its consultation ─────────────────────────────────

    @Test
    fun attachments_areHeldWhenTheirConsultationsEncounterIsRefused() = runBlocking {
        rootPatient(SyncState.PENDING)
        db.encounterDao().insert(encounter("enc-1", "pat-1", state = SyncState.FAILED))
        db.consultationDao().insert(consultation("cons-1", "pat-1", "enc-1"))
        db.attachmentDao().insert(attachment("att-1", "cons-1"))

        assertFalse(db.attachmentDao().getPendingForSync(cutoff).any { it.id == "att-1" })
        assertEquals(1, db.attachmentDao().getSyncStateCounts().heldCount())
        val review = db.attachmentDao().getFailedForReview().single()
        assertEquals("pat-1", review.patientId)
        assertEquals("enc-1", review.encounterId)
        assertEquals("cons-1", review.parentId)
    }

    // ── S2, two hops: medication_lines through its prescription ──────────────────

    @Test
    fun medicationLines_areHeldThroughThePrescriptionsCase() = runBlocking {
        rootPatient(SyncState.PENDING)
        db.encounterDao().insert(encounter("enc-1", "pat-1"))
        db.caseRecordDao().insert(caseRecord("case-1", "pat-1", "enc-1", state = SyncState.FAILED))
        db.prescriptionDao().insertPrescription(prescription("rx-1", "pat-1", "enc-1", "case-1"))
        db.prescriptionDao().insertMedicationLines(listOf(medicationLine("ml-1", "rx-1")))

        assertFalse(db.prescriptionDao().getPendingMedicationLinesForSync(cutoff).any { it.id == "ml-1" })
        assertEquals(1, db.prescriptionDao().getMedicationLineSyncStateCounts().heldCount())
        val review = db.prescriptionDao().getFailedMedicationLinesForReview().single()
        assertEquals("rx-1", review.parentId)
        assertEquals("case-1", review.caseRecordId)
    }

    @Test
    fun medicationLines_areNotHeldByAFailedCaseTheServerAlreadyHolds() = runBlocking {
        rootPatient(SyncState.PENDING)
        db.encounterDao().insert(encounter("enc-1", "pat-1"))
        db.caseRecordDao().insert(caseRecord("case-1", "pat-1", "enc-1", state = SyncState.FAILED, serverVersion = 2))
        db.prescriptionDao().insertPrescription(prescription("rx-1", "pat-1", "enc-1", "case-1"))
        db.prescriptionDao().insertMedicationLines(listOf(medicationLine("ml-1", "rx-1")))

        assertTrue(db.prescriptionDao().getPendingMedicationLinesForSync(cutoff).any { it.id == "ml-1" })
        assertEquals(0, db.prescriptionDao().getMedicationLineSyncStateCounts().heldCount())
    }

    // ── Roots: no held fragment, a constant held flag ────────────────────────────

    @Test
    fun rootTables_neverReportHeldRows() = runBlocking {
        db.patientDao().insert(patient("pat-1", SyncState.PENDING))
        db.patientDao().insert(patient("pat-2", SyncState.FAILED))

        val counts = db.patientDao().getSyncStateCounts()

        assertEquals(0, counts.heldCount())
        assertEquals(2, counts.notHeldCount())
    }
}
