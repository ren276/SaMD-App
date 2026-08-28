package com.example.samdapp.domain.model

import java.time.Instant

/** One row of the Patients tab's directory (see [com.example.samdapp.domain.repository.PatientRepository.observeRegisteredOrSeenRecently]).
 *  [lastSeenAt] is null exactly when the patient has been registered but never had an
 *  encounter - the tab renders that as "registered, not yet seen" rather than hiding the row,
 *  unlike Home's encounter-required roster which never returns such a patient at all. */
data class PatientDirectoryEntry(
    val patient: Patient,
    val lastSeenAt: Instant?,
)
