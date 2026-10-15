package com.example.samdapp.data.vitalssource.ble

import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Test

/** G-B22 (addendum B11). The fixture is SaMDPi's, not ours: the two repositories can only agree on
 *  the bytes of the wire if both test against the same file. */
class GoldenFixturePinTest {

    @Test
    fun `the fixture copy is byte-identical to SaMDPi bba2c6b`() {
        val actual = MessageDigest.getInstance("SHA-256").digest(GoldenFixture.resourceBytes())
            .joinToString("") { "%02x".format(it) }
        assertEquals(
            "app/src/testDev/resources/${GoldenFixture.RESOURCE} changed: copy " +
                "tests/fixtures/ble_golden_vectors.json byte-identically from SaMDPi bba2c6b, do not edit it here",
            PINNED_SHA256,
            actual,
        )
    }

    private companion object {
        const val PINNED_SHA256 = "e82aea4dfd9f4388c77a11ed58fc25c6e08598af05597c5eccb822a6ac96bffc"
    }
}
