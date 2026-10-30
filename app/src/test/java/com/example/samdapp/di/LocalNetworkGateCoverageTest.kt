package com.example.samdapp.di

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Memo 11.2: every `OkHttpClient` the app builds carries the local-network gate, as an application
 * interceptor (E1) after any dev host rewrite, plus the gated `Dns`. A client added without it
 * would send a local call straight to a 10 s connect timeout and the wrong copy.
 *
 * Reads `src/{main,dev,staging,prod}` as text. The source tree is a declared input of the unit-test
 * task (`app/build.gradle.kts`, `sourceTreeScannedBySourceScanningTests`), so an edit to any client
 * reruns this.
 */
class LocalNetworkGateCoverageTest {

    @Test
    fun `every OkHttp client in the app is gated`() {
        val files = SOURCE_SETS.flatMap { dir ->
            dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        }
        assertTrue("no sources found under $SOURCE_SETS", files.size > 100)
        val problems = files.flatMap { file -> gateProblems(file.readText()).map { "${file.path}: $it" } }
        assertEquals("", problems.joinToString("\n"))
        val builders = files.sumOf { BUILDER.toRegex(RegexOption.LITERAL).findAll(stripComments(it.readText())).count() }
        assertEquals("backend, ABHA, Gemini, Wi-Fi hub", 4, builders)
    }

    @Test
    fun `the checker rejects an ungated client`() {
        val snippet = """
            fun c(): OkHttpClient = OkHttpClient.Builder()
                .connectTimeout(1, TimeUnit.SECONDS)
                .build()
        """
        assertTrue(gateProblems(snippet).any { "application interceptor" in it })
        assertTrue(gateProblems(snippet).any { "gated Dns" in it })
    }

    @Test
    fun `the checker rejects the gate as a network interceptor`() {
        val snippet = """
            fun c(gate: LocalNetworkGate): OkHttpClient = OkHttpClient.Builder()
                .dns(LocalNetworkGateDns(Dns.SYSTEM, gate))
                .addNetworkInterceptor(LocalNetworkGateInterceptor(gate))
                .build()
        """
        assertTrue(gateProblems(snippet).any { "network interceptor" in it })
    }

    @Test
    fun `the checker rejects an ungated Dns and a gate placed before the dev rewrite`() {
        val snippet = """
            fun c(gate: LocalNetworkGate): OkHttpClient = OkHttpClient.Builder()
                .dns(Dns.SYSTEM)
                .addInterceptor(LocalNetworkGateInterceptor(gate))
                .apply { devInterceptors.forEach(::addInterceptor) }
                .build()
        """
        assertTrue(gateProblems(snippet).any { "ungated Dns" in it })
        assertTrue(gateProblems(snippet).any { "before the dev" in it })
        assertTrue(gateProblems("val c = OkHttpClient()").any { "OkHttpClient()" in it })
    }

    @Test
    fun `the checker passes the shipped shape`() {
        val snippet = """
            fun c(gate: LocalNetworkGate): OkHttpClient = OkHttpClient.Builder()
                .dns(LocalNetworkGateDns(Dns.SYSTEM, gate))
                .apply { devInterceptors.forEach(::addInterceptor) }
                .addInterceptor(LocalNetworkGateInterceptor(gate))
                .build()
            // A fresh `OkHttpClient.Builder()` in a comment is not a client.
            val url = "https://example.org/"
        """
        assertEquals(emptyList<String>(), gateProblems(snippet))
    }

    private companion object {
        const val BUILDER = "OkHttpClient.Builder()"
        const val GATE_INTERCEPTOR = ".addInterceptor(LocalNetworkGateInterceptor("
        const val GATE_DNS = ".dns(LocalNetworkGateDns("

        val MAIN: File = listOf(File("src/main"), File("app/src/main")).firstOrNull { it.isDirectory } ?: File("src/main")
        val SOURCE_SETS: List<File> = listOf("main", "dev", "staging", "prod").map { File(MAIN.parentFile, it) }

        fun stripComments(text: String): String = text
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("(?<![:\"])//[^\\n]*"), "")

        fun gateProblems(source: String): List<String> {
            val text = stripComments(source)
            val problems = mutableListOf<String>()
            if (Regex("\\bOkHttpClient\\(\\)").containsMatchIn(text)) problems += "OkHttpClient() built without a builder"
            Regex("\\.dns\\(").findAll(text).forEach { m ->
                if (!text.startsWith(GATE_DNS, m.range.first)) problems += "ungated Dns at offset ${m.range.first}"
            }
            var from = text.indexOf(BUILDER)
            while (from >= 0) {
                val end = text.indexOf(".build()", from).let { if (it < 0) text.length else it }
                val chain = text.substring(from, end)
                if (GATE_INTERCEPTOR !in chain) problems += "builder at $from lacks the gate as an application interceptor"
                if (GATE_DNS !in chain) problems += "builder at $from lacks the gated Dns"
                if ("addNetworkInterceptor(LocalNetworkGateInterceptor" in chain) {
                    problems += "builder at $from adds the gate as a network interceptor"
                }
                val dev = chain.indexOf("devInterceptors")
                val gate = chain.indexOf(GATE_INTERCEPTOR)
                if (dev >= 0 && gate >= 0 && gate < dev) problems += "builder at $from adds the gate before the dev host rewrite"
                from = text.indexOf(BUILDER, end)
            }
            return problems
        }
    }
}
