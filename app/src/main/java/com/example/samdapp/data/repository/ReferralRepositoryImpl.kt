package com.example.samdapp.data.repository

import com.example.samdapp.data.local.dao.ReferralDao
import com.example.samdapp.data.local.entity.ReferralEntity
import com.example.samdapp.domain.model.ReferralRequest
import com.example.samdapp.domain.repository.ReferralRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class ReferralRepositoryImpl @Inject constructor(
    private val referralDao: ReferralDao,
) : ReferralRepository {

    override suspend fun createReferral(referral: ReferralRequest): Result<Unit> = asDataResult {
        referralDao.insert(referral.toEntity())
    }

    override fun observeForCase(caseRecordId: String): Flow<List<ReferralRequest>> =
        referralDao.observeForCase(caseRecordId).map { rows -> rows.map { it.toDomain() } }
}

private fun ReferralRequest.toEntity() = ReferralEntity(
    id = id,
    patientUid = patientUid,
    caseRecordId = caseRecordId,
    urgencyLevel = urgencyLevel,
    reason = reason,
    sendingPhcId = sendingPhcId,
    status = status,
    timestamp = timestamp,
)

private fun ReferralEntity.toDomain() = ReferralRequest(
    id = id,
    patientUid = patientUid,
    caseRecordId = caseRecordId,
    urgencyLevel = urgencyLevel,
    reason = reason,
    sendingPhcId = sendingPhcId,
    status = status,
    timestamp = timestamp,
)
