package com.example.samdapp.data.vitalssource

import com.example.samdapp.domain.model.VitalsReading
import com.example.samdapp.domain.vitalssource.AcquisitionResult
import com.example.samdapp.domain.vitalssource.Instrument
import com.example.samdapp.presentation.compounder.VitalsField
import com.example.samdapp.presentation.compounder.writtenFields
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Two instrument-to-field tables have to agree and cannot see each other: [PiMeasurementMapper]'s,
 * which lives in `src/dev/` and decides which [VitalsReading] fields an accepted reading fills, and
 * `Instrument.writtenFields()` in the presentation layer, which decides which form fields show the
 * acquiring indicator. Drift between them is silent: the spinner would sit on a field that never
 * fills, or a field would fill with no indication it was about to.
 *
 * This test is the only thing holding them together, so it derives one from the real mapper rather
 * than restating the table a third time.
 */
class PiInstrumentFieldAgreementTest {

    private fun VitalsReading.populatedFields(): Set<VitalsField> = buildSet {
        pulseBpm?.let { add(VitalsField.PULSE_BPM) }
        bpSystolic?.let { add(VitalsField.BP_SYSTOLIC) }
        bpDiastolic?.let { add(VitalsField.BP_DIASTOLIC) }
        spo2Percent?.let { add(VitalsField.SPO2_PERCENT) }
        temperatureCelsius?.let { add(VitalsField.TEMPERATURE_CELSIUS) }
        respiratoryRate?.let { add(VitalsField.RESPIRATORY_RATE) }
        weightKg?.let { add(VitalsField.WEIGHT_KG) }
        heightCm?.let { add(VitalsField.HEIGHT_CM) }
        bloodGlucoseMgDl?.let { add(VitalsField.BLOOD_GLUCOSE_MG_DL) }
    }

    @Test
    fun `the mapper fills exactly the fields the UI puts an acquiring indicator on`() {
        for (instrument in Instrument.entries) {
            val measurement = NormalizedMeasurementDto(
                deviceType = PiMeasurementMapper.expectedDeviceType(instrument),
                qualityStatus = "OK",
                // All three present, so the comparison reflects the table and not a sparse payload.
                primaryValue = 100.0,
                secondaryValue = 80.0,
                tertiaryValue = 70.0,
                provenance = mapOf("session_id" to "session-A"),
            )

            val result = PiMeasurementMapper.map(measurement, instrument, "session-A")

            val filled = (result as AcquisitionResult.Accepted).reading.populatedFields()
            assertEquals("$instrument table drift", instrument.writtenFields(), filled)
        }
    }
}
