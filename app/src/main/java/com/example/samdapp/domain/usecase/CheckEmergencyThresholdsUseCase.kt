package com.example.samdapp.domain.usecase

import javax.inject.Inject

/** [triggered] true if any threshold in [CheckEmergencyThresholdsUseCase] was crossed; [reasons]
 *  is the human-readable list shown on the full-screen emergency override (REQ-TRS-02). */
data class EmergencyFlag(val triggered: Boolean, val reasons: List<String>)

/**
 * Hard-coded critical-vitals thresholds (REQ-TRS-02). Crossing any of these must short-circuit
 * straight past the offline-sync queue — store-and-forward telemedicine is explicitly disallowed
 * for acute emergencies, so this check runs on the Compounder screen, before Consultation/Sending/
 * doctor assignment are ever reached, not after.
 *
 * Thresholds are deliberately conservative (favor a false alarm over a missed emergency) and are
 * a starting point for clinical review, not a finished clinical decision rule.
 */
class CheckEmergencyThresholdsUseCase @Inject constructor() {
    companion object {
        const val SPO2_FLOOR_PERCENT = 90
        const val BP_SYSTOLIC_CEILING_MMHG = 180
        const val BP_SYSTOLIC_FLOOR_MMHG = 90
        const val BP_DIASTOLIC_CEILING_MMHG = 120
    }

    operator fun invoke(spo2Percent: Int?, bpSystolic: Int?, bpDiastolic: Int?): EmergencyFlag {
        val reasons = buildList {
            if (spo2Percent != null && spo2Percent < SPO2_FLOOR_PERCENT) {
                add("SpO2 $spo2Percent% is below the $SPO2_FLOOR_PERCENT% floor")
            }
            if (bpSystolic != null && (bpSystolic >= BP_SYSTOLIC_CEILING_MMHG || bpSystolic < BP_SYSTOLIC_FLOOR_MMHG)) {
                add("Systolic BP $bpSystolic mmHg is outside the safe $BP_SYSTOLIC_FLOOR_MMHG–$BP_SYSTOLIC_CEILING_MMHG mmHg range")
            }
            if (bpDiastolic != null && bpDiastolic >= BP_DIASTOLIC_CEILING_MMHG) {
                add("Diastolic BP $bpDiastolic mmHg is at or above the $BP_DIASTOLIC_CEILING_MMHG mmHg ceiling")
            }
        }
        return EmergencyFlag(triggered = reasons.isNotEmpty(), reasons = reasons)
    }
}
