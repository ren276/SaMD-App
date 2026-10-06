package com.example.samdapp.data.sync

import com.example.samdapp.data.local.dao.AbhaProfileDao
import com.example.samdapp.data.local.dao.AilmentDao
import com.example.samdapp.data.local.dao.AllergyDao
import com.example.samdapp.data.local.dao.AttachmentDao
import com.example.samdapp.data.local.dao.AuditLogDao
import com.example.samdapp.data.local.dao.CaseRecordDao
import com.example.samdapp.data.local.dao.ConsultationDao
import com.example.samdapp.data.local.dao.DiagnosisFeedbackDao
import com.example.samdapp.data.local.dao.EncounterDao
import com.example.samdapp.data.local.dao.EvaluateReportDao
import com.example.samdapp.data.local.dao.FailedSyncRow
import com.example.samdapp.data.local.dao.FamilyHistoryEntryDao
import com.example.samdapp.data.local.dao.KernelReportDao
import com.example.samdapp.data.local.dao.MedicalHistoryItemDao
import com.example.samdapp.data.local.dao.MedicationEntryDao
import com.example.samdapp.data.local.dao.ObservationDao
import com.example.samdapp.data.local.dao.PatientDao
import com.example.samdapp.data.local.dao.PrescriptionDao
import com.example.samdapp.data.local.dao.ReferralDao
import com.example.samdapp.data.local.dao.SocialHistoryDao
import com.example.samdapp.data.remote.dto.SyncRecordDto
import com.example.samdapp.data.remote.dto.SyncResultDto
import com.example.samdapp.domain.model.MAX_SYNC_ATTEMPTS
import com.example.samdapp.domain.model.RETRY_EXHAUSTED_CODE
import com.example.samdapp.domain.model.RETRY_MIN_INTERVAL
import com.example.samdapp.domain.model.SyncState
import com.example.samdapp.domain.model.syncFailureReasonFor
import com.example.samdapp.domain.sync.FailedSyncRecord
import com.example.samdapp.domain.sync.foldHeldRecords
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.Instant
import java.util.logging.Logger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomSyncOutboxRepository @Inject constructor(
    private val patientDao: PatientDao,
    private val encounterDao: EncounterDao,
    private val consultationDao: ConsultationDao,
    private val attachmentDao: AttachmentDao,
    private val observationDao: ObservationDao,
    private val ailmentDao: AilmentDao,
    private val medicalHistoryItemDao: MedicalHistoryItemDao,
    private val allergyDao: AllergyDao,
    private val familyHistoryEntryDao: FamilyHistoryEntryDao,
    private val socialHistoryDao: SocialHistoryDao,
    private val medicationEntryDao: MedicationEntryDao,
    private val caseRecordDao: CaseRecordDao,
    private val kernelReportDao: KernelReportDao,
    private val evaluateReportDao: EvaluateReportDao,
    private val diagnosisFeedbackDao: DiagnosisFeedbackDao,
    private val prescriptionDao: PrescriptionDao,
    private val referralDao: ReferralDao,
    private val abhaProfileDao: AbhaProfileDao,
    private val auditLogDao: AuditLogDao,
) : SyncOutboxRepository {

    private val logger = Logger.getLogger("SyncOutbox")

    override suspend fun collectPendingRecords(): List<SyncRecordDto> = buildList {
        // One cutoff for the whole sweep, computed once so every table sees the same instant and
        // a slow sweep cannot make a row eligible in one DAO and not the next.
        val retryEligibleBefore = Instant.now().minus(RETRY_MIN_INTERVAL)
        // Table order here is arbitrary — the backend re-sorts every batch by its own apply-rank
        // regardless of array order (api-contract.md §6.1), so this list only needs to be
        // exhaustive over the twenty syncable tables, not ordered.
        patientDao.getPendingForSync(retryEligibleBefore).forEach { add(it.toSyncRecord()) }
        encounterDao.getPendingForSync(retryEligibleBefore).forEach { add(it.toSyncRecord()) }
        consultationDao.getPendingForSync(retryEligibleBefore).forEach { add(it.toSyncRecord()) }
        attachmentDao.getPendingForSync(retryEligibleBefore).forEach { add(it.toSyncRecord()) }
        observationDao.getPendingForSync(retryEligibleBefore).forEach { add(it.toSyncRecord()) }
        ailmentDao.getPendingForSync(retryEligibleBefore).forEach { add(it.toSyncRecord()) }
        medicalHistoryItemDao.getPendingForSync(retryEligibleBefore).forEach { add(it.toSyncRecord()) }
        allergyDao.getPendingForSync(retryEligibleBefore).forEach { add(it.toSyncRecord()) }
        familyHistoryEntryDao.getPendingForSync(retryEligibleBefore).forEach { add(it.toSyncRecord()) }
        socialHistoryDao.getPendingForSync(retryEligibleBefore).forEach { add(it.toSyncRecord()) }
        medicationEntryDao.getPendingForSync(retryEligibleBefore).forEach { add(it.toSyncRecord()) }
        caseRecordDao.getPendingForSync(retryEligibleBefore).forEach { add(it.toSyncRecord()) }
        kernelReportDao.getPendingForSync(retryEligibleBefore).forEach { add(it.toSyncRecord()) }
        evaluateReportDao.getPendingForSync(retryEligibleBefore).forEach { add(it.toSyncRecord()) }
        diagnosisFeedbackDao.getPendingForSync(retryEligibleBefore).forEach { add(it.toSyncRecord()) }
        prescriptionDao.getPendingPrescriptionsForSync(retryEligibleBefore).forEach { add(it.toSyncRecord()) }
        prescriptionDao.getPendingMedicationLinesForSync(retryEligibleBefore).forEach { add(it.toSyncRecord()) }
        referralDao.getPendingForSync(retryEligibleBefore).forEach { add(it.toSyncRecord()) }
        abhaProfileDao.getPendingForSync(retryEligibleBefore).forEach { add(it.toSyncRecord()) }
        auditLogDao.getPendingForSync(retryEligibleBefore).forEach { add(it.toSyncRecord()) }
    }

    override suspend fun applyAck(result: SyncResultDto, sentLocalModifiedAt: Instant) {
        // An ack status this build does not know is recorded and skipped, exactly as an unknown
        // table is below, and for exactly the same reasons. S-1 left this throwing because
        // skipping could spin; S-2's in-drain `attempted` set bounded that, so the safe option
        // is now available and is taken. See toLocalSyncState's KDoc for the full argument.
        val syncState = result.toLocalSyncState() ?: run {
            skippedAcks += SkippedAck(result.table, result.id, result.status)
            logger.warning(
                "Sync ack with unknown status \"${result.status}\" for ${result.table}/${result.id} " +
                    "was skipped. The row stays PENDING and is resent on the next drain.",
            )
            return
        }
        val attemptAt = Instant.now()
        // A CONFLICT row keeps the base it was written on. Adopting the ack's server_version
        // would let any later resend carry a matching base_version, pass rule 4 of
        // _resolve_write and overwrite the newer server data the conflict protects. The backend's
        // conflict result carries no server_version today; this does not rely on that.
        val serverVersion = if (syncState == SyncState.CONFLICT) null else result.serverVersion
        when (result.table) {
            "patients" -> patientDao.applySyncResult(result.id, syncState, serverVersion, result.code, result.message, attemptAt, sentLocalModifiedAt, MAX_SYNC_ATTEMPTS, RETRY_EXHAUSTED_CODE)
            "encounters" -> encounterDao.applySyncResult(result.id, syncState, serverVersion, result.code, result.message, attemptAt, sentLocalModifiedAt, MAX_SYNC_ATTEMPTS, RETRY_EXHAUSTED_CODE)
            "consultations" -> consultationDao.applySyncResult(result.id, syncState, serverVersion, result.code, result.message, attemptAt, sentLocalModifiedAt, MAX_SYNC_ATTEMPTS, RETRY_EXHAUSTED_CODE)
            "attachments" -> attachmentDao.applySyncResult(result.id, syncState, serverVersion, result.code, result.message, attemptAt, sentLocalModifiedAt, MAX_SYNC_ATTEMPTS, RETRY_EXHAUSTED_CODE)
            "observations" -> observationDao.applySyncResult(result.id, syncState, serverVersion, result.code, result.message, attemptAt, sentLocalModifiedAt, MAX_SYNC_ATTEMPTS, RETRY_EXHAUSTED_CODE)
            "ailments" -> ailmentDao.applySyncResult(result.id, syncState, serverVersion, result.code, result.message, attemptAt, sentLocalModifiedAt, MAX_SYNC_ATTEMPTS, RETRY_EXHAUSTED_CODE)
            "medical_history_items" -> medicalHistoryItemDao.applySyncResult(result.id, syncState, serverVersion, result.code, result.message, attemptAt, sentLocalModifiedAt, MAX_SYNC_ATTEMPTS, RETRY_EXHAUSTED_CODE)
            "allergies" -> allergyDao.applySyncResult(result.id, syncState, serverVersion, result.code, result.message, attemptAt, sentLocalModifiedAt, MAX_SYNC_ATTEMPTS, RETRY_EXHAUSTED_CODE)
            "family_history_entries" -> familyHistoryEntryDao.applySyncResult(result.id, syncState, serverVersion, result.code, result.message, attemptAt, sentLocalModifiedAt, MAX_SYNC_ATTEMPTS, RETRY_EXHAUSTED_CODE)
            "social_histories" -> socialHistoryDao.applySyncResult(result.id, syncState, serverVersion, result.code, result.message, attemptAt, sentLocalModifiedAt, MAX_SYNC_ATTEMPTS, RETRY_EXHAUSTED_CODE)
            "medication_entries" -> medicationEntryDao.applySyncResult(result.id, syncState, serverVersion, result.code, result.message, attemptAt, sentLocalModifiedAt, MAX_SYNC_ATTEMPTS, RETRY_EXHAUSTED_CODE)
            "case_records" -> caseRecordDao.applySyncResult(result.id, syncState, serverVersion, result.code, result.message, attemptAt, sentLocalModifiedAt, MAX_SYNC_ATTEMPTS, RETRY_EXHAUSTED_CODE)
            "kernel_reports" -> kernelReportDao.applySyncResult(result.id, syncState, serverVersion, result.code, result.message, attemptAt, sentLocalModifiedAt, MAX_SYNC_ATTEMPTS, RETRY_EXHAUSTED_CODE)
            "evaluate_reports" -> evaluateReportDao.applySyncResult(result.id, syncState, serverVersion, result.code, result.message, attemptAt, sentLocalModifiedAt, MAX_SYNC_ATTEMPTS, RETRY_EXHAUSTED_CODE)
            "diagnosis_feedback" -> diagnosisFeedbackDao.applySyncResult(result.id, syncState, serverVersion, result.code, result.message, attemptAt, sentLocalModifiedAt, MAX_SYNC_ATTEMPTS, RETRY_EXHAUSTED_CODE)
            "prescriptions" -> prescriptionDao.applyPrescriptionSyncResult(result.id, syncState, serverVersion, result.code, result.message, attemptAt, sentLocalModifiedAt, MAX_SYNC_ATTEMPTS, RETRY_EXHAUSTED_CODE)
            "medication_lines" -> prescriptionDao.applyMedicationLineSyncResult(result.id, syncState, serverVersion, result.code, result.message, attemptAt, sentLocalModifiedAt, MAX_SYNC_ATTEMPTS, RETRY_EXHAUSTED_CODE)
            "referrals" -> referralDao.applySyncResult(result.id, syncState, serverVersion, result.code, result.message, attemptAt, sentLocalModifiedAt, MAX_SYNC_ATTEMPTS, RETRY_EXHAUSTED_CODE)
            "abha_profiles" -> abhaProfileDao.applySyncResult(result.id, syncState, serverVersion, result.code, result.message, attemptAt, sentLocalModifiedAt, MAX_SYNC_ATTEMPTS, RETRY_EXHAUSTED_CODE)
            "audit_log" -> auditLogDao.applySyncResult(result.id, syncState, serverVersion, result.code, result.message, attemptAt, sentLocalModifiedAt, MAX_SYNC_ATTEMPTS, RETRY_EXHAUSTED_CODE)
            // Recorded and skipped, not thrown. A backend that acks a table this build's `when`
            // does not cover is a mirror break, and throwing here made it a drain-wide outage:
            // the exception escaped applyAck, so every ack AFTER it in the same batch was never
            // applied either, valid ones included, and InFlightBatchStore was never cleared. The
            // rows behind those lost acks stay PENDING and resend on the next drain, which the
            // S-1 absence analysis establishes is safe for every record type. An unknown table
            // also means this device has no DAO for it and therefore no local row to update, so
            // skipping updates nothing that existed. Same class of coupling as SyncRetryClass
            // itself, which is why it is fixed in the same change set.
            else -> {
                skippedAcks += SkippedAck(result.table, result.id, result.status)
                logger.warning(
                    "Sync ack for unknown table \"${result.table}\" (id ${result.id}, status " +
                        "${result.status}) was skipped. This device has no DAO for that table.",
                )
            }
        }
    }

    override suspend fun requeueFailed(table: String, id: String) {
        when (table) {
            "patients" -> patientDao.requeueFailed(id)
            "encounters" -> encounterDao.requeueFailed(id)
            "consultations" -> consultationDao.requeueFailed(id)
            "attachments" -> attachmentDao.requeueFailed(id)
            "observations" -> observationDao.requeueFailed(id)
            "ailments" -> ailmentDao.requeueFailed(id)
            "medical_history_items" -> medicalHistoryItemDao.requeueFailed(id)
            "allergies" -> allergyDao.requeueFailed(id)
            "family_history_entries" -> familyHistoryEntryDao.requeueFailed(id)
            "social_histories" -> socialHistoryDao.requeueFailed(id)
            "medication_entries" -> medicationEntryDao.requeueFailed(id)
            "case_records" -> caseRecordDao.requeueFailed(id)
            "kernel_reports" -> kernelReportDao.requeueFailed(id)
            "evaluate_reports" -> evaluateReportDao.requeueFailed(id)
            "diagnosis_feedback" -> diagnosisFeedbackDao.requeueFailed(id)
            "prescriptions" -> prescriptionDao.requeueFailedPrescription(id)
            "medication_lines" -> prescriptionDao.requeueFailedMedicationLine(id)
            "referrals" -> referralDao.requeueFailed(id)
            "abha_profiles" -> abhaProfileDao.requeueFailed(id)
            "audit_log" -> auditLogDao.requeueFailed(id)
            // Same recorded-and-skipped degradation as applyAck above, for the same reason: a
            // table this build has no DAO for has no local row to requeue either.
            else -> {
                skippedAcks += SkippedAck(table, id, "requeue")
                logger.warning("Requeue for unknown table \"$table\" (id $id) was skipped.")
            }
        }
    }

    /** One ack this build could not apply because it names a table the `when` above does not
     *  cover. Kept in memory only: persisting it would need a table, which is schema and
     *  therefore S-2. Readable by a test, and by anything that later wants to surface it. */
    data class SkippedAck(val table: String, val id: String, val status: String)

    private val _skippedAcks = mutableListOf<SkippedAck>()

    /** Appended to by [applyAck]. Not cleared: the list is bounded by the number of distinct
     *  unknown tables a backend can name, which is a mirror break and therefore a bug, not a
     *  volume. */
    val skippedAcks: MutableList<SkippedAck> get() = _skippedAcks

    override fun observeOutboxCounts(): Flow<OutboxCounts> = combine(
        combine(
            listOf(
                patientDao.observeSyncStateCounts(), encounterDao.observeSyncStateCounts(),
                consultationDao.observeSyncStateCounts(), attachmentDao.observeSyncStateCounts(),
                observationDao.observeSyncStateCounts(), ailmentDao.observeSyncStateCounts(),
                medicalHistoryItemDao.observeSyncStateCounts(), allergyDao.observeSyncStateCounts(),
                familyHistoryEntryDao.observeSyncStateCounts(), socialHistoryDao.observeSyncStateCounts(),
                medicationEntryDao.observeSyncStateCounts(), caseRecordDao.observeSyncStateCounts(),
                kernelReportDao.observeSyncStateCounts(), evaluateReportDao.observeSyncStateCounts(),
                diagnosisFeedbackDao.observeSyncStateCounts(), prescriptionDao.observePrescriptionSyncStateCounts(),
                prescriptionDao.observeMedicationLineSyncStateCounts(), referralDao.observeSyncStateCounts(),
                abhaProfileDao.observeSyncStateCounts(),
            ),
        ) { perTable -> perTable.flatMap { it } },
        // audit_log apart: an audit-only backlog is a different sentence on Home (ruling H4).
        auditLogDao.observeSyncStateCounts(),
        ::outboxCountsOf,
    )

    /**
     * Every FAILED and CONFLICT row in the outbox, newest first, with its patient's name resolved and its
     * cause classified.
     *
     * Twenty queries plus one name lookup, run on demand rather than observed. The alternative
     * considered and rejected was a single twenty-branch `UNION ALL` in a new DAO: one artifact
     * instead of twenty is nicer to count, but this environment has no Robolectric and no
     * device, so neither shape can be EXECUTED by a runnable test. Given that, twenty statements
     * that each mirror the `observeSyncStateCounts` sitting directly above them are readable by
     * a reviewer one at a time, and a seventy-line UNION with correlated sub-selects is not.
     * `SyncDaoSqlContractTest` pins the count at twenty either way.
     *
     * The name lookup is one `IN (:ids)` query, not one per row: a worker opens this list with a
     * patient in front of them, on the SQLCipher pool the perf audit found already contended
     * (F2A-01).
     */
    override suspend fun readOutboxCounts(): OutboxCounts = outboxCountsOf(
        clinical = listOf(
            patientDao.getSyncStateCounts(), encounterDao.getSyncStateCounts(),
            consultationDao.getSyncStateCounts(), attachmentDao.getSyncStateCounts(),
            observationDao.getSyncStateCounts(), ailmentDao.getSyncStateCounts(),
            medicalHistoryItemDao.getSyncStateCounts(), allergyDao.getSyncStateCounts(),
            familyHistoryEntryDao.getSyncStateCounts(), socialHistoryDao.getSyncStateCounts(),
            medicationEntryDao.getSyncStateCounts(), caseRecordDao.getSyncStateCounts(),
            kernelReportDao.getSyncStateCounts(), evaluateReportDao.getSyncStateCounts(),
            diagnosisFeedbackDao.getSyncStateCounts(), prescriptionDao.getPrescriptionSyncStateCounts(),
            prescriptionDao.getMedicationLineSyncStateCounts(), referralDao.getSyncStateCounts(),
            abhaProfileDao.getSyncStateCounts(),
        ).flatten(),
        audit = auditLogDao.getSyncStateCounts(),
    )

    override suspend fun failedRecords(): List<FailedSyncRecord> {
        val rows: List<FailedSyncRow> = buildList {
            addAll(patientDao.getFailedForReview())
            addAll(encounterDao.getFailedForReview())
            addAll(consultationDao.getFailedForReview())
            addAll(attachmentDao.getFailedForReview())
            addAll(observationDao.getFailedForReview())
            addAll(ailmentDao.getFailedForReview())
            addAll(medicalHistoryItemDao.getFailedForReview())
            addAll(allergyDao.getFailedForReview())
            addAll(familyHistoryEntryDao.getFailedForReview())
            addAll(socialHistoryDao.getFailedForReview())
            addAll(medicationEntryDao.getFailedForReview())
            addAll(caseRecordDao.getFailedForReview())
            addAll(kernelReportDao.getFailedForReview())
            addAll(evaluateReportDao.getFailedForReview())
            addAll(diagnosisFeedbackDao.getFailedForReview())
            addAll(prescriptionDao.getFailedPrescriptionsForReview())
            addAll(prescriptionDao.getFailedMedicationLinesForReview())
            addAll(referralDao.getFailedForReview())
            addAll(abhaProfileDao.getFailedForReview())
            addAll(auditLogDao.getFailedForReview())
        }
        if (rows.isEmpty()) return emptyList()

        val names = patientDao.getNamesByIds(rows.mapNotNull { it.patientId }.distinct())
            .associate { it.id to it.fullName }

        return foldHeldRecords(
            rows
                .sortedByDescending { it.recordedAt }
                .map { row -> row.toRecord(names) },
        )
    }
}

/** The tables whose FAILED, never-held rows hold their descendants (see `SyncSql.HELD_*`). */
private val HOLDER_TABLES = setOf("patients", "encounters", "consultations", "case_records", "prescriptions")

internal fun FailedSyncRow.toRecord(patientNames: Map<String, String>) = FailedSyncRecord(
    table = tableName,
    recordId = recordId,
    patientName = patientId?.let { patientNames[it] },
    recordedAt = recordedAt,
    reason = syncFailureReasonFor(syncState, syncErrorCode, syncErrorMessage),
    patientId = patientId,
    // Nearest to the patient first, so a patient that holds a record wins over an encounter that
    // is itself held by that patient.
    ancestors = listOfNotNull(
        patientId?.let { "patients" to it },
        encounterId?.let { "encounters" to it },
        caseRecordId?.let { "case_records" to it },
        parentId?.let { (if (tableName == "attachments") "consultations" else "prescriptions") to it },
    ),
    holdsDescendants = syncState == SyncState.FAILED && serverVersion == null && tableName in HOLDER_TABLES,
    held = syncState == SyncState.PENDING || syncState == SyncState.RETRYABLE,
)
