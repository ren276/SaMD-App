package com.example.samdapp.data.sync

import com.example.samdapp.data.local.entity.AbhaProfileEntity
import com.example.samdapp.data.local.entity.AilmentEntity
import com.example.samdapp.data.local.entity.AllergyEntity
import com.example.samdapp.data.local.entity.AttachmentEntity
import com.example.samdapp.data.local.entity.AuditLogEntity
import com.example.samdapp.data.local.entity.CaseRecordEntity
import com.example.samdapp.data.local.entity.ConsultationEntity
import com.example.samdapp.data.local.entity.DiagnosisFeedbackEntity
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
import com.example.samdapp.data.remote.dto.AbhaProfileSyncPayloadDto
import com.example.samdapp.data.remote.dto.AilmentSyncPayloadDto
import com.example.samdapp.data.remote.dto.AllergySyncPayloadDto
import com.example.samdapp.data.remote.dto.AttachmentSyncPayloadDto
import com.example.samdapp.data.remote.dto.AuditLogSyncPayloadDto
import com.example.samdapp.data.remote.dto.CaseRecordSyncPayloadDto
import com.example.samdapp.data.remote.dto.ConsultationSyncPayloadDto
import com.example.samdapp.data.remote.dto.DiagnosisFeedbackSyncPayloadDto
import com.example.samdapp.data.remote.dto.EncounterSyncPayloadDto
import com.example.samdapp.data.remote.dto.EvaluateReportSyncPayloadDto
import com.example.samdapp.data.remote.dto.FamilyHistoryEntrySyncPayloadDto
import com.example.samdapp.data.remote.dto.KernelReportSyncPayloadDto
import com.example.samdapp.data.remote.dto.MedicalHistoryItemSyncPayloadDto
import com.example.samdapp.data.remote.dto.MedicationEntrySyncPayloadDto
import com.example.samdapp.data.remote.dto.MedicationLineSyncPayloadDto
import com.example.samdapp.data.remote.dto.ObservationSyncPayloadDto
import com.example.samdapp.data.remote.dto.PatientSyncPayloadDto
import com.example.samdapp.data.remote.dto.PrescriptionSyncPayloadDto
import com.example.samdapp.data.remote.dto.ReferralSyncPayloadDto
import com.example.samdapp.data.remote.dto.SocialHistorySyncPayloadDto
import com.example.samdapp.data.remote.dto.SyncRecordDto

/** One `toSyncRecord()` per syncable entity — table name, `op`, envelope fields (`id`,
 *  `client_updated_at` from [localModifiedAt], `base_version` from `serverVersion`), and the
 *  table's [com.example.samdapp.data.remote.dto.SyncPayload]. `op` is `"insert"` only for
 *  `audit_log` (append-only, api-contract.md §6.1); every other table is `"upsert"`. */

fun PatientEntity.toSyncRecord() = SyncRecordDto(
    table = "patients", op = "upsert", id = id,
    clientUpdatedAt = localModifiedAt, baseVersion = serverVersion,
    data = PatientSyncPayloadDto(
        fullName = fullName, dateOfBirth = dateOfBirth, age = age, biologicalSex = biologicalSex,
        guardianOrSpouseName = guardianOrSpouseName, guardianRelation = guardianRelation,
        mobileNumber = mobileNumber, aadhaarNumber = aadhaarNumber, abhaNumber = abhaNumber,
        village = village, block = block, district = district, state = state, pincode = pincode,
        category = category, maritalStatus = maritalStatus, bloodGroup = bloodGroup,
        emergencyContact = emergencyContact, primaryCareClinicName = primaryCareClinicName,
        referringPhysicianName = referringPhysicianName, createdAt = createdAt, updatedAt = updatedAt,
    ),
)

fun EncounterEntity.toSyncRecord() = SyncRecordDto(
    table = "encounters", op = "upsert", id = id,
    clientUpdatedAt = localModifiedAt, baseVersion = serverVersion,
    data = EncounterSyncPayloadDto(
        patientId = patientId, startedAt = startedAt, createdAt = createdAt, updatedAt = updatedAt,
        followUpOfEncounterId = followUpOfEncounterId,
    ),
)

fun ConsultationEntity.toSyncRecord() = SyncRecordDto(
    table = "consultations", op = "upsert", id = id,
    clientUpdatedAt = localModifiedAt, baseVersion = serverVersion,
    data = ConsultationSyncPayloadDto(
        patientId = patientId, encounterId = encounterId, chiefComplaint = chiefComplaint,
        onset = onset, durationBucket = durationBucket, severityScore = severityScore,
        aggravatingFactors = aggravatingFactors, relievingFactors = relievingFactors,
        impactOnDailyActivities = impactOnDailyActivities,
        impactOnDailyActivitiesProvenance = impactOnDailyActivitiesProvenance?.name,
        relevantHistory = relevantHistory,
        transcription = transcription, createdAt = createdAt, updatedAt = updatedAt,
    ),
)

fun AttachmentEntity.toSyncRecord() = SyncRecordDto(
    table = "attachments", op = "upsert", id = id,
    clientUpdatedAt = localModifiedAt, baseVersion = serverVersion,
    data = AttachmentSyncPayloadDto(
        consultationId = consultationId, type = type.name, uri = uri, createdAt = createdAt,
    ),
)

fun ObservationEntity.toSyncRecord() = SyncRecordDto(
    table = "observations", op = "upsert", id = id,
    clientUpdatedAt = localModifiedAt, baseVersion = serverVersion,
    data = ObservationSyncPayloadDto(
        patientId = patientId, encounterId = encounterId, type = type.name,
        valueNumeric = valueNumeric, valueText = valueText, unit = unit, deviceId = deviceId,
        source = source.name, captureMethod = captureMethod?.name, recordedAt = recordedAt,
        createdAt = createdAt,
    ),
)

fun AilmentEntity.toSyncRecord() = SyncRecordDto(
    table = "ailments", op = "upsert", id = id,
    clientUpdatedAt = localModifiedAt, baseVersion = serverVersion,
    data = AilmentSyncPayloadDto(
        patientId = patientId, encounterId = encounterId, description = description,
        measurementType = measurementType.name, visibility = visibility.name,
        measuredValue = measuredValue, measuredUnit = measuredUnit, severity = severity,
        onset = onset, duration = duration, qualifiers = qualifiers,
        capturedAtOffline = capturedAtOffline, deletedAt = deletedAt, createdAt = createdAt,
    ),
)

fun MedicalHistoryItemEntity.toSyncRecord() = SyncRecordDto(
    table = "medical_history_items", op = "upsert", id = id,
    clientUpdatedAt = localModifiedAt, baseVersion = serverVersion,
    data = MedicalHistoryItemSyncPayloadDto(
        patientId = patientId, category = category.name, description = description,
        yearOrDate = yearOrDate, createdAt = createdAt,
    ),
)

fun AllergyEntity.toSyncRecord() = SyncRecordDto(
    table = "allergies", op = "upsert", id = id,
    clientUpdatedAt = localModifiedAt, baseVersion = serverVersion,
    data = AllergySyncPayloadDto(
        patientId = patientId, category = category.name, allergen = allergen,
        reactionType = reactionType, createdAt = createdAt,
    ),
)

fun FamilyHistoryEntryEntity.toSyncRecord() = SyncRecordDto(
    table = "family_history_entries", op = "upsert", id = id,
    clientUpdatedAt = localModifiedAt, baseVersion = serverVersion,
    data = FamilyHistoryEntrySyncPayloadDto(
        patientId = patientId, condition = condition, relation = relation, createdAt = createdAt,
    ),
)

fun SocialHistoryEntity.toSyncRecord() = SyncRecordDto(
    table = "social_histories", op = "upsert", id = patientId,
    clientUpdatedAt = localModifiedAt, baseVersion = serverVersion,
    data = SocialHistorySyncPayloadDto(
        occupation = occupation, tobaccoUse = tobaccoUse, alcoholUse = alcoholUse,
        recreationalDrugUse = recreationalDrugUse, environmentalExposure = environmentalExposure,
        recentTravel = recentTravel, updatedAt = updatedAt,
    ),
)

fun MedicationEntryEntity.toSyncRecord() = SyncRecordDto(
    table = "medication_entries", op = "upsert", id = id,
    clientUpdatedAt = localModifiedAt, baseVersion = serverVersion,
    data = MedicationEntrySyncPayloadDto(
        patientId = patientId, encounterId = encounterId, kind = kind.name, name = name,
        dosage = dosage, frequency = frequency, active = active, createdAt = createdAt,
    ),
)

fun CaseRecordEntity.toSyncRecord() = SyncRecordDto(
    table = "case_records", op = "upsert", id = id,
    clientUpdatedAt = localModifiedAt, baseVersion = serverVersion,
    data = CaseRecordSyncPayloadDto(
        patientId = patientId, encounterId = encounterId, status = status.name,
        assignedDoctorId = assignedDoctorId, createdAt = createdAt, updatedAt = updatedAt,
    ),
)

fun KernelReportEntity.toSyncRecord() = SyncRecordDto(
    table = "kernel_reports", op = "upsert", id = id,
    clientUpdatedAt = localModifiedAt, baseVersion = serverVersion,
    data = KernelReportSyncPayloadDto(
        caseRecordId = caseRecordId, predictedCondition = predictedCondition,
        confidenceScore = confidenceScore, differentials = differentials,
        reasoningSummary = reasoningSummary, evidenceFor = evidenceFor,
        evidenceAgainst = evidenceAgainst, modelVersion = modelVersion, icdCode = icdCode,
        deviceId = deviceId, softwareVersion = softwareVersion, dataQualityScore = dataQualityScore,
        uncertaintyScore = uncertaintyScore, riskCategory = riskCategory.name,
        urgencyLevel = urgencyLevel.name, inferenceStartedAt = inferenceStartedAt,
        inferenceEndedAt = inferenceEndedAt, requiredHumanVerification = requiredHumanVerification,
        inferenceSource = inferenceSource.name,
    ),
)

fun EvaluateReportEntity.toSyncRecord() = SyncRecordDto(
    table = "evaluate_reports", op = "upsert", id = id,
    clientUpdatedAt = localModifiedAt, baseVersion = serverVersion,
    data = EvaluateReportSyncPayloadDto(
        caseRecordId = caseRecordId, payloadJson = payloadJson,
        inferenceStartedAt = inferenceStartedAt, inferenceEndedAt = inferenceEndedAt,
    ),
)

fun DiagnosisFeedbackEntity.toSyncRecord() = SyncRecordDto(
    table = "diagnosis_feedback", op = "upsert", id = id,
    clientUpdatedAt = localModifiedAt, baseVersion = serverVersion,
    data = DiagnosisFeedbackSyncPayloadDto(
        caseRecordId = caseRecordId, icdCandidate = icdCandidate,
        physicianDecision = physicianDecision.name, physicianFinalDiagnosis = physicianFinalDiagnosis,
        clinicalNote = clinicalNote, createdAt = createdAt,
    ),
)

fun PrescriptionEntity.toSyncRecord() = SyncRecordDto(
    table = "prescriptions", op = "upsert", id = id,
    clientUpdatedAt = localModifiedAt, baseVersion = serverVersion,
    data = PrescriptionSyncPayloadDto(
        patientId = patientId, encounterId = encounterId, caseRecordId = caseRecordId,
        doctorId = doctorId, diagnosis = diagnosis, kernelDecision = kernelDecision?.name,
        createdAt = createdAt,
    ),
)

fun MedicationLineEntity.toSyncRecord() = SyncRecordDto(
    table = "medication_lines", op = "upsert", id = id,
    clientUpdatedAt = localModifiedAt, baseVersion = serverVersion,
    data = MedicationLineSyncPayloadDto(
        prescriptionId = prescriptionId, position = position, genericName = genericName,
        brandName = brandName, strength = strength, dosage = dosage, frequency = frequency,
        route = route, duration = duration, quantity = quantity, foodRelation = foodRelation,
        instructions = instructions,
    ),
)

fun ReferralEntity.toSyncRecord() = SyncRecordDto(
    table = "referrals", op = "upsert", id = id,
    clientUpdatedAt = localModifiedAt, baseVersion = serverVersion,
    data = ReferralSyncPayloadDto(
        patientUid = patientUid, caseRecordId = caseRecordId, urgencyLevel = urgencyLevel.name,
        reason = reason, sendingPhcId = sendingPhcId, status = status.name, timestamp = timestamp,
    ),
)

fun AbhaProfileEntity.toSyncRecord() = SyncRecordDto(
    table = "abha_profiles", op = "upsert", id = abhaId,
    clientUpdatedAt = localModifiedAt, baseVersion = serverVersion,
    data = AbhaProfileSyncPayloadDto(
        abhaAddress = abhaAddress, name = name, dateOfBirth = dateOfBirth, gender = gender,
        address = address, district = district, state = state, pincode = pincode,
        mobileNumber = mobileNumber, emailAddress = emailAddress, photoUrlMock = photoUrlMock,
        kycVerified = kycVerified, createdAt = createdAt,
    ),
)

fun AuditLogEntity.toSyncRecord() = SyncRecordDto(
    table = "audit_log", op = "insert", id = id,
    clientUpdatedAt = localModifiedAt, baseVersion = serverVersion,
    data = AuditLogSyncPayloadDto(
        timestamp = timestamp, userId = userId, patientId = patientId,
        caseRecordId = caseRecordId, action = action, payload = payload,
    ),
)
