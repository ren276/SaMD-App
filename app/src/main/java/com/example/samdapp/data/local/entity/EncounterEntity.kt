package com.example.samdapp.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

/** [followUpOfEncounterId] is set once, at creation, when the worker marks this visit as a
 *  follow-up to a specific prior encounter (picked from that patient's consultation history —
 *  see PatientSummaryScreen). Null for a fresh/unrelated visit. Self-referential, not enforced
 *  via `@ForeignKey` — same posture as the rest of this schema (no FK constraints anywhere). */
@Entity(tableName = "encounters", indices = [Index("patientId")])
data class EncounterEntity(
    @PrimaryKey val id: String,
    val patientId: String,
    val startedAt: Instant,
    val createdAt: Instant,
    val updatedAt: Instant,
    val followUpOfEncounterId: String?,
)
