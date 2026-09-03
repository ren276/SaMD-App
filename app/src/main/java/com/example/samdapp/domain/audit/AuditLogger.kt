package com.example.samdapp.domain.audit

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

interface AuditLogger {
    suspend fun log(action: AuditAction, patientId: String? = null, caseRecordId: String? = null, payload: String)
}

/**
 * The complete, enforced audit action vocabulary. [AuditLogger.log] takes [AuditAction], not
 * [String]: that is the enforcement mechanism (S4 of the audit-logging-discipline session that
 * created this list), not a lint rule or a scanning test on top of it. [RoomAuditLogger] is the
 * only place that ever constructs an `AuditLogEntity`, so narrowing this one signature closes
 * every call site at compile time; a free-text literal cannot reach `.log()` again.
 *
 * [value] is the wire string, stored verbatim in `AuditLogEntity.action` and read back unchanged
 * by the backend on sync push. It is written out explicitly per entry rather than derived from
 * the enum constant's own name, so that renaming a Kotlin identifier for readability can never
 * silently change a value already sitting in shipped audit rows. Do not rename any [value]: it is
 * the wire contract this list exists to pin down, and this list is the exact input the backend
 * sync-push session mirrors into its own accepted-action set next.
 */
enum class AuditAction(val value: String) {
    ABHA_PROFILE_CREATED("abha_profile_created"),
    ABHA_LOGIN_VERIFIED("abha_login_verified"),
    AILMENT_CAPTURED("ailment_captured"),
    AILMENT_DELETED("ailment_deleted"),
    ALLERGY_ADDED("allergy_added"),
    ATTACHMENT_ADDED("attachment_added"),
    AUDIO_CAPTURED("audio_captured"),
    CASE_QUEUED_FOR_SYNC("case_queued_for_sync"),
    CASE_SENT_TO_DOCTOR("case_sent_to_doctor"),
    CONSENT_RECORDED("consent_recorded"),
    CONSULTATION_LOCKED("consultation_locked"),
    CONSULTATION_SAVED("consultation_saved"),
    EMERGENCY_OVERRIDE("emergency_override"),
    ENCOUNTER_STARTED("encounter_started"),
    FAMILY_HISTORY_ADDED("family_history_added"),
    MEDICAL_HISTORY_ITEM_ADDED("medical_history_item_added"),
    MEDICATION_ADDED("medication_added"),
    PATIENT_REGISTERED("patient_registered"),
    REFERRAL_CREATED("referral_created"),

    /** [com.example.samdapp.data.local.dao.ReferralDao.updateStatus] exists and is real, but has
     *  no caller anywhere in this codebase (confirmed by grep, matching MIGRATION_12_13's own
     *  prior finding that this DAO method is "dormant, not active"). Kept, not deleted, because
     *  the mutation path it would log is real, deliberately-retained schema/DAO capability, not
     *  a hypothetical; wiring an actual `.log()` call in requires a live caller to attach it to,
     *  which does not exist today and is out of scope for an audit-logging-discipline session. */
    REFERRAL_STATUS_CHANGED("referral_status_changed"),

    REPORT_EXPORTED("report_exported"),
    SOCIAL_HISTORY_SAVED("social_history_saved"),
    TRANSCRIPTION_COMPLETED("transcription_completed"),
    VITALS_RECORDED("vitals_recorded"),

    /** Full raw `/api/v1/evaluate` response dump (inference start/end timestamps, diagnostic
     *  summary, NLEM treatment, brand mapping, safety/triage) — the complete backend data, not
     *  just the curated subset shown on the prescription page. Insert-only audit trail per
     *  REQ-AUD-02; the prescription/report only ever shows the curated view. */
    EVALUATE_RESPONSE_RECEIVED("evaluate_response_received"),

    /** The `/api/v1/evaluate` call failed — logged so the audit trail shows why no evaluate
     *  section appears on the report/prescription for this case. */
    EVALUATE_RESPONSE_FAILED("evaluate_response_failed"),

    /** Physician AGREE/MODIFY/REJECT decision on the AI's top diagnostic candidate — mirrors
     *  `refine_diagnosis.py`'s `DiagnosisFeedback` schema. See [com.example.samdapp.domain.model.DiagnosisFeedback] KDoc. */
    DIAGNOSIS_FEEDBACK_RECORDED("diagnosis_feedback_recorded"),

    /** Crash-recovery resume: the worker was dropped back into an already-`DRAFT` case rather than
     *  [com.example.samdapp.domain.usecase.StartCaseUseCase] minting a new encounter/case record —
     *  distinct from [ENCOUNTER_STARTED]. */
    ENCOUNTER_RESUMED("encounter_resumed"),

    KERNEL_ASSESSMENT_ACKNOWLEDGED("kernel_assessment_acknowledged"),
    KERNEL_RESPONSE_RECEIVED("kernel_response_received"),

    /** `/api/v1/assess` answered 200 but with an empty `differential_diagnosis`: the kernel was
     *  reached and ran, and produced no usable assessment. The worker sees the same
     *  [com.example.samdapp.domain.model.InferenceSource.UNAVAILABLE] state as an unreachable
     *  kernel, deliberately, since operationally the two are identical. This row is what lets
     *  field analysis tell them apart afterwards, because "the kernel is down" and "the kernel is
     *  returning empty differentials" are different root causes with different fixes. Emitted by
     *  [com.example.samdapp.domain.usecase.GenerateKernelReportUseCase] at the branch itself, so
     *  it covers the retry path as well as the initial send. Payload carries only server-verbatim
     *  values (triage urgency, model version, safety screen) plus a measured `differentialCount`
     *  of 0: never a condition string and never a confidence, since no such value legitimately
     *  exists for this case and the whole point of the fix is that none gets invented. */
    KERNEL_EMPTY_DIFFERENTIAL("kernel_empty_differential"),

    /** ASR track (`scratchpad/asr-field-audit-memo.md` B.4), one per state in the
     *  confirmation-gate model. Field-level provenance only, no transcript: `auditPayload("slot"
     *  to ..., "provenance" to ..., "asrModelId" to ..., "asrModelVersion" to ..., "charCount"
     *  to ..., "editDistance" to ...)`. Not emitted by anything yet; PR 3 wires the confirmation
     *  gate and its call sites. Kept ahead of that caller the same way [REFERRAL_STATUS_CHANGED]
     *  is: adding the value now and the backend mirror entry in the same commit is cheap, and a
     *  device action the mirror does not yet accept would be a silent, permanent sync rejection
     *  of every row the device sends once PR 3 starts emitting it. */
    VOICE_FIELD_SUGGESTED("voice_field_suggested"),
    VOICE_FIELD_CONFIRMED("voice_field_confirmed"),
    VOICE_FIELD_EDITED("voice_field_edited"),
    VOICE_FIELD_REJECTED("voice_field_rejected"),

    /** Prescription visibility gate (H-17, Build 1): the physician's AGREE/MODIFY/REJECT decision
     *  was committed and the prescription became worker-visible. Emitted once, at the commit, by
     *  [com.example.samdapp.domain.usecase.SubmitDoctorDecisionUseCase] — never the drug name. */
    PRESCRIPTION_APPROVED("prescription_approved"),

    /** Prescription visibility gate (H-17, Build 1): a worker-facing report was assembled with the
     *  gate satisfied, i.e. an approved/modified/rejected prescription outcome was actually
     *  surfaced to a non-physician viewer. Emitted once per gated-open report load by
     *  [com.example.samdapp.presentation.report.ReportViewModel] — never the drug name. */
    PRESCRIPTION_SURFACED_TO_WORKER("prescription_surfaced_to_worker"),
}

/** Builds the JSON blob stored in AuditLogEntity.payload from a flat set of fields. */
fun auditPayload(vararg fields: Pair<String, String?>): String =
    buildJsonObject { fields.forEach { (key, value) -> put(key, value) } }.toString()
