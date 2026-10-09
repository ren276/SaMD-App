package com.example.samdapp.data.vitalssource.ble

import com.example.samdapp.domain.vitalssource.Instrument
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The instrument code table of the hub wire (parent memo 5.5). The same codes are used in the
 *  envelope and in the Control Point START request. */
object InstrumentCodes {
    private val CODES = mapOf(
        Instrument.BP to 1,
        Instrument.SPO2 to 2,
        Instrument.THERMOMETER to 3,
        Instrument.GLUCOMETER to 4,
        Instrument.WEIGHT_SCALE to 5,
        Instrument.HEART_RATE to 6,
    )

    fun codeOf(instrument: Instrument): Int = CODES.getValue(instrument)

    fun fromCode(code: Int): Instrument? = CODES.entries.firstOrNull { it.value == code }?.key
}

/** The quality byte of the envelope. Only [OK] is admissible, and the mapper enforces that (RC-2). */
enum class EnvelopeQuality { OK, WARNING, ERROR, UNAVAILABLE }

/** Which parse rule refused an envelope, numbered as in addendum 8.3. The order of the entries is the
 *  order the rules run in: SaMDPi's `envelope.parse` rules 1 to 14, then the phone-only rule 15. */
enum class EnvelopeRule(val number: Int) {
    TOO_SHORT(1),
    ENVELOPE_VERSION(2),
    INSTRUMENT_CODE(3),
    QUALITY_CODE(4),
    SEQUENCE_ZERO(5),
    RESERVED_FLAGS(6),
    NOT_EMULATED(7),
    HUB_ID_LENGTH(8),
    TRUNCATED_BEFORE_SIG_LENGTH(9),
    HUB_ID_NOT_ASCII(10),
    HUB_ID_INVALID(11),
    SIG_LENGTH_ZERO(12),
    TRUNCATED_SIG(13),
    TRAILING_BYTES(14),
    PROFILE_VERSION(15),
}

/** The outcome of [HubEnvelope.parse]. Parsing is total: every input ends in one of these two, and
 *  never in an exception (pinned by a property test, G-B21b). */
sealed interface EnvelopeParse {
    data class Parsed(val envelope: HubEnvelope) : EnvelopeParse

    /** The caller maps this to MALFORMED. [rule] is the first rule the bytes broke. */
    data class Rejected(val rule: EnvelopeRule, val detail: String) : EnvelopeParse
}

/**
 * A parsed Reading Envelope (parent memo 5.5), `envelope_version` 1.
 *
 * [nonce] is the 16 echoed session-nonce bytes, verbatim; the caller formats them for RC-1.
 * [sig] is the SIG characteristic value the hub embedded, verbatim. [deviceTimeMs] is the hub's
 * clock: advisory only, the phone's own receive time is authoritative.
 */
data class HubEnvelope(
    val profileVersion: Int,
    val instrument: Instrument,
    val quality: EnvelopeQuality,
    val nonce: ByteArray,
    val sequence: Long,
    val deviceTimeMs: Long,
    val hubId: String,
    val sig: ByteArray,
) {
    companion object {
        private const val ENVELOPE_VERSION_1 = 1
        private const val PROFILE_VERSION_1 = 1
        private const val FLAG_EMULATED = 0x01

        /** Everything up to and including device_time: 33 bytes. */
        private const val FIXED_LEN = 33

        /** The fixed part, the hub_id length byte and the SIG length byte: SaMDPi's "too short". */
        private const val MIN_LEN = FIXED_LEN + 2

        private val HUB_ID = Regex("^[a-z0-9-]{3,32}$")

        /**
         * Parses [raw], rejecting it at the FIRST rule it breaks, in this order. The order is part of
         * the contract and is pinned by test: a payload that breaks two rules reports the earlier.
         * Rules 1 to 14 are SaMDPi's `envelope.parse` (`envelope.py:103-137`); rule 15, the profile
         * version, is the phone's own strictness and runs last so that 1 to 14 keep SaMDPi's order.
         *
         * Total: each rule is checked before the bytes it guards are read, so no input throws.
         */
        fun parse(raw: ByteArray): EnvelopeParse {
            if (raw.size < MIN_LEN) return reject(EnvelopeRule.TOO_SHORT, "${raw.size} bytes, need at least $MIN_LEN")
            val buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)

            val version = u8(raw, 0)
            if (version != ENVELOPE_VERSION_1) return reject(EnvelopeRule.ENVELOPE_VERSION, "version $version")

            val profileVersion = u8(raw, 1)

            val instrumentCode = u8(raw, 2)
            val instrument = InstrumentCodes.fromCode(instrumentCode)
                ?: return reject(EnvelopeRule.INSTRUMENT_CODE, "code $instrumentCode")

            val qualityCode = u8(raw, 4)
            if (qualityCode !in EnvelopeQuality.entries.indices) return reject(EnvelopeRule.QUALITY_CODE, "code $qualityCode")

            val sequence = buf.getInt(21).toLong() and 0xFFFFFFFFL
            if (sequence == 0L) return reject(EnvelopeRule.SEQUENCE_ZERO, "sequence is 0")

            val flags = u8(raw, 3)
            if ((flags and FLAG_EMULATED.inv()) != 0) return reject(EnvelopeRule.RESERVED_FLAGS, "flags 0x%02x".format(flags))
            if ((flags and FLAG_EMULATED) == 0) return reject(EnvelopeRule.NOT_EMULATED, "emulated flag is 0")

            val hubIdLen = u8(raw, FIXED_LEN)
            if (hubIdLen !in 3..32) return reject(EnvelopeRule.HUB_ID_LENGTH, "hub_id length $hubIdLen")
            val sigLenAt = FIXED_LEN + 1 + hubIdLen
            if (raw.size < sigLenAt + 1) return reject(EnvelopeRule.TRUNCATED_BEFORE_SIG_LENGTH, "${raw.size} bytes")

            val hubIdBytes = raw.copyOfRange(FIXED_LEN + 1, sigLenAt)
            if (hubIdBytes.any { it < 0 }) return reject(EnvelopeRule.HUB_ID_NOT_ASCII, "non-ASCII byte in hub_id")
            val hubId = String(hubIdBytes, Charsets.US_ASCII)
            if (!HUB_ID.matches(hubId)) return reject(EnvelopeRule.HUB_ID_INVALID, "hub_id '$hubId'")

            val sigLen = u8(raw, sigLenAt)
            if (sigLen < 1) return reject(EnvelopeRule.SIG_LENGTH_ZERO, "SIG length is 0")
            val end = sigLenAt + 1 + sigLen
            if (raw.size < end) return reject(EnvelopeRule.TRUNCATED_SIG, "${raw.size} bytes, SIG ends at $end")
            if (raw.size > end) return reject(EnvelopeRule.TRAILING_BYTES, "${raw.size - end} bytes after the SIG")

            if (profileVersion != PROFILE_VERSION_1) return reject(EnvelopeRule.PROFILE_VERSION, "profile_version $profileVersion")

            return EnvelopeParse.Parsed(
                HubEnvelope(
                    profileVersion = profileVersion,
                    instrument = instrument,
                    quality = EnvelopeQuality.entries[qualityCode],
                    nonce = raw.copyOfRange(5, 21),
                    sequence = sequence,
                    deviceTimeMs = buf.getLong(25),
                    hubId = hubId,
                    sig = raw.copyOfRange(sigLenAt + 1, end),
                ),
            )
        }

        private fun reject(rule: EnvelopeRule, detail: String) = EnvelopeParse.Rejected(rule, detail)

        private fun u8(raw: ByteArray, at: Int): Int = raw[at].toInt() and 0xFF
    }
}
