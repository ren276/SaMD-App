package com.example.samdapp.presentation.referrals

import com.example.samdapp.domain.model.ReferralRequest
import com.example.samdapp.domain.model.ReferralStatus
import com.example.samdapp.domain.model.UrgencyLevel
import com.example.samdapp.testutil.FakeReferralRepository
import com.example.samdapp.testutil.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ReferralsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `uiState reflects this device's sent-referral outbox`() = runTest(mainDispatcherRule.dispatcher) {
        val repo = FakeReferralRepository().apply {
            created += ReferralRequest(
                id = "r1", patientUid = "UID1", caseRecordId = "case-1", urgencyLevel = UrgencyLevel.URGENT,
                reason = "High severity", sendingPhcId = "PHC Rampur", status = ReferralStatus.QUEUED,
                timestamp = Instant.EPOCH,
            )
        }
        val viewModel = ReferralsViewModel(repo)

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("r1"), state.referrals.map { it.id })
        assertFalse(state.isLoading)
    }
}
