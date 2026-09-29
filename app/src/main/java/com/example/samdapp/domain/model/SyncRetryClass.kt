package com.example.samdapp.domain.model

/**
 * Whether a rejected sync record may be resent unchanged. **The device mirror of
 * `app.models.enums.SyncRetryClass`** (`backend/core/app/models/enums.py`), carried on every
 * `rejected` result of `POST /api/v1/sync/push` (api-contract.md section 6.1).
 *
 * **Read from the wire, never inferred from the `code` string.** The backend owns this
 * classification because it owns the reason: only it knows whether a 23503 meant "the parent has
 * not landed" or a 23505 meant "another patient already holds this ABHA". A device that guessed
 * from the code would be a second implementation of a rule that already exists, which is the
 * mirror drift this project has paid for twice. `SyncRetryClassMirrorTest` fails if this enum and
 * the Python one stop agreeing, in either direction.
 *
 * Three values and no `UNKNOWN` sentinel: an absent or unrecognised wire value is `null`, handled
 * at the one place that parses it ([parseSyncRetryClass]), so this enum stays exactly the
 * backend's set and the mirror test stays a plain set comparison.
 */
enum class SyncRetryClass {
    /** Resending the identical bytes later can succeed. The cause is outside this record: a
     *  parent row that has not landed, or a vocabulary the backend has not learned yet. */
    RETRYABLE,

    /** Resending the identical bytes fails identically, forever. Only a device-side change helps. */
    TERMINAL,

    /** A different, already-present record claims something this one also claims. A person must
     *  decide which of the two is right; no amount of retrying changes it. */
    CONFLICT,
}

/**
 * The value an unclassified rejection is treated as. [SyncRetryClass.TERMINAL] specifically
 * because it is what this seam did for every rejection before the field existed: an ack from an
 * older backend that sends no `retry_class`, or one from a newer backend that sends a value this
 * build has never heard of, keeps exactly today's behaviour instead of silently gaining an
 * unbounded retry nobody reasoned about.
 */
val CONSERVATIVE_SYNC_RETRY_CLASS = SyncRetryClass.TERMINAL

/**
 * Parses the wire value. Null means "no classification is available", which covers three real
 * cases and deliberately does not distinguish them here, because every caller must do the same
 * conservative thing with all three:
 *
 * 1. The result is not a rejection, so the backend sent no `retry_class`.
 * 2. The backend predates this field.
 * 3. The backend sent a value this build does not know, which is a mirror break.
 *
 * Case 3 is the one worth knowing about, and the guard against it is
 * `SyncRetryClassMirrorTest`, at build time, rather than a log line at 2am in a PHC.
 */
fun parseSyncRetryClass(raw: String?): SyncRetryClass? =
    raw?.let { value -> SyncRetryClass.entries.firstOrNull { it.name == value } }
