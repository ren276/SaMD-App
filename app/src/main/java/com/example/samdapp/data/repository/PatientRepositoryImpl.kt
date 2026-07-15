package com.example.samdapp.data.repository

import com.example.samdapp.data.local.dao.PatientDao
import com.example.samdapp.data.local.entity.PatientEntity
import com.example.samdapp.domain.model.Patient
import com.example.samdapp.domain.repository.PatientRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

class PatientRepositoryImpl @Inject constructor(
    private val patientDao: PatientDao,
) : PatientRepository {

    override suspend fun register(patient: Patient): Result<Unit> = asDataResult {
        patientDao.insert(patient.toEntity())
    }

    override fun observePatient(patientId: String): Flow<Patient?> =
        patientDao.observeById(patientId).map { it?.toDomain() }

    override fun observeTodaysPatients(): Flow<List<Patient>> {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val startMillis = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMillis = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return patientDao.observePatientsWithEncounterBetween(startMillis, endMillis)
            .map { entities -> entities.map(PatientEntity::toDomain) }
    }
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
