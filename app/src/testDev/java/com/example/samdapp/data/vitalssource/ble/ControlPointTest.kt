package com.example.samdapp.data.vitalssource.ble

import com.example.samdapp.data.vitalssource.ble.GoldenFixture.hex
import com.example.samdapp.domain.vitalssource.Instrument
import com.example.samdapp.domain.vitalssource.RejectReason
import com.example.samdapp.domain.vitalssource.Scenario
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** G-B20 (the result table), the formatting half of G-B18 (nonce bytes to a lowercase canonical
 *  UUID), and START and STOP encoding against the SaMDPi fixture. */
class ControlPointTest {

    private val cp = GoldenFixture.json.getAsJsonObject("control_point")
    private val nonce = hex("00112233445566778899aabbccddeeff")

    private fun refused(reason: RejectReason) = StartResponse.Refused(reason)

    private fun answer(result: Int) = ControlPoint.decodeStartResponse(byteArrayOf(0x80.toByte(), 0x01, result.toByte()))

    // ---- encoding ----------------------------------------------------------------------------

    @Test
    fun `START encodes exactly like the SaMDPi fixture`() {
        assertArrayEquals(
            hex(cp["start_bp_normal"].asString),
            ControlPoint.encodeStart(Instrument.BP, Scenario.NORMAL, nonce),
        )
    }

    @Test
    fun `STOP encodes exactly like the SaMDPi fixture`() {
        assertArrayEquals(hex(cp["stop"].asString), ControlPoint.encodeStop(nonce))
    }

    @Test
    fun `START and STOP lengths are 19 and 17`() {
        assertEquals(19, ControlPoint.encodeStart(Instrument.SPO2, Scenario.HIGH, nonce).size)
        assertEquals(17, ControlPoint.encodeStop(nonce).size)
    }

    @Test
    fun `instrument and scenario codes are the memo's tables`() {
        val instrumentCodes = mapOf(
            Instrument.BP to 1, Instrument.SPO2 to 2, Instrument.THERMOMETER to 3,
            Instrument.GLUCOMETER to 4, Instrument.WEIGHT_SCALE to 5, Instrument.HEART_RATE to 6,
        )
        assertEquals(Instrument.entries.toSet(), instrumentCodes.keys)
        for ((instrument, code) in instrumentCodes) {
            assertEquals(code, ControlPoint.encodeStart(instrument, Scenario.NORMAL, nonce)[1].toInt())
        }
        val scenarioCodes = mapOf(
            Scenario.NORMAL to 0, Scenario.LOW to 1, Scenario.HIGH to 2,
            Scenario.DEVICE_ERROR to 3, Scenario.SENSOR_UNAVAILABLE to 4,
        )
        assertEquals(Scenario.entries.toSet(), scenarioCodes.keys)
        for ((scenario, code) in scenarioCodes) {
            assertEquals(code, ControlPoint.encodeStart(Instrument.BP, scenario, nonce)[2].toInt())
        }
    }

    @Test
    fun `a nonce that is not 16 bytes is refused`() {
        assertThrows(IllegalArgumentException::class.java) { ControlPoint.encodeStart(Instrument.BP, Scenario.NORMAL, ByteArray(15)) }
        assertThrows(IllegalArgumentException::class.java) { ControlPoint.encodeStop(ByteArray(17)) }
        assertThrows(IllegalArgumentException::class.java) { ControlPoint.formatNonce(ByteArray(0)) }
    }

    @Test
    fun `the vendor UUIDs are the ones the hub registers`() {
        assertEquals("f4610001-95ad-4b7d-a8ed-b568b5b0e927", ControlPoint.VENDOR_SERVICE.toString())
        assertEquals("f4610002-95ad-4b7d-a8ed-b568b5b0e927", ControlPoint.CONTROL_POINT.toString())
        assertEquals("f4610003-95ad-4b7d-a8ed-b568b5b0e927", ControlPoint.READING_ENVELOPE.toString())
    }

    // ---- G-B18, formatting half --------------------------------------------------------------

    @Test
    fun `the fixture nonce formats as a lowercase canonical UUID`() {
        val o = GoldenFixture.json.getAsJsonObject("envelope")
        assertEquals("00112233-4455-6677-8899-aabbccddeeff", ControlPoint.formatNonce(hex(o["nonce_hex"].asString)))
    }

    @Test
    fun `a generated nonce formats to exactly UUID toString, in lowercase`() {
        // Hex letters in the value, so an uppercase formatter cannot pass by luck.
        val fixed = UUID.fromString("a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d")
        assertEquals("a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d", ControlPoint.formatNonce(ControlPoint.nonceBytes(fixed)))
        repeat(200) {
            val u = UUID.randomUUID()
            val formatted = ControlPoint.formatNonce(ControlPoint.nonceBytes(u))
            assertEquals(u.toString(), formatted)
            assertEquals(formatted, formatted.lowercase())
            assertEquals(36, formatted.length)
        }
    }

    @Test
    fun `nonce bytes are the 16 big-endian bytes of the UUID`() {
        val bytes = ControlPoint.nonceBytes(UUID.fromString("00112233-4455-6677-8899-aabbccddeeff"))
        assertArrayEquals(nonce, bytes)
        assertTrue(bytes.size == ControlPoint.NONCE_LEN)
    }

    // ---- G-B20: the result table -------------------------------------------------------------

    @Test
    fun `fixture START responses decode`() {
        assertEquals(StartResponse.Success, ControlPoint.decodeStartResponse(hex(cp["response_start_success"].asString)))
        assertEquals(refused(RejectReason.UNREACHABLE), ControlPoint.decodeStartResponse(hex(cp["response_busy_for_start"].asString)))
        assertEquals(refused(RejectReason.UNREACHABLE), ControlPoint.decodeStartResponse(hex(cp["response_cccd_not_enabled_for_start"].asString)))
        assertEquals(refused(RejectReason.MALFORMED), ControlPoint.decodeStartResponse(hex(cp["response_mtu_too_small_for_start"].asString)))
    }

    @Test
    fun `each result code maps as in the table`() {
        assertEquals(StartResponse.Success, answer(0x01))
        assertEquals(refused(RejectReason.MALFORMED), answer(0x02))
        assertEquals(refused(RejectReason.MALFORMED), answer(0x03))
        assertEquals(refused(RejectReason.HUB_MISMATCH), answer(0x04))
        assertEquals(refused(RejectReason.UNREACHABLE), answer(0x05))
        assertEquals(refused(RejectReason.MALFORMED), answer(0x07))
        assertEquals(refused(RejectReason.UNREACHABLE), answer(0x08))
    }

    @Test
    fun `NOT_RUNNING is a STOP result, so on a START answer it is malformed`() {
        assertEquals(refused(RejectReason.MALFORMED), answer(0x06))
    }

    @Test
    fun `an unknown result code is malformed`() {
        for (code in listOf(0x00, 0x09, 0x0F, 0x10, 0x7F, 0x80, 0xFF)) {
            assertEquals("code $code", refused(RejectReason.MALFORMED), answer(code))
        }
    }

    @Test
    fun `a response that is not 80 01 result is malformed`() {
        val malformed = refused(RejectReason.MALFORMED)
        assertEquals(malformed, ControlPoint.decodeStartResponse(ByteArray(0)))
        assertEquals(malformed, ControlPoint.decodeStartResponse(hex("80")))
        assertEquals(malformed, ControlPoint.decodeStartResponse(hex("8001")))
        assertEquals(malformed, ControlPoint.decodeStartResponse(hex("80010100")))
        // not the response marker
        assertEquals(malformed, ControlPoint.decodeStartResponse(hex("000101")))
        assertEquals(malformed, ControlPoint.decodeStartResponse(hex("010101")))
        // a well-formed answer to STOP is not an answer to START
        assertEquals(malformed, ControlPoint.decodeStartResponse(hex("800201")))
        assertEquals(malformed, ControlPoint.decodeStartResponse(hex("800001")))
    }
}
