package com.example.samdapp.domain.kernel

import com.example.samdapp.domain.model.AssessGateSnapshot
import com.example.samdapp.domain.model.BackendConstraintMessages
import com.example.samdapp.domain.model.SYNC_RECORD_INVALID_CODE
import com.example.samdapp.domain.model.SyncChainRow
import com.example.samdapp.domain.model.SyncState
import com.example.samdapp.domain.model.isServerPresent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The assess gate (memo section 3.2), as a pure decision over this case's own chain: case
 * record, encounter, patient. `/api/v1/assess` needs exactly one server row, the case record,
 * and on the server its patient and encounter are foreign keys, so a case the server holds
 * implies the whole chain. The parents are read only to name the cause when it does not.
 * Never the global syncNow() Result.
 */
class AssessGateDecisionTest {

    private fun row(state: SyncState, serverVersion: Int? = null, code: String? = null, message: String? = null) =
        SyncChainRow(syncState = state, serverVersion = serverVersion, syncErrorCode = code, syncErrorMessage = message)

    private val pending = row(SyncState.PENDING)

    @Test
    fun `server presence is a server version or SYNCED, and survives a local edit`() {
        assertTrue(isServerPresent(SyncState.SYNCED, null))
        assertTrue("an edit resets PENDING but keeps the version", isServerPresent(SyncState.PENDING, 4))
        assertFalse(isServerPresent(SyncState.PENDING, null))
        assertFalse(isServerPresent(SyncState.FAILED, null))
    }

    @Test
    fun `no local case record cannot be assessed`() =
        assertEquals(AssessGate.Stop(KernelFailure.RECORD_INCOMPLETE), assessGateDecision(null))

    @Test
    fun `a case the server holds proceeds, even after a local edit`() =
        assertEquals(
            AssessGate.Proceed,
            assessGateDecision(AssessGateSnapshot(caseRecord = row(SyncState.PENDING, serverVersion = 4), encounter = pending, patient = pending)),
        )

    @Test
    fun `a duplicate ABHA patient names the duplicate`() =
        assertEquals(
            AssessGate.Stop(KernelFailure.PATIENT_DUPLICATE),
            assessGateDecision(
                AssessGateSnapshot(
                    caseRecord = row(SyncState.RETRYABLE),
                    encounter = row(SyncState.FAILED, code = "SAMD-SYNC-RETRY-EXHAUSTED"),
                    patient = row(SyncState.FAILED, code = SYNC_RECORD_INVALID_CODE, message = BackendConstraintMessages.UNIQUE_VIOLATION),
                ),
            ),
        )

    @Test
    fun `any other FAILED or CONFLICT row in the chain blocks the visit`() {
        listOf(
            AssessGateSnapshot(caseRecord = pending, encounter = pending, patient = row(SyncState.FAILED, code = SYNC_RECORD_INVALID_CODE, message = BackendConstraintMessages.NOT_NULL_VIOLATION)),
            AssessGateSnapshot(caseRecord = pending, encounter = row(SyncState.CONFLICT), patient = pending),
            AssessGateSnapshot(caseRecord = row(SyncState.FAILED, code = "SAMD-SYNC-RETRY-EXHAUSTED"), encounter = pending, patient = pending),
        ).forEach { snapshot ->
            assertEquals(snapshot.toString(), AssessGate.Stop(KernelFailure.CASE_SYNC_BLOCKED), assessGateDecision(snapshot))
        }
    }

    @Test
    fun `a chain that is only pending or retryable has not been sent yet`() {
        assertEquals(
            AssessGate.Stop(KernelFailure.CASE_NOT_SENT_YET),
            assessGateDecision(AssessGateSnapshot(caseRecord = row(SyncState.RETRYABLE), encounter = pending, patient = row(SyncState.SYNCED, serverVersion = 1))),
        )
        assertEquals(
            "missing parent rows on this phone do not invent a cause",
            AssessGate.Stop(KernelFailure.CASE_NOT_SENT_YET),
            assessGateDecision(AssessGateSnapshot(caseRecord = pending, encounter = null, patient = null)),
        )
    }

    @Test
    fun `the new gate failures carry the right advice`() {
        assertEquals(KernelRetryAdvice.RETRY_NOW, KernelFailure.CASE_NOT_SENT_YET.advice)
        listOf(KernelFailure.RECORD_INCOMPLETE, KernelFailure.PATIENT_DUPLICATE, KernelFailure.CASE_SYNC_BLOCKED)
            .forEach { assertEquals(it.name, KernelRetryAdvice.NEEDS_ACTION, it.advice) }
    }
}
