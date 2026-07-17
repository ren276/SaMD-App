package com.example.samdapp.presentation.compounder

import com.example.samdapp.domain.model.Visibility
import com.example.samdapp.testutil.testAilmentEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * REQ-AIL-02: this is the exact boundary the requirement is about — a PRIVATE [AilmentEntry]'s
 * clinical text/values must be genuinely absent from the worker-facing [AilmentListItem], not
 * merely unrendered. If this test passes, no composable downstream can leak the content by
 * accident, because the state it reads from never has it.
 */
class AilmentListItemMappingTest {

    @Test
    fun `a public entry keeps its clinical detail in the worker-facing item`() {
        val entry = testAilmentEntry(visibility = Visibility.PUBLIC, description = "Fever, 3 days")

        val item = entry.toListItem()

        assertEquals("Fever, 3 days", item.description)
        assertEquals(entry.severity, item.severity)
        assertEquals(entry.duration, item.duration)
    }

    @Test
    fun `a private entry drops description, severity, duration, and onset — not just hides them`() {
        val entry = testAilmentEntry(visibility = Visibility.PRIVATE, description = "Sensitive detail")

        val item = entry.toListItem()

        assertNull(item.description)
        assertNull(item.severity)
        assertNull(item.duration)
        assertNull(item.onset)
        assertEquals(Visibility.PRIVATE, item.visibility)
    }

    @Test
    fun `a private entry with audio reports hasAudio without exposing anything beyond a delete handle`() {
        val entry = testAilmentEntry(visibility = Visibility.PRIVATE).copy(audioLocalUri = "file:///private.m4a")

        val item = entry.toListItem()

        assertTrue(item.hasAudio)
        assertEquals("file:///private.m4a", item.audioUriForDelete)
        assertNull(item.description)
    }
}
