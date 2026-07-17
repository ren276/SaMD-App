package com.example.samdapp.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.samdapp.domain.model.ReferralStatus
import com.example.samdapp.domain.model.UrgencyLevel
import java.time.Instant

@Entity(tableName = "referrals", indices = [Index("patientUid"), Index("caseRecordId")])
data class ReferralEntity(
    @PrimaryKey val id: String,
    val patientUid: String,
    val caseRecordId: String,
    val urgencyLevel: UrgencyLevel,
    val reason: String,
    val sendingPhcId: String,
    val status: ReferralStatus,
    val timestamp: Instant,
)
