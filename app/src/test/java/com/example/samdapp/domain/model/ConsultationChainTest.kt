package com.example.samdapp.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/** groupIntoChains: collapse follow-up links into chains, newest-first, cycle-safe. */
class ConsultationChainTest {

    private fun entry(id: String, seconds: Long, followUpOf: String? = null) = ConsultationHistoryEntry(
        encounterId = id, visitDate = Instant.EPOCH.plusSeconds(seconds), chiefComplaint = "c-$id",
        caseRecordId = "case-$id", caseStatus = CaseStatus.SAVED_LOCALLY, followUpOfEncounterId = followUpOf,
        doctorName = null, doctorSpecialty = null,
    )

    @Test
    fun `standalone visits each become their own single-visit chain`() {
        val chains = listOf(entry("a", 100), entry("b", 200)).groupIntoChains()

        assertEquals(2, chains.size)
        // Newest chain first.
        assertEquals("b", chains[0].rootEncounterId)
        assertEquals(1, chains[0].visitCount)
        assertEquals("a", chains[1].rootEncounterId)
    }

    @Test
    fun `a follow-up collapses into its root's chain, latest first`() {
        // c follows b follows a — one chain of 3, root a, latest c.
        val chains = listOf(
            entry("a", 100),
            entry("b", 200, followUpOf = "a"),
            entry("c", 300, followUpOf = "b"),
        ).groupIntoChains()

        assertEquals(1, chains.size)
        val chain = chains.single()
        assertEquals("a", chain.rootEncounterId)
        assertEquals(3, chain.visitCount)
        assertEquals(listOf("c", "b", "a"), chain.visits.map { it.encounterId })
        assertEquals("c", chain.latest.encounterId)
    }

    @Test
    fun `two independent chains sort by their latest visit`() {
        val chains = listOf(
            entry("a", 100),
            entry("a2", 150, followUpOf = "a"),
            entry("b", 400),
        ).groupIntoChains()

        assertEquals(2, chains.size)
        assertEquals("b", chains[0].rootEncounterId) // latest 400
        assertEquals("a", chains[1].rootEncounterId) // latest 150
    }

    @Test
    fun `a circular follow-up link does not loop forever`() {
        // Corrupted data: a -> b -> a. Grouping must terminate.
        val chains = listOf(
            entry("a", 100, followUpOf = "b"),
            entry("b", 200, followUpOf = "a"),
        ).groupIntoChains()

        // Both resolve into a single chain without hanging; exact root is whichever the walk
        // stops at, but the invariant that matters is: it terminates and keeps both visits.
        assertEquals(2, chains.sumOf { it.visitCount })
    }
}
