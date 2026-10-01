package com.example.samdapp.presentation.kernelassessment

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The explanation card is drawn only when it has content for the viewer. A worker on the
 * /evaluate path has none (the classifier's scored explanation is physician-only), and an empty
 * Card still draws as a blank rounded box on the assessment screen.
 */
class ExplainabilityVisibilityTest {

    private fun display(
        reasoning: List<String> = emptyList(),
        explanation: List<String> = emptyList(),
        evidenceFor: List<String> = emptyList(),
        evidenceAgainst: List<String> = emptyList(),
    ) = AssessmentDisplay(
        predictedCondition = "Essential hypertension",
        icdCode = "I10",
        confidencePercent = 55,
        requiresHumanVerification = false,
        isMockFallback = false,
        isUnavailable = false,
        failure = null,
        sourceLabel = "Real-time AI inference (/api/v1/evaluate)",
        differentialLines = emptyList(),
        reasoningLines = reasoning,
        modelExplanationLines = explanation,
        evidenceFor = evidenceFor,
        evidenceAgainst = evidenceAgainst,
    )

    @Test
    fun aWorkerOnEvaluateWithOnlyAScoredExplanationGetsNoCard() {
        val evaluate = display(explanation = listOf("ranked #1 (42.9%)"))
        assertFalse(hasExplainabilityContent(evaluate, showModelScore = false))
    }

    @Test
    fun aPhysicianWithTheSameResultGetsTheCard() {
        val evaluate = display(explanation = listOf("ranked #1 (42.9%)"))
        assertTrue(hasExplainabilityContent(evaluate, showModelScore = true))
    }

    @Test
    fun roleNeutralReasoningAndEvidenceShowForEveryone() {
        assertTrue(hasExplainabilityContent(display(reasoning = listOf("SpO2 88%")), showModelScore = false))
        assertTrue(hasExplainabilityContent(display(evidenceFor = listOf("cough")), showModelScore = false))
        assertTrue(hasExplainabilityContent(display(evidenceAgainst = listOf("no fever")), showModelScore = false))
    }

    @Test
    fun nothingToShowMeansNoCardForAnyone() {
        assertFalse(hasExplainabilityContent(display(), showModelScore = false))
        assertFalse(hasExplainabilityContent(display(), showModelScore = true))
    }
}
