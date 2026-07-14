package com.example.samdapp.data.repository

import com.example.samdapp.data.doctor.DoctorAssetDataSource
import com.example.samdapp.domain.model.Doctor
import com.example.samdapp.domain.repository.DoctorRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

class DoctorRepositoryImpl @Inject constructor(
    private val doctorAssetDataSource: DoctorAssetDataSource,
) : DoctorRepository {

    override suspend fun getDoctors(): Result<List<Doctor>> = asDataResult {
        withContext(Dispatchers.IO) { doctorAssetDataSource.loadDoctors() }
    }
}
