package com.example.samdapp.data.repository

import com.example.samdapp.data.local.dao.PatientDao
import com.example.samdapp.data.local.entity.PatientEntity
import com.example.samdapp.domain.model.Patient
import com.example.samdapp.domain.repository.PatientRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class PatientRepositoryImpl @Inject constructor(
    private val patientDao: PatientDao,
) : PatientRepository {

    override suspend fun register(patient: Patient): Result<Unit> = asDataResult {
        patientDao.insert(patient.toEntity())
    }

    override fun observePatient(patientId: String): Flow<Patient?> =
        patientDao.observeById(patientId).map { it?.toDomain() }
}

private fun Patient.toEntity() = PatientEntity(
    id = id,
    fullName = fullName,
    dateOfBirth = dateOfBirth,
    age = age,
    biologicalSex = biologicalSex,
    guardianOrSpouseName = guardianOrSpouseName,
    mobileNumber = mobileNumber,
    aadhaarNumber = aadhaarNumber,
    abhaNumber = abhaNumber,
    village = village,
    block = block,
    district = district,
    state = state,
    pincode = pincode,
    category = category,
    maritalStatus = maritalStatus,
    bloodGroup = bloodGroup,
    emergencyContact = emergencyContact,
    primaryCareClinicName = primaryCareClinicName,
    referringPhysicianName = referringPhysicianName,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun PatientEntity.toDomain() = Patient(
    id = id,
    fullName = fullName,
    dateOfBirth = dateOfBirth,
    age = age,
    biologicalSex = biologicalSex,
    guardianOrSpouseName = guardianOrSpouseName,
    mobileNumber = mobileNumber,
    aadhaarNumber = aadhaarNumber,
    abhaNumber = abhaNumber,
    village = village,
    block = block,
    district = district,
    state = state,
    pincode = pincode,
    category = category,
    maritalStatus = maritalStatus,
    bloodGroup = bloodGroup,
    emergencyContact = emergencyContact,
    primaryCareClinicName = primaryCareClinicName,
    referringPhysicianName = referringPhysicianName,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
