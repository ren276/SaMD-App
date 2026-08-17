package com.example.samdapp.domain.model

/** Per-record push state against the backend (api-contract.md §6.1's Android handling rule).
 *  Persisted as its [name] string in Room via [com.example.samdapp.data.local.Converters].
 *  Only [PENDING] is ever written as of `MIGRATION_12_13`. The other three are declared for
 *  Phase 6's outbox to set and are unused until then. */
enum class SyncState {
    /** Local write, not yet acknowledged by the server. Default for every new and every
     *  pre-existing row. */
    PENDING,

    /** Server acknowledged `applied`, `stale`, or `duplicate` (api-contract.md §6.1). */
    SYNCED,

    /** Server acknowledged `conflict` (`base_version` mismatch). Stays queued, surfaced for
     *  review rather than retried blindly. */
    CONFLICT,

    /** Server acknowledged `rejected`. Retrying a malformed row forever drains the battery for
     *  nothing, so this state stops the outbox from retrying it. */
    FAILED,
}
