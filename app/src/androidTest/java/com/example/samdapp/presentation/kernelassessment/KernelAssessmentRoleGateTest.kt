package com.example.samdapp.presentation.kernelassessment

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.example.samdapp.domain.auth.CadreTier
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The model's numeric score is shown to a physician only (memo D2). Role is self-asserted (H-06),
 * so this is an automation-bias control, not an access control. Workers of both non-physician
 * tiers get the prediction, the verification flag and a plain-words reason, and no number.
 *
 * The fixture deliberately holds legitimate percentages the worker MUST still see (an SpO2 in a
 * reasoning line), so a screen that hides every "%" cannot pass.
 */
class KernelAssessmentRoleGateTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val legitimateReasoning = "SpO2 88% is below the 90% floor."

    private val display = AssessmentDisplay(
        predictedCondition = "Pneumonia",
        icdCode = "J18",
        confidencePercent = 42,
        requiresHumanVerification = true,
        isMockFallback = false,
        isUnavailable = false,
        failure = null,
        sourceLabel = "Real-time AI inference (/api/v1/evaluate)",
        differentialLines = listOf(
            DifferentialLine(
                "J18",
                42,
                "Symptom model ranked J18 #1 (42.0%). Classifier A predicts moderate; confidence boosted to 42.0%.",
            ),
            DifferentialLine("A09", 31, "Symptom model ranked A09 #2 (31.2%)."),
        ),
        reasoningLines = listOf(legitimateReasoning),
        modelExplanationLines = listOf("Top candidate ranked #1 (42.0%), confidence 42.0%."),
        evidenceFor = listOf("cough"),
        evidenceAgainst = emptyList(),
    )

    private val actions = object : KernelAssessmentActions {
        override fun onLiabilityAcknowledgedChange(acknowledged: Boolean) = Unit
        override fun onContinue() = Unit
        override fun onRetry() = Unit
    }

    private fun show(tier: CadreTier) {
        composeRule.setContent {
            KernelAssessmentContent(
                uiState = KernelAssessmentUiState(isLoading = false, display = display, cadreTier = tier),
                actions = actions,
            )
        }
    }

    private fun allTexts(): List<String> = composeRule
        .onAllNodes(SemanticsMatcher("has text") { it.config.getOrNull(SemanticsProperties.Text) != null }, useUnmergedTree = true)
        .fetchSemanticsNodes()
        .flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }

    private fun assertNoScoreForWorker(tier: CadreTier) {
        show(tier)
        composeRule.onNodeWithTag(CONFIDENCE_BAR_TAG).assertDoesNotExist()
        val texts = allTexts()
        assertTrue("no text nodes found: the screen did not render", texts.isNotEmpty())
        assertTrue(texts.toString(), texts.none { it.contains("confidence", ignoreCase = true) })
        assertTrue(texts.toString(), texts.none { it.contains("score", ignoreCase = true) })
        val parenthesisedPercent = Regex("""\(\d+(\.\d+)?%\)""")
        assertTrue(texts.toString(), texts.none { parenthesisedPercent.containsMatchIn(it) })
        // The screen still tells the worker what matters, and legitimate figures survive.
        composeRule.onNodeWithText(legitimateReasoning).assertExists()
        composeRule.onNodeWithText("Physician verification required", substring = true).assertExists()
        composeRule.onNodeWithText("Predicted: Pneumonia (ICD-10: J18)").assertExists()
    }

    @Test
    fun aCommunityWorkerSeesNoModelScore() = assertNoScoreForWorker(CadreTier.COMMUNITY)

    @Test
    fun aLicensedClinicalWorkerSeesNoModelScore() = assertNoScoreForWorker(CadreTier.LICENSED_CLINICAL)

    @Test
    fun aPhysicianSeesTheModelScoreLabelledUncalibrated() {
        show(CadreTier.PHYSICIAN)

        composeRule.onNodeWithTag(CONFIDENCE_BAR_TAG).assertExists()
        composeRule.onNodeWithText("Model score 42% (uncalibrated)").assertExists()
        composeRule.onNodeWithText(
            "Model score below 0.90 (uncalibrated): physician verification required",
            substring = true,
        ).assertExists()
        composeRule.onNodeWithText("J18 (model score 42%, uncalibrated)", substring = true).assertExists()
        composeRule.onNodeWithText(legitimateReasoning).assertExists()
        val texts = allTexts()
        assertTrue(texts.toString(), texts.none { it.contains("Confidence below", ignoreCase = false) })
    }

    @Test
    fun theDefaultTierFailsClosedToAWorker() {
        assertTrue(!KernelAssessmentUiState().showModelScore)
    }
}
