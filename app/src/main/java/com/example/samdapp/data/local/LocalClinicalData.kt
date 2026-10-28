package com.example.samdapp.data.local

import androidx.sqlite.db.SimpleSQLiteQuery
import com.example.samdapp.data.local.entity.AbhaProfileEntity
import com.example.samdapp.data.local.entity.AilmentEntity
import com.example.samdapp.data.local.entity.AllergyEntity
import com.example.samdapp.data.local.entity.AttachmentEntity
import com.example.samdapp.data.local.entity.AuditLogEntity
import com.example.samdapp.data.local.entity.CaseRecordEntity
import com.example.samdapp.data.local.entity.ConsultationDocumentEntity
import com.example.samdapp.data.local.entity.ConsultationEntity
import com.example.samdapp.data.local.entity.DeviceDataOwnerEntity
import com.example.samdapp.data.local.entity.DiagnosisFeedbackEntity
import com.example.samdapp.data.local.entity.DoctorEntity
import com.example.samdapp.data.local.entity.EncounterEntity
import com.example.samdapp.data.local.entity.EvaluateReportEntity
import com.example.samdapp.data.local.entity.FamilyHistoryEntryEntity
import com.example.samdapp.data.local.entity.KernelReportEntity
import com.example.samdapp.data.local.entity.MedicalHistoryItemEntity
import com.example.samdapp.data.local.entity.MedicationEntryEntity
import com.example.samdapp.data.local.entity.MedicationLineEntity
import com.example.samdapp.data.local.entity.ObservationEntity
import com.example.samdapp.data.local.entity.PatientEntity
import com.example.samdapp.data.local.entity.PrescriptionEntity
import com.example.samdapp.data.local.entity.ReferralEntity
import com.example.samdapp.data.local.entity.SocialHistoryEntity
import com.example.samdapp.domain.audit.AuditAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.reflect.KClass

/** Whether the database holds any facility clinical data (facility-scope memo 3.2): the input
 *  that tells a fresh phone (CLAIM) from one upgraded with data and no owner (LEGACY_CLAIM). */
interface LocalClinicalData {
    suspend fun present(): Boolean
}

/** How one Room entity counts for [LocalClinicalData]: its table, and why it is or is not data a
 *  facility owns. [notData] is a SQL condition on the table's rows that do not count even when the
 *  table does. */
internal data class EntityScope(val table: String, val clinical: Boolean, val reason: String, val notData: String? = null)

/**
 * Every entity in [AppDatabase], classified. `ClinicalEntityClassificationTest` fails when an entity
 * is added to the database without a row here, so a new table can never be left out of the check
 * silently. Local-only rows (an evaluate failure marker, H-14) count: they are never pushed, but
 * they are still facility data.
 */
internal val ENTITY_SCOPES: Map<KClass<*>, EntityScope> = mapOf(
    PatientEntity::class to EntityScope("patients", true, "patient identity and demographics"),
    EncounterEntity::class to EntityScope("encounters", true, "a visit of a patient"),
    ObservationEntity::class to EntityScope("observations", true, "a patient's measured vitals"),
    ConsultationEntity::class to EntityScope("consultations", true, "a patient's history taking"),
    AttachmentEntity::class to EntityScope("attachments", true, "images and audio of a consultation"),
    MedicalHistoryItemEntity::class to EntityScope("medical_history_items", true, "a patient's medical history"),
    MedicationEntryEntity::class to EntityScope("medication_entries", true, "a patient's current medication"),
    AllergyEntity::class to EntityScope("allergies", true, "a patient's allergies"),
    FamilyHistoryEntryEntity::class to EntityScope("family_history_entries", true, "a patient's family history"),
    SocialHistoryEntity::class to EntityScope("social_histories", true, "a patient's social history"),
    CaseRecordEntity::class to EntityScope("case_records", true, "a patient's case"),
    AuditLogEntity::class to EntityScope(
        "audit_log", true, "actions on the facility's records, with patient ids; pushed with the outbox",
        // A refusal is written while the phone may have no owner (no facility id, a lost claim), so
        // it must not make the next sign-in on a fresh phone a LEGACY_CLAIM.
        notData = "action = '${AuditAction.CROSS_FACILITY_SIGN_IN_BLOCKED.value}'",
    ),
    AbhaProfileEntity::class to EntityScope("abha_profiles", true, "a patient's ABHA identity"),
    AilmentEntity::class to EntityScope("ailments", true, "a patient's complaints"),
    PrescriptionEntity::class to EntityScope("prescriptions", true, "a patient's prescription"),
    MedicationLineEntity::class to EntityScope("medication_lines", true, "the drugs of a prescription"),
    KernelReportEntity::class to EntityScope("kernel_reports", true, "a case's assessment"),
    ReferralEntity::class to EntityScope("referrals", true, "a patient's referral"),
    DoctorEntity::class to EntityScope("doctors", false, "reference list seeded at database creation (DatabaseModule), present on a fresh phone"),
    EvaluateReportEntity::class to EntityScope("evaluate_reports", true, "a case's evaluation, local-only failure markers included"),
    DiagnosisFeedbackEntity::class to EntityScope("diagnosis_feedback", true, "a physician's decision on a case"),
    ConsultationDocumentEntity::class to EntityScope("consultation_documents", true, "a patient's uploaded documents"),
    DeviceDataOwnerEntity::class to EntityScope("device_data_owner", false, "the owner marker itself, not data it describes"),
)

class RoomLocalClinicalData @Inject constructor(private val db: AppDatabase) : LocalClinicalData {

    override suspend fun present(): Boolean = withContext(Dispatchers.IO) {
        db.query(SimpleSQLiteQuery(PRESENT_SQL)).use { it.moveToFirst() && it.getInt(0) == 1 }
    }

    private companion object {
        val PRESENT_SQL = "SELECT " + ENTITY_SCOPES.values.filter { it.clinical }
            .joinToString(" OR ") { scope ->
                val where = scope.notData?.let { " WHERE NOT ($it)" }.orEmpty()
                "EXISTS (SELECT 1 FROM `${scope.table}`$where)"
            }
    }
}
