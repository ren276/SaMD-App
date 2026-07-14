package com.example.samdapp.domain.usecase

import com.example.samdapp.domain.model.Allergy
import com.example.samdapp.domain.model.AllergyCategory
import com.example.samdapp.domain.model.FamilyHistoryEntry
import com.example.samdapp.domain.model.MedicalHistoryCategory
import com.example.samdapp.domain.model.MedicalHistoryItem
import com.example.samdapp.domain.model.MedicationEntry
import com.example.samdapp.domain.model.MedicationKind
import com.example.samdapp.domain.model.SocialHistory
import com.example.samdapp.domain.repository.MedicalBackgroundRepository
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

class AddMedicalHistoryItemUseCase @Inject constructor(
    private val repository: MedicalBackgroundRepository,
) {
    suspend operator fun invoke(
        patientId: String,
        category: MedicalHistoryCategory,
        description: String,
        yearOrDate: String?,
    ): Result<Unit> {
        if (description.isBlank()) return Result.failure(IllegalArgumentException("Description is required"))
        return repository.addMedicalHistoryItem(
            MedicalHistoryItem(
                id = UUID.randomUUID().toString(),
                patientId = patientId,
                category = category,
                description = description,
                yearOrDate = yearOrDate,
                createdAt = Instant.now(),
            )
        )
    }
}

class AddMedicationUseCase @Inject constructor(
    private val repository: MedicalBackgroundRepository,
) {
    suspend operator fun invoke(
        patientId: String,
        encounterId: String?,
        kind: MedicationKind,
        name: String,
        dosage: String?,
        frequency: String?,
        active: Boolean = true,
    ): Result<Unit> {
        if (name.isBlank()) return Result.failure(IllegalArgumentException("Medication name is required"))
        return repository.addMedication(
            MedicationEntry(
                id = UUID.randomUUID().toString(),
                patientId = patientId,
                encounterId = encounterId,
                kind = kind,
                name = name,
                dosage = dosage,
                frequency = frequency,
                active = active,
                createdAt = Instant.now(),
            )
        )
    }
}

class AddAllergyUseCase @Inject constructor(
    private val repository: MedicalBackgroundRepository,
) {
    suspend operator fun invoke(
        patientId: String,
        category: AllergyCategory,
        allergen: String,
        reactionType: String?,
    ): Result<Unit> {
        if (allergen.isBlank()) return Result.failure(IllegalArgumentException("Allergen is required"))
        return repository.addAllergy(
            Allergy(
                id = UUID.randomUUID().toString(),
                patientId = patientId,
                category = category,
                allergen = allergen,
                reactionType = reactionType,
                createdAt = Instant.now(),
            )
        )
    }
}

class AddFamilyHistoryEntryUseCase @Inject constructor(
    private val repository: MedicalBackgroundRepository,
) {
    suspend operator fun invoke(patientId: String, condition: String, relation: String?): Result<Unit> {
        if (condition.isBlank()) return Result.failure(IllegalArgumentException("Condition is required"))
        return repository.addFamilyHistoryEntry(
            FamilyHistoryEntry(
                id = UUID.randomUUID().toString(),
                patientId = patientId,
                condition = condition,
                relation = relation,
                createdAt = Instant.now(),
            )
        )
    }
}

class SaveSocialHistoryUseCase @Inject constructor(
    private val repository: MedicalBackgroundRepository,
) {
    suspend operator fun invoke(
        patientId: String,
        occupation: String?,
        tobaccoUse: String?,
        alcoholUse: String?,
        recreationalDrugUse: String?,
        environmentalExposure: String?,
        recentTravel: String?,
    ): Result<Unit> = repository.upsertSocialHistory(
        SocialHistory(
            patientId = patientId,
            occupation = occupation,
            tobaccoUse = tobaccoUse,
            alcoholUse = alcoholUse,
            recreationalDrugUse = recreationalDrugUse,
            environmentalExposure = environmentalExposure,
            recentTravel = recentTravel,
            updatedAt = Instant.now(),
        )
    )
}
