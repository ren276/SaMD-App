package com.example.samdapp.data.repository

import com.example.samdapp.data.local.dao.AbhaProfileDao
import com.example.samdapp.data.local.entity.AbhaProfileEntity
import com.example.samdapp.domain.model.AbhaProfile
import com.example.samdapp.domain.repository.AbhaProfileRepository
import java.time.Instant
import javax.inject.Inject

class AbhaProfileRepositoryImpl @Inject constructor(
    private val abhaProfileDao: AbhaProfileDao,
) : AbhaProfileRepository {

    override suspend fun saveProfile(profile: AbhaProfile): Result<Unit> = asDataResult {
        // upsert() is REPLACE: without this read, a re-saved profile would silently wipe
        // serverVersion (syncstate-reset session). syncState needs no explicit reset —
        // AbhaProfileEntity's default is already PENDING, and REPLACE always writes the full
        // default set.
        val existingServerVersion = abhaProfileDao.getByAbhaId(profile.abhaId)?.serverVersion
        abhaProfileDao.upsert(profile.toEntity(serverVersion = existingServerVersion))
    }

    override suspend fun getProfile(abhaId: String): AbhaProfile? =
        abhaProfileDao.getByAbhaId(abhaId)?.toDomain()
}

private fun AbhaProfile.toEntity(serverVersion: Int?) = AbhaProfileEntity(
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
    localModifiedAt = Instant.now(),
    serverVersion = serverVersion,
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
