package com.example.samdapp.data.vitalssource.ble

import com.example.samdapp.data.vitalssource.ble.GoldenFixture.hex
import com.example.samdapp.domain.vitalssource.Instrument
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** G-B6 (emulated bit), G-B21 (the 15 rules, one case each, in order) and the fixture envelope. */
class HubEnvelopeTest {

    private fun hub(s: String) = s.toByteArray(Charsets.US_ASCII)

    /** Parses [raw]; an exception, of any type, fails here at an assertion, because parse is total. */
    private fun parseTotal(raw: ByteArray): EnvelopeParse {
        val outcome = runCatching { HubEnvelope.parse(raw) }
        outcome.exceptionOrNull()?.let { fail("parse threw instead of rejecting ${raw.size} bytes: $it") }
        return outcome.getOrThrow()
    }

    private fun parsed(raw: ByteArray): HubEnvelope = when (val r = parseTotal(raw)) {
        is EnvelopeParse.Parsed -> r.envelope
        is EnvelopeParse.Rejected -> throw AssertionError("expected a parse, got rule ${r.rule.number} (${r.rule.name}): ${r.detail}")
    }

    private fun assertRule(rule: EnvelopeRule, raw: ByteArray) {
        when (val r = parseTotal(raw)) {
            is EnvelopeParse.Rejected -> assertEquals(r.detail, rule, r.rule)
            is EnvelopeParse.Parsed -> fail("expected rule ${rule.number} (${rule.name}), but the envelope parsed")
        }
    }

    // ---- the fixture envelope ----------------------------------------------------------------

    @Test
    fun `the test builder reproduces the SaMDPi fixture envelope byte for byte`() {
        val o = GoldenFixture.json.getAsJsonObject("envelope")
        assertArrayEquals(hex(o["hex"].asString), envelopeBytes())
    }

    @Test
    fun `the fixture envelope parses`() {
        val o = GoldenFixture.json.getAsJsonObject("envelope")
        val raw = hex(o["hex"].asString)
        assertEquals(o["length"].asInt, raw.size)
        val e = parsed(raw)
        assertEquals(Instrument.BP, e.instrument)
        assertEquals(EnvelopeQuality.OK, e.quality)
        assertEquals(o["profile_version"].asInt, e.profileVersion)
        assertEquals(o["sequence"].asLong, e.sequence)
        assertEquals(o["device_time_ms"].asLong, e.deviceTimeMs)
        assertEquals(o["hub_id"].asString, e.hubId)
        assertArrayEquals(hex(o["sig_hex"].asString), e.sig)
        assertArrayEquals(hex(o["nonce_hex"].asString), e.nonce)
        assertEquals(120.0, (SigDecoders.bloodPressure(e.sig).systolic as SigValue.Finite).value, 0.0)
    }

    @Test
    fun `all six instrument codes and all four quality codes parse`() {
        val instruments = listOf(
            Instrument.BP, Instrument.SPO2, Instrument.THERMOMETER,
            Instrument.GLUCOMETER, Instrument.WEIGHT_SCALE, Instrument.HEART_RATE,
        )
        instruments.forEachIndexed { i, expected ->
            assertEquals(expected, parsed(envelopeBytes(instrument = i + 1)).instrument)
            assertEquals(i + 1, InstrumentCodes.codeOf(expected))
        }
        listOf(EnvelopeQuality.OK, EnvelopeQuality.WARNING, EnvelopeQuality.ERROR, EnvelopeQuality.UNAVAILABLE)
            .forEachIndexed { code, expected -> assertEquals(expected, parsed(envelopeBytes(quality = code)).quality) }
    }

    @Test
    fun `boundary values parse`() {
        assertEquals("abc", parsed(envelopeBytes(hubIdBytes = hub("abc"))).hubId)
        val longest = "a".repeat(30) + "-9"
        assertEquals(longest, parsed(envelopeBytes(hubIdBytes = hub(longest))).hubId)
        assertEquals(0xFFFFFFFFL, parsed(envelopeBytes(sequence = 0xFFFFFFFFL)).sequence)
        assertEquals(-1L, parsed(envelopeBytes(deviceTime = -1L)).deviceTimeMs)
    }

    // ---- G-B6 --------------------------------------------------------------------------------

    @Test
    fun `an envelope that does not claim to be emulated is malformed`() {
        assertRule(EnvelopeRule.NOT_EMULATED, envelopeBytes(flags = 0x00))
    }

    // ---- G-B21: one case per rule ------------------------------------------------------------

    @Test
    fun `rule 1 too short`() {
        assertRule(EnvelopeRule.TOO_SHORT, envelopeBytes().copyOf(34))
        assertRule(EnvelopeRule.TOO_SHORT, ByteArray(0))
    }

    @Test
    fun `rule 2 envelope version`() {
        assertRule(EnvelopeRule.ENVELOPE_VERSION, envelopeBytes(version = 2))
        assertRule(EnvelopeRule.ENVELOPE_VERSION, envelopeBytes(version = 0))
    }

    @Test
    fun `rule 3 instrument code`() {
        assertRule(EnvelopeRule.INSTRUMENT_CODE, envelopeBytes(instrument = 0))
        assertRule(EnvelopeRule.INSTRUMENT_CODE, envelopeBytes(instrument = 7))
    }

    @Test
    fun `rule 4 quality code`() {
        assertRule(EnvelopeRule.QUALITY_CODE, envelopeBytes(quality = 4))
        assertRule(EnvelopeRule.QUALITY_CODE, envelopeBytes(quality = 255))
    }

    @Test
    fun `rule 5 sequence zero`() {
        assertRule(EnvelopeRule.SEQUENCE_ZERO, envelopeBytes(sequence = 0))
    }

    @Test
    fun `rule 6 reserved flag bits`() {
        assertRule(EnvelopeRule.RESERVED_FLAGS, envelopeBytes(flags = 0x03))
        assertRule(EnvelopeRule.RESERVED_FLAGS, envelopeBytes(flags = 0x81))
    }

    @Test
    fun `rule 7 emulated flag clear`() {
        assertRule(EnvelopeRule.NOT_EMULATED, envelopeBytes(flags = 0x00))
    }

    @Test
    fun `rule 8 hub id length`() {
        assertRule(EnvelopeRule.HUB_ID_LENGTH, envelopeBytes(hubIdBytes = hub("ab")))
        assertRule(EnvelopeRule.HUB_ID_LENGTH, envelopeBytes(hubIdBytes = hub("a".repeat(33))))
        assertRule(EnvelopeRule.HUB_ID_LENGTH, envelopeBytes(hubIdBytes = ByteArray(0)))
    }

    @Test
    fun `rule 9 truncated before the SIG length`() {
        assertRule(EnvelopeRule.TRUNCATED_BEFORE_SIG_LENGTH, envelopeBytes(truncateTo = 44))
        assertRule(EnvelopeRule.TRUNCATED_BEFORE_SIG_LENGTH, envelopeBytes(truncateTo = 36))
    }

    @Test
    fun `rule 10 hub id not ASCII`() {
        val bytes = hub("kernelhub2").also { it[3] = 0xC3.toByte() }
        assertRule(EnvelopeRule.HUB_ID_NOT_ASCII, envelopeBytes(hubIdBytes = bytes))
    }

    @Test
    fun `rule 11 hub id does not match the profile pattern`() {
        assertRule(EnvelopeRule.HUB_ID_INVALID, envelopeBytes(hubIdBytes = hub("KERNELHUB2")))
        assertRule(EnvelopeRule.HUB_ID_INVALID, envelopeBytes(hubIdBytes = hub("kernel_hub")))
        assertRule(EnvelopeRule.HUB_ID_INVALID, envelopeBytes(hubIdBytes = hub("kernel hub")))
    }

    @Test
    fun `rule 11 a trailing newline in the hub id cannot pass`() {
        assertRule(EnvelopeRule.HUB_ID_INVALID, envelopeBytes(hubIdBytes = hub("kernelhub2\n")))
    }

    @Test
    fun `rule 12 SIG length zero`() {
        assertRule(EnvelopeRule.SIG_LENGTH_ZERO, envelopeBytes(sig = ByteArray(0)))
    }

    @Test
    fun `rule 13 truncated SIG`() {
        assertRule(EnvelopeRule.TRUNCATED_SIG, envelopeBytes().copyOf(envelopeBytes().size - 1))
        assertRule(EnvelopeRule.TRUNCATED_SIG, envelopeBytes(sigLen = 17))
    }

    @Test
    fun `rule 14 trailing bytes`() {
        assertRule(EnvelopeRule.TRAILING_BYTES, envelopeBytes(trailing = byteArrayOf(0)))
        assertRule(EnvelopeRule.TRAILING_BYTES, envelopeBytes(sigLen = 15))
    }

    @Test
    fun `rule 15 profile version, the phone's own strictness`() {
        assertRule(EnvelopeRule.PROFILE_VERSION, envelopeBytes(profile = 2))
        assertRule(EnvelopeRule.PROFILE_VERSION, envelopeBytes(profile = 0))
    }

    // ---- G-B21: a payload that breaks two rules reports the earlier one -----------------------

    @Test
    fun `rules are checked in order, so the earlier of two broken rules is reported`() {
        assertRule(EnvelopeRule.TOO_SHORT, ByteArray(10).also { it[0] = 2 })
        assertRule(EnvelopeRule.ENVELOPE_VERSION, envelopeBytes(version = 2, instrument = 9))
        assertRule(EnvelopeRule.INSTRUMENT_CODE, envelopeBytes(instrument = 9, quality = 9))
        assertRule(EnvelopeRule.QUALITY_CODE, envelopeBytes(quality = 9, sequence = 0))
        assertRule(EnvelopeRule.SEQUENCE_ZERO, envelopeBytes(sequence = 0, flags = 0x03))
        // Flags 0x02: a reserved bit set AND the emulated bit clear. Reserved is rule 6, emulated 7.
        assertRule(EnvelopeRule.RESERVED_FLAGS, envelopeBytes(flags = 0x02))
        assertRule(EnvelopeRule.NOT_EMULATED, envelopeBytes(flags = 0x00, hubIdBytes = hub("ab")))
        assertRule(EnvelopeRule.HUB_ID_LENGTH, envelopeBytes(hubIdLen = 40, truncateTo = null))
        val nonAscii = hub("kernelhub2").also { it[3] = 0xC3.toByte() }
        assertRule(EnvelopeRule.TRUNCATED_BEFORE_SIG_LENGTH, envelopeBytes(hubIdBytes = nonAscii, truncateTo = 44))
        assertRule(EnvelopeRule.HUB_ID_NOT_ASCII, envelopeBytes(hubIdBytes = nonAscii))
        assertRule(EnvelopeRule.HUB_ID_INVALID, envelopeBytes(hubIdBytes = hub("KERNELHUB2"), sig = ByteArray(0)))
        assertRule(EnvelopeRule.SIG_LENGTH_ZERO, envelopeBytes(sig = ByteArray(0), trailing = byteArrayOf(0)))
        assertRule(EnvelopeRule.TRUNCATED_SIG, envelopeBytes(profile = 2, truncateTo = envelopeBytes().size - 1))
        assertRule(EnvelopeRule.TRAILING_BYTES, envelopeBytes(trailing = byteArrayOf(0), profile = 2))
    }

    // ---- G-B21b: parse is total --------------------------------------------------------------

    @Test
    fun `parse never throws, it parses or names the rule it rejected on`() {
        val golden = envelopeBytes()
        // Every prefix, so every length check is reached with the bytes it guards missing.
        for (n in 0..golden.size) parseTotal(golden.copyOf(n))
        // Every single-byte substitution of the golden envelope, so each later rule is reached.
        for (i in golden.indices) {
            for (v in 0..255) parseTotal(golden.copyOf().also { it[i] = v.toByte() })
        }
        val rnd = java.util.Random(0x5A4D4450L)
        // Random bytes, lengths 0 to 128.
        repeat(20_000) { parseTotal(ByteArray(rnd.nextInt(129)).also(rnd::nextBytes)) }
        // The golden fixed part with a random tail, so the hub_id and SIG length fields vary.
        repeat(20_000) {
            val tail = ByteArray(rnd.nextInt(96)).also(rnd::nextBytes)
            parseTotal(golden.copyOf(33) + tail)
        }
    }

    @Test
    fun `the rule table is numbered 1 to 15 in running order`() {
        assertEquals((1..15).toList(), EnvelopeRule.entries.map { it.number })
        assertTrue(EnvelopeRule.PROFILE_VERSION == EnvelopeRule.entries.last())
    }
}
