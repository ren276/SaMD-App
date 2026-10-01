package com.example.samdapp.domain.usecase

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every classifier fixture must come from the pinned classifier build and carry the model
 * identity that build emits. A fixture captured before the identity change would keep agreeing
 * with consumers that read a key the classifier no longer relies on.
 * Mirrored by `test_fixtures_were_captured_from_the_pinned_classifier_and_carry_model_identity`
 * in the backend suite. PR 6 replaces the literal with the pin file.
 */
class ClassifierFixtureProvenanceTest {

    private val pinnedClassifierCommit = "5e1ca00"
    private val fixtures = listOf(
        "assess_spo2_85.json",
        "assess_sbp_185.json",
        "assess_dbp_112.json",
        "assess_high_risk_urgent.json",
        "assess_normal_model_branch.json",
    )

    private fun load(name: String) = JsonParser.parseString(
        requireNotNull(
            ClassifierFixtureProvenanceTest::class.java.classLoader!!.getResource("classifier-fixtures/$name"),
        ) { "missing fixture $name" }.readText(),
    ).asJsonObject

    @Test
    fun everyFixtureWasCapturedFromThePinnedClassifierAndCarriesModelIdentity() {
        fixtures.forEach { name ->
            val fixture = load(name)
            assertEquals(name, pinnedClassifierCommit, fixture.get("classifier_commit").asString)
            val metadata = fixture.getAsJsonObject("response").getAsJsonObject("model_metadata")
            assertTrue(name, metadata.get("model_version").asString.isNotBlank())
            assertEquals(name, 64, metadata.get("model_sha256").asString.length)
            assertTrue(name, metadata.has("calibrated"))
        }
    }
}
