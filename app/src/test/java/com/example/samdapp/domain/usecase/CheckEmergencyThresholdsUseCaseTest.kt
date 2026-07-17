package com.example.samdapp.domain.usecase

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** REQ-TRS-02: hard-coded critical-vitals thresholds must trip before Consultation/Sending. */
class CheckEmergencyThresholdsUseCaseTest {

    private val useCase = CheckEmergencyThresholdsUseCase()

    @Test
    fun `normal vitals do not trigger`() {
        val flag = useCase(spo2Percent = 97, bpSystolic = 120, bpDiastolic = 80)
        assertFalse(flag.triggered)
        assertTrue(flag.reasons.isEmpty())
    }

    @Test
    fun `spo2 below the floor triggers with a reason`() {
        val flag = useCase(spo2Percent = 85, bpSystolic = 120, bpDiastolic = 80)
        assertTrue(flag.triggered)
        assertTrue(flag.reasons.any { it.contains("SpO2") })
    }

    @Test
    fun `systolic bp at or above the ceiling triggers`() {
        val flag = useCase(spo2Percent = 97, bpSystolic = 185, bpDiastolic = 80)
        assertTrue(flag.triggered)
    }

    @Test
    fun `systolic bp below the floor triggers (hypotension, not just hypertension)`() {
        val flag = useCase(spo2Percent = 97, bpSystolic = 80, bpDiastolic = 60)
        assertTrue(flag.triggered)
    }

    @Test
    fun `diastolic bp at the ceiling triggers`() {
        val flag = useCase(spo2Percent = 97, bpSystolic = 120, bpDiastolic = 120)
        assertTrue(flag.triggered)
    }

    @Test
    fun `null vitals never trigger — missing data is not treated as critical`() {
        val flag = useCase(spo2Percent = null, bpSystolic = null, bpDiastolic = null)
        assertFalse(flag.triggered)
    }

    @Test
    fun `multiple crossed thresholds all appear in reasons`() {
        val flag = useCase(spo2Percent = 80, bpSystolic = 190, bpDiastolic = 130)
        assertTrue(flag.triggered)
        assertEqualsSize(3, flag.reasons)
    }

    private fun assertEqualsSize(expected: Int, list: List<String>) =
        org.junit.Assert.assertEquals(expected, list.size)
}
