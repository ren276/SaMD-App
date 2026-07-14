package com.example.samdapp.domain.usecase

import com.example.samdapp.domain.model.Patient
import com.example.samdapp.domain.repository.PatientRepository
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject

class RegisterPatientUseCase @Inject constructor(
    private val patientRepository: PatientRepository,
) {
    suspend operator fun invoke(
        fullName: String,
        dateOfBirth: LocalDate?,
        age: Int?,
        biologicalSex: String,
        mobileNumber: String?,
        village: String?,
        guardianOrSpouseName: String? = null,
        aadhaarNumber: String? = null,
        abhaNumber: String? = null,
        block: String? = null,
        district: String? = null,
        state: String? = null,
        pincode: String? = null,
        category: String? = null,
        maritalStatus: String? = null,
        bloodGroup: String? = null,
        emergencyContact: String? = null,
        primaryCareClinicName: String? = null,
        referringPhysicianName: String? = null,
    ): Result<Patient> {
        if (fullName.isBlank()) {
            return Result.failure(IllegalArgumentException("Full name is required"))
        }
        val hasAddress = !village.isNullOrBlank() || !district.isNullOrBlank()
        if (mobileNumber.isNullOrBlank() && !hasAddress) {
            return Result.failure(IllegalArgumentException("Provide a phone number or an address"))
        }

        val now = Instant.now()
        val patient = Patient(
            id = UUID.randomUUID().toString(),
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
            createdAt = now,
            updatedAt = now,
        )

        return patientRepository.register(patient).map { patient }
    }
}
