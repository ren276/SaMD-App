package com.example.samdapp.domain.usecase

import com.example.samdapp.domain.model.ReferralStatus
import com.example.samdapp.domain.model.UrgencyLevel
import com.example.samdapp.testutil.FakeReferralRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** REQ-REF-01: single PHC-side action, no receiving-side system. */
class CreateReferralUseCaseTest {

    @Test
    fun `blank reason is rejected`() = runTest {
        val repo = FakeReferralRepository()
        val result = CreateReferralUseCase(repo)(
            patientUid = "UID1", caseRecordId = "case-1", urgencyLevel = UrgencyLevel.URGENT,
            reason = "  ", sendingPhcId = "PHC Rampur",
        )
        assertTrue(result.isFailure)
        assertTrue(repo.created.isEmpty())
    }

    @Test
    fun `a valid referral is queued with the given fields`() = runTest {
        val repo = FakeReferralRepository()
        val result = CreateReferralUseCase(repo)(
            patientUid = "UID1", caseRecordId = "case-1", urgencyLevel = UrgencyLevel.URGENT,
            reason = "High severity ailment", sendingPhcId = "PHC Rampur",
        )
        val referral = result.getOrThrow()
        assertEquals(UrgencyLevel.URGENT, referral.urgencyLevel)
        assertEquals(ReferralStatus.QUEUED, referral.status)
        assertEquals("PHC Rampur", referral.sendingPhcId)
        assertEquals(referral, repo.created.single())
    }

    @Test
    fun `repository failure surfaces as a failed Result`() = runTest {
        val repo = FakeReferralRepository().apply { createResult = Result.failure(RuntimeException("db error")) }
        val result = CreateReferralUseCase(repo)(
            patientUid = "UID1", caseRecordId = "case-1", urgencyLevel = UrgencyLevel.ROUTINE,
            reason = "reason", sendingPhcId = "PHC Rampur",
        )
        assertTrue(result.isFailure)
    }
}
