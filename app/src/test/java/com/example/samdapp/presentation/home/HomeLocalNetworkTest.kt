package com.example.samdapp.presentation.home

import com.example.samdapp.R
import com.example.samdapp.data.sync.MemoryOwnerMismatchHold
import com.example.samdapp.domain.connectivity.BackendHost
import com.example.samdapp.domain.connectivity.BackendLocalNetwork
import com.example.samdapp.domain.connectivity.LocalNetworkGate
import com.example.samdapp.domain.sync.DrainFailure
import com.example.samdapp.domain.sync.SyncOfflineException
import com.example.samdapp.domain.sync.SyncState
import com.example.samdapp.domain.usecase.GetTodaysPatientsUseCase
import com.example.samdapp.testutil.FakeAuthSession
import com.example.samdapp.testutil.FakeCaseRecordRepository
import com.example.samdapp.testutil.FakePatientRepository
import com.example.samdapp.testutil.FakeSyncStatus
import com.example.samdapp.testutil.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** A staging or prod shape for Home: a public backend host, so the gate never blocks. */
internal fun publicBackendLocalNetwork(): BackendLocalNetwork =
    BackendLocalNetwork(LocalNetworkGate(sdkInt = 37) { false }, BackendHost { "api.samd.example.com" })

/**
 * Memo P2-R4, P2-R5, P2-R9, F1-M1 and 5.5 on Home: the local-network caption comes from the gate's
 * live decision for the backend host, only while something is waiting, never over the owner-mismatch
 * hold; Open settings is its only action; resume after the block clears runs one Sync now.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeLocalNetworkTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private var host = "10.203.2.52"
    private var granted = false
    private val sync = FakeSyncStatus()
    private val hold = MemoryOwnerMismatchHold()
    private val waiting = SyncState(outboxPending = 2)

    private fun viewModel() = HomeViewModel(
        GetTodaysPatientsUseCase(FakePatientRepository()),
        sync,
        FakeAuthSession(),
        FakeCaseRecordRepository(),
        FakePatientRepository(),
        hold,
        BackendLocalNetwork(LocalNetworkGate(sdkInt = 37) { granted }, BackendHost { host }),
    )

    private fun HomeViewModel.caption() = uiState.value.caption(isOnline = true)

    private fun TestScope.homeWith(state: SyncState): HomeViewModel {
        sync.emit(state)
        return viewModel().also { advanceUntilIdle() }
    }

    @Test
    fun `blocked with something waiting shows the local-network caption and Open settings`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = homeWith(waiting)

        assertEquals(SyncCaption.CouldNotSend(DrainFailure.LOCAL_NETWORK_NOT_ALLOWED, waiting = 2), vm.caption())
        assertTrue(vm.caption().offersLocalNetworkSettings())
    }

    @Test
    fun `blocked with nothing waiting shows no local-network caption`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = homeWith(SyncState())

        assertEquals(SyncCaption.UpToDate, vm.caption())
        assertFalse(vm.caption().offersLocalNetworkSettings())
    }

    @Test
    fun `a public backend with the permission denied shows no local-network caption`() = runTest(mainDispatcherRule.dispatcher) {
        host = "api.samd.example.com"
        val vm = homeWith(waiting)

        assertEquals(SyncCaption.Waiting(records = 2, doctorCases = 0), vm.caption())
        assertFalse(vm.caption().offersLocalNetworkSettings())
    }

    @Test
    fun `OWNER_MISMATCH held and blocked shows the owner-mismatch caption and no local-network action`() = runTest(mainDispatcherRule.dispatcher) {
        hold.hold()
        val vm = homeWith(waiting.copy(lastDrainFailure = DrainFailure.OWNER_MISMATCH))

        assertEquals(SyncCaption.CouldNotSend(DrainFailure.OWNER_MISMATCH, waiting = 2), vm.caption())
        assertFalse(vm.caption().offersLocalNetworkSettings())
    }

    @Test
    fun `the hold set at cold start with the gate blocked shows no local-network caption`() = runTest(mainDispatcherRule.dispatcher) {
        hold.hold()
        val vm = homeWith(waiting)

        assertEquals(SyncCaption.Waiting(records = 2, doctorCases = 0), vm.caption())
        assertFalse(vm.caption().offersLocalNetworkSettings())
    }

    @Test
    fun `the hold released while Home is visible brings the local-network state on the next emission`() = runTest(mainDispatcherRule.dispatcher) {
        hold.hold()
        val vm = homeWith(waiting)
        assertFalse("held: no local-network action", vm.caption().offersLocalNetworkSettings())

        hold.release()
        advanceUntilIdle()

        assertEquals(SyncCaption.CouldNotSend(DrainFailure.LOCAL_NETWORK_NOT_ALLOWED, waiting = 2), vm.caption())
        assertTrue(vm.caption().offersLocalNetworkSettings())
    }

    @Test
    fun `a backend host changed from local to public clears the caption on the next evaluation`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = homeWith(waiting)
        assertTrue(vm.caption().offersLocalNetworkSettings())

        host = "api.samd.example.com"
        sync.emit(waiting.copy(outboxPending = 3))
        advanceUntilIdle()

        assertEquals(SyncCaption.Waiting(records = 3, doctorCases = 0), vm.caption())
    }

    @Test
    fun `resume after the block clears with records waiting runs exactly one sync`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = homeWith(waiting)
        granted = true

        vm.onResume()
        advanceUntilIdle()
        assertEquals(1, sync.syncCalls)

        vm.onResume()
        advanceUntilIdle()
        assertEquals("the flag is cleared: a second resume does not sync again", 1, sync.syncCalls)
    }

    @Test
    fun `resume while still blocked runs no sync`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = homeWith(waiting)

        vm.onResume()
        advanceUntilIdle()

        assertEquals(0, sync.syncCalls)
    }

    @Test
    fun `resume with nothing waiting runs no sync`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = homeWith(SyncState())
        granted = true

        vm.onResume()
        advanceUntilIdle()

        assertEquals(0, sync.syncCalls)
    }

    @Test
    fun `resume with no prior block and no local-network failure runs no sync`() = runTest(mainDispatcherRule.dispatcher) {
        granted = true
        val vm = homeWith(waiting.copy(lastDrainFailure = DrainFailure.NO_CONNECTION))

        vm.onResume()
        advanceUntilIdle()

        assertEquals(0, sync.syncCalls)
    }

    @Test
    fun `resume after a drain the gate refused, granted since, runs one sync`() = runTest(mainDispatcherRule.dispatcher) {
        granted = true
        val vm = homeWith(waiting.copy(lastDrainFailure = DrainFailure.LOCAL_NETWORK_NOT_ALLOWED))

        vm.onResume()
        advanceUntilIdle()

        assertEquals(1, sync.syncCalls)
    }

    @Test
    fun `Up to date never shows while something is waiting`() {
        val failures = listOf(null) + DrainFailure.entries
        for (blocked in listOf(false, true)) for (held in listOf(false, true)) for (last in failures) {
            for (state in listOf(SyncState(outboxPending = 1), SyncState(auditPending = 1), SyncState(pendingCount = 1))) {
                val ui = HomeUiState(sync = state.copy(lastDrainFailure = last), localNetworkBlocked = blocked, ownerMismatchHeld = held)
                assertNotEquals("blocked=$blocked held=$held last=$last $state", SyncCaption.UpToDate, ui.caption(isOnline = true))
            }
        }
    }

    @Test
    fun `the Sync now snackbar for a blocked drain still names the local network`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = homeWith(waiting)
        sync.nextSyncResult = Result.failure(IllegalStateException("blocked"))
        sync.stateAfterSync = waiting.copy(lastDrainFailure = DrainFailure.LOCAL_NETWORK_NOT_ALLOWED)
        vm.onSyncNow()
        advanceUntilIdle()

        val message = (vm.effects.first() as HomeEffect.ShowSyncNowResult).message
        assertEquals(SyncNowMessage.Failed(DrainFailure.LOCAL_NETWORK_NOT_ALLOWED), message)
        assertEquals(CopyRef.Str(R.string.sync_now_failed_local_network), message.copyRef())
    }

    private val outcomes = listOf(
        "offline" to Result.failure<Unit>(SyncOfflineException()),
        "failure" to Result.failure(IllegalStateException("refused")),
        "success" to Result.success(Unit),
    )

    @Test
    fun `an automatic resume sync shows no snackbar, offline, failed or successful`() = runTest(mainDispatcherRule.dispatcher) {
        for ((name, outcome) in outcomes) {
            granted = false
            val calls = sync.syncCalls
            val vm = homeWith(waiting)
            val effects = mutableListOf<HomeEffect>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.toList(effects) }
            granted = true
            sync.nextSyncResult = outcome
            sync.stateAfterSync = waiting

            vm.onResume()
            advanceUntilIdle()

            assertEquals("$name: one enqueue", calls + 1, sync.syncCalls)
            assertEquals("$name: no snackbar", emptyList<HomeEffect>(), effects)
        }
    }

    @Test
    fun `the Sync now button still shows its snackbar, offline, failed or successful`() = runTest(mainDispatcherRule.dispatcher) {
        for ((name, outcome) in outcomes) {
            val vm = homeWith(waiting)
            val effects = mutableListOf<HomeEffect>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.toList(effects) }
            sync.nextSyncResult = outcome

            vm.onSyncNow()
            advanceUntilIdle()

            assertEquals("$name: one snackbar", 1, effects.count { it is HomeEffect.ShowSyncNowResult })
        }
    }
}
