package com.example.samdapp.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.samdapp.data.local.entity.ReferralEntity
import com.example.samdapp.domain.model.ReferralStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface ReferralDao {
    @Insert
    suspend fun insert(referral: ReferralEntity)

    @Query("SELECT * FROM referrals WHERE caseRecordId = :caseRecordId ORDER BY timestamp DESC")
    fun observeForCase(caseRecordId: String): Flow<List<ReferralEntity>>

    @Query("UPDATE referrals SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: ReferralStatus)
}
