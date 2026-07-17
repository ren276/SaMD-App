package com.example.samdapp.domain.model

/**
 * A follow-up chain: one root visit plus every visit logged as a follow-up to it (directly or
 * transitively), for a single patient. [visits] is newest first. Backs the "one row per chain"
 * Consultation History and the chain-detail screen.
 */
data class ConsultationChain(
    val rootEncounterId: String,
    val visits: List<ConsultationHistoryEntry>,
) {
    /** The most recent visit — the row shown on PatientSummary represents the chain by this. */
    val latest: ConsultationHistoryEntry get() = visits.first()
    val visitCount: Int get() = visits.size
}

/**
 * Groups a patient's flat visit history into follow-up chains. A visit's root is found by walking
 * [ConsultationHistoryEntry.followUpOfEncounterId] up until it hits a visit with no (resolvable)
 * parent. Standalone visits become single-visit chains. Chains are newest first (by their latest
 * visit); visits within each chain are newest first too.
 *
 * A `visited` guard makes the upward walk safe against a corrupted circular follow-up link — the
 * schema has no DB constraint preventing one.
 */
fun List<ConsultationHistoryEntry>.groupIntoChains(): List<ConsultationChain> {
    val byEncounterId = associateBy { it.encounterId }

    fun rootOf(entry: ConsultationHistoryEntry): String {
        var current = entry
        val visited = mutableSetOf(current.encounterId)
        while (true) {
            val parentId = current.followUpOfEncounterId ?: return current.encounterId
            val parent = byEncounterId[parentId] ?: return current.encounterId
            if (!visited.add(parent.encounterId)) return current.encounterId
            current = parent
        }
    }

    return groupBy { rootOf(it) }
        .map { (rootId, visits) ->
            ConsultationChain(rootEncounterId = rootId, visits = visits.sortedByDescending { it.visitDate })
        }
        .sortedByDescending { it.latest.visitDate }
}
