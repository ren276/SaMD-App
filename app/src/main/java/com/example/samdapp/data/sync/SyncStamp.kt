package com.example.samdapp.data.sync

import java.time.Instant

/**
 * The stamp every outbox write puts in `localModifiedAt`: wall time in milliseconds, but never
 * equal to or before the previous stamp this process issued (`max(now, previous + 1 ms)`).
 *
 * Room stores epoch milliseconds, and `localModifiedAt` is both the revision the server compares
 * (`client_updated_at`) and the key the ack guard matches (`localModifiedAt = :sentLocalModifiedAt`).
 * Two writes of one row in the same millisecond, with a drain packing the first between them,
 * left two failures: the ack for the first matched the second and stamped SYNCED content the server
 * never received, or the second reached the server's rule 2 (equal timestamp, different content)
 * and came back as a self-inflicted conflict. A stamp that never repeats closes both.
 *
 * One process-wide clock rather than a per-row SQL `MAX(:now, localModifiedAt + 1)`: it covers the
 * whole-row insert paths as well as the UPDATE statements, and DAOs that copy one argument into
 * both `updatedAt` and `localModifiedAt` keep them equal. Every outbox write runs in this process.
 *
 * Known limit: the clock is per process, so a wall clock stepped back across a restart can still issue a stamp
 * below one written before the restart. Rule 4 (a matching base_version) protects every row that
 * has synced once; move to a per-row MAX if rows written only offline across restarts matter.
 */
object SyncStamp {
    @Volatile
    internal var wallMillis: () -> Long = { System.currentTimeMillis() }

    private var last = Long.MIN_VALUE

    @Synchronized
    fun now(): Instant {
        val stamp = maxOf(wallMillis(), last + 1)
        last = stamp
        return Instant.ofEpochMilli(stamp)
    }

    /** Restores the real wall clock and forgets the last stamp. Tests only. */
    @Synchronized
    internal fun resetForTest() {
        wallMillis = { System.currentTimeMillis() }
        last = Long.MIN_VALUE
    }
}
