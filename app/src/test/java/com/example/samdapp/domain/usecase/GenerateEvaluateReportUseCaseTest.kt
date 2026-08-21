package com.example.samdapp.domain.usecase

import com.example.samdapp.domain.kernel.BrandLookupSource
import com.example.samdapp.domain.kernel.EvaluateKernelSource
import com.example.samdapp.domain.kernel.EvaluateResult
import com.example.samdapp.domain.model.Attachment
import com.example.samdapp.domain.model.EvaluateBrandMapping
import com.example.samdapp.domain.model.EvaluateDiagnosticSummary
import com.example.samdapp.domain.model.EvaluateNlemTreatment
import com.example.samdapp.domain.model.EvaluateSafetyAndTriage
import com.example.samdapp.domain.model.KernelPayload
import com.example.samdapp.domain.model.VitalsReading
import com.example.samdapp.testutil.FakeEvaluateReportRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Stub EvaluateKernelSource that always throws IOException — simulates the ML server offline. */
private class OfflineEvaluateSource : EvaluateKernelSource {
    override suspend fun evaluate(payload: KernelPayload, patientAge: Int, patientSex: String): EvaluateResult {
        throw IOException("Simulated network unavailability")
    }
}

/** Stub EvaluateKernelSource that always succeeds. */
private class WorkingEvaluateSource : EvaluateKernelSource {
    override suspend fun evaluate(payload: KernelPayload, patientAge: Int, patientSex: String): EvaluateResult =
        EvaluateResult(
            diagnosticSummary = EvaluateDiagnosticSummary(
                primaryIcdCandidate = "J11", primaryAilmentName = "Viral fever", differential = emptyList(),
            ),
            nlemTreatment = EvaluateNlemTreatment(
                recommendedDrug = "Paracetamol", levelOfHealthcare = listOf("PHC"), availableAtPHC = true,
                dosageForms = listOf("Tablet"), pediatricDose = null, citation = null, confidence = null,
                referralReason = null, matchedDisease = null,
            ),
            brandMapping = null,
            safetyAndTriage = EvaluateSafetyAndTriage(
                vitalsTriage = null, requiresHumanReview = false, pediatricReferralFlag = false, failureReason = null,
            ),
        )
}

/** Stub BrandLookupSource that never resolves a brand — irrelevant to this use case's own logic. */
private object NoBrandLookupSource : BrandLookupSource {
    override suspend fun lookupTopIndianBrand(genericDrugName: String) = null
}

/**
 * H-14 (docs/quality/h-14-evaluate-failure-decision.md, Option 2): before this fix, a failed
 * `/api/v1/evaluate` call returned `Result.failure` and wrote NO row at all, so
 * `EvaluateReportRepository.getForCase()` returned null identically for "hasn't run yet" and
 * "failed." These tests assert the persisted state on [FakeEvaluateReportRepository] (the
 * repository boundary this use case actually writes through), not just the returned `Result` —
 * same rule CLAUDE.md states for the `_fail()` rollback trap this project has hit before.
 */
class GenerateEvaluateReportUseCaseTest {

    private fun payload(chiefComplaint: String = "fever") = KernelPayload(
        caseToken = "case-1",
        vitals = VitalsReading(),
        chiefComplaint = chiefComplaint,
        durationBucket = "few_days",
        severityScore = 5,
        relevantHistory = null,
        transcription = null,
        attachments = emptyList<Attachment>(),
    )

    @Test
    fun `evaluate failure persists a readable-back failure marker, distinguishable from never having run`() = runTest {
        val repo = FakeEvaluateReportRepository()
        val useCase = GenerateEvaluateReportUseCase(repo, OfflineEvaluateSource(), NoBrandLookupSource)

        val result = useCase("case-1", payload())

        assertTrue(result.isFailure)
        assertNull("a failed evaluate must never look like a real report", repo.getForCase("case-1"))
        assertNotNull(
            "a failed evaluate must be readable back as a distinguishable failure, not silently absent",
            repo.getFailureCodeForCase("case-1"),
        )
    }

    @Test
    fun `evaluate success persists a real report and leaves no failure marker`() = runTest {
        val repo = FakeEvaluateReportRepository()
        val useCase = GenerateEvaluateReportUseCase(repo, WorkingEvaluateSource(), NoBrandLookupSource)

        val output = useCase("case-1", payload()).getOrThrow()

        assertEquals(output, repo.getForCase("case-1"))
        assertNull(repo.getFailureCodeForCase("case-1"))
    }

    @Test
    fun `a retry that succeeds after a prior failure clears the failure marker`() = runTest {
        val repo = FakeEvaluateReportRepository()
        val failingUseCase = GenerateEvaluateReportUseCase(repo, OfflineEvaluateSource(), NoBrandLookupSource)
        val succeedingUseCase = GenerateEvaluateReportUseCase(repo, WorkingEvaluateSource(), NoBrandLookupSource)

        failingUseCase("case-1", payload())
        assertNotNull(repo.getFailureCodeForCase("case-1"))
        assertNull(repo.getForCase("case-1"))

        val output = succeedingUseCase("case-1", payload()).getOrThrow()

        assertEquals(
            "a successful retry must clear the earlier failure marker",
            null,
            repo.getFailureCodeForCase("case-1"),
        )
        assertEquals(output, repo.getForCase("case-1"))
    }
}
