package com.example.samdapp.data.repository

import com.example.samdapp.data.local.dao.ObservationDao
import com.example.samdapp.data.local.entity.ObservationEntity
import com.example.samdapp.domain.model.ObservationSource
import com.example.samdapp.domain.model.ObservationType
import com.example.samdapp.domain.model.VitalsSnapshot
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FakeObservationDao : ObservationDao {
    private val store = MutableStateFlow<List<ObservationEntity>>(emptyList())

    override suspend fun insertAll(observations: List<ObservationEntity>) {
        store.value = store.value + observations
    }

    override fun observeForEncounter(encounterId: String): Flow<List<ObservationEntity>> =
        store.map { rows -> rows.filter { it.encounterId == encounterId } }
}

class VitalsRepositoryImplTest {

    private lateinit var dao: FakeObservationDao
    private lateinit var repository: VitalsRepositoryImpl

    @Before
    fun setUp() {
        dao = FakeObservationDao()
        repository = VitalsRepositoryImpl(dao)
    }

    private fun snapshot(
        pulseBpm: Int? = 78,
        bpSystolic: Int? = 120,
        bpDiastolic: Int? = 80,
        weightKg: Double? = 70.0,
        heightCm: Double? = 175.0,
        urinalysisResult: String? = null,
    ) = VitalsSnapshot(
        encounterId = "encounter-1",
        patientId = "patient-1",
        pulseBpm = pulseBpm,
        bpSystolic = bpSystolic,
        bpDiastolic = bpDiastolic,
        weightKg = weightKg,
        heightCm = heightCm,
        urinalysisResult = urinalysisResult,
        source = ObservationSource.MANUAL,
        recordedAt = Instant.EPOCH,
    )

    @Test
    fun `saving a vitals snapshot fans out into one Observation row per non-null vital plus BMI`() = runTest {
        val result = repository.saveVitals(snapshot())
        assertTrue(result.isSuccess)

        val rows = dao.observeForEncounter("encounter-1").first()
        val types = rows.map { it.type }.toSet()
        // pulse, bpSystolic, bpDiastolic, weight, height, bmi = 6 rows
        assertEquals(
            setOf(
                ObservationType.PULSE,
                ObservationType.BP_SYSTOLIC,
                ObservationType.BP_DIASTOLIC,
                ObservationType.WEIGHT,
                ObservationType.HEIGHT,
                ObservationType.BMI,
            ),
            types,
        )
    }

    @Test
    fun `urinalysis is stored as valueText not valueNumeric`() = runTest {
        repository.saveVitals(snapshot(urinalysisResult = "Negative"))

        val rows = dao.observeForEncounter("encounter-1").first()
        val urinalysisRow = rows.first { it.type == ObservationType.URINALYSIS }
        assertEquals("Negative", urinalysisRow.valueText)
        assertNull(urinalysisRow.valueNumeric)
    }

    @Test
    fun `null vitals are not persisted as rows`() = runTest {
        repository.saveVitals(snapshot(pulseBpm = null, weightKg = null, heightCm = null))

        val rows = dao.observeForEncounter("encounter-1").first()
        assertTrue(rows.none { it.type == ObservationType.PULSE })
        assertTrue(rows.none { it.type == ObservationType.BMI })
    }

    @Test
    fun `observeLatestForEncounter reassembles a snapshot from the persisted rows`() = runTest {
        repository.saveVitals(snapshot())

        val reassembled = repository.observeLatestForEncounter("encounter-1").first()
        assertEquals(78, reassembled?.pulseBpm)
        assertEquals(120, reassembled?.bpSystolic)
        assertEquals(80, reassembled?.bpDiastolic)
        assertEquals(70.0, reassembled?.weightKg)
        assertEquals(175.0, reassembled?.heightCm)
    }
}
