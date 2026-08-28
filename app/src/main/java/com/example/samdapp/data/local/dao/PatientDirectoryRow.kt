package com.example.samdapp.data.local.dao

import androidx.room.Embedded
import com.example.samdapp.data.local.entity.PatientEntity
import java.time.Instant

/** Projection for [PatientDao.observeRegisteredOrSeenBetween] - the Patients tab's directory
 *  read. [lastSeenAt] is null exactly when the patient has never had an encounter (registered
 *  but not yet seen); non-null is the most recent encounter's startedAt. Deliberately a
 *  different shape from [PatientEntity] alone (unlike Home's roster query) because the tab
 *  needs to render the two cases differently, not just list rows. */
data class PatientDirectoryRow(
    @Embedded val patient: PatientEntity,
    val lastSeenAt: Instant?,
)
