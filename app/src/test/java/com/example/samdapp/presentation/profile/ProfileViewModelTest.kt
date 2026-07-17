package com.example.samdapp.presentation.profile

import com.example.samdapp.domain.audit.AuditLogEntry
import com.example.samdapp.testutil.FakeAuditLogRepository
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
class ProfileViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `uiState reflects this worker's recent audit trail`() = runTest(mainDispatcherRule.dispatcher) {
        val entries = listOf(
            AuditLogEntry(id = "a1", timestamp = Instant.EPOCH, action = "referral_created", patientId = "p1", caseRecordId = "case-1"),
        )
        val viewModel = ProfileViewModel(userId = "user-1", auditLogRepository = FakeAuditLogRepository(entries))

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("a1"), state.recentActions.map { it.id })
        assertFalse(state.isLoadingAudit)
    }
}
