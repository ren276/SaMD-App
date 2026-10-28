package com.example.samdapp.domain.slm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * G-5 final (on-device SLM memo 5.6, slice S6): the pinned list of the 70 litertlm HTML tag pieces.
 * The fixture is the GPU machine's G4 file, copied byte for byte; its sha256 below was confirmed
 * by the operator against the GPU machine. The Kotlin set must equal it exactly.
 */
class TokenizerDivergentMarkupTest {

    private val bytes: ByteArray = checkNotNull(javaClass.classLoader?.getResourceAsStream("slm/litertlm_html_tag_pieces.txt")) {
        "missing fixture"
    }.use { it.readBytes() }

    // The file has no trailing newline, so a line count of 69 and 70 entries are both expected.
    private val fixture: List<String> = String(bytes, Charsets.UTF_8).split('\n').filter { it.isNotEmpty() }

    @Test
    fun theFixtureIsTheOperatorConfirmedFile() {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals(PINNED_SHA256, digest)
        assertEquals(70, fixture.size)
        assertEquals(70, fixture.toSet().size)
        assertFalse("the fixture is expected to have no trailing newline", bytes.last() == '\n'.code.toByte())
    }

    @Test
    fun theKotlinSetEqualsTheFixtureExactly() {
        assertEquals(70, TOKENIZER_DIVERGENT_MARKUP.size)
        assertEquals(fixture.toSet(), TOKENIZER_DIVERGENT_MARKUP)
    }

    @Test
    fun everyPieceIsFoundAsAnExactSubstring() {
        fixture.forEach { assertEquals(it, firstDivergentMarkupIn("record text $it more")) }
    }

    @Test
    fun maskAndUnusedAreNotListedBecauseControlTokensAlreadyRefuseThem() {
        assertFalse("<mask>" in TOKENIZER_DIVERGENT_MARKUP)
        assertFalse(TOKENIZER_DIVERGENT_MARKUP.any { it.startsWith("<unused") })
        assertTrue("<mask>" in CONTROL_TOKENS)
        listOf("<unused0>", "<unused5>", "<unused6226>").forEach { assertTrue(it, it in CONTROL_TOKENS) }
    }

    @Test
    fun matchingIsCaseSensitive() {
        assertNull(firstDivergentMarkupIn("<B>"))
        assertNull(firstDivergentMarkupIn("</DIV>"))
        assertEquals("<b>", firstDivergentMarkupIn("<b>"))
    }

    @Test
    fun clinicalAngleBracketsAndNearMissesAreNotPieces() {
        listOf(
            "BP <90", "BP >=95%", "<5 years", "SpO2 < 94 and HR > 100", "a<b and c>d", "<5>", "<div", "div>",
            "< b >", "<bb>", "<unclosed", "<h7>", "</h7>", "<pending>",
        ).forEach { assertNull(it, firstDivergentMarkupIn(it)) }
    }

    private companion object {
        const val PINNED_SHA256 = "32261808c5e6e679d350f03c4894590ab22fbfe95841f6118016d6013ee07554"
    }
}
