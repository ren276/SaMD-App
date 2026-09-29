package com.example.samdapp.presentation.sync

import com.example.samdapp.R
import com.example.samdapp.domain.model.SyncFailureReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The copy map, as far as a JVM test can check it: that every cause and every table has its own
 * words, and that none of them accidentally share a resource id.
 *
 * It cannot read the strings themselves — that needs resources, so a device or Robolectric, and
 * this module has neither. What it CAN catch is the failure that copy-paste actually produces: a
 * second reason pointing at the first one's string, which renders plausible, wrong text with
 * nothing anywhere complaining.
 */
class SyncFailureCopyTest {

    /** The twenty tables the outbox drains. Written out rather than derived, for the reason
     *  `SyncDaoSqlContractTest` pins its counts: a list derived the same way as the thing it
     *  checks agrees with it even when both are wrong. */
    private val syncedTables = listOf(
        "patients", "encounters", "consultations", "attachments", "observations", "ailments",
        "medical_history_items", "allergies", "family_history_entries", "social_histories",
        "medication_entries", "case_records", "kernel_reports", "evaluate_reports",
        "diagnosis_feedback", "prescriptions", "medication_lines", "referrals", "abha_profiles",
        "audit_log",
    )

    @Test
    fun `every cause has its own title and its own body`() {
        val titles = SyncFailureReason.entries.map { it.titleRes }
        val bodies = SyncFailureReason.entries.map { it.bodyRes }
        assertEquals("two causes share a title resource", titles.size, titles.toSet().size)
        assertEquals("two causes share a body resource", bodies.size, bodies.toSet().size)
        SyncFailureReason.entries.forEach {
            assertNotEquals("$it uses one resource as both its title and its body", it.titleRes, it.bodyRes)
            assertTrue("$it has no title", it.titleRes != 0)
            assertTrue("$it has no body", it.bodyRes != 0)
        }
    }

    @Test
    fun `every syncable table has a worker-facing noun, and none falls through to the generic one`() {
        // The fallback exists for a table name from a future build, not for one this build
        // drains. A table that reaches it would be labelled "Record", which tells a worker
        // nothing about what failed.
        syncedTables.forEach { table ->
            assertNotEquals(
                "$table has no noun of its own and would render as the generic fallback",
                R.string.sync_record_type_other,
                recordTypeLabelFor(table),
            )
        }
        assertEquals(20, syncedTables.size)
    }

    @Test
    fun `an unknown table falls back rather than throwing`() {
        // A row that failed is still a row that failed. Blanking it would hide exactly the thing
        // this list exists to show.
        assertEquals(R.string.sync_record_type_other, recordTypeLabelFor("something_new"))
        assertEquals(R.string.sync_record_type_other, recordTypeLabelFor(""))
    }

    @Test
    fun `the twenty tables collapse onto seven nouns, not twenty and not one`() {
        val nouns = syncedTables.map { recordTypeLabelFor(it) }.toSet()
        assertEquals(
            "Seven nouns is the deliberate collapse: a worker does not distinguish an allergy " +
                "row from a family-history row. Twenty would be twenty strings saying five " +
                "things; one would be the defect this list exists to fix.",
            7,
            nouns.size,
        )
    }
}
