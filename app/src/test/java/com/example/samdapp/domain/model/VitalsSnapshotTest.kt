package com.example.samdapp.domain.model

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VitalsSnapshotTest {

    private fun snapshot(weightKg: Double?, heightCm: Double?) = VitalsSnapshot(
        encounterId = "encounter-1",
        patientId = "patient-1",
        weightKg = weightKg,
        heightCm = heightCm,
        recordedAt = Instant.EPOCH,
    )

    @Test
    fun `bmi is null when weight is missing`() {
        assertNull(snapshot(weightKg = null, heightCm = 170.0).bmi)
    }

    @Test
    fun `bmi is null when height is missing`() {
        assertNull(snapshot(weightKg = 70.0, heightCm = null).bmi)
    }

    @Test
    fun `bmi is null when height is zero`() {
        assertNull(snapshot(weightKg = 70.0, heightCm = 0.0).bmi)
    }

    @Test
    fun `bmi is computed and rounded to one decimal`() {
        // 70kg at 1.75m: 70 / (1.75*1.75) = 22.857...
        assertEquals(22.9, snapshot(weightKg = 70.0, heightCm = 175.0).bmi!!, 0.001)
    }

    @Test
    fun `all-null VitalsReading has no value`() {
        assertEquals(false, VitalsReading().hasAnyValue())
    }

    @Test
    fun `VitalsReading with one populated field has a value`() {
        assertEquals(true, VitalsReading(pulseBpm = 72).hasAnyValue())
    }

    @Test
    fun `all-null VitalsReading derives MANUAL source`() {
        assertEquals(ObservationSource.MANUAL, VitalsReading().derivedSource())
    }

    @Test
    fun `populated VitalsReading derives DEVICE source`() {
        assertEquals(ObservationSource.DEVICE, VitalsReading(spo2Percent = 98).derivedSource())
    }

    @Test
    fun `toSnapshot on all-null reading is MANUAL`() {
        val result = VitalsReading().toSnapshot(
            encounterId = "encounter-1",
            patientId = "patient-1",
            recordedAt = Instant.EPOCH,
        )
        assertEquals(ObservationSource.MANUAL, result.source)
    }

    @Test
    fun `toSnapshot on reading with a value is DEVICE`() {
        val result = VitalsReading(pulseBpm = 72).toSnapshot(
            encounterId = "encounter-1",
            patientId = "patient-1",
            recordedAt = Instant.EPOCH,
        )
        assertEquals(ObservationSource.DEVICE, result.source)
    }
}
