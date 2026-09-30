package com.example.samdapp.domain.kernel

import com.example.samdapp.domain.model.RiskCategory
import com.example.samdapp.domain.model.UrgencyLevel

/**
 * How a `/v1/assess` result becomes the urgency, risk and verification stored on a kernel report.
 * Mirrored verbatim by `backend/core/app/domain/kernel_derivation.py`; change both together.
 *
 * Every token the classifier emits is listed explicitly (SaMDClassifier `src/app.py`, captured as
 * real output under `app/src/test/resources/classifier-fixtures/`). Anything else fails closed:
 * an urgency it does not know becomes at least [UrgencyLevel.URGENT], a class it does not know
 * becomes [RiskCategory.HIGH], and either one requires physician verification. A silent default
 * is how `EMERGENCY_REFERRAL` was stored as ROUTINE, LOW risk, no verification.
 *
 * Risk comes from the predicted class, never from confidence. The previous rule mapped HIGH
 * confidence to LOW risk, so a confident `high_risk` prediction was stored as LOW (D-11).
 */
object KernelTriageRules {

    /** The differential the classifier's deterministic red-flag gate emits. Not a model class,
     *  and its probability of 1.0 is a literal, so it must be recognised by name, never by score. */
    const val CRITICAL_VITALS_FLAG = "critical_vitals_flag"

    const val HUMAN_VERIFICATION_CONFIDENCE_THRESHOLD = 0.90

    private val URGENCY_BY_TOKEN = mapOf(
        "EMERGENCY_REFERRAL" to UrgencyLevel.EMERGENCY,
        // Defensive aliases: not emitted by the classifier today, but an emergency token must
        // never be downgraded by the fail-closed default to URGENT.
        "EMERGENCY" to UrgencyLevel.EMERGENCY,
        "EMERGENT" to UrgencyLevel.EMERGENCY,
        "URGENT" to UrgencyLevel.URGENT,
        "MONITOR" to UrgencyLevel.ROUTINE,
        "ROUTINE" to UrgencyLevel.ROUTINE,
    )

    private val RISK_BY_CLASS = mapOf(
        CRITICAL_VITALS_FLAG to RiskCategory.HIGH,
        "high_risk" to RiskCategory.HIGH,
        "moderate_risk" to RiskCategory.MODERATE,
        "low_risk" to RiskCategory.LOW,
    )

    /** Which part of the response was not recognised. Typed so the audit trail can say which. */
    enum class UnrecognisedField(val wireName: String) {
        TRIAGE_URGENCY("triage_urgency"),
        CONDITION_TIER("condition_tier"),
    }

    data class Triage(
        val urgency: UrgencyLevel,
        val risk: RiskCategory,
        val requiresVerification: Boolean,
        val unrecognised: Map<UnrecognisedField, String>,
    ) {
        val isRuleBasedEmergency: Boolean get() = urgency == UrgencyLevel.EMERGENCY
    }

    fun triage(triageUrgency: String, predictedCondition: String, confidence: Double): Triage {
        val unrecognised = mutableMapOf<UnrecognisedField, String>()
        val urgency = URGENCY_BY_TOKEN[triageUrgency.uppercase()]
            ?: UrgencyLevel.URGENT.also { unrecognised[UnrecognisedField.TRIAGE_URGENCY] = triageUrgency }
        val risk = RISK_BY_CLASS[predictedCondition]
            ?: RiskCategory.HIGH.also { unrecognised[UnrecognisedField.CONDITION_TIER] = predictedCondition }
        val requiresVerification = urgency == UrgencyLevel.EMERGENCY ||
            confidence < HUMAN_VERIFICATION_CONFIDENCE_THRESHOLD ||
            unrecognised.isNotEmpty()
        return Triage(urgency, risk, requiresVerification, unrecognised)
    }

    /** The red-flag result, identified by what it is (its differential or its urgency), which is
     *  what the report screen uses to show a rule-based emergency instead of a percentage. */
    fun isRuleBasedEmergency(predictedCondition: String?, urgency: UrgencyLevel): Boolean =
        predictedCondition == CRITICAL_VITALS_FLAG || urgency == UrgencyLevel.EMERGENCY
}
