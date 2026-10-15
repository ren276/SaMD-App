package com.example.samdapp.data.vitalssource.ble

import com.example.samdapp.domain.vitalssource.Instrument
import com.example.samdapp.domain.vitalssource.RejectReason
import com.example.samdapp.domain.vitalssource.Scenario
import java.nio.ByteBuffer
import java.util.UUID

/** What the hub answered to a START request (addendum 5.3). */
sealed interface StartResponse {
    /** The hub accepted the session and will indicate one envelope. */
    data object Success : StartResponse

    /** The acquisition is refused for [reason]. Nothing was started that the phone must stop. */
    data class Refused(val reason: RejectReason) : StartResponse
}

/**
 * The vendor Control Point wire (parent memo 5.6, addendum 5.3): UUIDs, START and STOP encoding,
 * the START response table, and the 16-byte session nonce. Pure.
 */
object ControlPoint {
    val VENDOR_SERVICE: UUID = UUID.fromString("f4610001-95ad-4b7d-a8ed-b568b5b0e927")
    val CONTROL_POINT: UUID = UUID.fromString("f4610002-95ad-4b7d-a8ed-b568b5b0e927")
    val READING_ENVELOPE: UUID = UUID.fromString("f4610003-95ad-4b7d-a8ed-b568b5b0e927")

    const val NONCE_LEN = 16

    private const val OP_START = 0x01
    private const val OP_STOP = 0x02
    private const val OP_RESPONSE = 0x80
    private const val RESPONSE_LEN = 3

    private const val SUCCESS = 0x01
    private const val OPCODE_NOT_SUPPORTED = 0x02
    private const val INVALID_PARAMETER = 0x03
    private const val INSTRUMENT_NOT_SERVED = 0x04
    private const val BUSY = 0x05
    private const val MTU_TOO_SMALL = 0x07
    private const val CCCD_NOT_ENABLED = 0x08
    private val START_RESULTS =
        setOf(SUCCESS, OPCODE_NOT_SUPPORTED, INVALID_PARAMETER, INSTRUMENT_NOT_SERVED, BUSY, MTU_TOO_SMALL, CCCD_NOT_ENABLED)

    /** The nonce as the 16 big-endian bytes of [uuid]. The hub echoes them back verbatim. */
    fun nonceBytes(uuid: UUID): ByteArray =
        ByteBuffer.allocate(NONCE_LEN).putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits).array()

    /**
     * 16 nonce bytes as a lowercase canonical UUID string, the one session-id format RC-1 compares on
     * both transports (B7). Formatting only: no version or variant bit is read, so the fixture's
     * version-6 nonce formats like any other. For a [UUID] `u`, `formatNonce(nonceBytes(u))` is
     * `u.toString()`.
     */
    fun formatNonce(nonce: ByteArray): String {
        require(nonce.size == NONCE_LEN) { "nonce must be $NONCE_LEN bytes, got ${nonce.size}" }
        val buf = ByteBuffer.wrap(nonce)
        return UUID(buf.long, buf.long).toString()
    }

    /** START: `01 <instrument code> <scenario code> <nonce 16>`, 19 bytes. */
    fun encodeStart(instrument: Instrument, scenario: Scenario, nonce: ByteArray): ByteArray {
        require(nonce.size == NONCE_LEN) { "nonce must be $NONCE_LEN bytes, got ${nonce.size}" }
        return byteArrayOf(OP_START.toByte(), InstrumentCodes.codeOf(instrument).toByte(), scenarioCode(scenario).toByte()) + nonce
    }

    /** STOP: `02 <nonce 16>`, 17 bytes. */
    fun encodeStop(nonce: ByteArray): ByteArray {
        require(nonce.size == NONCE_LEN) { "nonce must be $NONCE_LEN bytes, got ${nonce.size}" }
        return byteArrayOf(OP_STOP.toByte()) + nonce
    }

    /**
     * Decodes a Control Point indication that should answer a START. It must be exactly
     * `80 01 <result>`; anything else, including a well-formed answer to another opcode, is MALFORMED.
     * A result byte outside the table is MALFORMED too, and so is 0x06 NOT_RUNNING: the hub sends it
     * for STOP only, so on a START answer it is a result this client has no meaning for.
     */
    fun decodeStartResponse(raw: ByteArray): StartResponse = when (startResult(raw)) {
        SUCCESS -> StartResponse.Success
        OPCODE_NOT_SUPPORTED -> StartResponse.Refused(RejectReason.MALFORMED)
        INVALID_PARAMETER -> StartResponse.Refused(RejectReason.MALFORMED)
        INSTRUMENT_NOT_SERVED -> StartResponse.Refused(RejectReason.HUB_MISMATCH)
        BUSY -> StartResponse.Refused(RejectReason.UNREACHABLE)
        MTU_TOO_SMALL -> StartResponse.Refused(RejectReason.MALFORMED)
        CCCD_NOT_ENABLED -> StartResponse.Refused(RejectReason.UNREACHABLE)
        else -> StartResponse.Refused(RejectReason.MALFORMED)
    }

    /** True only for `80 01 <result>` with a result in the table: an answer this client decoded,
     *  SUCCESS or a refusal. After anything else the hub's session state is unknown. */
    fun isStartAnswer(raw: ByteArray): Boolean = startResult(raw) != null

    private fun startResult(raw: ByteArray): Int? {
        if (raw.size != RESPONSE_LEN ||
            (raw[0].toInt() and 0xFF) != OP_RESPONSE ||
            (raw[1].toInt() and 0xFF) != OP_START
        ) {
            return null
        }
        return (raw[2].toInt() and 0xFF).takeIf { it in START_RESULTS }
    }

    private fun scenarioCode(scenario: Scenario): Int = when (scenario) {
        Scenario.NORMAL -> 0
        Scenario.LOW -> 1
        Scenario.HIGH -> 2
        Scenario.DEVICE_ERROR -> 3
        Scenario.SENSOR_UNAVAILABLE -> 4
    }
}
