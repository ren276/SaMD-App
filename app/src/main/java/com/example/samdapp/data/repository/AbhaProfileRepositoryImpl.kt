package com.example.samdapp.data.repository

import com.example.samdapp.data.local.dao.AbhaProfileDao
import com.example.samdapp.data.local.entity.AbhaProfileEntity
import com.example.samdapp.domain.model.AbhaProfile
import com.example.samdapp.domain.repository.AbhaProfileRepository
import javax.inject.Inject

class AbhaProfileRepositoryImpl @Inject constructor(
    private val abhaProfileDao: AbhaProfileDao,
) : AbhaProfileRepository {

    override suspend fun saveProfile(profile: AbhaProfile): Result<Unit> = asDataResult {
        abhaProfileDao.upsert(profile.toEntity())
    }

    override suspend fun getProfile(abhaId: String): AbhaProfile? =
        abhaProfileDao.getByAbhaId(abhaId)?.toDomain()
}

private fun AbhaProfile.toEntity() = AbhaProfileEntity(
    abhaId = abhaId,
    abhaAddress = abhaAddress,
    name = name,
    dateOfBirth = dateOfBirth,
    gender = gender,
    address = address,
    district = district,
    state = state,
    pincode = pincode,
    mobileNumber = mobileNumber,
    emailAddress = emailAddress,
    photoUrlMock = photoUrlMock,
    kycVerified = kycVerified,
    createdAt = createdAt,
)

private fun AbhaProfileEntity.toDomain() = AbhaProfile(
    abhaId = abhaId,
    abhaAddress = abhaAddress,
    name = name,
    dateOfBirth = dateOfBirth,
    gender = gender,
    address = address,
    district = district,
    state = state,
    pincode = pincode,
    mobileNumber = mobileNumber,
    emailAddress = emailAddress,
    photoUrlMock = photoUrlMock,
    kycVerified = kycVerified,
    createdAt = createdAt,
)
