package com.example.samdapp.presentation.report

import com.example.samdapp.R
import com.example.samdapp.domain.slm.SlmRefusal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The read-back's worker-facing copy table, asserted on identity rather than on English.
 *
 * Every assertion here is about WHICH string a refusal selects and WHAT action it offers, never
 * about the words, which are translatable and are allowed to be reworded without touching this
 * file. That is the same discipline `KernelFailureClassificationTest` applies to the
 * `kernel_failure_*` block, and it is why the copy lives in `strings.xml` at all.
 *
 * **This feature refuses more often than it answers by design**, so the copy table is not a
 * finishing touch on it, it is most of it. Nineteen refusals reach a health worker holding a phone
 * with a patient in front of them, and the difference between "the gate did its job" and "the app
 * is broken" is carried entirely by these pairs.
 */
class SlmRefusalCopyTest {

    @Test
    fun `every refusal has its own copy and none falls through to a generic message`() {
        val byRefusal = SlmRefusal.entries.associateWith { slmRefusalCopy(it) }

        // Totality is already a compile error: slmRefusalCopy has no `else`. What this guards is
        // the other half, which the compiler cannot see: two refusals pointed at one string pair.
        // That is the generic-bucket defect arriving by the back door, and it is exactly what the
        // domain-side taxonomy was built to prevent on the way in.
        assertEquals(
            "no two refusals may share a copy pair",
            SlmRefusal.entries.size,
            byRefusal.values.map { it.titleRes to it.bodyRes }.distinct().size,
        )
        assertEquals(
            "no two refusals may share a title",
            SlmRefusal.entries.size,
            byRefusal.values.map { it.titleRes }.distinct().size,
        )
        assertEquals(
            "no two refusals may share a body",
            SlmRefusal.entries.size,
            byRefusal.values.map { it.bodyRes }.distinct().size,
        )
        assertTrue(
            "every refusal must resolve to a real resource id",
            byRefusal.values.all { it.titleRes != 0 && it.bodyRes != 0 },
        )
    }

    @Test
    fun `the read-back never borrows another feature's copy`() {
        // The kernel and sync blocks are a different feature's failures on a different hop. Reusing
        // one of their strings would put "The assessment service is down" in front of a worker who
        // asked for a read-back, which is a true sentence about the wrong thing.
        val foreign = setOf(
            R.string.kernel_failure_offline_title,
            R.string.kernel_failure_secure_connection_failed_title,
            R.string.kernel_failure_unknown_title,
            R.string.kernel_failure_none_title,
            R.string.failed_sync_unrecognised_title,
        )
        val used = SlmRefusal.entries.flatMap { listOf(slmRefusalCopy(it).titleRes, slmRefusalCopy(it).bodyRes) }
        assertTrue("read-back copy must not reuse another feature's strings", used.none { it in foreign })
    }

    // ------------------------------------------------- the ones that must not invite a retry

    @Test
    fun `a TLS failure never offers a retry`() {
        // The reason PR-6 added SECURE_CONNECTION_FAILED at all. The offline and generic-failure
        // classes both carry "keep tapping", and one cause of a TLS failure is interception, where
        // tapping again means sending a physician's free-text diagnosis and a full prescription
        // line set once more to whoever is reading them.
        val copy = slmRefusalCopy(SlmRefusal.SECURE_CONNECTION_FAILED)
        assertEquals(SlmRetryOffer.NONE, copy.retry)
        assertEquals(R.string.readback_refusal_secure_connection_failed_title, copy.titleRes)
        // And it must not have been collapsed back into the offline wording, which is the specific
        // regression this class exists to make impossible.
        assertNotEquals(
            slmRefusalCopy(SlmRefusal.ENGINE_UNREACHABLE).titleRes,
            copy.titleRes,
        )
        assertNotEquals(slmRefusalCopy(SlmRefusal.ENGINE_UNREACHABLE).retry, SlmRetryOffer.RETRY_NOW)
    }

    @Test
    fun `an unverifiable served model never offers a retry`() {
        // A safety stop, not a transient failure. The gates that strip unsafe output were
        // calibrated for one artifact and this phone could not confirm it got that one.
        assertEquals(SlmRetryOffer.NONE, slmRefusalCopy(SlmRefusal.SERVED_MODEL_MISMATCH).retry)
    }

    @Test
    fun `a truncated answer offers a different question, not the same one again`() {
        // The finding this table produced. SlmRefusal.OUTPUT_TRUNCATED's KDoc said "Retryable: a
        // second generation may fit", written in PR-2 before the decode parameters were settled.
        // They are settled: temperature 0, do_sample false, constant seed, so the same question
        // produces the same cut-off answer byte for byte and "Try again" is a button that provably
        // cannot work. Asking something shorter is the only action that changes the outcome.
        assertEquals(SlmRetryOffer.ASK_AGAIN, slmRefusalCopy(SlmRefusal.OUTPUT_TRUNCATED).retry)
        assertNotEquals(SlmRetryOffer.RETRY_NOW, slmRefusalCopy(SlmRefusal.OUTPUT_TRUNCATED).retry)
    }

    @Test
    fun `an ungrounded answer offers a different question, not the same one again`() {
        // Same determinism argument. The generation completed and its content is what failed the
        // grounding check, so re-running it produces the identical text and the identical refusal.
        assertEquals(SlmRetryOffer.ASK_AGAIN, slmRefusalCopy(SlmRefusal.OUTPUT_NOT_GROUNDED).retry)
    }

    @Test
    fun `only the two transport failures where nothing was generated offer an immediate retry`() {
        // The general form of the two assertions above, so a new RETRY_NOW cannot be added without
        // this failing. A retry is honest only where no generation completed; anywhere a
        // deterministic decode already produced its answer, pressing again reproduces it exactly.
        val retryable = SlmRefusal.entries.filter { slmRefusalCopy(it).retry == SlmRetryOffer.RETRY_NOW }
        assertEquals(
            "only ENGINE_TIMEOUT and ENGINE_FAILED may offer an immediate retry",
            setOf(SlmRefusal.ENGINE_TIMEOUT, SlmRefusal.ENGINE_FAILED),
            retryable.toSet(),
        )
    }

    @Test
    fun `every refusal caused by the app or by a gate sends the worker somewhere real`() {
        // A gate refusal must not be a dead end. Either the worker can change the question, or the
        // copy points at the report, or it points at a supervisor. This asserts the structural half
        // of that: no refusal is left with an offer this table does not define.
        assertTrue(
            SlmRefusal.entries.all { slmRefusalCopy(it).retry in SlmRetryOffer.entries },
        )
        // And the three classes are all actually in use, so the type is not three names for one
        // behaviour.
        assertEquals(
            SlmRetryOffer.entries.toSet(),
            SlmRefusal.entries.map { slmRefusalCopy(it).retry }.toSet(),
        )
    }

    @Test
    fun `the gate refusals do not use the app-defect copy`() {
        // OUTPUT_NOT_GROUNDED and NOT_APPROVED are controls working correctly. ASSEMBLY_FAILED is
        // a real defect in this app. They must not read alike: "something went wrong in this app"
        // in front of a worker whose question was simply out of the record's scope teaches them
        // that the feature is unreliable, and it is the copy rule S-4 wrote down as "do not blame
        // a component that is working correctly".
        val defect = slmRefusalCopy(SlmRefusal.ASSEMBLY_FAILED)
        listOf(
            SlmRefusal.OUTPUT_NOT_GROUNDED,
            SlmRefusal.NOT_APPROVED,
            SlmRefusal.OUT_OF_SCOPE_PHRASING,
            SlmRefusal.OUT_OF_SCOPE_INTENT,
            SlmRefusal.OUT_OF_SCOPE_UNTETHERED,
            SlmRefusal.OUTPUT_TRUNCATED,
        ).forEach { gate ->
            assertNotEquals("$gate must not read as an app defect", defect.titleRes, slmRefusalCopy(gate).titleRes)
            assertNotEquals(defect.bodyRes, slmRefusalCopy(gate).bodyRes)
        }
    }
}
