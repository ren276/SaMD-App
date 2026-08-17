package com.example.samdapp.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.samdapp.domain.model.PhysicianDecision
import com.example.samdapp.domain.model.SyncState
import java.time.Instant

@Entity(tableName = "diagnosis_feedback", indices = [Index("caseRecordId")])
data class DiagnosisFeedbackEntity(
    @PrimaryKey val id: String,
    val caseRecordId: String,
    val icdCandidate: String,
    val physicianDecision: PhysicianDecision,
    val physicianFinalDiagnosis: String?,
    val clinicalNote: String?,
    val createdAt: Instant,
    val syncState: SyncState = SyncState.PENDING,
    val serverVersion: Int? = null,
    val syncErrorCode: String? = null,
    val lastSyncAttemptAt: Instant? = null,
    /** Sync metadata: when this row's bytes last changed on this device, including a
     *  same-id replace via [com.example.samdapp.data.local.dao.DiagnosisFeedbackDao.upsert].
     *  Maps to `client_updated_at` on the wire (Phase 6), see MIGRATION_12_13's KDoc. */
    val localModifiedAt: Instant,
)
