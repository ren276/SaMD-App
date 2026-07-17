package com.example.samdapp.domain.repository

import com.example.samdapp.domain.model.AbhaProfile

interface AbhaProfileRepository {
    suspend fun saveProfile(profile: AbhaProfile): Result<Unit>
    suspend fun getProfile(abhaId: String): AbhaProfile?
}
