package com.example.samdapp.data.vitalssource.ble

import java.math.BigDecimal
import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.Month
import java.util.UUID

/** Bluetooth SIG 16-bit UUIDs this client uses, expanded onto the SIG base UUID. */
object SigUuids {
    private fun sig(short: Int): UUID = UUID.fromString("0000%04x-0000-1000-8000-00805f9b34fb".format(short))

    val DEVICE_INFORMATION_SERVICE: UUID = sig(0x180A)
    val HEALTH_THERMOMETER_SERVICE: UUID = sig(0x1809)
    val BLOOD_PRESSURE_SERVICE: UUID = sig(0x1810)
    val PULSE_OXIMETER_SERVICE: UUID = sig(0x1822)

    val SOFTWARE_REVISION: UUID = sig(0x2A28)
    val SERIAL_NUMBER: UUID = sig(0x2A25)
    val BLOOD_PRESSURE_MEASUREMENT: UUID = sig(0x2A35)
    val PLX_SPOT_CHECK_MEASUREMENT: UUID = sig(0x2A5E)
    val TEMPERATURE_MEASUREMENT: UUID = sig(0x2A1C)
}

/** The bytes are not a well-formed SIG measurement. The caller maps this to MALFORMED. The decoders
 *  below throw nothing else, for any input (pinned by a property test). */
class SigFormatException(message: String) : Exception(message)

/** The special values of the IEEE-11073 SFLOAT and FLOAT formats (GSS section 2.1.1). None of them
 *  is a number, and none may reach the form. */
enum class SpecialValue { NAN, NRES, POSITIVE_INFINITY, NEGATIVE_INFINITY, RFU }

sealed interface SigValue {
    data class Finite(val value: Double) : SigValue

    data class Special(val kind: SpecialValue) : SigValue
}

/** One decoded SIG measurement. [values] holds every value field that was present on the wire. */
sealed interface SigMeasurement {
    val values: List<SigValue>
}

/** True when any present value field is a special value. A hub that reports quality OK with such a
 *  field is lying or broken (defence in depth for RC-2, parent memo 5.4). */
fun SigMeasurement.hasSpecialValue(): Boolean = values.any { it is SigValue.Special }

data class BloodPressureMeasurement(
    val systolic: SigValue,
    val diastolic: SigValue,
    val meanArterialPressure: SigValue,
    val pulse: SigValue?,
    val timestamp: LocalDateTime?,
) : SigMeasurement {
    override val values: List<SigValue> = listOfNotNull(systolic, diastolic, meanArterialPressure, pulse)
}

data class PulseOximeterSpotCheck(
    val spo2: SigValue,
    val pulse: SigValue,
    val timestamp: LocalDateTime?,
) : SigMeasurement {
    override val values: List<SigValue> = listOf(spo2, pulse)
}

data class TemperatureMeasurement(
    val celsius: SigValue,
    val timestamp: LocalDateTime?,
    val temperatureType: Int?,
) : SigMeasurement {
    override val values: List<SigValue> = listOf(celsius)
}

/**
 * Decoders for the three SIG characteristics a kernelhub can embed in its envelope (parent memo 5.4,
 * addendum 8). Pure, little endian, and strict: a flag bit this client does not handle, or a length
 * that is not exactly the one the flags imply, is [SigFormatException]. Nothing is skipped, guessed
 * or defaulted.
 */
object SigDecoders {

    // 0x2A35 flags: bit0 units (kPa when set), bit1 time stamp, bit2 pulse, bit3 user id, bit4 status.
    private const val BP_TIMESTAMP = 0x02
    private const val BP_PULSE = 0x04
    private const val BP_ALLOWED_FLAGS = BP_TIMESTAMP or BP_PULSE

    // 0x2A5E flags: bit0 time stamp. Bits 1 to 4 (status fields) are refused.
    private const val PLX_TIMESTAMP = 0x01
    private const val PLX_ALLOWED_FLAGS = PLX_TIMESTAMP

    // 0x2A1C flags: bit0 units (Fahrenheit when set), bit1 time stamp, bit2 temperature type.
    private const val HTS_TIMESTAMP = 0x02
    private const val HTS_TYPE = 0x04
    private const val HTS_ALLOWED_FLAGS = HTS_TIMESTAMP or HTS_TYPE

    private const val TIMESTAMP_LEN = 7

    private val SFLOAT_SPECIAL = mapOf(
        0x07FF to SpecialValue.NAN,
        0x0800 to SpecialValue.NRES,
        0x07FE to SpecialValue.POSITIVE_INFINITY,
        0x0802 to SpecialValue.NEGATIVE_INFINITY,
        0x0801 to SpecialValue.RFU,
    )

    private val FLOAT_SPECIAL = mapOf(
        0x007FFFFFL to SpecialValue.NAN,
        0x00800000L to SpecialValue.NRES,
        0x007FFFFEL to SpecialValue.POSITIVE_INFINITY,
        0x00800002L to SpecialValue.NEGATIVE_INFINITY,
        0x00800001L to SpecialValue.RFU,
    )

    /** SFLOAT: 16 bits little endian, a signed 4-bit exponent over a signed 12-bit mantissa. */
    fun sfloat(bytes: ByteArray, offset: Int): SigValue {
        val raw = u16(bytes, offset)
        SFLOAT_SPECIAL[raw]?.let { return SigValue.Special(it) }
        var mantissa = raw and 0x0FFF
        if (mantissa >= 0x0800) mantissa -= 0x1000
        var exponent = raw shr 12
        if (exponent >= 0x8) exponent -= 0x10
        return finite(mantissa, exponent)
    }

    /** FLOAT: 32 bits little endian, a signed 8-bit exponent over a signed 24-bit mantissa. */
    fun float(bytes: ByteArray, offset: Int): SigValue {
        val raw = u32(bytes, offset)
        FLOAT_SPECIAL[raw]?.let { return SigValue.Special(it) }
        var mantissa = (raw and 0x00FFFFFFL).toInt()
        if (mantissa >= 0x00800000) mantissa -= 0x01000000
        var exponent = (raw shr 24).toInt()
        if (exponent >= 0x80) exponent -= 0x100
        return finite(mantissa, exponent)
    }

    /**
     * GSS Date Time (GATT Specification Supplement 2026-09-09, section 3.80, Table 3.144): year u16,
     * month, day, hours, minutes, seconds.
     *
     * - Year 1582 to 9999, month 1 to 12, day 1 to 31, hours 0 to 23, minutes and seconds 0 to 59.
     *   Any other value is Reserved for Future Use, and so is a day the month does not have: MALFORMED.
     * - A year, month or day of 0 means "not known". The frame is still valid; the time stamp is
     *   absent and this returns null. The hub's time stamp is advisory anyway (parent memo 3.2).
     */
    fun timestamp(bytes: ByteArray, offset: Int): LocalDateTime? {
        val year = u16(bytes, offset)
        val month = u8(bytes, offset + 2)
        val day = u8(bytes, offset + 3)
        val hour = u8(bytes, offset + 4)
        val minute = u8(bytes, offset + 5)
        val second = u8(bytes, offset + 6)
        if (year != 0 && year !in 1582..9999) throw SigFormatException("time stamp year $year is reserved")
        if (month > 12) throw SigFormatException("time stamp month $month is reserved")
        if (day > 31) throw SigFormatException("time stamp day $day is reserved")
        if (hour > 23 || minute > 59 || second > 59) {
            throw SigFormatException("time stamp time %02d:%02d:%02d is reserved".format(hour, minute, second))
        }
        if (month != 0 && day > Month.of(month).maxLength()) {
            throw SigFormatException("time stamp day $day does not exist in month $month")
        }
        if (year == 0 || month == 0 || day == 0) return null
        return try {
            LocalDateTime.of(year, month, day, hour, minute, second)
        } catch (e: DateTimeException) {
            // Only 29 February of a common year is left to fail here.
            throw SigFormatException("time stamp is not a calendar date: ${e.message}")
        }
    }

    fun bloodPressure(bytes: ByteArray): BloodPressureMeasurement {
        val flags = flagsOf(bytes, BP_ALLOWED_FLAGS, "0x2A35")
        val hasTimestamp = (flags and BP_TIMESTAMP) != 0
        val hasPulse = (flags and BP_PULSE) != 0
        expectLength(bytes, 7 + (if (hasTimestamp) TIMESTAMP_LEN else 0) + (if (hasPulse) 2 else 0), "0x2A35")
        var at = 7
        val timestamp = if (hasTimestamp) timestamp(bytes, at).also { at += TIMESTAMP_LEN } else null
        val pulse = if (hasPulse) sfloat(bytes, at) else null
        return BloodPressureMeasurement(sfloat(bytes, 1), sfloat(bytes, 3), sfloat(bytes, 5), pulse, timestamp)
    }

    fun pulseOximeterSpotCheck(bytes: ByteArray): PulseOximeterSpotCheck {
        val flags = flagsOf(bytes, PLX_ALLOWED_FLAGS, "0x2A5E")
        val hasTimestamp = (flags and PLX_TIMESTAMP) != 0
        expectLength(bytes, 5 + (if (hasTimestamp) TIMESTAMP_LEN else 0), "0x2A5E")
        return PulseOximeterSpotCheck(
            spo2 = sfloat(bytes, 1),
            pulse = sfloat(bytes, 3),
            timestamp = if (hasTimestamp) timestamp(bytes, 5) else null,
        )
    }

    fun temperature(bytes: ByteArray): TemperatureMeasurement {
        val flags = flagsOf(bytes, HTS_ALLOWED_FLAGS, "0x2A1C")
        val hasTimestamp = (flags and HTS_TIMESTAMP) != 0
        val hasType = (flags and HTS_TYPE) != 0
        expectLength(bytes, 5 + (if (hasTimestamp) TIMESTAMP_LEN else 0) + (if (hasType) 1 else 0), "0x2A1C")
        var at = 5
        val timestamp = if (hasTimestamp) timestamp(bytes, at).also { at += TIMESTAMP_LEN } else null
        val type = if (hasType) u8(bytes, at) else null
        return TemperatureMeasurement(float(bytes, 1), timestamp, type)
    }

    /** BigDecimal, so 93.3 stays 93.3 and does not become 93.30000000000001. */
    private fun finite(mantissa: Int, exponent: Int): SigValue =
        SigValue.Finite(BigDecimal(mantissa).scaleByPowerOfTen(exponent).toDouble())

    private fun flagsOf(bytes: ByteArray, allowed: Int, name: String): Int {
        if (bytes.isEmpty()) throw SigFormatException("$name value is empty")
        val flags = u8(bytes, 0)
        if ((flags and allowed.inv()) != 0) {
            throw SigFormatException("$name flags 0x%02x set a bit this client does not handle".format(flags))
        }
        return flags
    }

    private fun expectLength(bytes: ByteArray, expected: Int, name: String) {
        if (bytes.size != expected) {
            throw SigFormatException("$name is ${bytes.size} bytes, its flags imply $expected")
        }
    }

    private fun u8(bytes: ByteArray, at: Int): Int = bytes[at].toInt() and 0xFF

    private fun u16(bytes: ByteArray, at: Int): Int = u8(bytes, at) or (u8(bytes, at + 1) shl 8)

    private fun u32(bytes: ByteArray, at: Int): Long =
        (u16(bytes, at).toLong()) or (u16(bytes, at + 2).toLong() shl 16)
}
