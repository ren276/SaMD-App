package com.example.samdapp.domain.model

/** Per-record push state against the backend (api-contract.md §6.1's Android handling rule).
 *  Persisted as its [name] string in Room via [com.example.samdapp.data.local.Converters].
 *  [RETRYABLE] was added in MIGRATION_20_21: before it, a `rejected` ack of ANY kind became
 *  [FAILED], which is terminal and which no drain re-collects, so a child row whose parent had
 *  simply not landed yet was destroyed permanently rather than syncing on the next drain. */
enum class SyncState {
    /** Local write, not yet acknowledged by the server. Default for every new and every
     *  pre-existing row. */
    PENDING,

    /** Server acknowledged `applied`, `stale`, or `duplicate` (api-contract.md §6.1). */
    SYNCED,

    /** Server acknowledged `conflict` (`base_version` mismatch). Never collected for resend
     *  (`SyncSql.PENDING_ELIGIBILITY_FRAGMENT` excludes it), so a stale `base_version` is never
     *  sent blindly. NOT yet surfaced anywhere: the failed-record count and review list read
     *  `FAILED` only, so a CONFLICT row is invisible to the worker. Leaves only by a local clinical
     *  edit, which resets it to [PENDING]. */
    CONFLICT,

    /** Server acknowledged `rejected` with `retry_class = RETRYABLE` (api-contract.md section
     *  6.1): the record itself is fine and something outside it has to change first, typically a
     *  parent row that has not landed yet. NOT terminal. Drained again, after
     *  [com.example.samdapp.domain.model.RETRY_MIN_INTERVAL] and at most
     *  [com.example.samdapp.domain.model.MAX_SYNC_ATTEMPTS] times, after which it becomes
     *  [FAILED] carrying [RETRY_EXHAUSTED_CODE]. */
    RETRYABLE,

    /** Terminal. Either the server refused the record on its merits (`retry_class = TERMINAL` or
     *  `CONFLICT`), or it was [RETRYABLE] and the attempt cap ran out, which is distinguishable
     *  by `syncErrorCode == `[RETRY_EXHAUSTED_CODE]. Retrying a malformed row forever drains the
     *  battery for nothing, so this state stops the outbox from retrying it. Leaves only by
     *  explicit human action (an edit, or a retry the worker asks for), never on its own. */
    FAILED,
}
