package com.example.samdapp.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.samdapp.data.local.dao.SyncStateCount
import com.example.samdapp.data.local.entity.AilmentEntity
import com.example.samdapp.data.local.entity.AttachmentEntity
import com.example.samdapp.data.local.entity.CaseRecordEntity
import com.example.samdapp.data.local.entity.ConsultationEntity
import com.example.samdapp.data.local.entity.EncounterEntity
import com.example.samdapp.data.local.entity.EvaluateReportEntity
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
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import java.time.Duration
import java.time.Instant

/**
 * The permanent guard behind "N records waiting to send": every row the Home outbox count calls
 * waiting (PENDING or RETRYABLE, not held) must be a row the drain's collect query returns once
 * the retry interval has passed, and the drain must collect nothing the count does not call
 * waiting. A row counted but never collected is a row Home says is waiting forever.
 *
 * Found by the D2 diagnostic (2026-10-07): two `evaluate_reports` rows sat PENDING with zero
 * attempts, counted as waiting on every drain and collected on none, because that drain query
 * carries `AND failureCode IS NULL` (an evaluate-failure marker must never be pushed, H-14) and
 * its two counts do not. That shape is [evaluateReports_failureMarkersAreCountedButNeverCollected],
 * ignored until the operator rules on the fix so that this class stays green at head.
 *
 * One test per table shape, as the held-ancestor tests do: root (`patients`), direct patient and
 * encounter (`ailments`), a differently named patient column (`referrals`), the self reference
 * (`encounters`), one hop (`kernel_reports`, `attachments`), two hops (`medication_lines`), and
 * the one table whose drain query adds a predicate of its own (`evaluate_reports`).
 */
class CountDrainConsistencyTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
    }

    @After
    fun tearDown() = db.close()

    // The test clock. A row attempted at [now] is inside the retry interval for a drain at [now];
    // a row attempted [longAgo] is outside it. [afterInterval] is a drain far enough in the future
    // that every RETRYABLE row has waited out its interval.
    private val now: Instant = Instant.now()
    private val longAgo: Instant = now.minus(Duration.ofHours(1))
    private val afterInterval: Instant = now.plus(Duration.ofDays(1))
    private val cutoffAtNow: Instant = now.minus(Duration.ofMinutes(5))

    private enum class Kind { PENDING, RETRY_INSIDE, RETRY_OUTSIDE, HELD, HELD_RETRY, FAILED, CONFLICT, SYNCED }

    private fun stateOf(kind: Kind) = when (kind) {
        Kind.PENDING, Kind.HELD -> SyncState.PENDING
        Kind.RETRY_INSIDE, Kind.RETRY_OUTSIDE, Kind.HELD_RETRY -> SyncState.RETRYABLE
        Kind.FAILED -> SyncState.FAILED
        Kind.CONFLICT -> SyncState.CONFLICT
        Kind.SYNCED -> SyncState.SYNCED
    }

    private fun attemptOf(kind: Kind): Instant? = when (kind) {
        Kind.RETRY_INSIDE -> now
        Kind.RETRY_OUTSIDE, Kind.HELD_RETRY -> longAgo
        else -> null
    }

    private fun versionOf(kind: Kind): Int? = if (kind == Kind.SYNCED) 1 else null
    private fun isHeld(kind: Kind) = kind == Kind.HELD || kind == Kind.HELD_RETRY
    private fun idOf(table: String, kind: Kind) = "$table-${kind.name.lowercase()}"
    private fun patientOf(kind: Kind) = if (isHeld(kind)) "pat-bad" else "pat-ok"

    // Ancestors: a healthy chain and a refused one (the patient FAILED and never on the server).
    private suspend fun ancestors() {
        db.patientDao().insert(patient("pat-ok", SyncState.SYNCED, 1))
        db.patientDao().insert(patient("pat-bad", SyncState.FAILED, null))
        db.encounterDao().insert(encounter("enc-ok", "pat-ok", SyncState.SYNCED, 1))
    }

    // What the drain should return after the interval, and what the count should call waiting.
    private val expectedCollected = Kind.values().filter { it == Kind.PENDING || it == Kind.RETRY_INSIDE || it == Kind.RETRY_OUTSIDE }

    private fun assertConsistent(
        table: String,
        counts: List<SyncStateCount>,
        collectedAfterInterval: List<String>,
        collectedAtNow: List<String>,
        expectHeld: Boolean = true,
    ) {
        val waiting = counts.filter { !it.held && (it.syncState == SyncState.PENDING || it.syncState == SyncState.RETRYABLE) }.sumOf { it.rowCount }
        val expectedIds = expectedCollected.map { idOf(table, it) }.toSet()
        assertEquals("$table: the count says $waiting waiting", expectedIds.size, waiting)
        assertEquals("$table: every counted waiting row, and nothing else, is collected once the interval passes", expectedIds, collectedAfterInterval.toSet())
        assertEquals("$table: no row is collected twice", collectedAfterInterval.size, collectedAfterInterval.toSet().size)
        assertEquals(
            "$table: inside its interval a RETRYABLE row waits, outside it is collected",
            setOf(idOf(table, Kind.PENDING), idOf(table, Kind.RETRY_OUTSIDE)),
            collectedAtNow.toSet(),
        )
        if (expectHeld) {
            assertEquals("$table: held rows are counted apart, never as waiting", 2, counts.filter { it.held }.sumOf { it.rowCount })
        }
    }

    // ── root: patients ───────────────────────────────────────────────────────────

    @Test
    fun patients_countAndDrainAgree() = runBlocking {
        // A root has no ancestor, so the two held kinds do not apply to it.
        Kind.values().filterNot(::isHeld).forEach { kind ->
            db.patientDao().insert(patient(idOf("patients", kind), stateOf(kind), versionOf(kind), attemptOf(kind)))
        }
        assertConsistent(
            "patients",
            db.patientDao().getSyncStateCounts(),
            db.patientDao().getPendingForSync(afterInterval).map { it.id }.filter { it.startsWith("patients-") },
            db.patientDao().getPendingForSync(cutoffAtNow).map { it.id }.filter { it.startsWith("patients-") },
            expectHeld = false,
        )
    }

    // ── S1: ailments, patient and encounter ──────────────────────────────────────

    @Test
    fun ailments_countAndDrainAgree() = runBlocking {
        ancestors()
        Kind.values().forEach { kind ->
            db.ailmentDao().insert(ailment(idOf("ailments", kind), patientOf(kind), "enc-ok", stateOf(kind), versionOf(kind), attemptOf(kind)))
        }
        assertConsistent(
            "ailments",
            db.ailmentDao().getSyncStateCounts(),
            db.ailmentDao().getPendingForSync(afterInterval).map { it.id },
            db.ailmentDao().getPendingForSync(cutoffAtNow).map { it.id },
        )
    }

    // ── S1: referrals, patientUid and the case ───────────────────────────────────

    @Test
    fun referrals_countAndDrainAgree() = runBlocking {
        ancestors()
        db.caseRecordDao().insert(caseRecord("case-ok", "pat-ok", "enc-ok", SyncState.SYNCED, 1))
        Kind.values().forEach { kind ->
            db.referralDao().insert(referral(idOf("referrals", kind), patientOf(kind), "case-ok", stateOf(kind), versionOf(kind), attemptOf(kind)))
        }
        assertConsistent(
            "referrals",
            db.referralDao().getSyncStateCounts(),
            db.referralDao().getPendingForSync(afterInterval).map { it.id },
            db.referralDao().getPendingForSync(cutoffAtNow).map { it.id },
        )
    }

    // ── S1: encounters, the self reference ───────────────────────────────────────

    @Test
    fun encounters_countAndDrainAgree() = runBlocking {
        db.patientDao().insert(patient("pat-ok", SyncState.SYNCED, 1))
        db.patientDao().insert(patient("pat-bad", SyncState.FAILED, null))
        Kind.values().forEach { kind ->
            db.encounterDao().insert(encounter(idOf("encounters", kind), patientOf(kind), stateOf(kind), versionOf(kind), attemptOf(kind)))
        }
        assertConsistent(
            "encounters",
            db.encounterDao().getSyncStateCounts(),
            db.encounterDao().getPendingForSync(afterInterval).map { it.id },
            db.encounterDao().getPendingForSync(cutoffAtNow).map { it.id },
        )
    }

    // ── S2: kernel_reports through its case ──────────────────────────────────────

    @Test
    fun kernelReports_countAndDrainAgree() = runBlocking {
        ancestors()
        Kind.values().forEach { kind ->
            val caseId = "case-" + idOf("kernel_reports", kind)
            db.caseRecordDao().insert(caseRecord(caseId, patientOf(kind), "enc-ok", SyncState.SYNCED, 1))
            db.kernelReportDao().upsert(kernelReport(idOf("kernel_reports", kind), caseId, stateOf(kind), versionOf(kind), attemptOf(kind)))
        }
        assertConsistent(
            "kernel_reports",
            db.kernelReportDao().getSyncStateCounts(),
            db.kernelReportDao().getPendingForSync(afterInterval).map { it.id },
            db.kernelReportDao().getPendingForSync(cutoffAtNow).map { it.id },
        )
    }

    // ── S2: attachments through its consultation ─────────────────────────────────

    @Test
    fun attachments_countAndDrainAgree() = runBlocking {
        ancestors()
        db.consultationDao().insert(consultation("cons-ok", "pat-ok", "enc-ok"))
        db.consultationDao().insert(consultation("cons-bad", "pat-bad", "enc-ok"))
        Kind.values().forEach { kind ->
            val consultation = if (isHeld(kind)) "cons-bad" else "cons-ok"
            db.attachmentDao().insert(attachment(idOf("attachments", kind), consultation, stateOf(kind), versionOf(kind), attemptOf(kind)))
        }
        assertConsistent(
            "attachments",
            db.attachmentDao().getSyncStateCounts(),
            db.attachmentDao().getPendingForSync(afterInterval).map { it.id },
            db.attachmentDao().getPendingForSync(cutoffAtNow).map { it.id },
        )
    }

    // ── S2, two hops: medication_lines through its prescription ──────────────────

    @Test
    fun medicationLines_countAndDrainAgree() = runBlocking {
        ancestors()
        db.caseRecordDao().insert(caseRecord("case-ok", "pat-ok", "enc-ok", SyncState.SYNCED, 1))
        db.caseRecordDao().insert(caseRecord("case-bad", "pat-bad", "enc-ok", SyncState.PENDING, null))
        db.prescriptionDao().insertPrescription(prescription("rx-ok", "pat-ok", "enc-ok", "case-ok"))
        db.prescriptionDao().insertPrescription(prescription("rx-bad", "pat-ok", "enc-ok", "case-bad"))
        Kind.values().forEach { kind ->
            val rx = if (isHeld(kind)) "rx-bad" else "rx-ok"
            db.prescriptionDao().insertMedicationLines(
                listOf(medicationLine(idOf("medication_lines", kind), rx, stateOf(kind), versionOf(kind), attemptOf(kind))),
            )
        }
        assertConsistent(
            "medication_lines",
            db.prescriptionDao().getMedicationLineSyncStateCounts(),
            db.prescriptionDao().getPendingMedicationLinesForSync(afterInterval).map { it.id },
            db.prescriptionDao().getPendingMedicationLinesForSync(cutoffAtNow).map { it.id },
        )
    }

    // ── evaluate_reports: the drain query's own extra predicate ──────────────────

    @Test
    fun evaluateReports_countAndDrainAgree() = runBlocking {
        ancestors()
        Kind.values().forEach { kind ->
            val caseId = "case-" + idOf("evaluate_reports", kind)
            db.caseRecordDao().insert(caseRecord(caseId, patientOf(kind), "enc-ok", SyncState.SYNCED, 1))
            db.evaluateReportDao().upsert(evaluateReport(idOf("evaluate_reports", kind), caseId, stateOf(kind), versionOf(kind), attemptOf(kind), failureCode = null))
        }
        assertConsistent(
            "evaluate_reports",
            db.evaluateReportDao().getSyncStateCounts(),
            db.evaluateReportDao().getPendingForSync(afterInterval).map { it.id },
            db.evaluateReportDao().getPendingForSync(cutoffAtNow).map { it.id },
        )
    }

    @Ignore("Known mismatch found by the D2 diagnostic: pending evaluate-failure markers are counted as waiting but never collected (the drain query has AND failureCode IS NULL, the counts do not). Un-ignore with the fix; operator ruling pending.")
    @Test
    fun evaluateReports_failureMarkersAreCountedButNeverCollected() = runBlocking {
        ancestors()
        db.caseRecordDao().insert(caseRecord("case-marker", "pat-ok", "enc-ok", SyncState.SYNCED, 1))
        db.evaluateReportDao().upsert(evaluateReport("marker-1", "case-marker", SyncState.PENDING, null, null, failureCode = "CASE_NOT_SENT_YET"))

        val counts = db.evaluateReportDao().getSyncStateCounts()
        val waiting = counts.filter { !it.held && it.syncState == SyncState.PENDING }.sumOf { it.rowCount }
        val collected = db.evaluateReportDao().getPendingForSync(afterInterval).map { it.id }

        assertEquals("a counted waiting row must be a collected row", waiting, collected.size)
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

    private fun encounter(id: String, patientId: String, state: SyncState, serverVersion: Int?, lastAttempt: Instant? = null) = EncounterEntity(
        id = id, patientId = patientId, startedAt = Instant.EPOCH, createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH, followUpOfEncounterId = null, localModifiedAt = Instant.EPOCH,
        syncState = state, serverVersion = serverVersion, lastSyncAttemptAt = lastAttempt,
    )

    private fun caseRecord(id: String, patientId: String, encounterId: String, state: SyncState, serverVersion: Int?) = CaseRecordEntity(
        id = id, patientId = patientId, encounterId = encounterId, status = CaseStatus.DRAFT,
        assignedDoctorId = null, createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
        localModifiedAt = Instant.EPOCH, syncState = state, serverVersion = serverVersion,
    )

    private fun ailment(id: String, patientId: String, encounterId: String, state: SyncState, serverVersion: Int?, lastAttempt: Instant?) = AilmentEntity(
        id = id, patientId = patientId, encounterId = encounterId, description = "cough",
        measurementType = MeasurementType.NON_MEASURABLE, visibility = Visibility.PUBLIC,
        measuredValue = null, measuredUnit = null, severity = null, onset = null, duration = null,
        qualifiers = null, audioLocalUri = null, capturedAtOffline = Instant.EPOCH, syncedToCloudAt = null,
        deletedAt = null, createdAt = Instant.EPOCH, localModifiedAt = Instant.EPOCH,
        syncState = state, serverVersion = serverVersion, lastSyncAttemptAt = lastAttempt,
    )

    private fun referral(id: String, patientUid: String, caseRecordId: String, state: SyncState, serverVersion: Int?, lastAttempt: Instant?) = ReferralEntity(
        id = id, patientUid = patientUid, caseRecordId = caseRecordId, urgencyLevel = UrgencyLevel.ROUTINE,
        reason = "r", sendingPhcId = "phc-1", status = ReferralStatus.QUEUED, timestamp = Instant.EPOCH,
        localModifiedAt = Instant.EPOCH, syncState = state, serverVersion = serverVersion, lastSyncAttemptAt = lastAttempt,
    )

    private fun consultation(id: String, patientId: String, encounterId: String) = ConsultationEntity(
        id = id, patientId = patientId, encounterId = encounterId, chiefComplaint = "cc", onset = null,
        durationBucket = null, severityScore = null, aggravatingFactors = null, relievingFactors = null,
        impactOnDailyActivities = null, impactOnDailyActivitiesProvenance = null, relevantHistory = null,
        transcription = null, createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH, localModifiedAt = Instant.EPOCH,
    )

    private fun attachment(id: String, consultationId: String, state: SyncState, serverVersion: Int?, lastAttempt: Instant?) = AttachmentEntity(
        id = id, consultationId = consultationId, type = AttachmentType.IMAGE, uri = "file://x",
        createdAt = Instant.EPOCH, localModifiedAt = Instant.EPOCH,
        syncState = state, serverVersion = serverVersion, lastSyncAttemptAt = lastAttempt,
    )

    private fun kernelReport(id: String, caseRecordId: String, state: SyncState, serverVersion: Int?, lastAttempt: Instant?) = KernelReportEntity(
        id = id, caseRecordId = caseRecordId, predictedCondition = "cond", confidenceScore = 0.8,
        differentials = emptyList(), reasoningSummary = "reasoning", evidenceFor = emptyList(),
        evidenceAgainst = emptyList(), modelVersion = "v1", icdCode = null, deviceId = "dev-1",
        softwareVersion = "1.0", dataQualityScore = null, uncertaintyScore = null,
        riskCategory = RiskCategory.MODERATE, urgencyLevel = UrgencyLevel.ROUTINE,
        inferenceStartedAt = Instant.EPOCH, inferenceEndedAt = Instant.EPOCH,
        requiredHumanVerification = false, inferenceSource = InferenceSource.REAL_INFERENCE,
        syncState = state, serverVersion = serverVersion, lastSyncAttemptAt = lastAttempt, localModifiedAt = Instant.EPOCH,
    )

    private fun evaluateReport(id: String, caseRecordId: String, state: SyncState, serverVersion: Int?, lastAttempt: Instant?, failureCode: String?) =
        EvaluateReportEntity(
            id = id, caseRecordId = caseRecordId, payloadJson = "{}", inferenceStartedAt = Instant.EPOCH,
            inferenceEndedAt = Instant.EPOCH, failureCode = failureCode, syncState = state,
            serverVersion = serverVersion, lastSyncAttemptAt = lastAttempt, localModifiedAt = Instant.EPOCH,
        )

    private fun prescription(id: String, patientId: String, encounterId: String, caseRecordId: String) = PrescriptionEntity(
        id = id, patientId = patientId, encounterId = encounterId, caseRecordId = caseRecordId,
        doctorId = "doc-1", diagnosis = "dx", createdAt = Instant.EPOCH, localModifiedAt = Instant.EPOCH,
    )

    private fun medicationLine(id: String, prescriptionId: String, state: SyncState, serverVersion: Int?, lastAttempt: Instant?) = MedicationLineEntity(
        id = id, prescriptionId = prescriptionId, position = 0, genericName = "Paracetamol", brandName = null,
        strength = "500 mg", dosage = "1", frequency = "TDS", route = "oral", duration = "3 days", quantity = "9",
        foodRelation = null, instructions = null, localModifiedAt = Instant.EPOCH,
        syncState = state, serverVersion = serverVersion, lastSyncAttemptAt = lastAttempt,
    )
}
