package com.example.samdapp.domain.model

import java.time.Instant

/** Referral urgency. Persisted as [name]; additive. */
enum class UrgencyLevel { ROUTINE, URGENT, EMERGENCY }

/** Referral lifecycle. Mock demo only reaches [QUEUED]; later states are forward-compat stubs. */
enum class ReferralStatus { QUEUED, SENT, ACKNOWLEDGED, CANCELLED }

/**
 * A referral of a case to a higher facility (CHC/District Hospital) — Phase 6. There is no
 * receiving-side system in the mock; this is the single PHC-side action. Creation, and any status
 * change, is logged via the audit trail.
 */
data class ReferralRequest(
    val id: String,
    val patientUid: String,
    val caseRecordId: String,
    val urgencyLevel: UrgencyLevel,
    val reason: String,
    val sendingPhcId: String,
    val status: ReferralStatus,
    val timestamp: Instant,
)
