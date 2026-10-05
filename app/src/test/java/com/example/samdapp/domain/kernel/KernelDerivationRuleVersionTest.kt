package com.example.samdapp.domain.kernel

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The device's derivation rule version must equal the value in the shared fixture, which the
 * backend's `test_kernel_emergency_fixtures.py` asserts its own constant against. Two constants,
 * one reference value, no hand-kept parity: a rule change that bumps only one side fails the
 * other side's suite.
 */
class KernelDerivationRuleVersionTest {

    private fun fixtureVersion(): String {
        val text = requireNotNull(
            KernelDerivationRuleVersionTest::class.java.classLoader!!
                .getResource("classifier-fixtures/expectations.json"),
        ) { "missing expectations.json" }.readText()
        return JsonParser.parseString(text).asJsonObject.get("derivation_rule_version").asString
    }

    @Test
    fun theDeviceRuleVersionEqualsTheSharedFixtureValue() {
        assertEquals(fixtureVersion(), KernelTriageRules.DERIVATION_RULE_VERSION)
    }
}
