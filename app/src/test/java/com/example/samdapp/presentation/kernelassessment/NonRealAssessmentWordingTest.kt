package com.example.samdapp.presentation.kernelassessment

import com.example.samdapp.domain.kernel.KernelFailure
import com.example.samdapp.domain.model.InferenceSource
import com.example.samdapp.testutil.StringsXml
import com.example.samdapp.testutil.testKernelReportOutput
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * For every inference source other than REAL_INFERENCE, no text the screen renders, in either
 * role's wording, mentions a score, certainty, confidence or calibration. An UNAVAILABLE card has
 * no model result and a MOCK_FALLBACK card has a fabricated one; on master the UNAVAILABLE card
 * told a worker "the model is not certain about this result" and a physician "Model score below
 * 0.90 (uncalibrated)", describing an output that did not exist.
 *
 * Renders through [assessmentCopy], the one place the screen takes its fixed strings from, and
 * resolves each to its actual English text.
 */
class NonRealAssessmentWordingTest {

    private val forbidden = listOf("score", "certain", "confiden", "calibrat")

    private fun nonRealDisplays(): Map<String, AssessmentDisplay> = buildMap {
        (KernelFailure.entries + listOf<KernelFailure?>(null)).forEach { failure ->
            put(
                "UNAVAILABLE/${failure?.name ?: "no cause"}",
                testKernelReportOutput("case-1", InferenceSource.UNAVAILABLE)
                    .copy(confidenceScore = 0.0, requiredHumanVerification = true, failureCode = failure)
                    .toDisplay(),
            )
        }
        put("UNAVAILABLE/stalled", stalledDisplay())
        listOf(0.42, 0.97).forEach { confidence ->
            put(
                "MOCK_FALLBACK/$confidence",
                testKernelReportOutput("case-1", InferenceSource.MOCK_FALLBACK)
                    .copy(confidenceScore = confidence, requiredHumanVerification = true)
                    .toDisplay(),
            )
        }
    }

    @Test
    fun `no non-REAL state mentions a score, certainty, confidence or calibration in either role`() {
        val offences = nonRealDisplays().flatMap { (state, display) ->
            listOf(true to "physician", false to "worker").flatMap { (showModelScore, role) ->
                assessmentCopy(display, showModelScore).textRes()
                    .map { StringsXml.text(it) }
                    .filter { text -> forbidden.any { text.contains(it, ignoreCase = true) } }
                    .map { "$state ($role): $it" }
            }
        }
        assertTrue(offences.joinToString("\n"), offences.isEmpty())
    }

    @Test
    fun `a real result still shows the physician score and the low-score notice`() {
        // The rule is scoped to non-REAL states; a real low-scoring result keeps its wording.
        val real = testKernelReportOutput("case-1", InferenceSource.REAL_INFERENCE)
            .copy(confidenceScore = 0.5, requiredHumanVerification = true)
            .toDisplay()
        val physician = assessmentCopy(real, showModelScore = true)
        assertTrue(physician.showModelScore)
        assertTrue(StringsXml.text(physician.verificationNotice!!).contains("score", ignoreCase = true))
    }
}
