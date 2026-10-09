package com.example.samdapp.data.vitalssource

import com.example.samdapp.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * G-B26. The two layers that validate `PI_HUB_ASSIGNMENT` (Gradle at configuration time, the
 * runtime parser) are tested against the GENERATED values, not against a copy of either function,
 * so a value one layer accepts and the other refuses fails here.
 *
 * Gradle derives the gateway base URL from the Wi-Fi entry of the assignment unless
 * `local.properties` overrides it. CI has no `local.properties`, so there the URL assertions
 * always run. A developer build made for desk work or a live check (an override) skips them, via
 * `PI_GATEWAY_BASE_URL_OVERRIDDEN`, instead of failing the suite. The runtime-parse assertion
 * never skips.
 */
class PiHubBuildConfigTest {

    @Test
    fun `BuildConfig PI_HUB_ASSIGNMENT parses with the runtime HubAssignment`() {
        // Throws, and so fails, if Gradle accepted a value the runtime parser refuses.
        HubAssignment.parse(BuildConfig.PI_HUB_ASSIGNMENT)
    }

    @Test
    fun `the gateway base URL is derived from the Wi-Fi hub of the assignment`() {
        assumeFalse("PI_GATEWAY_BASE_URL is overridden in local.properties", BuildConfig.PI_GATEWAY_BASE_URL_OVERRIDDEN)
        val wifiHub = HubAssignment.parse(BuildConfig.PI_HUB_ASSIGNMENT).wifiHubId
        assumeTrue("no Wi-Fi hub assigned, so the URL is the placeholder", wifiHub != null)

        assertEquals("http://$wifiHub.local:8090/", BuildConfig.PI_GATEWAY_BASE_URL)
    }

    @Test
    fun `the default build serves SPO2 from http kernelhub1 local 8090`() {
        // No second skip for an overridden PI_HUB_ASSIGNMENT: a build made with one is not a test
        // gate (addendum section 10), and a Gradle default that drifted must fail here, not skip.
        assumeFalse("PI_GATEWAY_BASE_URL is overridden in local.properties", BuildConfig.PI_GATEWAY_BASE_URL_OVERRIDDEN)

        assertEquals("http://kernelhub1.local:8090/", BuildConfig.PI_GATEWAY_BASE_URL)
    }
}
