package com.example.samdapp.data.repository

import com.example.samdapp.data.local.dao.DoctorDao
import com.example.samdapp.data.local.entity.DoctorEntity
import com.example.samdapp.domain.model.Doctor
import com.example.samdapp.domain.repository.DoctorRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

class DoctorRepositoryImpl @Inject constructor(
    private val doctorDao: DoctorDao,
) : DoctorRepository {

    override suspend fun getDoctors(): Result<List<Doctor>> = asDataResult {
        doctorDao.observeAll().first().map { it.toDomain() }.sortedByDescending { it.available }
    }
}

private fun DoctorEntity.toDomain() = Doctor(
    id = id,
    name = name,
    specialty = specialty,
    available = available,
    facilityName = facilityName,
    registrationNumber = registrationNumber,
)
