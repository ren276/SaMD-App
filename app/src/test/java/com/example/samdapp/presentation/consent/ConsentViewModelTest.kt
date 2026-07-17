package com.example.samdapp.presentation.consent

import com.example.samdapp.testutil.FakeAuditLogger
import com.example.samdapp.testutil.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** REQ-TRS-01: consent must be recorded (audited) before Continue fires, and cannot fire without it. */
@OptIn(ExperimentalCoroutinesApi::class)
class ConsentViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `continue is disabled until agreed is checked`() = runTest(mainDispatcherRule.dispatcher) {
        val audit = FakeAuditLogger()
        val viewModel = ConsentViewModel("p1", audit)

        viewModel.onContinue()
        advanceUntilIdle()

        assertTrue(audit.logged.isEmpty())
        assertFalse(viewModel.uiState.value.canContinue)
    }

    @Test
    fun `agreeing and continuing logs consent with patientId and emits Continue`() =
        runTest(mainDispatcherRule.dispatcher) {
            val audit = FakeAuditLogger()
            val viewModel = ConsentViewModel("p1", audit)
            val effects = mutableListOf<ConsentEffect>()
            val collectJob = launch { viewModel.effects.toList(effects) }

            viewModel.onAgreedChange(true)
            viewModel.onContinue()
            advanceUntilIdle()

            val entry = audit.logged.single()
            assertEquals("consent_recorded", entry.action)
            assertEquals("p1", entry.patientId)
            assertEquals(null, entry.caseRecordId)
            assertTrue(effects.single() is ConsentEffect.Continue)

            collectJob.cancel()
        }
}
