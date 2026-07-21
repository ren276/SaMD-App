package com.example.samdapp.domain.audit

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

interface AuditLogger {
    suspend fun log(action: String, patientId: String? = null, caseRecordId: String? = null, payload: String)
}

/**
 * Canonical audit action strings for the overhaul. Constants (not free-text at call sites) keep the
 * event vocabulary greppable and consistent. The DAO stays insert-only (REQ-AUD-02) — these name
 * WHAT happened, they do not add mutation. Pre-existing screen actions remain string literals for
 * now; new Phase 2/2.5/6 actions must use these.
 */
object AuditAction {
    const val ABHA_PROFILE_CREATED = "abha_profile_created"
    const val ABHA_LOGIN_VERIFIED = "abha_login_verified"
    const val AILMENT_CAPTURED = "ailment_captured"
    const val AILMENT_VISIBILITY_CHANGED = "ailment_visibility_changed"
    const val AILMENT_DELETED = "ailment_deleted"
    const val CONSENT_RECORDED = "consent_recorded"
    const val EMERGENCY_OVERRIDE = "emergency_override"
    const val REFERRAL_CREATED = "referral_created"
    const val REFERRAL_STATUS_CHANGED = "referral_status_changed"
    const val KERNEL_ASSESSMENT_ACKNOWLEDGED = "kernel_assessment_acknowledged"
    const val KERNEL_RESPONSE_RECEIVED = "kernel_response_received"

    /** Crash-recovery resume: the worker was dropped back into an already-`DRAFT` case rather than
     *  [com.example.samdapp.domain.usecase.StartCaseUseCase] minting a new encounter/case record —
     *  distinct from the pre-existing `"encounter_started"` string literal. */
    const val ENCOUNTER_RESUMED = "encounter_resumed"
}

/** Builds the JSON blob stored in AuditLogEntity.payload from a flat set of fields. */
fun auditPayload(vararg fields: Pair<String, String?>): String =
    buildJsonObject { fields.forEach { (key, value) -> put(key, value) } }.toString()
