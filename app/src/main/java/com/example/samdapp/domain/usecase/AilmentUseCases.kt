package com.example.samdapp.domain.usecase

import com.example.samdapp.domain.model.AilmentEntry
import com.example.samdapp.domain.model.MeasurementType
import com.example.samdapp.domain.model.Visibility
import com.example.samdapp.domain.repository.AilmentRepository
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

/** Replaces the old free-text "symptom" capture (REQ-AIL-01/02/03). */
class AddAilmentUseCase @Inject constructor(
    private val repository: AilmentRepository,
) {
    suspend operator fun invoke(
        patientId: String,
        encounterId: String,
        description: String,
        measurementType: MeasurementType,
        visibility: Visibility,
        measuredValue: Double?,
        measuredUnit: String?,
        severity: Int?,
        onset: String?,
        duration: String?,
        qualifiers: String?,
        audioLocalUri: String?,
    ): Result<Unit> {
        if (description.isBlank()) return Result.failure(IllegalArgumentException("Ailment description is required"))
        if (measurementType == MeasurementType.MEASURABLE && measuredValue == null) {
            return Result.failure(IllegalArgumentException("Measured value is required for a measurable ailment"))
        }
        val now = Instant.now()
        return repository.addAilment(
            AilmentEntry(
                id = UUID.randomUUID().toString(),
                patientId = patientId,
                encounterId = encounterId,
                description = description,
                measurementType = measurementType,
                visibility = visibility,
                measuredValue = measuredValue,
                measuredUnit = measuredUnit,
                severity = severity,
                onset = onset,
                duration = duration,
                qualifiers = qualifiers,
                audioLocalUri = audioLocalUri,
                capturedAtOffline = now,
                syncedToCloudAt = null,
                deletedAt = null,
                createdAt = now,
            ),
        )
    }
}

/** Soft-deletes an ailment entry (REQ-AIL-03's "delete button" for a private entry). Sets
 *  [AilmentEntry.deletedAt] rather than removing the row — the audit trail keeps the fact that a
 *  private entry existed and was deleted, even though its content was never shown to the worker. */
class DeleteAilmentUseCase @Inject constructor(
    private val repository: AilmentRepository,
) {
    suspend operator fun invoke(id: String): Result<Unit> = repository.markDeleted(id)
}
