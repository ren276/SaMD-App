package com.example.samdapp.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.samdapp.domain.model.PhysicianDecision
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
)
