package com.example.samdapp.data.vitalssource.ble

import com.example.samdapp.data.vitalssource.ble.GoldenFixture.hex
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** G-B7 (golden vectors decode exactly), the decoder half of G-B4 (special values), and the strict
 *  flag rules of addendum 8.2. */
class SigDecodersTest {

    private fun finite(v: SigValue): Double = (v as SigValue.Finite).value

    /** Only [SigFormatException] is a rejection. Any other exception fails here, at an assertion. */
    private fun assertMalformed(bytes: ByteArray, decode: (ByteArray) -> Any?, why: String) {
        val thrown = runCatching { decode(bytes) }.exceptionOrNull()
            ?: return fail("expected SigFormatException: $why")
        if (thrown !is SigFormatException) fail("threw $thrown instead of SigFormatException: $why")
    }

    // ---- G-B7: fixture vectors ------------------------------------------------------------

    @Test
    fun `every fixture SFLOAT decodes to its value, and NaN to a special value`() {
        val entries = GoldenFixture.json.getAsJsonArray("sfloat")
        assertTrue(entries.size() >= 8)
        for (e in entries) {
            val o = e.asJsonObject
            val decoded = SigDecoders.sfloat(hex(o["hex"].asString), 0)
            if (o["value"].isJsonNull) {
                assertEquals(o.toString(), SigValue.Special(SpecialValue.NAN), decoded)
            } else {
                // The fixture value is the pre-encoding number, so it may carry more digits than a
                // one-decimal SFLOAT can hold (93.33333 encodes as 93.3). Within one step of 0.1.
                assertEquals(o.toString(), o["value"].asDouble, finite(decoded), 0.05)
            }
        }
    }

    @Test
    fun `SFLOAT decodes exactly, with no binary rounding noise`() {
        assertEquals(120.0, finite(SigDecoders.sfloat(hex("7800"), 0)), 0.0)
        assertEquals(93.3, finite(SigDecoders.sfloat(hex("a5f3"), 0)), 0.0)
        assertEquals(36.7, finite(SigDecoders.sfloat(hex("6ff1"), 0)), 0.0)
        assertEquals(0.0, finite(SigDecoders.sfloat(hex("0000"), 0)), 0.0)
    }

    @Test
    fun `SFLOAT mantissa is signed`() {
        // 0xFFFF: exponent -1, mantissa -1.
        assertEquals(-0.1, finite(SigDecoders.sfloat(hex("ffff"), 0)), 0.0)
    }

    @Test
    fun `every fixture 32-bit FLOAT decodes to its value, and NaN to a special value`() {
        for (e in GoldenFixture.json.getAsJsonArray("float")) {
            val o = e.asJsonObject
            val decoded = SigDecoders.float(hex(o["hex"].asString), 0)
            if (o["value"].isJsonNull) {
                assertEquals(o.toString(), SigValue.Special(SpecialValue.NAN), decoded)
            } else {
                assertEquals(o.toString(), o["value"].asDouble, finite(decoded), 0.0)
            }
        }
    }

    @Test
    fun `32-bit FLOAT mantissa is signed`() {
        // exponent 0xFF = -1, mantissa 0xFFFFFF = -1.
        assertEquals(-0.1, finite(SigDecoders.float(hex("ffffffff"), 0)), 0.0)
    }

    @Test
    fun `the fixture time stamp decodes to its UTC instant`() {
        val o = GoldenFixture.json.getAsJsonObject("timestamp")
        val decoded = checkNotNull(SigDecoders.timestamp(hex(o["hex"].asString), 0))
        assertEquals(LocalDateTime.parse(o["utc"].asString), decoded)
        assertEquals(o["device_time_ms"].asLong, decoded.toInstant(ZoneOffset.UTC).toEpochMilli())
    }

    // ---- GSS 3.80 Date Time: 0 is "not known", anything else out of range is reserved -------------

    private fun ts(year: Int, month: Int, day: Int, hour: Int = 10, minute: Int = 30, second: Int = 15): ByteArray =
        byteArrayOf(
            (year and 0xFF).toByte(), (year shr 8).toByte(),
            month.toByte(), day.toByte(), hour.toByte(), minute.toByte(), second.toByte(),
        )

    /** Decodes, failing at an assertion if anything is thrown. */
    private fun <T> noThrow(what: String, block: () -> T): T {
        val outcome = runCatching(block)
        outcome.exceptionOrNull()?.let { fail("$what threw $it") }
        return outcome.getOrThrow()
    }

    private fun timestampOf(b: ByteArray): LocalDateTime? = noThrow("timestamp") { SigDecoders.timestamp(b, 0) }

    @Test
    fun `a year, month or day of 0 means not known, so the time stamp is absent`() {
        assertNull(timestampOf(ts(0, 10, 7)))
        assertNull(timestampOf(ts(2026, 0, 7)))
        assertNull(timestampOf(ts(2026, 10, 0)))
        assertNull(timestampOf(ts(0, 0, 0, 0, 0, 0)))
    }

    @Test
    fun `a frame whose time stamp is not known is accepted, without a time stamp`() {
        val stamp = ts(0, 10, 7).joinToString("") { "%02x".format(it) }
        val bp = noThrow("0x2A35") { SigDecoders.bloodPressure(hex("0678005000a5f3" + stamp + "4800")) }
        assertNull(bp.timestamp)
        assertEquals(72.0, finite(bp.pulse!!), 0.0)
        assertNull(noThrow("0x2A5E") { SigDecoders.pulseOximeterSpotCheck(hex("0162004800$stamp")) }.timestamp)
        val hts = noThrow("0x2A1C") { SigDecoders.temperature(hex("066f0100ff${stamp}02")) }
        assertNull(hts.timestamp)
        assertEquals(2, hts.temperatureType)
    }

    @Test
    fun `the valid ranges and leap days are accepted`() {
        assertEquals(LocalDateTime.of(1582, 1, 1, 0, 0, 0), timestampOf(ts(1582, 1, 1, 0, 0, 0)))
        assertEquals(LocalDateTime.of(9999, 12, 31, 23, 59, 59), timestampOf(ts(9999, 12, 31, 23, 59, 59)))
        assertEquals(LocalDateTime.of(2028, 2, 29, 10, 30, 15), timestampOf(ts(2028, 2, 29)))
    }

    @Test
    fun `a reserved or impossible time stamp is malformed`() {
        val decode = { b: ByteArray -> SigDecoders.timestamp(b, 0) }
        assertMalformed(ts(2026, 13, 7), decode, "month 13")
        assertMalformed(ts(2026, 10, 32), decode, "day 32")
        assertMalformed(ts(2026, 10, 7, hour = 24), decode, "hour 24")
        assertMalformed(ts(2026, 10, 7, minute = 60), decode, "minute 60")
        assertMalformed(ts(2026, 10, 7, second = 60), decode, "second 60")
        assertMalformed(ts(1581, 10, 7), decode, "year 1581")
        assertMalformed(ts(10000, 10, 7), decode, "year 10000")
        assertMalformed(ts(2026, 4, 31), decode, "31 April")
        assertMalformed(ts(2026, 2, 30), decode, "30 February")
        assertMalformed(ts(2026, 2, 29), decode, "29 February of a common year")
    }

    @Test
    fun `a reserved field or a day the month never has is malformed even when the date is not known`() {
        val decode = { b: ByteArray -> SigDecoders.timestamp(b, 0) }
        assertMalformed(ts(0, 2, 30), decode, "30 February, year not known")
        assertMalformed(ts(0, 13, 7), decode, "month 13, year not known")
        assertMalformed(ts(2026, 0, 32), decode, "day 32, month not known")
        assertMalformed(ts(0, 10, 7, hour = 24), decode, "hour 24, year not known")
        assertMalformed(ts(0, 10, 7, minute = 60), decode, "minute 60, year not known")
        assertMalformed(ts(0, 10, 7, second = 60), decode, "second 60, year not known")
    }

    // ---- decoders are total: SigFormatException or a value, for any bytes -----------------------

    @Test
    fun `the decoders reject arbitrary bytes only with SigFormatException`() {
        val rnd = java.util.Random(0x51474445L)
        val decoders = listOf<(ByteArray) -> Any>(
            SigDecoders::bloodPressure, SigDecoders::pulseOximeterSpotCheck, SigDecoders::temperature,
        )
        repeat(20_000) {
            val bytes = ByteArray(rnd.nextInt(33)).also(rnd::nextBytes)
            // Keep the flags plausible half the time, so the length and time stamp paths are reached.
            if (bytes.isNotEmpty() && rnd.nextBoolean()) bytes[0] = (bytes[0].toInt() and 0x07).toByte()
            for (decode in decoders) {
                val thrown = runCatching { decode(bytes) }.exceptionOrNull()
                if (thrown != null && thrown !is SigFormatException) fail("threw $thrown for ${bytes.size} bytes")
            }
        }
        // Random bytes rarely reach the time stamp with a valid frame around it, so sweep its fields
        // inside a real frame of each kind: every reserved and boundary value of each field.
        val years = listOf(0, 1, 1581, 1582, 2026, 2028, 9999, 10000, 65535)
        val months = (0..14) + 255
        val days = (0..33) + 255
        val times = listOf(Triple(0, 0, 0), Triple(23, 59, 59), Triple(24, 0, 0), Triple(0, 60, 0), Triple(0, 0, 60), Triple(255, 255, 255))
        for (year in years) for (month in months) for (day in days) for ((h, m, sec) in times) {
            val stamp = ts(year, month, day, h, m, sec)
            val frames = listOf<Pair<(ByteArray) -> Any, ByteArray>>(
                SigDecoders::bloodPressure to (hex("0678005000a5f3") + stamp + hex("4800")),
                SigDecoders::pulseOximeterSpotCheck to (hex("0162004800") + stamp),
                SigDecoders::temperature to (hex("066f0100ff") + stamp + hex("02")),
            )
            for ((decode, frame) in frames) {
                val thrown = runCatching { decode(frame) }.exceptionOrNull()
                if (thrown != null && thrown !is SigFormatException) {
                    fail("threw $thrown for time stamp $year-$month-$day $h:$m:$sec")
                }
            }
        }
    }

    @Test
    fun `fixture blood pressure vectors decode`() {
        val bp = GoldenFixture.json.getAsJsonObject("bp")
        val plain = SigDecoders.bloodPressure(hex(bp["normal_no_ts"].asJsonObject["hex"].asString))
        assertEquals(120.0, finite(plain.systolic), 0.0)
        assertEquals(80.0, finite(plain.diastolic), 0.0)
        assertEquals(93.3, finite(plain.meanArterialPressure), 0.0)
        assertEquals(72.0, finite(plain.pulse!!), 0.0)
        assertNull(plain.timestamp)

        val stamped = SigDecoders.bloodPressure(hex(bp["normal_ts"].asJsonObject["hex"].asString))
        assertEquals(120.0, finite(stamped.systolic), 0.0)
        assertEquals(72.0, finite(stamped.pulse!!), 0.0)
        assertEquals(LocalDateTime.parse("2026-10-07T10:30:15"), stamped.timestamp)
        assertFalse(stamped.hasSpecialValue())
    }

    @Test
    fun `the fixture blood pressure fault vector is all special values`() {
        val fault = SigDecoders.bloodPressure(hex(GoldenFixture.json.getAsJsonObject("bp")["fault_ts"].asJsonObject["hex"].asString))
        assertEquals(4, fault.values.size)
        assertTrue(fault.values.all { it == SigValue.Special(SpecialValue.NAN) })
        assertTrue(fault.hasSpecialValue())
    }

    @Test
    fun `fixture spot-check vectors decode`() {
        val plx = GoldenFixture.json.getAsJsonObject("plx_spot_check")
        val plain = SigDecoders.pulseOximeterSpotCheck(hex(plx["normal"].asJsonObject["hex"].asString))
        assertEquals(98.0, finite(plain.spo2), 0.0)
        assertEquals(72.0, finite(plain.pulse), 0.0)
        assertNull(plain.timestamp)

        val stamped = SigDecoders.pulseOximeterSpotCheck(hex(plx["normal_ts"].asJsonObject["hex"].asString))
        assertEquals(98.0, finite(stamped.spo2), 0.0)
        assertEquals(LocalDateTime.parse("2026-10-07T10:30:15"), stamped.timestamp)

        val fault = SigDecoders.pulseOximeterSpotCheck(hex(plx["fault_ts"].asJsonObject["hex"].asString))
        assertTrue(fault.hasSpecialValue())
        assertEquals(2, fault.values.size)
    }

    @Test
    fun `fixture thermometer vectors decode`() {
        val hts = GoldenFixture.json.getAsJsonObject("thermometer")
        val plain = SigDecoders.temperature(hex(hts["normal"].asJsonObject["hex"].asString))
        assertEquals(36.7, finite(plain.celsius), 0.0)
        assertNull(plain.timestamp)
        assertEquals(2, plain.temperatureType)

        val stamped = SigDecoders.temperature(hex(hts["normal_ts"].asJsonObject["hex"].asString))
        assertEquals(36.7, finite(stamped.celsius), 0.0)
        assertEquals(LocalDateTime.parse("2026-10-07T10:30:15"), stamped.timestamp)
        assertEquals(2, stamped.temperatureType)

        val fault = SigDecoders.temperature(hex(hts["fault_ts"].asJsonObject["hex"].asString))
        assertTrue(fault.hasSpecialValue())
    }

    // ---- G-B4, decoder half: a special value in any present field is reported ---------------

    private val sfloatSpecials = listOf(
        "ff07" to SpecialValue.NAN,
        "0008" to SpecialValue.NRES,
        "fe07" to SpecialValue.POSITIVE_INFINITY,
        "0208" to SpecialValue.NEGATIVE_INFINITY,
        "0108" to SpecialValue.RFU,
    )

    private val floatSpecials = listOf(
        "ffff7f00" to SpecialValue.NAN,
        "00008000" to SpecialValue.NRES,
        "feff7f00" to SpecialValue.POSITIVE_INFINITY,
        "02008000" to SpecialValue.NEGATIVE_INFINITY,
        "01008000" to SpecialValue.RFU,
    )

    @Test
    fun `each SFLOAT special word decodes to its special value`() {
        for ((word, kind) in sfloatSpecials) assertEquals(word, SigValue.Special(kind), SigDecoders.sfloat(hex(word), 0))
    }

    @Test
    fun `each 32-bit FLOAT special word decodes to its special value`() {
        for ((word, kind) in floatSpecials) assertEquals(word, SigValue.Special(kind), SigDecoders.float(hex(word), 0))
    }

    @Test
    fun `a special word in any present blood pressure field is reported`() {
        // flags 0x04: pulse present. Fields: systolic, diastolic, MAP, pulse.
        val ok = listOf("7800", "5000", "a5f3", "4800")
        for ((word, _) in sfloatSpecials) {
            for (field in ok.indices) {
                val fields = ok.toMutableList().also { it[field] = word }
                val bp = SigDecoders.bloodPressure(hex("04" + fields.joinToString("")))
                assertTrue("$word in field $field", bp.hasSpecialValue())
            }
        }
        assertFalse(SigDecoders.bloodPressure(hex("04" + ok.joinToString(""))).hasSpecialValue())
    }

    @Test
    fun `a special word in any present spot-check field is reported`() {
        val ok = listOf("6200", "4800")
        for ((word, _) in sfloatSpecials) {
            for (field in ok.indices) {
                val fields = ok.toMutableList().also { it[field] = word }
                val plx = SigDecoders.pulseOximeterSpotCheck(hex("00" + fields.joinToString("")))
                assertTrue("$word in field $field", plx.hasSpecialValue())
            }
        }
    }

    @Test
    fun `a special word in the temperature field is reported`() {
        for ((word, _) in floatSpecials) {
            assertTrue(word, SigDecoders.temperature(hex("00$word")).hasSpecialValue())
        }
    }

    // ---- strict flags (addendum 8.2) ---------------------------------------------------------

    private val bp = SigDecoders::bloodPressure
    private val plx = SigDecoders::pulseOximeterSpotCheck
    private val hts = SigDecoders::temperature

    @Test
    fun `blood pressure refuses kPa, user id, status and reserved flag bits`() {
        for (flags in listOf(0x01, 0x08, 0x10, 0x20, 0x40, 0x80)) {
            assertMalformed(hex("%02x".format(flags) + "78005000a5f3"), bp, "flags 0x%02x".format(flags))
        }
    }

    @Test
    fun `blood pressure length must be exactly what the flags imply`() {
        assertMalformed(ByteArray(0), bp, "empty")
        assertMalformed(hex("0078005000a5f3" + "00"), bp, "trailing byte, no flags set")
        assertMalformed(hex("0478005000a5f3"), bp, "pulse flag but no pulse")
        assertMalformed(hex("0678005000a5f3ea070a070a1e0f"), bp, "time stamp and pulse flags, no pulse")
        assertMalformed(hex("0278005000a5f3ea070a070a1e"), bp, "short time stamp")
    }

    @Test
    fun `spot-check refuses status and reserved flag bits`() {
        for (flags in listOf(0x02, 0x04, 0x08, 0x10, 0x20, 0x80)) {
            assertMalformed(hex("%02x".format(flags) + "62004800"), plx, "flags 0x%02x".format(flags))
        }
    }

    @Test
    fun `spot-check length must be exactly what the flags imply`() {
        assertMalformed(ByteArray(0), plx, "empty")
        assertMalformed(hex("006200480000"), plx, "trailing byte")
        assertMalformed(hex("01620048" + "00"), plx, "time stamp flag but no time stamp")
    }

    @Test
    fun `thermometer refuses Fahrenheit and reserved flag bits`() {
        for (flags in listOf(0x01, 0x08, 0x10, 0x80)) {
            assertMalformed(hex("%02x".format(flags) + "6f0100ff"), hts, "flags 0x%02x".format(flags))
        }
    }

    @Test
    fun `thermometer length must be exactly what the flags imply`() {
        assertMalformed(ByteArray(0), hts, "empty")
        assertMalformed(hex("006f0100ff00"), hts, "trailing byte")
        assertMalformed(hex("046f0100ff"), hts, "type flag but no type")
        assertMalformed(hex("066f0100ffea070a070a1e0f"), hts, "type flag but no type after the time stamp")
    }
}
