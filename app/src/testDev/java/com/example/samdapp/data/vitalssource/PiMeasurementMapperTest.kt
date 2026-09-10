package com.example.samdapp.data.vitalssource

import com.example.samdapp.domain.vitalssource.AcquisitionResult
import com.example.samdapp.domain.vitalssource.Instrument
import com.example.samdapp.domain.vitalssource.RejectReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs only under `testDevDebugUnitTest`. [PiMeasurementMapper] doesn't exist in the staging/prod
 * compilation unit, so this test class doesn't either. That is the compile-time half of the proof
 * that no instrument-gateway path is reachable outside dev.
 *
 * These are the risk-control tests. Each one names the control it pins: RC-1 correlation
 * (session id and device type), RC-2 the quality gate, and the fixed reject-before-map ordering
 * that makes both meaningful.
 */
class PiMeasurementMapperTest {

    private val sessionId = "session-A"

    private fun measurement(
        deviceType: String,
        qualityStatus: String = "OK",
        primary: Double? = null,
        secondary: Double? = null,
        tertiary: Double? = null,
        provenance: Map<String, Any?>? = mapOf("session_id" to sessionId),
    ) = NormalizedMeasurementDto(
        deviceType = deviceType,
        qualityStatus = qualityStatus,
        primaryValue = primary,
        secondaryValue = secondary,
        tertiaryValue = tertiary,
        provenance = provenance,
    )

    private fun accepted(result: AcquisitionResult): AcquisitionResult.Accepted {
        assertTrue("expected Accepted, was $result", result is AcquisitionResult.Accepted)
        return result as AcquisitionResult.Accepted
    }

    private fun rejectReason(result: AcquisitionResult): RejectReason {
        assertTrue("expected Rejected, was $result", result is AcquisitionResult.Rejected)
        return (result as AcquisitionResult.Rejected).reason
    }

    // --- Numeric conversion -------------------------------------------------------------------

    /** The demo's own number. BP HIGH emits 173.5 at step 1, and round-half-up makes that 174,
     *  which is still under the 180 emergency ceiling. Truncation would give 173 and silently
     *  change the reading. */
    @Test
    fun `rounds the half case up, 173_5 becomes 174`() {
        val result = PiMeasurementMapper.map(
            measurement("BLOOD_PRESSURE", primary = 173.5, secondary = 105.0, tertiary = 112.0),
            Instrument.BP,
            sessionId,
        )

        assertEquals(174, accepted(result).reading.bpSystolic)
    }

    @Test
    fun `rounds below the half down and at the half up`() {
        val low = PiMeasurementMapper.map(
            measurement("HEART_RATE_MONITOR", primary = 98.4),
            Instrument.HEART_RATE,
            sessionId,
        )
        val half = PiMeasurementMapper.map(
            measurement("HEART_RATE_MONITOR", primary = 98.5),
            Instrument.HEART_RATE,
            sessionId,
        )

        assertEquals(98, accepted(low).reading.pulseBpm)
        assertEquals(99, accepted(half).reading.pulseBpm)
    }

    @Test
    fun `passes Doubles through unrounded and unconverted`() {
        val temperature = PiMeasurementMapper.map(
            measurement("THERMOMETER", primary = 36.65),
            Instrument.THERMOMETER,
            sessionId,
        )
        val weight = PiMeasurementMapper.map(
            measurement("WEIGHT_SCALE", primary = 68.4),
            Instrument.WEIGHT_SCALE,
            sessionId,
        )

        assertEquals(36.65, accepted(temperature).reading.temperatureCelsius!!, 0.0)
        assertEquals(68.4, accepted(weight).reading.weightKg!!, 0.0)
    }

    /** No default substitution, anywhere. An absent secondary or tertiary reading is absent, and
     *  0.0 diastolic or 0 bpm would be a fabricated clinical value, not a missing one. */
    @Test
    fun `maps a null secondary and tertiary to null, never to zero`() {
        val result = PiMeasurementMapper.map(
            measurement("BLOOD_PRESSURE", primary = 120.0, secondary = null, tertiary = null),
            Instrument.BP,
            sessionId,
        )

        val reading = accepted(result).reading
        assertEquals(120, reading.bpSystolic)
        assertNull(reading.bpDiastolic)
        assertNull(reading.pulseBpm)
    }

    @Test
    fun `maps every instrument to its own fields and leaves the rest null`() {
        val spo2 = accepted(
            PiMeasurementMapper.map(
                measurement("PULSE_OXIMETER", primary = 98.0, secondary = 72.0),
                Instrument.SPO2,
                sessionId,
            ),
        ).reading
        val glucose = accepted(
            PiMeasurementMapper.map(
                measurement("GLUCOMETER", primary = 95.0),
                Instrument.GLUCOMETER,
                sessionId,
            ),
        ).reading

        assertEquals(98, spo2.spo2Percent)
        assertEquals(72, spo2.pulseBpm)
        assertNull(spo2.bpSystolic)
        assertEquals(95, glucose.bloodGlucoseMgDl)
        // No instrument on this gateway supplies these, so they are never device written.
        assertNull(glucose.respiratoryRate)
        assertNull(glucose.heightCm)
    }

    // --- RC-2, the quality gate ---------------------------------------------------------------

    /** The gateway's fault and lead-off profiles both emit 0.0 for every value. A 0.0 systolic or
     *  SpO2 written into the form would trip the emergency thresholds and manufacture a false
     *  emergency override, so this rejection happens before any value is looked at. */
    @Test
    fun `rejects every non-OK quality status, and writes no value`() {
        for (status in listOf("ERROR", "UNAVAILABLE", "WARNING", "ok", "", "SOMETHING_NEW")) {
            val result = PiMeasurementMapper.map(
                measurement(
                    "BLOOD_PRESSURE",
                    qualityStatus = status,
                    primary = 0.0,
                    secondary = 0.0,
                    tertiary = 0.0,
                ),
                Instrument.BP,
                sessionId,
            )

            assertEquals(
                "quality_status=$status must be inadmissible",
                RejectReason.QUALITY_STATUS_NOT_OK,
                rejectReason(result),
            )
        }
    }

    @Test
    fun `rejects a missing quality status`() {
        val result = PiMeasurementMapper.map(
            NormalizedMeasurementDto(
                deviceType = "BLOOD_PRESSURE",
                qualityStatus = null,
                primaryValue = 120.0,
                provenance = mapOf("session_id" to sessionId),
            ),
            Instrument.BP,
            sessionId,
        )

        assertEquals(RejectReason.QUALITY_STATUS_NOT_OK, rejectReason(result))
    }

    // --- RC-1, correlation --------------------------------------------------------------------

    @Test
    fun `rejects a measurement carrying a different session id`() {
        val result = PiMeasurementMapper.map(
            measurement(
                "BLOOD_PRESSURE",
                primary = 120.0,
                provenance = mapOf("session_id" to "session-B"),
            ),
            Instrument.BP,
            sessionId,
        )

        assertEquals(RejectReason.SESSION_ID_MISMATCH, rejectReason(result))
    }

    /** Absent is a mismatch, never a pass. The session id lives only inside provenance, so a
     *  payload that simply omits the key must not slip through as "nothing to compare". */
    @Test
    fun `rejects a missing session id key and a missing provenance map alike`() {
        val missingKey = PiMeasurementMapper.map(
            measurement("BLOOD_PRESSURE", primary = 120.0, provenance = mapOf("source" to "wifi")),
            Instrument.BP,
            sessionId,
        )
        val missingMap = PiMeasurementMapper.map(
            measurement("BLOOD_PRESSURE", primary = 120.0, provenance = null),
            Instrument.BP,
            sessionId,
        )

        assertEquals(RejectReason.SESSION_ID_MISMATCH, rejectReason(missingKey))
        assertEquals(RejectReason.SESSION_ID_MISMATCH, rejectReason(missingMap))
    }

    @Test
    fun `rejects a reading from an instrument other than the one requested`() {
        val result = PiMeasurementMapper.map(
            measurement("GLUCOMETER", primary = 95.0),
            Instrument.BP,
            sessionId,
        )

        assertEquals(RejectReason.DEVICE_TYPE_MISMATCH, rejectReason(result))
    }

    // --- The ordering itself ------------------------------------------------------------------

    /** A payload failing several gates reports the FIRST one. This pins the order quality then
     *  session then device, which is what guarantees no value is converted before every gate has
     *  passed. */
    @Test
    fun `reports the quality failure first when the payload also fails correlation`() {
        val result = PiMeasurementMapper.map(
            measurement(
                "GLUCOMETER",
                qualityStatus = "ERROR",
                primary = 0.0,
                provenance = mapOf("session_id" to "session-B"),
            ),
            Instrument.BP,
            sessionId,
        )

        assertEquals(RejectReason.QUALITY_STATUS_NOT_OK, rejectReason(result))
    }

    /** And session before device, so the second and third gates are ordered too. */
    @Test
    fun `reports the session failure before the device failure`() {
        val result = PiMeasurementMapper.map(
            measurement(
                "GLUCOMETER",
                primary = 95.0,
                provenance = mapOf("session_id" to "session-B"),
            ),
            Instrument.BP,
            sessionId,
        )

        assertEquals(RejectReason.SESSION_ID_MISMATCH, rejectReason(result))
    }

    @Test
    fun `carries the correlated provenance on an accepted reading`() {
        val result = accepted(
            PiMeasurementMapper.map(
                measurement("BLOOD_PRESSURE", primary = 120.0, secondary = 80.0, tertiary = 72.0),
                Instrument.BP,
                sessionId,
            ),
        )

        assertEquals(sessionId, result.sessionId)
        assertEquals("BLOOD_PRESSURE", result.deviceType)
        assertEquals(Instrument.BP, result.instrument)
    }
}
