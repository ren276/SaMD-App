package com.example.samdapp.data.vitalssource.ble

import com.example.samdapp.data.vitalssource.ble.GoldenFixture.hex
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The fixture envelope's nonce and BP SIG bytes. */
val FIXTURE_NONCE: ByteArray = hex("00112233445566778899aabbccddeeff")
val FIXTURE_BP_SIG: ByteArray = hex("0678005000a5f3ea070a070a1e0f4800")

/** Builds envelope bytes the way SaMDPi's `pack` does, with every field overridable so a test can
 *  break exactly one rule. The defaults reproduce the fixture envelope byte for byte. */
fun envelopeBytes(
    version: Int = 1,
    profile: Int = 1,
    instrument: Int = 1,
    flags: Int = 1,
    quality: Int = 0,
    nonce: ByteArray = FIXTURE_NONCE,
    sequence: Long = 1,
    deviceTime: Long = 1791369015000L,
    hubIdBytes: ByteArray = "kernelhub2".toByteArray(Charsets.US_ASCII),
    hubIdLen: Int = hubIdBytes.size,
    sig: ByteArray = FIXTURE_BP_SIG,
    sigLen: Int = sig.size,
    trailing: ByteArray = ByteArray(0),
    truncateTo: Int? = null,
): ByteArray {
    val head = ByteBuffer.allocate(33).order(ByteOrder.LITTLE_ENDIAN)
        .put(version.toByte()).put(profile.toByte()).put(instrument.toByte()).put(flags.toByte()).put(quality.toByte())
        .put(nonce).putInt(sequence.toInt()).putLong(deviceTime).array()
    val all = head + byteArrayOf(hubIdLen.toByte()) + hubIdBytes + byteArrayOf(sigLen.toByte()) + sig + trailing
    return if (truncateTo == null) all else all.copyOf(truncateTo)
}
