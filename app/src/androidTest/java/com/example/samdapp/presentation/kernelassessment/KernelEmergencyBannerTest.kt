package com.example.samdapp.presentation.kernelassessment

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The classifier's red-flag result must read as an emergency, never as a 100% model confidence:
 * its 1.0 is a literal from a rule, not a probability. Any other EMERGENCY result is an emergency
 * too, but its prediction and confidence are the model's own, so they stay and it is never called
 * critical vitals.
 */
class KernelEmergencyBannerTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun display(emergency: Boolean, criticalVitals: Boolean, percent: Int) = AssessmentDisplay(
        predictedCondition = if (criticalVitals) "critical_vitals_flag" else "high_risk",
        icdCode = null,
        confidencePercent = percent,
        requiresHumanVerification = emergency,
        isMockFallback = false,
        isUnavailable = false,
        failure = null,
        sourceLabel = "Real-time AI inference (/v1/assess)",
        differentialLines = emptyList(),
        reasoningLines = emptyList(),
        evidenceFor = emptyList(),
        evidenceAgainst = emptyList(),
        isEmergency = emergency,
        isCriticalVitalsFlag = criticalVitals,
    )

    private val redFlag = display(emergency = true, criticalVitals = true, percent = 100)
    private val urgencyOnlyEmergency = display(emergency = true, criticalVitals = false, percent = 97)
    private val modelResult = display(emergency = false, criticalVitals = false, percent = 98)

    @Test
    fun theRedFlagShowsTheCriticalVitalsBannerAndNoPercentage() {
        composeRule.setContent { ConfidenceGauge(redFlag) }

        composeRule.onNodeWithTag(EMERGENCY_BANNER_TAG).assertExists()
        composeRule.onNodeWithText("Critical vitals (rule-based)").assertExists()
        composeRule.onNodeWithTag(CONFIDENCE_BAR_TAG).assertDoesNotExist()
        composeRule.onNodeWithText("100%").assertDoesNotExist()
    }

    @Test
    fun anEmergencyOnAModelClassKeepsItsConfidenceAndIsNotCalledCriticalVitals() {
        composeRule.setContent { ConfidenceGauge(urgencyOnlyEmergency) }

        composeRule.onNodeWithTag(EMERGENCY_BANNER_TAG).assertExists()
        composeRule.onNodeWithText("Emergency referral").assertExists()
        composeRule.onNodeWithText("Critical vitals (rule-based)").assertDoesNotExist()
        composeRule.onNodeWithTag(CONFIDENCE_BAR_TAG).assertExists()
        composeRule.onNodeWithText("97%").assertExists()
    }

    @Test
    fun aModelResultShowsTheConfidenceBarAndNoBanner() {
        composeRule.setContent { ConfidenceGauge(modelResult) }

        composeRule.onNodeWithTag(CONFIDENCE_BAR_TAG).assertExists()
        composeRule.onNodeWithText("98%").assertExists()
        composeRule.onNodeWithTag(EMERGENCY_BANNER_TAG).assertDoesNotExist()
    }

    @Test
    fun theVerificationNoticeNamesTheRealReason() {
        val redFlagNotice = verificationNotice(redFlag)
        assertTrue(redFlagNotice, redFlagNotice.contains("critical vitals") && !redFlagNotice.contains("below 90%"))
        val emergencyNotice = verificationNotice(urgencyOnlyEmergency)
        assertTrue(emergencyNotice, emergencyNotice.contains("Emergency referral") && !emergencyNotice.contains("critical vitals"))
    }
}
