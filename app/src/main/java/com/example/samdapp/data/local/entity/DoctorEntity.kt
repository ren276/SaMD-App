package com.example.samdapp.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Moved off the `doctors.json` asset (Part B) so specialty-scoped/least-busy queries can be done
 *  in SQL instead of loading and filtering a cached in-memory list. Still mock reference data —
 *  the migration seeds the same 9 doctors the asset used to, real onboarding is out of scope. */
@Entity(tableName = "doctors")
data class DoctorEntity(
    @PrimaryKey val id: String,
    val name: String,
    val specialty: String,
    val available: Boolean,
    val facilityName: String?,
    val registrationNumber: String?,
)
