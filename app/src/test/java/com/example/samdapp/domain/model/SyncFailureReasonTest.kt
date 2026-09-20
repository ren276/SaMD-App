package com.example.samdapp.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The copy table, as assertions.
 *
 * Every `(syncErrorCode, syncErrorMessage)` pair a FAILED row can carry, mapped to the one cause
 * a worker is told about and the one action they are offered. Pure Kotlin, no device, because
 * [syncFailureReasonFor] deliberately has no `androidx` in it.
 */
class SyncFailureReasonTest {

    @Test
    fun `a duplicate ABHA is separable from every other validation reject`() {
        // Both arrive as SAMD-SYNC-6003. The message is the only discriminator the backend gives
        // us, which is why BackendConstraintMessages is mirrored and mirror-tested.
        assertEquals(
            SyncFailureReason.DUPLICATE_RECORD,
            syncFailureReasonFor(SYNC_RECORD_INVALID_CODE, BackendConstraintMessages.UNIQUE_VIOLATION),
        )
        assertEquals(
            SyncFailureReason.RECORD_REJECTED,
            syncFailureReasonFor(SYNC_RECORD_INVALID_CODE, BackendConstraintMessages.NOT_NULL_VIOLATION),
        )
        assertEquals(
            SyncFailureReason.RECORD_REJECTED,
            syncFailureReasonFor(SYNC_RECORD_INVALID_CODE, BackendConstraintMessages.CHECK_VIOLATION),
        )
    }

    @Test
    fun `a validation reject with a message this app has no constant for is still a reject`() {
        // "op: unsupported operation.", "action: unknown audit action." and the rest. One action
        // for a worker, so one value.
        assertEquals(
            SyncFailureReason.RECORD_REJECTED,
            syncFailureReasonFor(SYNC_RECORD_INVALID_CODE, "client_updated_at: invalid timestamp."),
        )
        assertEquals(
            SyncFailureReason.RECORD_REJECTED,
            syncFailureReasonFor(SYNC_FORBIDDEN_FIELD_CODE, "audio_local_uri: forbidden field."),
        )
    }

    @Test
    fun `giving up after the attempt cap is not reported as the server refusing the record`() {
        // The whole justification for allowing a cap at all (SyncRetryPolicy's KDoc): "we gave up
        // after five tries" and "the server says this record is wrong" need different words.
        val gaveUp = syncFailureReasonFor(RETRY_EXHAUSTED_CODE, null)
        assertEquals(SyncFailureReason.RETRIES_EXHAUSTED, gaveUp)
        assertEquals(SyncFailureAction.SEND_AGAIN, gaveUp.action)
        assertEquals(
            SyncFailureAction.TELL_SUPERVISOR,
            syncFailureReasonFor(SYNC_RECORD_INVALID_CODE, BackendConstraintMessages.UNIQUE_VIOLATION).action,
        )
    }

    @Test
    fun `a locally oversized record is its own cause and offers no button`() {
        val tooLarge = syncFailureReasonFor(RECORD_TOO_LARGE_CODE, null)
        assertEquals(SyncFailureReason.RECORD_TOO_LARGE, tooLarge)
        assertEquals(SyncFailureAction.TELL_SUPERVISOR, tooLarge.action)
    }

    @Test
    fun `an unknown code, and no code at all, both fall back instead of crashing or blanking`() {
        assertEquals(SyncFailureReason.UNRECOGNISED, syncFailureReasonFor("SAMD-SYNC-6099", "something new"))
        assertEquals(SyncFailureReason.UNRECOGNISED, syncFailureReasonFor(null, null))
        assertEquals(SyncFailureReason.UNRECOGNISED, syncFailureReasonFor(null, "a message with no code"))
        assertEquals(SyncFailureReason.UNRECOGNISED, syncFailureReasonFor("", ""))
    }

    @Test
    fun `the classifier is total, and only the causes that can change offer a button`() {
        // Pinned rather than derived: a new reason must be a deliberate edit here, and the split
        // between "press this" and "tell someone" is the one decision a worker acts on.
        assertEquals(5, SyncFailureReason.entries.size)
        assertEquals(
            setOf(SyncFailureReason.RETRIES_EXHAUSTED, SyncFailureReason.UNRECOGNISED),
            SyncFailureReason.entries.filter { it.action == SyncFailureAction.SEND_AGAIN }.toSet(),
        )
    }
}
