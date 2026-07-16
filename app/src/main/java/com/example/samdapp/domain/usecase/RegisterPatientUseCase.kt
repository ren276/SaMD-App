package com.example.samdapp.domain.usecase

import com.example.samdapp.domain.model.Patient
import com.example.samdapp.domain.repository.PatientRepository
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject

class RegisterPatientUseCase @Inject constructor(
    private val patientRepository: PatientRepository,
) {
    companion object {
        private const val ID_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        private const val ID_LENGTH = 12
        private val secureRandom = SecureRandom()
    }

    /**
     * 12-char alphanumeric, per agent_docs/spec.md (10-12 char UID). 62^12 keyspace makes
     * collisions negligible at PHC patient volumes; the Room primary-key constraint on
     * PatientEntity.id is the backstop if one ever occurs (insert aborts, register() surfaces
     * Result.failure, caller retries and gets a fresh id) — no central registry to check against
     * offline.
     */
    private fun generatePatientId(): String =
        (1..ID_LENGTH).map { ID_CHARS[secureRandom.nextInt(ID_CHARS.length)] }.joinToString("")
    suspend operator fun invoke(
        fullName: String,
        dateOfBirth: LocalDate?,
        age: Int?,
        biologicalSex: String,
        mobileNumber: String?,
        village: String?,
        guardianOrSpouseName: String? = null,
        guardianRelation: String? = null,
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
            id = generatePatientId(),
            fullName = fullName,
            dateOfBirth = dateOfBirth,
            age = age,
            biologicalSex = biologicalSex,
            guardianOrSpouseName = guardianOrSpouseName,
            guardianRelation = guardianRelation,
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
