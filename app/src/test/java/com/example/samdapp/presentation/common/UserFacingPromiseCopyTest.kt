package com.example.samdapp.presentation.common

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Memo section 12.5: no user-facing text may promise a time or an outcome the app cannot
 * guarantee. Reads `strings.xml` and every Kotlin string literal under `presentation/` as text,
 * so it covers copy that never reached a resource. Comments are stripped, because several quote
 * the phrases they ban.
 *
 * The banned phrases are the ones the 2026-10-06 grep found: a doctor "will review", a review
 * "within N hours", "by itself", "on its own", "automatically", and "syncs when". A genuine
 * guarantee can be phrased without them; a phrase that is true today and false after the next
 * change (a retry that stops, a toggle that holds the queue) is what this is here to catch.
 */
class UserFacingPromiseCopyTest {

    private val mainDir: File = File(System.getProperty("user.dir")!!).let { cwd ->
        generateSequence(cwd) { it.parentFile }
            .map { File(it, "app/src/main") }
            .firstOrNull { it.isDirectory }
            ?: error("could not locate app/src/main from $cwd")
    }

    private val banned = listOf(
        Regex("""within\s+(\d+|\$\{[^}]*\})\s+hours""", RegexOption.IGNORE_CASE),
        Regex("""\bwill review\b""", RegexOption.IGNORE_CASE),
        Regex("""\bby itself\b""", RegexOption.IGNORE_CASE),
        Regex("""\bon its own\b""", RegexOption.IGNORE_CASE),
        Regex("""\bautomatically\b""", RegexOption.IGNORE_CASE),
        Regex("""\bsyncs when\b""", RegexOption.IGNORE_CASE),
        Regex("""\bSync Up\b"""),
        // No consented training pipeline exists (DPDP purpose and consent), so no screen may say a case feeds one.
        Regex("""training (dataset|pipeline|example)""", RegexOption.IGNORE_CASE),
        Regex("""\bretrain""", RegexOption.IGNORE_CASE),
    )

    private fun stringsXmlText(): List<Pair<String, String>> {
        val xml = File(mainDir, "res/values/strings.xml").readText()
            .replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        return Regex("""<(string|item)[^>]*>(.*?)</(string|item)>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml).map { "strings.xml" to it.groupValues[2] }.toList()
    }

    private fun kotlinLiterals(): List<Pair<String, String>> {
        val root = File(mainDir, "java/com/example/samdapp/presentation")
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { file ->
            val stripped = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL).replace(file.readText(), "")
                .lineSequence().joinToString("\n") { it.substringBefore(" //") }
            Regex("\"((?:[^\"\\\\\\n]|\\\\.)*)\"").findAll(stripped).map { file.name to it.groupValues[1] }
        }.toList()
    }

    @Test
    fun `no user-facing text promises a time or an outcome the app cannot guarantee`() {
        val offences = (stringsXmlText() + kotlinLiterals()).flatMap { (source, text) ->
            banned.filter { it.containsMatchIn(text) }.map { "$source: \"${text.take(90)}\" matches ${it.pattern}" }
        }
        assertEquals(offences.joinToString("\n"), emptyList<String>(), offences)
    }
}
