package com.example.samdapp.domain.usecase

import com.example.samdapp.domain.model.MeasurementType
import com.example.samdapp.domain.model.Visibility
import com.example.samdapp.testutil.FakeAilmentRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** REQ-AIL-01/02/03/04. */
class AilmentUseCasesTest {

    @Test
    fun `blank description is rejected`() = runTest {
        val repo = FakeAilmentRepository()
        val result = AddAilmentUseCase(repo)(
            patientId = "p1", encounterId = "e1", description = "",
            measurementType = MeasurementType.NON_MEASURABLE, visibility = Visibility.PUBLIC,
            measuredValue = null, measuredUnit = null, severity = null, onset = null,
            duration = null, qualifiers = null, audioLocalUri = null,
        )
        assertTrue(result.isFailure)
        assertTrue(repo.added.isEmpty())
    }

    @Test
    fun `measurable ailment without a measured value is rejected`() = runTest {
        val repo = FakeAilmentRepository()
        val result = AddAilmentUseCase(repo)(
            patientId = "p1", encounterId = "e1", description = "Fever",
            measurementType = MeasurementType.MEASURABLE, visibility = Visibility.PUBLIC,
            measuredValue = null, measuredUnit = "°F", severity = null, onset = null,
            duration = null, qualifiers = null, audioLocalUri = null,
        )
        assertTrue(result.isFailure)
        assertTrue(repo.added.isEmpty())
    }

    @Test
    fun `a private ailment is persisted with full detail — repository boundary keeps everything for the kernel`() = runTest {
        val repo = FakeAilmentRepository()
        AddAilmentUseCase(repo)(
            patientId = "p1", encounterId = "e1", description = "Sharp stomach pain",
            measurementType = MeasurementType.NON_MEASURABLE, visibility = Visibility.PRIVATE,
            measuredValue = null, measuredUnit = null, severity = 8, onset = "sudden",
            duration = "2 days", qualifiers = "sharp", audioLocalUri = "file:///private.m4a",
        )
        val stored = repo.added.single()
        assertEquals(Visibility.PRIVATE, stored.visibility)
        assertEquals("Sharp stomach pain", stored.description)
        assertEquals(8, stored.severity)
        assertEquals("file:///private.m4a", stored.audioLocalUri)
        // The repository's own observe stream — the same one the kernel path reads from — still
        // carries the full entry. Visibility filtering is a presentation-layer concern only.
        val observed = repo.observeForEncounter("e1").first().single()
        assertEquals("Sharp stomach pain", observed.description)
    }

    @Test
    fun `markDeleted soft-deletes via the repository`() = runTest {
        val repo = FakeAilmentRepository()
        AddAilmentUseCase(repo)(
            patientId = "p1", encounterId = "e1", description = "Cough",
            measurementType = MeasurementType.NON_MEASURABLE, visibility = Visibility.PUBLIC,
            measuredValue = null, measuredUnit = null, severity = null, onset = null,
            duration = null, qualifiers = null, audioLocalUri = null,
        )
        val id = repo.added.single().id

        DeleteAilmentUseCase(repo)(id)

        assertTrue(id in repo.deletedIds)
        assertNull(repo.observeForEncounter("e1").first().find { it.id == id })
    }
}
