package com.example.samdapp.data.sync

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * Strictly increasing `localModifiedAt` (memo section 6). Room stores epoch milliseconds, so two
 * writes of one row in the same millisecond used to share a stamp: the ack guard
 * (`localModifiedAt = :sentLocalModifiedAt`) could then stamp SYNCED a write the server never
 * received, or the server's rule 2 answered a self-inflicted conflict. One process-wide clock that
 * never repeats a millisecond closes both.
 */
class SyncStampTest {

    // Before as well as after: the clock is process-wide, and repository tests that ran earlier in
    // the same JVM leave its last stamp at real wall time.
    @Before
    fun freshClock() = SyncStamp.resetForTest()

    @After
    fun restoreWallClock() = SyncStamp.resetForTest()

    @Test
    fun `two stamps in the same wall millisecond differ by one`() {
        SyncStamp.wallMillis = { 5_000L }
        val first = SyncStamp.now()
        val second = SyncStamp.now()
        assertEquals(Instant.ofEpochMilli(5_000L), first)
        assertEquals(Instant.ofEpochMilli(5_001L), second)
    }

    @Test
    fun `a wall clock stepped back still yields an increasing stamp`() {
        SyncStamp.wallMillis = { 9_000L }
        val before = SyncStamp.now()
        SyncStamp.wallMillis = { 1_000L }
        assertTrue(SyncStamp.now() > before)
    }

    @Test
    fun `a wall clock ahead of the last stamp is taken as is`() {
        SyncStamp.wallMillis = { 2_000L }
        SyncStamp.now()
        SyncStamp.wallMillis = { 7_000L }
        assertEquals(Instant.ofEpochMilli(7_000L), SyncStamp.now())
    }
}
