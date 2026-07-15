package com.example.samdapp.data.sync

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** REQ-SYN-01: sync starts un-synced; a sync round stamps lastSyncedAt and clears pending. */
class MockSyncStatusTest {

    @Test
    fun `initial state is not synced`() = runTest {
        val state = MockSyncStatus().state.first()
        assertNull(state.lastSyncedAt)
        assertFalse(state.isSyncing)
        assertEquals(0, state.pendingCount)
    }

    @Test
    fun `syncNow stamps lastSyncedAt and settles not syncing`() = runTest {
        val sync = MockSyncStatus()

        val result = sync.syncNow()

        assertEquals(Result.success(Unit), result)
        val state = sync.state.first()
        assertFalse(state.isSyncing)
        assertEquals(0, state.pendingCount)
        assert(state.lastSyncedAt != null) { "lastSyncedAt should be set after sync" }
    }
}
