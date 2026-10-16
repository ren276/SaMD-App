package com.example.samdapp.domain.slm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Control-token coverage against the service (on-device SLM memo 6.2, slice S6; ruling G-4).
 *
 * The fixture is the service's 24 special tokens from the G1 report, committed verbatim. It is an
 * independent source from [CONTROL_TOKENS]: deleting a literal from the Kotlin set cannot also edit
 * the file this test reads.
 */
class ServiceSpecialTokensTest {

    private val fixture: List<String> = resource("slm/service_special_tokens.txt").lines().filter { it.isNotEmpty() }

    @Test
    fun theFixtureHasTheServicesTwentyFourDistinctTokens() {
        assertEquals(24, fixture.size)
        assertEquals(24, fixture.toSet().size)
    }

    @Test
    fun everyServiceSpecialTokenIsInTheDeviceSet() {
        val missing = fixture.filterNot { it in CONTROL_TOKENS }
        assertEquals("service special tokens the device does not carry", emptyList<String>(), missing)
    }

    @Test
    fun theFourBoundaryMarkersAreNamedNotLeftToTheCatchAll() {
        listOf("<|image>", "<image|>", "<|audio>", "<audio|>").forEach {
            assertTrue("$it", it in CONTROL_TOKENS)
        }
    }

    @Test
    fun aBoundaryMarkerIsCountedAsKnownNotAsUnknown() {
        val sanitizer = SlmStreamSanitizer()
        val text = sanitizer.accept("a<|image>b<audio|>c") + sanitizer.finish()

        assertEquals("abc", text)
        assertEquals(2, sanitizer.suppression.droppedTokens)
        assertEquals(0, sanitizer.suppression.unknownTokens)
    }

    @Test
    fun aPromptCarryingAnyServiceSpecialTokenIsRefusedLocally() {
        fixture.forEach { assertTrue("$it", firstControlTokenIn("before $it after") != null) }
    }

    private fun resource(path: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(path)) { "missing test resource $path" }
            .use { String(it.readBytes(), Charsets.UTF_8) }
}
