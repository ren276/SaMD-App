package com.example.samdapp.data.repository

import com.example.samdapp.data.local.dao.PrescriptionDao
import com.example.samdapp.data.local.entity.MedicationLineEntity
import com.example.samdapp.data.local.entity.PrescriptionEntity
import com.example.samdapp.domain.model.MedicationLine
import com.example.samdapp.domain.model.Prescription
import com.example.samdapp.domain.repository.PrescriptionRepository
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

class PrescriptionRepositoryImpl @Inject constructor(
    private val prescriptionDao: PrescriptionDao,
) : PrescriptionRepository {

    override suspend fun save(prescription: Prescription): Result<Unit> = asDataResult {
        prescriptionDao.insertPrescription(prescription.toEntity())
        prescriptionDao.insertMedicationLines(
            prescription.medications.mapIndexed { i, line -> line.toEntity(prescription.id, i, prescription.createdAt) },
        )
    }

    override suspend fun getForCase(caseRecordId: String): Prescription? {
        val entity = prescriptionDao.observeForCase(caseRecordId).first() ?: return null
        val lines = prescriptionDao.observeLines(entity.id).first()
        return entity.toDomain(lines)
    }
}

private fun Prescription.toEntity() = PrescriptionEntity(
    id = id,
    patientId = patientId,
    encounterId = encounterId,
    caseRecordId = caseRecordId,
    doctorId = doctorId,
    diagnosis = diagnosis,
    kernelDecision = kernelDecision,
    createdAt = createdAt,
    localModifiedAt = createdAt,
)

private fun MedicationLine.toEntity(prescriptionId: String, position: Int, localModifiedAt: Instant) = MedicationLineEntity(
    id = UUID.randomUUID().toString(),
    prescriptionId = prescriptionId,
    position = position,
    genericName = genericName,
    brandName = brandName,
    strength = strength,
    dosage = dosage,
    frequency = frequency,
    route = route,
    duration = duration,
    quantity = quantity,
    foodRelation = foodRelation,
    instructions = instructions,
    localModifiedAt = localModifiedAt,
)

private fun PrescriptionEntity.toDomain(lines: List<MedicationLineEntity>) = Prescription(
    id = id,
    patientId = patientId,
    encounterId = encounterId,
    caseRecordId = caseRecordId,
    doctorId = doctorId,
    diagnosis = diagnosis,
    medications = lines.sortedBy { it.position }.map { it.toDomain() },
    kernelDecision = kernelDecision,
    createdAt = createdAt,
)

private fun MedicationLineEntity.toDomain() = MedicationLine(
    genericName = genericName,
    brandName = brandName,
    strength = strength,
    dosage = dosage,
    frequency = frequency,
    route = route,
    duration = duration,
    quantity = quantity,
    foodRelation = foodRelation,
    instructions = instructions,
)
