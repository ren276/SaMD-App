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
import com.example.samdapp.domain.model.SyncState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.Instant
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

    override suspend fun collectPendingRecords(): List<SyncRecordDto> = buildList {
        // Table order here is arbitrary — the backend re-sorts every batch by its own apply-rank
        // regardless of array order (api-contract.md §6.1), so this list only needs to be
        // exhaustive over the twenty syncable tables, not ordered.
        patientDao.getPendingForSync().forEach { add(it.toSyncRecord()) }
        encounterDao.getPendingForSync().forEach { add(it.toSyncRecord()) }
        consultationDao.getPendingForSync().forEach { add(it.toSyncRecord()) }
        attachmentDao.getPendingForSync().forEach { add(it.toSyncRecord()) }
        observationDao.getPendingForSync().forEach { add(it.toSyncRecord()) }
        ailmentDao.getPendingForSync().forEach { add(it.toSyncRecord()) }
        medicalHistoryItemDao.getPendingForSync().forEach { add(it.toSyncRecord()) }
        allergyDao.getPendingForSync().forEach { add(it.toSyncRecord()) }
        familyHistoryEntryDao.getPendingForSync().forEach { add(it.toSyncRecord()) }
        socialHistoryDao.getPendingForSync().forEach { add(it.toSyncRecord()) }
        medicationEntryDao.getPendingForSync().forEach { add(it.toSyncRecord()) }
        caseRecordDao.getPendingForSync().forEach { add(it.toSyncRecord()) }
        kernelReportDao.getPendingForSync().forEach { add(it.toSyncRecord()) }
        evaluateReportDao.getPendingForSync().forEach { add(it.toSyncRecord()) }
        diagnosisFeedbackDao.getPendingForSync().forEach { add(it.toSyncRecord()) }
        prescriptionDao.getPendingPrescriptionsForSync().forEach { add(it.toSyncRecord()) }
        prescriptionDao.getPendingMedicationLinesForSync().forEach { add(it.toSyncRecord()) }
        referralDao.getPendingForSync().forEach { add(it.toSyncRecord()) }
        abhaProfileDao.getPendingForSync().forEach { add(it.toSyncRecord()) }
        auditLogDao.getPendingForSync().forEach { add(it.toSyncRecord()) }
    }

    override suspend fun applyAck(result: SyncResultDto, sentLocalModifiedAt: Instant) {
        val syncState = result.toLocalSyncState()
        val attemptAt = Instant.now()
        when (result.table) {
            "patients" -> patientDao.applySyncResult(result.id, syncState, result.serverVersion, result.code, attemptAt, sentLocalModifiedAt)
            "encounters" -> encounterDao.applySyncResult(result.id, syncState, result.serverVersion, result.code, attemptAt, sentLocalModifiedAt)
            "consultations" -> consultationDao.applySyncResult(result.id, syncState, result.serverVersion, result.code, attemptAt, sentLocalModifiedAt)
            "attachments" -> attachmentDao.applySyncResult(result.id, syncState, result.serverVersion, result.code, attemptAt, sentLocalModifiedAt)
            "observations" -> observationDao.applySyncResult(result.id, syncState, result.serverVersion, result.code, attemptAt, sentLocalModifiedAt)
            "ailments" -> ailmentDao.applySyncResult(result.id, syncState, result.serverVersion, result.code, attemptAt, sentLocalModifiedAt)
            "medical_history_items" -> medicalHistoryItemDao.applySyncResult(result.id, syncState, result.serverVersion, result.code, attemptAt, sentLocalModifiedAt)
            "allergies" -> allergyDao.applySyncResult(result.id, syncState, result.serverVersion, result.code, attemptAt, sentLocalModifiedAt)
            "family_history_entries" -> familyHistoryEntryDao.applySyncResult(result.id, syncState, result.serverVersion, result.code, attemptAt, sentLocalModifiedAt)
            "social_histories" -> socialHistoryDao.applySyncResult(result.id, syncState, result.serverVersion, result.code, attemptAt, sentLocalModifiedAt)
            "medication_entries" -> medicationEntryDao.applySyncResult(result.id, syncState, result.serverVersion, result.code, attemptAt, sentLocalModifiedAt)
            "case_records" -> caseRecordDao.applySyncResult(result.id, syncState, result.serverVersion, result.code, attemptAt, sentLocalModifiedAt)
            "kernel_reports" -> kernelReportDao.applySyncResult(result.id, syncState, result.serverVersion, result.code, attemptAt, sentLocalModifiedAt)
            "evaluate_reports" -> evaluateReportDao.applySyncResult(result.id, syncState, result.serverVersion, result.code, attemptAt, sentLocalModifiedAt)
            "diagnosis_feedback" -> diagnosisFeedbackDao.applySyncResult(result.id, syncState, result.serverVersion, result.code, attemptAt, sentLocalModifiedAt)
            "prescriptions" -> prescriptionDao.applyPrescriptionSyncResult(result.id, syncState, result.serverVersion, result.code, attemptAt, sentLocalModifiedAt)
            "medication_lines" -> prescriptionDao.applyMedicationLineSyncResult(result.id, syncState, result.serverVersion, result.code, attemptAt, sentLocalModifiedAt)
            "referrals" -> referralDao.applySyncResult(result.id, syncState, result.serverVersion, result.code, attemptAt, sentLocalModifiedAt)
            "abha_profiles" -> abhaProfileDao.applySyncResult(result.id, syncState, result.serverVersion, result.code, attemptAt, sentLocalModifiedAt)
            "audit_log" -> auditLogDao.applySyncResult(result.id, syncState, result.serverVersion, result.code, attemptAt, sentLocalModifiedAt)
            else -> error("Unknown sync table \"${result.table}\" in ack for id ${result.id}")
        }
    }

    override fun observeFailedCount(): Flow<Int> = combine(
        listOf(
            patientDao.observeFailedSyncCount(), encounterDao.observeFailedSyncCount(),
            consultationDao.observeFailedSyncCount(), attachmentDao.observeFailedSyncCount(),
            observationDao.observeFailedSyncCount(), ailmentDao.observeFailedSyncCount(),
            medicalHistoryItemDao.observeFailedSyncCount(), allergyDao.observeFailedSyncCount(),
            familyHistoryEntryDao.observeFailedSyncCount(), socialHistoryDao.observeFailedSyncCount(),
            medicationEntryDao.observeFailedSyncCount(), caseRecordDao.observeFailedSyncCount(),
            kernelReportDao.observeFailedSyncCount(), evaluateReportDao.observeFailedSyncCount(),
            diagnosisFeedbackDao.observeFailedSyncCount(), prescriptionDao.observePrescriptionFailedSyncCount(),
            prescriptionDao.observeMedicationLineFailedSyncCount(), referralDao.observeFailedSyncCount(),
            abhaProfileDao.observeFailedSyncCount(), auditLogDao.observeFailedSyncCount(),
        ),
    ) { counts -> counts.sum() }
}
