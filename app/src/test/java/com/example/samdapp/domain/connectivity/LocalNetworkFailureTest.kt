package com.example.samdapp.domain.connectivity

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalNetworkFailureTest {

    @Test
    fun `permission denied on an enforcing OS classifies as PERMISSION_DENIED regardless of exception text`() {
        val result = classifyLocalNetworkFailure(
            permissionGranted = false,
            sdkInt = ACCESS_LOCAL_NETWORK_ENFORCED_SDK,
            cause = IOException("some meaningless OEM-specific errno gibberish"),
        )
        assertEquals(LocalNetworkFailure.PERMISSION_DENIED, result)
    }

    @Test
    fun `permission granted on an enforcing OS with a failed socket classifies as UNREACHABLE`() {
        val result = classifyLocalNetworkFailure(
            permissionGranted = true,
            sdkInt = ACCESS_LOCAL_NETWORK_ENFORCED_SDK,
            cause = IOException("ECONNABORTED"),
        )
        assertEquals(LocalNetworkFailure.UNREACHABLE, result)
    }

    @Test
    fun `pre-enforcement OS with permission granted classifies as UNREACHABLE_OR_BLOCKED`() {
        val result = classifyLocalNetworkFailure(
            permissionGranted = true,
            sdkInt = ACCESS_LOCAL_NETWORK_ENFORCED_SDK - 1,
        )
        assertEquals(LocalNetworkFailure.UNREACHABLE_OR_BLOCKED, result)
    }

    @Test
    fun `pre-enforcement OS with permission reading denied still classifies as UNREACHABLE_OR_BLOCKED, never PERMISSION_DENIED`() {
        val result = classifyLocalNetworkFailure(
            permissionGranted = false,
            sdkInt = ACCESS_LOCAL_NETWORK_ENFORCED_SDK - 1,
        )
        assertEquals(LocalNetworkFailure.UNREACHABLE_OR_BLOCKED, result)
    }
}
