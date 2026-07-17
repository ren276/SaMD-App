package com.example.samdapp.domain.usecase

import com.example.samdapp.domain.model.CaseRecord
import com.example.samdapp.domain.model.Encounter
import com.example.samdapp.domain.repository.CaseRecordRepository
import com.example.samdapp.domain.repository.EncounterRepository
import javax.inject.Inject

data class StartedCase(val encounter: Encounter, val caseRecord: CaseRecord)

/** Called once, when the Compounder / Initial Assessment screen opens — this is when a PHC visit
 * actually begins clinically, not at Register (demographics can be captured without a visit). */
class StartCaseUseCase @Inject constructor(
    private val encounterRepository: EncounterRepository,
    private val caseRecordRepository: CaseRecordRepository,
) {
    suspend operator fun invoke(patientId: String, followUpOfEncounterId: String? = null): Result<StartedCase> {
        val encounter = encounterRepository.startEncounter(patientId, followUpOfEncounterId).getOrElse {
            return Result.failure(it)
        }
        val caseRecord = caseRecordRepository.createDraft(patientId, encounter.id).getOrElse {
            return Result.failure(it)
        }
        return Result.success(StartedCase(encounter, caseRecord))
    }
}
