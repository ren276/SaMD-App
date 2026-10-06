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

    /* A row is HELD when an ancestor in its chain is FAILED and has no `serverVersion`:
     *  the server has never held that ancestor, so this row's foreign key can never be satisfied.
     *  A held PENDING or RETRYABLE row is not collected by the drain, is not counted as waiting to send,
     *  and is listed under the ancestor that holds it. A CONFLICT ancestor holds nothing: a conflict is
     *  only ever acked for a row the server already has (operator ruling Q2). One constant per
     *  non-root table, each naming its own columns, written against the table's unaliased name so the
     *  four statements per table that use it (drain, observed count, one-shot count, review) share it.
     *  The three root tables (patients, abha_profiles, audit_log) have no ancestor and use none. */

    /** The states a row is in while the device still owes it to the server. Shared so the review queries
     *  name them in one place, apart from the drain's own eligibility fragment. */
    const val UNSENT_STATES = "('PENDING', 'RETRYABLE')"

    /** Rows of [encounters] whose parent the server can never accept. */
    const val HELD_ENCOUNTERS =
        "(EXISTS (SELECT 1 FROM patients h1 WHERE h1.id = encounters.patientId AND h1.syncState = " +
        "'FAILED' AND h1.serverVersion IS NULL) OR EXISTS (SELECT 1 FROM encounters h1 WHERE h1.id = " +
        "encounters.followUpOfEncounterId AND h1.syncState = 'FAILED' AND h1.serverVersion IS NULL))"

    /** Rows of [consultations] whose parent the server can never accept. */
    const val HELD_CONSULTATIONS =
        "(EXISTS (SELECT 1 FROM patients h1 WHERE h1.id = consultations.patientId AND h1.syncState = " +
        "'FAILED' AND h1.serverVersion IS NULL) OR EXISTS (SELECT 1 FROM encounters h1 WHERE h1.id = " +
        "consultations.encounterId AND h1.syncState = 'FAILED' AND h1.serverVersion IS NULL))"

    /** Rows of [attachments] whose parent the server can never accept. */
    const val HELD_ATTACHMENTS =
        "(EXISTS (SELECT 1 FROM consultations h1 WHERE h1.id = attachments.consultationId AND " +
        "((h1.syncState = 'FAILED' AND h1.serverVersion IS NULL) OR EXISTS (SELECT 1 FROM patients h2 " +
        "WHERE h2.id = h1.patientId AND h2.syncState = 'FAILED' AND h2.serverVersion IS NULL) OR EXISTS " +
        "(SELECT 1 FROM encounters h2 WHERE h2.id = h1.encounterId AND h2.syncState = 'FAILED' AND " +
        "h2.serverVersion IS NULL))))"

    /** Rows of [observations] whose parent the server can never accept. */
    const val HELD_OBSERVATIONS =
        "(EXISTS (SELECT 1 FROM patients h1 WHERE h1.id = observations.patientId AND h1.syncState = " +
        "'FAILED' AND h1.serverVersion IS NULL) OR EXISTS (SELECT 1 FROM encounters h1 WHERE h1.id = " +
        "observations.encounterId AND h1.syncState = 'FAILED' AND h1.serverVersion IS NULL))"

    /** Rows of [ailments] whose parent the server can never accept. */
    const val HELD_AILMENTS =
        "(EXISTS (SELECT 1 FROM patients h1 WHERE h1.id = ailments.patientId AND h1.syncState = 'FAILED' " +
        "AND h1.serverVersion IS NULL) OR EXISTS (SELECT 1 FROM encounters h1 WHERE h1.id = " +
        "ailments.encounterId AND h1.syncState = 'FAILED' AND h1.serverVersion IS NULL))"

    /** Rows of [medical_history_items] whose parent the server can never accept. */
    const val HELD_MEDICAL_HISTORY_ITEMS =
        "(EXISTS (SELECT 1 FROM patients h1 WHERE h1.id = medical_history_items.patientId AND " +
        "h1.syncState = 'FAILED' AND h1.serverVersion IS NULL))"

    /** Rows of [allergies] whose parent the server can never accept. */
    const val HELD_ALLERGIES =
        "(EXISTS (SELECT 1 FROM patients h1 WHERE h1.id = allergies.patientId AND h1.syncState = 'FAILED' " +
        "AND h1.serverVersion IS NULL))"

    /** Rows of [family_history_entries] whose parent the server can never accept. */
    const val HELD_FAMILY_HISTORY_ENTRIES =
        "(EXISTS (SELECT 1 FROM patients h1 WHERE h1.id = family_history_entries.patientId AND " +
        "h1.syncState = 'FAILED' AND h1.serverVersion IS NULL))"

    /** Rows of [social_histories] whose parent the server can never accept. */
    const val HELD_SOCIAL_HISTORIES =
        "(EXISTS (SELECT 1 FROM patients h1 WHERE h1.id = social_histories.patientId AND h1.syncState = " +
        "'FAILED' AND h1.serverVersion IS NULL))"

    /** Rows of [medication_entries] whose parent the server can never accept. */
    const val HELD_MEDICATION_ENTRIES =
        "(EXISTS (SELECT 1 FROM patients h1 WHERE h1.id = medication_entries.patientId AND h1.syncState = " +
        "'FAILED' AND h1.serverVersion IS NULL) OR EXISTS (SELECT 1 FROM encounters h1 WHERE h1.id = " +
        "medication_entries.encounterId AND h1.syncState = 'FAILED' AND h1.serverVersion IS NULL))"

    /** Rows of [case_records] whose parent the server can never accept. */
    const val HELD_CASE_RECORDS =
        "(EXISTS (SELECT 1 FROM patients h1 WHERE h1.id = case_records.patientId AND h1.syncState = " +
        "'FAILED' AND h1.serverVersion IS NULL) OR EXISTS (SELECT 1 FROM encounters h1 WHERE h1.id = " +
        "case_records.encounterId AND h1.syncState = 'FAILED' AND h1.serverVersion IS NULL))"

    /** Rows of [kernel_reports] whose parent the server can never accept. */
    const val HELD_KERNEL_REPORTS =
        "(EXISTS (SELECT 1 FROM case_records h1 WHERE h1.id = kernel_reports.caseRecordId AND " +
        "((h1.syncState = 'FAILED' AND h1.serverVersion IS NULL) OR EXISTS (SELECT 1 FROM patients h2 " +
        "WHERE h2.id = h1.patientId AND h2.syncState = 'FAILED' AND h2.serverVersion IS NULL) OR EXISTS " +
        "(SELECT 1 FROM encounters h2 WHERE h2.id = h1.encounterId AND h2.syncState = 'FAILED' AND " +
        "h2.serverVersion IS NULL))))"

    /** Rows of [evaluate_reports] whose parent the server can never accept. */
    const val HELD_EVALUATE_REPORTS =
        "(EXISTS (SELECT 1 FROM case_records h1 WHERE h1.id = evaluate_reports.caseRecordId AND " +
        "((h1.syncState = 'FAILED' AND h1.serverVersion IS NULL) OR EXISTS (SELECT 1 FROM patients h2 " +
        "WHERE h2.id = h1.patientId AND h2.syncState = 'FAILED' AND h2.serverVersion IS NULL) OR EXISTS " +
        "(SELECT 1 FROM encounters h2 WHERE h2.id = h1.encounterId AND h2.syncState = 'FAILED' AND " +
        "h2.serverVersion IS NULL))))"

    /** Rows of [diagnosis_feedback] whose parent the server can never accept. */
    const val HELD_DIAGNOSIS_FEEDBACK =
        "(EXISTS (SELECT 1 FROM case_records h1 WHERE h1.id = diagnosis_feedback.caseRecordId AND " +
        "((h1.syncState = 'FAILED' AND h1.serverVersion IS NULL) OR EXISTS (SELECT 1 FROM patients h2 " +
        "WHERE h2.id = h1.patientId AND h2.syncState = 'FAILED' AND h2.serverVersion IS NULL) OR EXISTS " +
        "(SELECT 1 FROM encounters h2 WHERE h2.id = h1.encounterId AND h2.syncState = 'FAILED' AND " +
        "h2.serverVersion IS NULL))))"

    /** Rows of [prescriptions] whose parent the server can never accept. */
    const val HELD_PRESCRIPTIONS =
        "(EXISTS (SELECT 1 FROM patients h1 WHERE h1.id = prescriptions.patientId AND h1.syncState = " +
        "'FAILED' AND h1.serverVersion IS NULL) OR EXISTS (SELECT 1 FROM encounters h1 WHERE h1.id = " +
        "prescriptions.encounterId AND h1.syncState = 'FAILED' AND h1.serverVersion IS NULL) OR EXISTS " +
        "(SELECT 1 FROM case_records h1 WHERE h1.id = prescriptions.caseRecordId AND ((h1.syncState = " +
        "'FAILED' AND h1.serverVersion IS NULL) OR EXISTS (SELECT 1 FROM patients h2 WHERE h2.id = " +
        "h1.patientId AND h2.syncState = 'FAILED' AND h2.serverVersion IS NULL) OR EXISTS (SELECT 1 FROM " +
        "encounters h2 WHERE h2.id = h1.encounterId AND h2.syncState = 'FAILED' AND h2.serverVersion IS " +
        "NULL))))"

    /** Rows of [medication_lines] whose parent the server can never accept. */
    const val HELD_MEDICATION_LINES =
        "(EXISTS (SELECT 1 FROM prescriptions h1 WHERE h1.id = medication_lines.prescriptionId AND " +
        "((h1.syncState = 'FAILED' AND h1.serverVersion IS NULL) OR EXISTS (SELECT 1 FROM patients h2 " +
        "WHERE h2.id = h1.patientId AND h2.syncState = 'FAILED' AND h2.serverVersion IS NULL) OR EXISTS " +
        "(SELECT 1 FROM encounters h2 WHERE h2.id = h1.encounterId AND h2.syncState = 'FAILED' AND " +
        "h2.serverVersion IS NULL) OR EXISTS (SELECT 1 FROM case_records h2 WHERE h2.id = h1.caseRecordId " +
        "AND ((h2.syncState = 'FAILED' AND h2.serverVersion IS NULL) OR EXISTS (SELECT 1 FROM patients h3 " +
        "WHERE h3.id = h2.patientId AND h3.syncState = 'FAILED' AND h3.serverVersion IS NULL) OR EXISTS " +
        "(SELECT 1 FROM encounters h3 WHERE h3.id = h2.encounterId AND h3.syncState = 'FAILED' AND " +
        "h3.serverVersion IS NULL))))))"

    /** Rows of [referrals] whose parent the server can never accept. */
    const val HELD_REFERRALS =
        "(EXISTS (SELECT 1 FROM patients h1 WHERE h1.id = referrals.patientUid AND h1.syncState = " +
        "'FAILED' AND h1.serverVersion IS NULL) OR EXISTS (SELECT 1 FROM case_records h1 WHERE h1.id = " +
        "referrals.caseRecordId AND ((h1.syncState = 'FAILED' AND h1.serverVersion IS NULL) OR EXISTS " +
        "(SELECT 1 FROM patients h2 WHERE h2.id = h1.patientId AND h2.syncState = 'FAILED' AND " +
        "h2.serverVersion IS NULL) OR EXISTS (SELECT 1 FROM encounters h2 WHERE h2.id = h1.encounterId " +
        "AND h2.syncState = 'FAILED' AND h2.serverVersion IS NULL))))"
}
