package com.example.samdapp.domain.model

import java.time.Duration

/**
 * The bound on retrying a [SyncState.RETRYABLE] row. Two numbers and one sentinel code, in one
 * place, because a retry policy scattered across twenty DAO queries is a policy nobody can read.
 *
 * **Why a cap at all.** S-1 made a retryable rejection distinguishable; this makes it survivable
 * without making it eternal. Permanent retrying is not an improvement on permanent silence: a row
 * whose parent will never land would otherwise be resent on every drain for the life of the
 * install, burning a field device's battery and radio for a record no human is ever told about.
 */

/**
 * How many acked attempts a row gets before it is abandoned.
 *
 * Five, against a fifteen-minute periodic drain
 * (`WorkManagerSyncOutboxScheduler.PERIODIC_INTERVAL_MINUTES`), so a retryable row is carried for
 * at least an hour of real time and normally across a whole working session. The dominant
 * retryable cause is a `23503` foreign key whose parent is sitting in the same outbox and lands
 * on the very next drain, so one retry usually suffices and five is generous; a row that has
 * burned all five is a row whose parent is genuinely not coming, and it should reach a human the
 * same working day rather than being retried into the next week.
 */
const val MAX_SYNC_ATTEMPTS = 5

/**
 * The floor between two attempts on the same row.
 *
 * Five minutes, deliberately BELOW the fifteen-minute periodic interval, because this is not the
 * backoff. The periodic schedule is the backoff, and WorkManager already applies its own
 * exponential policy on top of it. This exists only to stop a row being retried within a single
 * drain, or by a worker tapping a manual sync repeatedly, and it is kept under the periodic
 * interval so it can never delay a scheduled drain that would otherwise have carried the row.
 *
 * Exponential per-row backoff was considered and rejected: it buys nothing for the dominant case
 * (a parent landing on the next drain, where a longer wait is strictly worse) and it would need a
 * `nextAttemptAt` column on all twenty tables to express.
 */
val RETRY_MIN_INTERVAL: Duration = Duration.ofMinutes(5)

/**
 * Stored in `syncErrorCode` when the cap runs out, so the resulting [SyncState.FAILED] is
 * separable from a server refusing the record on its merits. A device-local code, in no backend
 * registry, following the precedent `SyncOutboxDrainer.failOversizedRecordsLocally` already set
 * with `SAMD-SYNC-RECORD-TOO-LARGE`.
 *
 * This distinction is the whole reason the cap is allowed to exist. "We gave up after five tries"
 * and "the server says this record is wrong" need different words in front of a health worker,
 * and S-3 cannot tell them apart without this.
 */
const val RETRY_EXHAUSTED_CODE = "SAMD-SYNC-RETRY-EXHAUSTED"
