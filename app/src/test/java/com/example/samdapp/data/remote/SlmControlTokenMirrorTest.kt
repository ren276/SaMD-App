package com.example.samdapp.data.remote

import com.example.samdapp.domain.slm.SlmEngineError
import com.example.samdapp.domain.slm.SlmEngineException
import com.example.samdapp.domain.slm.SlmRefusal
import com.example.samdapp.domain.slm.engineRefusalFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **The device half of the injection-guard coupling (PR-8).** Fails if either side of
 * `SAMD-SLM-8009` goes missing.
 *
 * The service refuses a prompt carrying the loaded artifact's control tokens with
 * `SAMD-SLM-8009` (`slm-service-contract.md` §2.8). `backend/core` maps that to a device-facing
 * code of its own, §4.2.2's rule, because relaying the service's number outward would make one
 * number mean two things on two hops. This device build branches on that code and nothing else,
 * so the two files have to agree on one string, and neither compiler can see the other.
 *
 * **Shaped after [com.example.samdapp.data.sync.SyncRetryClassMirrorTest] and existing for its
 * reason.** The backend half runs under pytest and this module's contributors run
 * `testDevDebugUnitTest`. Whichever side renumbers first, the other side's suite has to be the
 * thing that goes red, because the failure this prevents is silent: `httpErrorFor` falls back to
 * the status class for a code it does not know, so a renumbered `8016` would quietly become an
 * ordinary `PAYLOAD_REJECTED` and a worker would be told the request was shaped wrong when the
 * truth is that something written in an approved record reads as an instruction to the model.
 *
 * **The Gradle half matters as much as the assertions.** `app/build.gradle.kts` declares both
 * backend files read here as task inputs. Without that, a backend-only edit leaves
 * `testDevDebugUnitTest` `UP-TO-DATE` and serves the previous green result, which is this
 * project's characteristic bug and has landed here three times: S-1's `SyncRetryClass`, the
 * audit-action mirror that predated it, and PR-7's source-scanning tests.
 */
class SlmControlTokenMirrorTest {

    private val repoRoot: File = File(System.getProperty("user.dir")!!).let { cwd ->
        generateSequence(cwd) { it.parentFile }
            .firstOrNull { File(it, "backend/core/app/errors.py").isFile }
            ?: error("could not locate the repo root from $cwd")
    }

    private val errorsFile = File(repoRoot, "backend/core/app/errors.py")
    private val proxyFile = File(repoRoot, "backend/core/app/services/slm.py")

    /** The `SLM_PROMPT_CONTROL_TOKENS = "SAMD-SLM-xxxx"` value, read out of the backend enum. */
    private fun backendDeviceFacingCode(): String {
        val match = Regex("""SLM_PROMPT_CONTROL_TOKENS\s*=\s*"(SAMD-SLM-\d{4})"""")
            .find(errorsFile.readText())
        assertTrue(
            "${errorsFile.path} no longer declares ErrorCode.SLM_PROMPT_CONTROL_TOKENS; the " +
                "backend stopped naming the service's injection refusal separately",
            match != null,
        )
        return match!!.groupValues[1]
    }

    /** The body of the proxy's `4xx` branch, which is where the 8009 split lives. */
    private fun fourXxBranch(): String {
        val source = proxyFile.readText()
        val start = source.indexOf("if 400 <= status < 500:")
        assertTrue("${proxyFile.path} no longer has a 4xx branch; the file shape changed", start >= 0)
        val after = source.substring(start)
        val end = after.indexOf("\n    try:").let { if (it < 0) after.length else it }
        val body = after.substring(0, end)
        assertTrue("parsed an empty 4xx branch from ${proxyFile.path}", body.length > 200)
        return body
    }

    @Test
    fun `the backend refuses the service's 8009 and the device branches on the code it relays`() {
        val proxy = proxyFile.readText()
        assertTrue(
            "${proxyFile.path} no longer names SAMD-SLM-8009; the service's injection refusal " +
                "would fall into the generic 4xx branch and reach the device as SAMD-SLM-8012",
            proxy.contains("\"SAMD-SLM-8009\""),
        )
        assertTrue(
            "the 4xx branch no longer maps the service's 8009 to its own device-facing code",
            fourXxBranch().contains("ErrorCode.SLM_PROMPT_CONTROL_TOKENS"),
        )

        // The string comes from the backend file, never from a literal in this test, so a
        // renumbering on that side arrives here as a red test rather than as a silent fallback.
        val code = backendDeviceFacingCode()
        assertEquals(
            "$code is not mapped in httpErrorFor; it fell back to the status class",
            SlmEngineError.CONTROL_TOKENS_REJECTED,
            httpErrorFor(code, 422),
        )
        assertNotEquals(
            "the injection refusal was collapsed back into the generic payload rejection",
            httpErrorFor("SAMD-SLM-8012", 422),
            httpErrorFor(code, 422),
        )
        assertEquals(
            "the seam turned the service's injection refusal into an unnamed failure",
            SlmRefusal.PROMPT_CONTROL_TOKENS,
            engineRefusalFor(SlmEngineException(SlmEngineError.CONTROL_TOKENS_REJECTED)),
        )
    }

    @Test
    fun `the 8009 path is logged as a rejected payload and never trips the circuit breaker`() {
        val branch = fourXxBranch()

        // Contract §4.2.3. The outcome is the existing PAYLOAD_REJECTED: the operational fact is
        // the same as the service's other 4xx, and a new outcome value would be an Alembic
        // migration over slm_call_log.outcome's CHECK constraint to record what error_code
        // already says.
        assertTrue(
            "the 8009 path no longer records SlmCallOutcome.PAYLOAD_REJECTED",
            branch.contains("SlmCallOutcome.PAYLOAD_REJECTED"),
        )

        // §4.2's standing rule for every 4xx. A healthy service refusing one malformed prompt is
        // not an outage, and opening the circuit would punish every other worker in the PHC.
        assertFalse(
            "a breaker failure was recorded on the 4xx path; a rejected prompt is not an outage",
            branch.contains("breaker.record_failure()"),
        )
    }
}
