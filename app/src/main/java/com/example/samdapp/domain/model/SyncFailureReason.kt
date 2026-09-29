package com.example.samdapp.domain.model

/**
 * What a worker is told about a `FAILED` outbox row, and what they can do about it.
 *
 * S-2 persisted the two facts this needs, `syncErrorCode` and `syncErrorMessage`, and nothing
 * read them. This is the read side: it turns the pair into one of five worker-facing causes, each
 * with exactly one action. It is pure Kotlin so it is unit-testable without a device, following
 * [com.example.samdapp.domain.kernel.KernelFailure]'s shape, and for the same reason: the copy
 * for each value lives in `strings.xml` next to the `kernel_failure_*` strings, written to the
 * same rules.
 *
 * **Why the backend message is mapped and never rendered.** `syncErrorMessage` is written by
 * `backend/core/app/services/sync.py` for an operator reading a log. It is PHI-safe by contract
 * (errors.py: "detail never contains PHI") but it says things like "a referenced record does not
 * exist yet", which tells a health worker with a patient in front of them nothing they can act
 * on. So the message is used only as a discriminator here, and the words a worker sees are
 * written for them.
 *
 * **The one place the code alone is not enough.** The backend rejects every constraint violation
 * as `SAMD-SYNC-6003`, so a duplicate ABHA number and a missing required field arrive under the
 * same code and differ only in the message. The duplicate is the case this whole sequence started
 * from, so it has to be separable, which means matching the message text. See
 * [BackendConstraintMessages].
 */
enum class SyncFailureReason {
    /** `SAMD-SYNC-6003` with the unique-violation message. The backend classifies sqlstate 23505
     *  as [SyncRetryClass.CONFLICT], and the only client-reachable unique constraint that matters
     *  is `ix_patients_abha_number`: two patient records claiming one ABHA number. A person has
     *  to decide which is right, and there is no screen on this device that can do it. */
    DUPLICATE_RECORD,

    /** Any other rejection the backend judged on the record's merits: a missing required column,
     *  a value the schema forbids, a forbidden field (`SAMD-SYNC-6006`). On every table except
     *  `patients` this means the app wrote a row the server will never accept, which is a defect
     *  in this app, not something a worker did. */
    RECORD_REJECTED,

    /** [RETRY_EXHAUSTED_CODE]. The record is fine. Something it depends on was not on the server
     *  through all [MAX_SYNC_ATTEMPTS] attempts. This is the ONE cause where pressing a button
     *  can genuinely change the outcome, because the parent may have landed since. */
    RETRIES_EXHAUSTED,

    /** `SAMD-SYNC-RECORD-TOO-LARGE`, stamped locally by
     *  `SyncOutboxDrainer.failOversizedRecordsLocally` without the record ever being sent.
     *  Resending an unchanged oversize record cannot work. */
    RECORD_TOO_LARGE,

    /** No code at all, or a code this build does not know. A backend that grew a new code, or a
     *  row that reached FAILED by a path nobody anticipated. Deliberately NOT a crash and
     *  deliberately not a blank row: the fallback copy says plainly that the app does not know
     *  why, and offers the one safe action. */
    UNRECOGNISED,
    ;

    /** The single action offered for this cause. Two values, not three, because the app has no
     *  screen that edits a synced record: `PatientRepository` exposes `register` and no update,
     *  and no other repository exposes a corrective edit either. Offering "fix this record" would
     *  be a button with nowhere to go, which is the defect S-4 was written to stop. */
    val action: SyncFailureAction
        get() = when (this) {
            RETRIES_EXHAUSTED, UNRECOGNISED -> SyncFailureAction.SEND_AGAIN
            DUPLICATE_RECORD, RECORD_REJECTED, RECORD_TOO_LARGE -> SyncFailureAction.TELL_SUPERVISOR
        }
}

/** What the row's one button does, or that there is no button. */
enum class SyncFailureAction {
    /** Calls `SyncOutboxRepository.requeueFailed`, the FAILED to PENDING transition S-2 built.
     *  No edit: the record is not believed to be wrong, only unlucky. */
    SEND_AGAIN,

    /** No button. Nothing this worker can press changes the outcome, and the copy says so and
     *  names what to do instead. */
    TELL_SUPERVISOR,
}

/**
 * The exact strings `_SQLSTATE_MESSAGES` in `backend/core/app/services/sync.py` returns for the
 * four constraint classes it recognises. A mirror, and therefore a coupling: if the backend
 * rewords one of these, a duplicate silently starts reading as a generic rejection.
 *
 * `SyncFailureMessageMirrorTest` reads the backend source as text and fails if a constant here is
 * no longer present in it. That is the same crude-but-running guard `SyncDaoSqlContractTest` and
 * `AuditActionBackendMirrorTest` use, and it is used here for the same reason: the alternative is
 * a coupling with nothing watching it at all.
 */
object BackendConstraintMessages {
    /** sqlstate 23505, the duplicate-ABHA terminus. */
    const val UNIQUE_VIOLATION = "this record already exists with different data."

    /** sqlstate 23503. Seen here only if it somehow reached FAILED without the retry path; the
     *  normal route for a 23503 is RETRYABLE and then [RETRY_EXHAUSTED_CODE]. */
    const val FOREIGN_KEY_VIOLATION = "a referenced record does not exist yet."

    /** sqlstate 23502. */
    const val NOT_NULL_VIOLATION = "a required field is missing."

    /** sqlstate 23514. */
    const val CHECK_VIOLATION = "a field value is not valid."
}

/** Backend `ErrorCode.SYNC_RECORD_INVALID`. Every per-record rejection carries it. */
const val SYNC_RECORD_INVALID_CODE = "SAMD-SYNC-6003"

/** Backend `ErrorCode.SYNC_FORBIDDEN_FIELD`. */
const val SYNC_FORBIDDEN_FIELD_CODE = "SAMD-SYNC-6006"

/** Device-local, stamped by `SyncOutboxDrainer.failOversizedRecordsLocally`. */
const val RECORD_TOO_LARGE_CODE = "SAMD-SYNC-RECORD-TOO-LARGE"

/**
 * Total over every `(code, message)` pair a FAILED row can carry, including both nulls.
 *
 * Ordered by how much the pair tells us: the two device-local codes are unambiguous and go first,
 * then the backend's one rejection code disambiguated by message, then the fallback. A `when`
 * rather than a map because the 6003 branch needs the message and the others must ignore it.
 */
fun syncFailureReasonFor(code: String?, message: String?): SyncFailureReason = when (code) {
    RETRY_EXHAUSTED_CODE -> SyncFailureReason.RETRIES_EXHAUSTED
    RECORD_TOO_LARGE_CODE -> SyncFailureReason.RECORD_TOO_LARGE
    SYNC_FORBIDDEN_FIELD_CODE -> SyncFailureReason.RECORD_REJECTED
    SYNC_RECORD_INVALID_CODE -> when (message?.trim()) {
        BackendConstraintMessages.UNIQUE_VIOLATION -> SyncFailureReason.DUPLICATE_RECORD
        // Every other 6003 is the server refusing the record on its merits. That includes the
        // messages this app does not have a constant for ("op: unsupported operation.",
        // "action: unknown audit action.", and the rest): they are all one thing to a worker,
        // and enumerating them would be twelve values with one identical action, which is the
        // shape S-4 argued against.
        else -> SyncFailureReason.RECORD_REJECTED
    }
    // A null code is genuinely possible: a FAILED row written before S-2 persisted the message,
    // or a rejection the backend sent with no code at all.
    else -> SyncFailureReason.UNRECOGNISED
}
