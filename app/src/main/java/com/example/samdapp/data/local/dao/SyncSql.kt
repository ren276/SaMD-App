package com.example.samdapp.data.local.dao

/**
 * SQL shared by every outbox DAO, defined once so the twenty drain queries cannot drift apart.
 * `SyncDaoSqlContractTest` fails if a DAO stops using [PENDING_ELIGIBILITY_FRAGMENT] or if the old
 * inline copy comes back.
 */
internal object SyncSql {
    /**
     * Which rows a drain may collect, as a WHERE fragment. The caller supplies `:retryEligibleBefore`.
     *
     * A PENDING row is ALWAYS eligible. It is either new or a worker just changed it, and making
     * that wait on the time of its last push delayed send-to-doctor and referral status updates by
     * up to a full periodic drain. Spinning within one drain is bounded by the drainer's in-memory
     * `attempted` set, not by this fragment.
     *
     * A RETRYABLE row waits until `lastSyncAttemptAt` is at or before the cutoff, so the attempt
     * budget is spent over time and not in a burst.
     */
    const val PENDING_ELIGIBILITY_FRAGMENT =
        "(syncState = 'PENDING' OR (syncState = 'RETRYABLE' AND " +
            "(lastSyncAttemptAt IS NULL OR lastSyncAttemptAt <= :retryEligibleBefore)))"
}
