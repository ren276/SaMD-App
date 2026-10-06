package com.example.samdapp.presentation.kernelassessment

import androidx.annotation.StringRes
import com.example.samdapp.R
import com.example.samdapp.domain.kernel.KernelRetryAdvice

/**
 * Every fixed piece of text the assessment screen draws for one [AssessmentDisplay], chosen in
 * one pure place so the rule below is a JVM test rather than a hope.
 *
 * **No score, certainty, confidence or calibration language for anything but a real result.** An
 * UNAVAILABLE card has no result, and a MOCK_FALLBACK card (dev builds only) has a fabricated one;
 * telling a worker "the model is not certain about this result", or a physician "Model score below
 * 0.90 (uncalibrated)", under either of them describes a model output that does not exist. So for
 * those two the physician score bar and scored differentials are off ([showModelScore] false) and
 * the verification notice says why review is needed in their own terms.
 */
internal data class AssessmentCopy(
    /** The unavailable card's title and body; null when there is a result to show. */
    val failure: KernelFailureCopy?,
    /** Whether the unavailable card offers Try again (see [KernelRetryAdvice]). */
    val offerRetry: Boolean,
    /** Replaces the result's source label for a mock result; null otherwise. */
    @StringRes val mockSourceLabel: Int?,
    /** The mock-result banner; null otherwise. */
    @StringRes val mockNotice: Int?,
    /** The physician score bar and scored differentials. Real results only, physician only. */
    val showModelScore: Boolean,
    /** Why physician verification is required; null when it is not. */
    @StringRes val verificationNotice: Int?,
    @StringRes val liability: Int,
) {
    /** Every string resource this copy puts on screen, for the wording test. */
    fun textRes(): List<Int> = listOfNotNull(
        failure?.titleRes,
        failure?.bodyRes,
        R.string.kernel_failure_retry.takeIf { offerRetry },
        mockSourceLabel,
        mockNotice,
        R.string.assessment_model_score.takeIf { showModelScore },
        verificationNotice,
        liability,
    )
}

internal fun assessmentCopy(display: AssessmentDisplay, showModelScore: Boolean): AssessmentCopy {
    val isReal = !display.isUnavailable && !display.isMockFallback
    return AssessmentCopy(
        failure = kernelFailureCopy(display.failure).takeIf { display.isUnavailable },
        offerRetry = display.isUnavailable &&
            (display.failure == null || display.failure.advice == KernelRetryAdvice.RETRY_NOW),
        mockSourceLabel = R.string.assessment_source_mock.takeIf { display.isMockFallback },
        mockNotice = R.string.assessment_mock_notice.takeIf { display.isMockFallback },
        showModelScore = showModelScore && isReal,
        verificationNotice = if (display.requiresHumanVerification) verificationNoticeRes(display, showModelScore) else null,
        liability = if (display.isUnavailable) R.string.assessment_liability_unavailable else R.string.assessment_liability_ai,
    )
}

/**
 * Why verification is required, stated truthfully: an emergency or an unrecognised result is not
 * "model score below 0.90", and no result at all is neither. A worker is told the reason in plain
 * words with no number; only a physician is shown the score threshold, and only for a real result.
 * Emergency wording is the same for both roles.
 */
@StringRes
internal fun verificationNoticeRes(display: AssessmentDisplay, showModelScore: Boolean): Int = when {
    display.isCriticalVitalsFlag -> R.string.assessment_notice_critical_vitals
    display.isEmergency -> R.string.assessment_notice_emergency
    display.isUnavailable -> R.string.assessment_notice_no_result
    display.isMockFallback -> R.string.assessment_notice_mock
    display.confidencePercent < 90 ->
        if (showModelScore) R.string.assessment_notice_low_score_physician else R.string.assessment_notice_low_certainty_worker
    else -> R.string.assessment_notice_flagged
}
