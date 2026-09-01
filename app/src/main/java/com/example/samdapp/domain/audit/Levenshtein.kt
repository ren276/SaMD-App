package com.example.samdapp.domain.audit

/**
 * Levenshtein edit distance, character-level. First use is the `editDistance` field on the
 * `VOICE_FIELD_EDITED` audit payload (`scratchpad/pr3-voice-gate-design-memo.md` C.2): a measured
 * signal of how much a worker changed an ASR suggestion, carrying no content of its own. Standard
 * single-row dynamic-programming form, no dependency needed for a function this small.
 */
fun levenshteinDistance(a: String, b: String): Int {
    if (a == b) return 0
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length

    var previousRow = IntArray(b.length + 1) { it }
    var currentRow = IntArray(b.length + 1)

    for (i in 1..a.length) {
        currentRow[0] = i
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            currentRow[j] = minOf(
                currentRow[j - 1] + 1, // insertion
                previousRow[j] + 1, // deletion
                previousRow[j - 1] + cost, // substitution
            )
        }
        val swap = previousRow
        previousRow = currentRow
        currentRow = swap
    }
    return previousRow[b.length]
}
