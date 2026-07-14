package com.example.samdapp.domain.usecase

import com.example.samdapp.domain.repository.CaseRecordRepository
import javax.inject.Inject

class AcknowledgeCaseUseCase @Inject constructor(
    private val caseRecordRepository: CaseRecordRepository,
) {
    suspend operator fun invoke(caseRecordId: String): Result<Unit> =
        caseRecordRepository.markSavedLocally(caseRecordId)
}
