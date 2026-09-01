package com.example.samdapp.domain.usecase

import com.example.samdapp.domain.model.FieldProvenance
import com.example.samdapp.testutil.FakeConsultationRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SaveConsultationUseCaseTest {

    private val useCase = SaveConsultationUseCase(FakeConsultationRepository())

    @Test
    fun impactOnDailyActivitiesPresent_stampsTypedProvenance() = runTest {
        val result = useCase(
            patientId = "p1", encounterId = "e1", chiefComplaint = "Fever",
            onset = null, durationBucket = null, severityScore = null,
            aggravatingFactors = null, relievingFactors = null,
            impactOnDailyActivities = "Cannot go to work", relevantHistory = null,
        )

        assertEquals(FieldProvenance.TYPED, result.getOrThrow().impactOnDailyActivitiesProvenance)
    }

    @Test
    fun impactOnDailyActivitiesAbsent_provenanceStaysNull() = runTest {
        val result = useCase(
            patientId = "p1", encounterId = "e1", chiefComplaint = "Fever",
            onset = null, durationBucket = null, severityScore = null,
            aggravatingFactors = null, relievingFactors = null,
            impactOnDailyActivities = null, relevantHistory = null,
        )

        assertNull(result.getOrThrow().impactOnDailyActivitiesProvenance)
    }
}
