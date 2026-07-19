package com.example.samdapp.domain.usecase

import com.example.samdapp.domain.repository.CaseRecordRepository
import javax.inject.Inject

class AssignDoctorUseCase @Inject constructor(
    private val caseRecordRepository: CaseRecordRepository,
) {
    suspend operator fun invoke(caseRecordId: String, doctorId: String, isOnline: Boolean): Result<Unit> =
        caseRecordRepository.assignDoctor(caseRecordId, doctorId, isOnline)
}
