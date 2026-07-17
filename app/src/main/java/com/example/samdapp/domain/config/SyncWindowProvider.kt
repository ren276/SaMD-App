package com.example.samdapp.domain.config

/**
 * The promised turnaround the expectation-management message quotes to the patient (REQ-TRS-03):
 * "a doctor will review this within N hours." Deliberately not a hardcoded string in the
 * Acknowledgement screen — sync windows vary by PHC deployment, so this is a resource-backed value
 * ([com.example.samdapp.data.config.AndroidSyncWindowProvider], `R.integer.sync_window_hours`),
 * overridable per build flavor/resource overlay without a code change.
 */
interface SyncWindowProvider {
    fun hoursUntilReview(): Int
}
