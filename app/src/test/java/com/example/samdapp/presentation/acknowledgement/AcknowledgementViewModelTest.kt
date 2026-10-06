package com.example.samdapp.presentation.acknowledgement

import com.example.samdapp.domain.model.CaseRecord
import com.example.samdapp.domain.model.CaseStatus
import com.example.samdapp.domain.usecase.AcknowledgeCaseUseCase
import com.example.samdapp.testutil.FakeAuditLogger
import com.example.samdapp.testutil.FakeCaseRecordRepository
import com.example.samdapp.testutil.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

/**
 * The acknowledgement says where the visit is, from the same server-presence rule the assess gate
 * uses, and promises no review time (memo section 12.5). It must follow the case as the outbox
 * moves it: a screen that said "not on the server" must not keep saying it after the push lands.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AcknowledgementViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun caseRecord(onServer: Boolean) = CaseRecord(
        id = "case-1", patientId = "p1", encounterId = "enc-1", status = CaseStatus.DRAFT,
        assignedDoctorId = null, createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH, isOnServer = onServer,
    )

    private fun viewModel(cases: FakeCaseRecordRepository) = AcknowledgementViewModel(
        caseRecordId = "case-1",
        acknowledgeCaseUseCase = AcknowledgeCaseUseCase(cases),
        caseRecordRepository = cases,
        auditLogger = FakeAuditLogger(),
    )

    @Test
    fun `a case that has not reached the server is not shown as on it`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = viewModel(FakeCaseRecordRepository(initial = listOf(caseRecord(onServer = false))))
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.caseOnServer)
        assertFalse(viewModel.uiState.value.isSaving)
    }

    @Test
    fun `a case the server holds is shown as on it`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = viewModel(FakeCaseRecordRepository(initial = listOf(caseRecord(onServer = true))))
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.caseOnServer)
    }

    @Test
    fun `the message follows the case when the push lands while the screen is open`() = runTest(mainDispatcherRule.dispatcher) {
        val cases = FakeCaseRecordRepository(initial = listOf(caseRecord(onServer = false)))
        val viewModel = viewModel(cases)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.caseOnServer)

        cases.setOnServer("case-1", true)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.caseOnServer)
    }

    @Test
    fun `an unknown case is never shown as on the server`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = viewModel(FakeCaseRecordRepository())
        advanceUntilIdle()

        assertEquals(false, viewModel.uiState.value.caseOnServer)
    }
}
