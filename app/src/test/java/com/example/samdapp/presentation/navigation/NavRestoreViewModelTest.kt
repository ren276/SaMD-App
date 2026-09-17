package com.example.samdapp.presentation.navigation

import com.example.samdapp.domain.audit.AuditAction
import com.example.samdapp.domain.audit.AuditLogger
import com.example.samdapp.testutil.MainDispatcherRule
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * V5, audit half. The saver's own behaviour (discard instead of throw) is asserted in
 * NavBackStackSaverTest; this asserts the row that discard must leave behind.
 *
 * A stack that vanishes silently is indistinguishable from a worker who walked back to Home, and
 * the corrupt case in particular means an app update invalidated every saved stack on every
 * device, which nobody would otherwise find out about.
 */
class NavRestoreViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class RecordingAuditLogger : AuditLogger {
        data class Row(val action: AuditAction, val patientId: String?, val caseRecordId: String?, val payload: String)

        val rows = mutableListOf<Row>()

        override suspend fun log(action: AuditAction, patientId: String?, caseRecordId: String?, payload: String) {
            rows += Row(action, patientId, caseRecordId, payload)
        }
    }

    private val auditLogger = RecordingAuditLogger()

    @Before
    fun drainReporter() {
        NavStackRestoreReporter.consume()
    }

    @Test
    fun aDiscardWritesExactlyOneRowCarryingTheReasonAndNothingElse() = runTest(mainDispatcherRule.dispatcher) {
        NavStackRestoreReporter.report(NavStackDiscardReason.CORRUPT)

        NavRestoreViewModel(auditLogger).logPendingDiscard()

        assertEquals(1, auditLogger.rows.size)
        val row = auditLogger.rows.single()
        assertEquals(AuditAction.NAV_STACK_RESTORE_DISCARDED, row.action)
        assertEquals("nav_stack_restore_discarded", row.action.value)
        assertTrue("The reason is the whole point of the row", row.payload.contains("corrupt"))

        // The discarded stack is exactly the thing that may have carried patient ids, so none of
        // it may be echoed into the row that records its loss.
        assertEquals(null, row.patientId)
        assertEquals(null, row.caseRecordId)
        assertFalse(row.payload.contains("Route"))
        assertFalse(row.payload.contains("pat-"))
    }

    @Test
    fun oneDiscardLogsOnceEvenIfDrainedRepeatedly() = runTest(mainDispatcherRule.dispatcher) {
        NavStackRestoreReporter.report(NavStackDiscardReason.SESSION_CHANGED)
        val viewModel = NavRestoreViewModel(auditLogger)

        viewModel.logPendingDiscard()
        viewModel.logPendingDiscard()
        viewModel.logPendingDiscard()

        assertEquals(1, auditLogger.rows.size)
        assertTrue(auditLogger.rows.single().payload.contains("session_changed"))
    }

    @Test
    fun noDiscardWritesNoRow() = runTest(mainDispatcherRule.dispatcher) {
        NavRestoreViewModel(auditLogger).logPendingDiscard()

        assertTrue(auditLogger.rows.isEmpty())
    }
}
