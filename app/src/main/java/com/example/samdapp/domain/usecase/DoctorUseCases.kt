package com.example.samdapp.domain.usecase

import com.example.samdapp.domain.model.Doctor
import com.example.samdapp.domain.repository.CaseRecordRepository
import com.example.samdapp.domain.repository.DoctorRepository
import javax.inject.Inject

class GetAvailableDoctorsUseCase @Inject constructor(
    private val doctorRepository: DoctorRepository,
) {
    suspend operator fun invoke(): Result<List<Doctor>> =
        doctorRepository.getDoctors().map { doctors -> doctors.sortedByDescending { it.available } }
}

class AssignDoctorUseCase @Inject constructor(
    private val caseRecordRepository: CaseRecordRepository,
) {
    suspend operator fun invoke(caseRecordId: String, doctorId: String): Result<Unit> =
        caseRecordRepository.assignDoctor(caseRecordId, doctorId)
}
