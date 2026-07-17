package com.example.samdapp.domain.repository

import com.example.samdapp.domain.model.ReferralRequest
import kotlinx.coroutines.flow.Flow

interface ReferralRepository {
    suspend fun createReferral(referral: ReferralRequest): Result<Unit>
    fun observeForCase(caseRecordId: String): Flow<List<ReferralRequest>>

    /** This device's own sent-referral outbox (Referrals tab). */
    fun observeAll(): Flow<List<ReferralRequest>>
}
