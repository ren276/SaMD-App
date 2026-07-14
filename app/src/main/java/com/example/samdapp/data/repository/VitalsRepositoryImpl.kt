package com.example.samdapp.data.repository

import com.example.samdapp.data.local.dao.ObservationDao
import com.example.samdapp.data.local.entity.ObservationEntity
import com.example.samdapp.domain.model.ObservationType
import com.example.samdapp.domain.model.VitalsSnapshot
import com.example.samdapp.domain.repository.VitalsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

class VitalsRepositoryImpl @Inject constructor(
    private val observationDao: ObservationDao,
) : VitalsRepository {

    override suspend fun saveVitals(snapshot: VitalsSnapshot): Result<Unit> = asDataResult {
        observationDao.insertAll(snapshot.toObservationEntities())
    }

    override fun observeLatestForEncounter(encounterId: String): Flow<VitalsSnapshot?> =
        observationDao.observeForEncounter(encounterId).map { rows ->
            if (rows.isEmpty()) return@map null
            val latestByType = rows.groupBy { it.type }.mapValues { (_, v) -> v.maxBy { it.recordedAt } }
            VitalsSnapshot(
                encounterId = encounterId,
                patientId = rows.first().patientId,
                pulseBpm = latestByType[ObservationType.PULSE]?.valueNumeric?.toInt(),
                bpSystolic = latestByType[ObservationType.BP_SYSTOLIC]?.valueNumeric?.toInt(),
                bpDiastolic = latestByType[ObservationType.BP_DIASTOLIC]?.valueNumeric?.toInt(),
                spo2Percent = latestByType[ObservationType.SPO2]?.valueNumeric?.toInt(),
                temperatureCelsius = latestByType[ObservationType.TEMPERATURE]?.valueNumeric,
                respiratoryRate = latestByType[ObservationType.RESPIRATORY_RATE]?.valueNumeric?.toInt(),
                weightKg = latestByType[ObservationType.WEIGHT]?.valueNumeric,
                heightCm = latestByType[ObservationType.HEIGHT]?.valueNumeric,
                bloodGlucoseMgDl = latestByType[ObservationType.BLOOD_GLUCOSE]?.valueNumeric?.toInt(),
                painScore = latestByType[ObservationType.PAIN_SCORE]?.valueNumeric?.toInt(),
                urinalysisResult = latestByType[ObservationType.URINALYSIS]?.valueText,
                deviceId = latestByType.values.firstOrNull { it.deviceId != null }?.deviceId,
                source = rows.maxBy { it.recordedAt }.source,
                recordedAt = rows.maxOf { it.recordedAt },
            )
        }
}

private fun VitalsSnapshot.toObservationEntities(): List<ObservationEntity> {
    val entries = buildList {
        pulseBpm?.let { add(ObservationType.PULSE to it.toDouble()) }
        bpSystolic?.let { add(ObservationType.BP_SYSTOLIC to it.toDouble()) }
        bpDiastolic?.let { add(ObservationType.BP_DIASTOLIC to it.toDouble()) }
        spo2Percent?.let { add(ObservationType.SPO2 to it.toDouble()) }
        temperatureCelsius?.let { add(ObservationType.TEMPERATURE to it) }
        respiratoryRate?.let { add(ObservationType.RESPIRATORY_RATE to it.toDouble()) }
        weightKg?.let { add(ObservationType.WEIGHT to it) }
        heightCm?.let { add(ObservationType.HEIGHT to it) }
        bloodGlucoseMgDl?.let { add(ObservationType.BLOOD_GLUCOSE to it.toDouble()) }
        painScore?.let { add(ObservationType.PAIN_SCORE to it.toDouble()) }
        bmi?.let { add(ObservationType.BMI to it) }
    }
    val numericRows = entries.map { (type, value) ->
        ObservationEntity(
            id = UUID.randomUUID().toString(),
            patientId = patientId,
            encounterId = encounterId,
            type = type,
            valueNumeric = value,
            valueText = null,
            unit = type.defaultUnit,
            deviceId = deviceId,
            source = source,
            recordedAt = recordedAt,
            createdAt = Instant.now(),
        )
    }
    val urinalysisRow = urinalysisResult?.let {
        ObservationEntity(
            id = UUID.randomUUID().toString(),
            patientId = patientId,
            encounterId = encounterId,
            type = ObservationType.URINALYSIS,
            valueNumeric = null,
            valueText = it,
            unit = null,
            deviceId = null,
            source = source,
            recordedAt = recordedAt,
            createdAt = Instant.now(),
        )
    }
    return numericRows + listOfNotNull(urinalysisRow)
}
