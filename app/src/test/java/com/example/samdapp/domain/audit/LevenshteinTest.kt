package com.example.samdapp.domain.audit

import org.junit.Assert.assertEquals
import org.junit.Test

class LevenshteinTest {

    @Test
    fun identicalStrings_haveZeroDistance() {
        assertEquals(0, levenshteinDistance("cannot walk far", "cannot walk far"))
    }

    @Test
    fun emptyAgainstNonEmpty_isTheOtherStringsLength() {
        assertEquals(5, levenshteinDistance("", "hello"))
        assertEquals(5, levenshteinDistance("hello", ""))
    }

    @Test
    fun bothEmpty_isZero() {
        assertEquals(0, levenshteinDistance("", ""))
    }

    @Test
    fun oneSubstitution() {
        assertEquals(1, levenshteinDistance("cat", "cot"))
    }

    @Test
    fun oneInsertion() {
        assertEquals(1, levenshteinDistance("cat", "cats"))
    }

    @Test
    fun oneDeletion() {
        assertEquals(1, levenshteinDistance("cats", "cat"))
    }

    @Test
    fun knownClassicPair() {
        assertEquals(3, levenshteinDistance("kitten", "sitting"))
    }

    @Test
    fun aRealisticHandCorrection() {
        assertEquals(
            6,
            levenshteinDistance(
                "cannot lift water buckets",
                "cannot lift heavy water buckets",
            ),
        )
    }
}
