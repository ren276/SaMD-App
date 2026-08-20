package com.example.samdapp.presentation.report

import com.example.samdapp.domain.model.InferenceSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kernel-mock production safety fix, H-09/H-14 (`docs/quality/risk-management-file.md`): the
 * exported/printed clinical report must not be silent about a non-real assessment.
 * [assessmentMarkerLabel] is exactly the text [ReportCanvasRenderer.demographicBlock] draws for
 * `report.kernelOutput.inferenceSource`. Pulled out as a plain function specifically so it's
 * testable — `ReportCanvasRenderer.render()` itself draws onto `android.graphics.Canvas`, which
 * this project's plain-JVM unit tests cannot exercise (no Robolectric configured); this is the
 * closest assertion on actual rendered content available without adding that dependency.
 */
class ReportCanvasRendererMarkerTest {

    @Test
    fun `REAL_INFERENCE renders no marker`() {
        assertNull(assessmentMarkerLabel(InferenceSource.REAL_INFERENCE))
    }

    @Test
    fun `MOCK_FALLBACK renders an explicit offline-mock marker`() {
        val label = assessmentMarkerLabel(InferenceSource.MOCK_FALLBACK)
        requireNotNull(label)
        assertTrue(label.contains("MOCK", ignoreCase = true))
    }

    @Test
    fun `UNAVAILABLE renders an explicit unavailable marker, distinct from the mock marker`() {
        val label = assessmentMarkerLabel(InferenceSource.UNAVAILABLE)
        requireNotNull(label)
        assertTrue(label.contains("UNAVAILABLE", ignoreCase = true))
        assertEquals(false, label.contains("MOCK", ignoreCase = true))
    }

    @Test
    fun `null evaluate failure code renders no marker`() {
        assertNull(evaluateFailureMarkerLabel(null))
    }

    @Test
    fun `a set evaluate failure code renders an explicit evaluation-failed marker`() {
        val label = evaluateFailureMarkerLabel("IOException")
        requireNotNull(label)
        assertTrue(label.contains("FAILED", ignoreCase = true))
    }
}
