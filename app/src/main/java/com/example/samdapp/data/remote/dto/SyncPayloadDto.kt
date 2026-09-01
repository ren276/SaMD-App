package com.example.samdapp.data.remote.dto

import com.google.gson.annotations.SerializedName
import java.time.Instant
import java.time.LocalDate

/**
 * One `data class` per syncable table's `data` object (api-contract.md §6.1), hand-written with
 * explicit `@SerializedName` per field rather than a `FieldNamingPolicy` or Gson-reflected-off-
 * the-entity payload, matching this package's existing convention (see EvaluateReportDto.kt's
 * "do not replace with a FieldNamingPolicy" warning). Reflection off the Room entity was
 * considered and rejected: several tables need fields *excluded* that the entity itself carries
 * (ailments.audioLocalUri — forbidden, SAMD-SYNC-6006; observations/ailments.syncedToCloudAt —
 * server-stamped) or *renamed* (attachments.uri is sent as the wire key `uri`, which the backend
 * aliases to its own `local_uri` column, TABLE_REGISTRY in backend/core/app/services/sync.py) —
 * an explicit DTO makes each of those a compile-time-visible field list instead of a runtime
 * exclusion set that silently drifts from the entity.
 *
 * Field lists and wire names are cross-checked against the Python model files under
 * backend/core/app/models directly, not just api-contract.md's illustrative example, since
 * TABLE_REGISTRY there is the actual accept/reject boundary.
 */

data class PatientSyncPayloadDto(
    @SerializedName("full_name") val fullName: String,
    @SerializedName("date_of_birth") val dateOfBirth: LocalDate?,
    val age: Int?,
    @SerializedName("biological_sex") val biologicalSex: String,
    @SerializedName("guardian_or_spouse_name") val guardianOrSpouseName: String?,
    @SerializedName("guardian_relation") val guardianRelation: String?,
    @SerializedName("mobile_number") val mobileNumber: String?,
    @SerializedName("aadhaar_number") val aadhaarNumber: String?,
    @SerializedName("abha_number") val abhaNumber: String?,
    val village: String?,
    val block: String?,
    val district: String?,
    val state: String?,
    val pincode: String?,
    val category: String?,
    @SerializedName("marital_status") val maritalStatus: String?,
    @SerializedName("blood_group") val bloodGroup: String?,
    @SerializedName("emergency_contact") val emergencyContact: String?,
    @SerializedName("primary_care_clinic_name") val primaryCareClinicName: String?,
    @SerializedName("referring_physician_name") val referringPhysicianName: String?,
    @SerializedName("created_at") val createdAt: Instant,
    @SerializedName("updated_at") val updatedAt: Instant,
) : SyncPayload

data class EncounterSyncPayloadDto(
    @SerializedName("patient_id") val patientId: String,
    @SerializedName("started_at") val startedAt: Instant,
    @SerializedName("created_at") val createdAt: Instant,
    @SerializedName("updated_at") val updatedAt: Instant,
    @SerializedName("follow_up_of_encounter_id") val followUpOfEncounterId: String?,
) : SyncPayload

data class ConsultationSyncPayloadDto(
    @SerializedName("patient_id") val patientId: String,
    @SerializedName("encounter_id") val encounterId: String,
    @SerializedName("chief_complaint") val chiefComplaint: String,
    val onset: String?,
    @SerializedName("duration_bucket") val durationBucket: String?,
    @SerializedName("severity_score") val severityScore: Int?,
    @SerializedName("aggravating_factors") val aggravatingFactors: String?,
    @SerializedName("relieving_factors") val relievingFactors: String?,
    @SerializedName("impact_on_daily_activities") val impactOnDailyActivities: String?,
    @SerializedName("impact_on_daily_activities_provenance") val impactOnDailyActivitiesProvenance: String?,
    @SerializedName("relevant_history") val relevantHistory: String?,
    val transcription: String?,
    @SerializedName("created_at") val createdAt: Instant,
    @SerializedName("updated_at") val updatedAt: Instant,
) : SyncPayload

/** [uri] is sent under the wire key `uri`, deliberately not renamed to `local_uri`: the backend's
 *  TableSpec aliases `uri` -> its own `local_uri` column server-side. Sending `local_uri` directly
 *  would be rejected as an unrecognized field. */
data class AttachmentSyncPayloadDto(
    @SerializedName("consultation_id") val consultationId: String,
    val type: String,
    val uri: String,
    @SerializedName("created_at") val createdAt: Instant,
) : SyncPayload

/** No `synced_to_cloud_at`: server-stamped on apply (TableSpec.server_owned), sending it is a
 *  SAMD-SYNC-6003 unexpected-field rejection. */
data class ObservationSyncPayloadDto(
    @SerializedName("patient_id") val patientId: String,
    @SerializedName("encounter_id") val encounterId: String,
    val type: String,
    @SerializedName("value_numeric") val valueNumeric: Double?,
    @SerializedName("value_text") val valueText: String?,
    val unit: String?,
    @SerializedName("device_id") val deviceId: String?,
    val source: String,
    @SerializedName("capture_method") val captureMethod: String?,
    @SerializedName("recorded_at") val recordedAt: Instant,
    @SerializedName("created_at") val createdAt: Instant,
) : SyncPayload

/** No `audio_local_uri` (forbidden, SAMD-SYNC-6006, REQ-AIL-03 — private-ailment audio never
 *  leaves the device) and no `synced_to_cloud_at` (server-stamped). Note visibility=PRIVATE rows
 *  DO sync otherwise, clinical text included (api-contract.md §6.1's own note) — only the audio
 *  field is excluded. */
data class AilmentSyncPayloadDto(
    @SerializedName("patient_id") val patientId: String,
    @SerializedName("encounter_id") val encounterId: String,
    val description: String,
    @SerializedName("measurement_type") val measurementType: String,
    val visibility: String,
    @SerializedName("measured_value") val measuredValue: Double?,
    @SerializedName("measured_unit") val measuredUnit: String?,
    val severity: Int?,
    val onset: String?,
    val duration: String?,
    val qualifiers: String?,
    @SerializedName("captured_at_offline") val capturedAtOffline: Instant,
    @SerializedName("deleted_at") val deletedAt: Instant?,
    @SerializedName("created_at") val createdAt: Instant,
) : SyncPayload

data class MedicalHistoryItemSyncPayloadDto(
    @SerializedName("patient_id") val patientId: String,
    val category: String,
    val description: String,
    @SerializedName("year_or_date") val yearOrDate: String?,
    @SerializedName("created_at") val createdAt: Instant,
) : SyncPayload

data class AllergySyncPayloadDto(
    @SerializedName("patient_id") val patientId: String,
    val category: String,
    val allergen: String,
    @SerializedName("reaction_type") val reactionType: String?,
    @SerializedName("created_at") val createdAt: Instant,
) : SyncPayload

data class FamilyHistoryEntrySyncPayloadDto(
    @SerializedName("patient_id") val patientId: String,
    val condition: String,
    val relation: String?,
    @SerializedName("created_at") val createdAt: Instant,
) : SyncPayload

/** No `patient_id` field here: `social_histories.patient_id` IS the primary key and travels as
 *  [SyncRecordDto.id], not inside `data` (mirrors how `patients.id`/etc. are never repeated in
 *  their own `data` object either). */
data class SocialHistorySyncPayloadDto(
    val occupation: String?,
    @SerializedName("tobacco_use") val tobaccoUse: String?,
    @SerializedName("alcohol_use") val alcoholUse: String?,
    @SerializedName("recreational_drug_use") val recreationalDrugUse: String?,
    @SerializedName("environmental_exposure") val environmentalExposure: String?,
    @SerializedName("recent_travel") val recentTravel: String?,
    @SerializedName("updated_at") val updatedAt: Instant,
) : SyncPayload

data class MedicationEntrySyncPayloadDto(
    @SerializedName("patient_id") val patientId: String,
    @SerializedName("encounter_id") val encounterId: String?,
    val kind: String,
    val name: String,
    val dosage: String?,
    val frequency: String?,
    val active: Boolean,
    @SerializedName("created_at") val createdAt: Instant,
) : SyncPayload

/** `status` here is the clinical [com.example.samdapp.domain.model.CaseStatus] workflow field
 *  (e.g. `PENDING_SYNC`, `SENT_TO_DOCTOR`) — an ordinary clinical column on this table, wholly
 *  distinct from the transport-level `sync_state` MIGRATION_12_13 added and this very payload is
 *  built to drain. See SyncStatusImpl's KDoc for why draining this row's transport state must
 *  never touch [com.example.samdapp.data.local.entity.CaseRecordEntity.status]. */
data class CaseRecordSyncPayloadDto(
    @SerializedName("patient_id") val patientId: String,
    @SerializedName("encounter_id") val encounterId: String,
    val status: String,
    @SerializedName("assigned_doctor_id") val assignedDoctorId: String?,
    @SerializedName("created_at") val createdAt: Instant,
    @SerializedName("updated_at") val updatedAt: Instant,
) : SyncPayload

data class KernelReportSyncPayloadDto(
    @SerializedName("case_record_id") val caseRecordId: String,
    @SerializedName("predicted_condition") val predictedCondition: String,
    @SerializedName("confidence_score") val confidenceScore: Double,
    val differentials: List<String>,
    @SerializedName("reasoning_summary") val reasoningSummary: String,
    @SerializedName("evidence_for") val evidenceFor: List<String>,
    @SerializedName("evidence_against") val evidenceAgainst: List<String>,
    @SerializedName("model_version") val modelVersion: String,
    @SerializedName("icd_code") val icdCode: String?,
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("software_version") val softwareVersion: String,
    @SerializedName("data_quality_score") val dataQualityScore: Double?,
    @SerializedName("uncertainty_score") val uncertaintyScore: Double?,
    @SerializedName("risk_category") val riskCategory: String,
    @SerializedName("urgency_level") val urgencyLevel: String,
    @SerializedName("inference_started_at") val inferenceStartedAt: Instant,
    @SerializedName("inference_ended_at") val inferenceEndedAt: Instant,
    @SerializedName("required_human_verification") val requiredHumanVerification: Boolean,
    @SerializedName("inference_source") val inferenceSource: String,
) : SyncPayload

/** [payloadJson] travels as a JSON *string* (matches the Kotlin entity's own `String` column):
 *  the backend's evaluate_reports handler explicitly `json.loads()`s this field before storing it
 *  to its JSONB column (backend/core/app/services/sync.py) rather than accepting a nested object
 *  directly. */
data class EvaluateReportSyncPayloadDto(
    @SerializedName("case_record_id") val caseRecordId: String,
    @SerializedName("payload_json") val payloadJson: String,
    @SerializedName("inference_started_at") val inferenceStartedAt: Instant,
    @SerializedName("inference_ended_at") val inferenceEndedAt: Instant,
) : SyncPayload

data class DiagnosisFeedbackSyncPayloadDto(
    @SerializedName("case_record_id") val caseRecordId: String,
    @SerializedName("icd_candidate") val icdCandidate: String,
    @SerializedName("physician_decision") val physicianDecision: String,
    @SerializedName("physician_final_diagnosis") val physicianFinalDiagnosis: String?,
    @SerializedName("clinical_note") val clinicalNote: String?,
    @SerializedName("created_at") val createdAt: Instant,
) : SyncPayload

data class PrescriptionSyncPayloadDto(
    @SerializedName("patient_id") val patientId: String,
    @SerializedName("encounter_id") val encounterId: String,
    @SerializedName("case_record_id") val caseRecordId: String,
    @SerializedName("doctor_id") val doctorId: String,
    val diagnosis: String,
    @SerializedName("kernel_decision") val kernelDecision: String?,
    @SerializedName("created_at") val createdAt: Instant,
) : SyncPayload

data class MedicationLineSyncPayloadDto(
    @SerializedName("prescription_id") val prescriptionId: String,
    val position: Int,
    @SerializedName("generic_name") val genericName: String,
    @SerializedName("brand_name") val brandName: String?,
    val strength: String,
    val dosage: String,
    val frequency: String,
    val route: String,
    val duration: String,
    val quantity: String,
    @SerializedName("food_relation") val foodRelation: String?,
    val instructions: String?,
) : SyncPayload

/** [patientUid] is sent under the wire key `patient_uid`, deliberately not `patient_id`: the
 *  backend's `Referral.patient_uid` column KDoc is explicit that it "keeps the device's field
 *  name rather than being renamed" (backend/core/app/models/referral.py). */
data class ReferralSyncPayloadDto(
    @SerializedName("patient_uid") val patientUid: String,
    @SerializedName("case_record_id") val caseRecordId: String,
    @SerializedName("urgency_level") val urgencyLevel: String,
    val reason: String,
    @SerializedName("sending_phc_id") val sendingPhcId: String,
    val status: String,
    val timestamp: Instant,
) : SyncPayload

/** No `mobile_blind_idx`: server-computed (TableSpec.server_owned) from [mobileNumber], and
 *  [AbhaProfileEntity] has no such field to send in the first place. */
data class AbhaProfileSyncPayloadDto(
    @SerializedName("abha_address") val abhaAddress: String?,
    val name: String,
    @SerializedName("date_of_birth") val dateOfBirth: LocalDate?,
    val gender: String,
    val address: String?,
    val district: String?,
    val state: String?,
    val pincode: String?,
    @SerializedName("mobile_number") val mobileNumber: String?,
    @SerializedName("email_address") val emailAddress: String?,
    @SerializedName("photo_url_mock") val photoUrlMock: String?,
    @SerializedName("kyc_verified") val kycVerified: Boolean,
    @SerializedName("created_at") val createdAt: Instant,
) : SyncPayload

/** audit_log is append-only server side (REQ-AUD-02) and bypasses TABLE_REGISTRY entirely
 *  (backend/core/app/services/sync.py's `_apply_audit_log`): exactly these six keys are the
 *  allowed set (`_AUDIT_LOG_ALLOWED_KEYS`), and [SyncRecordMappers.toSyncRecord] sends `op =
 *  "insert"` for this table, never `"upsert"`. */
data class AuditLogSyncPayloadDto(
    val timestamp: Instant,
    @SerializedName("user_id") val userId: String,
    @SerializedName("patient_id") val patientId: String?,
    @SerializedName("case_record_id") val caseRecordId: String?,
    val action: String,
    val payload: String,
) : SyncPayload
