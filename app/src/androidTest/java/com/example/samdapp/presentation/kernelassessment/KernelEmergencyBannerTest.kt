package com.example.samdapp.presentation.kernelassessment

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The classifier's red-flag result must read as an emergency, never as a 100% model confidence:
 * its 1.0 is a literal from a rule, not a probability.
 */
class KernelEmergencyBannerTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun display(ruleBasedEmergency: Boolean, percent: Int) = AssessmentDisplay(
        predictedCondition = if (ruleBasedEmergency) "critical_vitals_flag" else "moderate_risk",
        icdCode = null,
        confidencePercent = percent,
        requiresHumanVerification = ruleBasedEmergency,
        isMockFallback = false,
        isUnavailable = false,
        failure = null,
        sourceLabel = "Real-time AI inference (/v1/assess)",
        differentialLines = emptyList(),
        reasoningLines = emptyList(),
        evidenceFor = emptyList(),
        evidenceAgainst = emptyList(),
        isRuleBasedEmergency = ruleBasedEmergency,
    )

    @Test
    fun ruleBasedEmergencyShowsTheBannerAndNoPercentage() {
        composeRule.setContent { ConfidenceGauge(display(ruleBasedEmergency = true, percent = 100)) }

        composeRule.onNodeWithTag(EMERGENCY_BANNER_TAG).assertExists()
        composeRule.onNodeWithText("Critical vitals (rule-based)").assertExists()
        composeRule.onNodeWithTag(CONFIDENCE_BAR_TAG).assertDoesNotExist()
        composeRule.onNodeWithText("100%").assertDoesNotExist()
    }

    @Test
    fun aModelResultShowsTheConfidenceBarAndNoBanner() {
        composeRule.setContent { ConfidenceGauge(display(ruleBasedEmergency = false, percent = 98)) }

        composeRule.onNodeWithTag(CONFIDENCE_BAR_TAG).assertExists()
        composeRule.onNodeWithText("98%").assertExists()
        composeRule.onNodeWithTag(EMERGENCY_BANNER_TAG).assertDoesNotExist()
    }

    @Test
    fun theVerificationNoticeNamesTheRealReason() {
        val emergency = verificationNotice(display(ruleBasedEmergency = true, percent = 100))
        assertTrue(emergency, emergency.contains("critical vitals"))
        assertTrue(emergency, !emergency.contains("below 90%"))
    }
}
