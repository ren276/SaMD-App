package com.example.samdapp.data.vitalssource.ble

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** The shared BLE golden vectors, copied byte-identically from SaMDPi (see [GoldenFixturePinTest]). */
object GoldenFixture {
    const val RESOURCE = "ble_golden_vectors.json"

    fun resourceBytes(): ByteArray =
        checkNotNull(GoldenFixture::class.java.classLoader?.getResourceAsStream(RESOURCE)) {
            "$RESOURCE is not on the testDev classpath (app/src/testDev/resources)"
        }.use { it.readBytes() }

    val json: JsonObject by lazy { JsonParser.parseString(String(resourceBytes(), Charsets.UTF_8)).asJsonObject }

    fun hex(s: String): ByteArray {
        require(s.length % 2 == 0) { "odd hex length: $s" }
        return ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
