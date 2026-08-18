package com.example.samdapp.data.sync

import com.example.samdapp.data.remote.dto.SyncResultDto
import com.example.samdapp.domain.model.SyncState
import org.junit.Assert.assertEquals
import org.junit.Test

/** api-contract.md §6.1's Android handling rule, table-driven: every status the backend can
 *  return, mapped to the local [SyncState] transition it must produce. This is the exact mapping
 *  [SyncOutboxRepository.applyAck] is built on ([RoomSyncOutboxRepository] calls this same
 *  function; see SyncAckMapping.kt). */
class SyncAckMappingTest {

    private fun result(status: String) = SyncResultDto(table = "patients", id = "p1", status = status)

    @Test
    fun `applied maps to SYNCED`() {
        assertEquals(SyncState.SYNCED, result("applied").toLocalSyncState())
    }

    @Test
    fun `stale maps to SYNCED`() {
        assertEquals(SyncState.SYNCED, result("stale").toLocalSyncState())
    }

    @Test
    fun `duplicate maps to SYNCED`() {
        assertEquals(SyncState.SYNCED, result("duplicate").toLocalSyncState())
    }

    @Test
    fun `conflict maps to CONFLICT, not silently clobbered as SYNCED`() {
        assertEquals(SyncState.CONFLICT, result("conflict").toLocalSyncState())
    }

    @Test
    fun `rejected maps to FAILED`() {
        assertEquals(SyncState.FAILED, result("rejected").toLocalSyncState())
    }

    @Test(expected = IllegalStateException::class)
    fun `an unrecognized status is a hard error, not silently ignored`() {
        result("something-new-the-backend-added").toLocalSyncState()
    }
}
