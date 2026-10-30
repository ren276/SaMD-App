package com.example.samdapp.presentation.home

import com.example.samdapp.data.sync.OwnerMismatchHold
import com.example.samdapp.domain.connectivity.BackendHost
import com.example.samdapp.domain.connectivity.BackendLocalNetwork
import com.example.samdapp.domain.connectivity.LocalNetworkGate
import com.example.samdapp.domain.sync.DrainFailure
import com.example.samdapp.domain.sync.SyncState
import com.example.samdapp.domain.usecase.GetTodaysPatientsUseCase
import com.example.samdapp.testutil.FakeAuthSession
import com.example.samdapp.testutil.FakeCaseRecordRepository
import com.example.samdapp.testutil.FakePatientRepository
import com.example.samdapp.testutil.FakeSyncStatus
import com.example.samdapp.testutil.MainDispatcherRule
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException

/**
 * Memo P2-R12: Home never lets an [OwnerMismatchHold] read error crash it or misreport the hold.
 * An error emits the last known value, or `true` when none was read yet, and the hold is
 * resubscribed after 1 s, 2 s, 4 s ... capped at 30 s. All in virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeOwnerMismatchHoldReadErrorTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    /** A hold whose n-th subscription runs [body] with n (0 based). */
    private class ScriptedHold(private val body: suspend FlowCollector<Boolean>.(attempt: Int) -> Unit) : OwnerMismatchHold {
        var subscriptions = 0
            private set

        override val held: Flow<Boolean> = flow { body(subscriptions++) }
        override suspend fun isHeld(): Boolean = held.first()
        override suspend fun hold() = Unit
        override suspend fun release() = Unit
    }

    private val sync = FakeSyncStatus()
    private val waiting = SyncState(outboxPending = 2)

    /** The gate blocks (a local host, permission denied) and something is waiting. */
    private fun viewModel(hold: OwnerMismatchHold) = HomeViewModel(
        GetTodaysPatientsUseCase(FakePatientRepository()),
        sync,
        FakeAuthSession(),
        FakeCaseRecordRepository(),
        FakePatientRepository(),
        hold,
        BackendLocalNetwork(LocalNetworkGate(sdkInt = 37) { false }, BackendHost { "10.203.2.52" }),
    )

    private fun HomeViewModel.caption() = uiState.value.caption(isOnline = true)

    private val homes = mutableListOf<HomeViewModel>()

    private fun TestScope.homeWith(hold: OwnerMismatchHold): HomeViewModel {
        sync.emit(waiting)
        return viewModel(hold).also { homes += it; runCurrent() }
    }

    /** A hold that keeps failing keeps Home retrying, so runTest never goes idle: cancel Home first. */
    private fun homeTest(body: suspend TestScope.() -> Unit) = runTest(mainDispatcherRule.dispatcher) {
        try {
            body()
        } finally {
            homes.forEach { it.viewModelScope.cancel() }
        }
    }

    private fun TestScope.at(ms: Long) {
        advanceTimeBy(ms - currentTime)
        runCurrent()
    }

    @Test
    fun `the first read throws, no crash, the hold is treated as held and nothing local-network shows`() = homeTest {
        val hold = ScriptedHold { throw IOException("disk") }
        val vm = homeWith(hold)

        assertTrue(vm.uiState.value.ownerMismatchHeld)
        assertFalse(vm.caption().offersLocalNetworkSettings())
        assertEquals(SyncCaption.Waiting(records = 2, doctorCases = 0), vm.caption())
    }

    @Test
    fun `an unknown hold never invents owner-mismatch copy`() = homeTest {
        val hold = ScriptedHold { throw IOException("disk") }
        val vm = homeWith(hold)

        val caption = vm.caption()
        assertFalse(caption is SyncCaption.CouldNotSend && caption.failure == DrainFailure.OWNER_MISMATCH)
    }

    @Test
    fun `the flow recovers with false, the local-network caption appears`() = homeTest {
        val hold = ScriptedHold { attempt ->
            if (attempt == 0) throw IOException("disk")
            emit(false)
            awaitCancellation()
        }
        val vm = homeWith(hold)
        assertFalse("unknown until the retry", vm.caption().offersLocalNetworkSettings())

        at(1_000)

        assertFalse(vm.uiState.value.ownerMismatchHeld)
        assertEquals(SyncCaption.CouldNotSend(DrainFailure.LOCAL_NETWORK_NOT_ALLOWED, waiting = 2), vm.caption())
        assertTrue(vm.caption().offersLocalNetworkSettings())
    }

    @Test
    fun `a known true then an error stays held`() = homeTest {
        val hold = ScriptedHold { attempt ->
            if (attempt == 0) {
                emit(true)
                throw IOException("disk")
            }
            awaitCancellation()
        }
        val vm = homeWith(hold)
        assertTrue(vm.uiState.value.ownerMismatchHeld)

        at(1_000)

        assertTrue("still held after the resubscribe", vm.uiState.value.ownerMismatchHeld)
        assertFalse(vm.caption().offersLocalNetworkSettings())
    }

    @Test
    fun `a known false then an error stays false`() = homeTest {
        val hold = ScriptedHold { attempt ->
            if (attempt == 0) {
                emit(false)
                throw IOException("disk")
            }
            awaitCancellation()
        }
        val vm = homeWith(hold)

        assertFalse(vm.uiState.value.ownerMismatchHeld)
        assertTrue(vm.caption().offersLocalNetworkSettings())

        at(1_000)

        assertFalse("still not held after the resubscribe", vm.uiState.value.ownerMismatchHeld)
        assertTrue(vm.caption().offersLocalNetworkSettings())
    }

    @Test
    fun `the resubscribe delays follow 1 2 4 seconds capped at 30`() = homeTest {
        val hold = ScriptedHold { throw IOException("disk") }
        homeWith(hold)
        assertEquals(1, hold.subscriptions)

        // Subscriptions start at 0, 1 000, 3 000, 7 000, 15 000, 31 000, 61 000, 91 000 ms.
        val starts = listOf(0L, 1_000L, 3_000L, 7_000L, 15_000L, 31_000L, 61_000L, 91_000L)
        for ((i, start) in starts.withIndex()) {
            if (i > 0) {
                at(start - 1)
                assertEquals("just before $start ms", i, hold.subscriptions)
                at(start)
            }
            assertEquals("at $start ms", i + 1, hold.subscriptions)
        }
    }

    @Test
    fun `the wait restarts at one second after a read succeeds`() = homeTest {
        val hold = ScriptedHold { attempt ->
            when (attempt) {
                0, 1 -> throw IOException("disk")
                2 -> {
                    emit(false)
                    throw IOException("disk")
                }
                else -> awaitCancellation()
            }
        }
        homeWith(hold)

        at(3_000)
        assertEquals("third subscription after 1 s then 2 s", 3, hold.subscriptions)
        at(3_999)
        assertEquals(3, hold.subscriptions)
        at(4_000)
        assertEquals("the failure after a good read waits 1 s again", 4, hold.subscriptions)
    }

    @Test
    fun `Home survives repeated errors`() = homeTest {
        val hold = ScriptedHold { throw IOException("disk") }
        val vm = homeWith(hold)

        at(200_000)

        assertTrue(hold.subscriptions > 8)
        assertTrue(vm.uiState.value.ownerMismatchHeld)
        assertFalse(vm.caption().offersLocalNetworkSettings())
    }

    @Test
    fun `an error that is not an IOException still propagates`() = runTest {
        val failing = flow<Boolean> { throw IllegalStateException("bug") }
        val thrown = runCatching { failing.holdFailingClosed { }.first() }.exceptionOrNull()
        assertTrue("got $thrown", thrown is IllegalStateException)
    }

    @Test
    fun `the log line never carries the exception text`() = runTest {
        val lines = mutableListOf<String>()
        var calls = 0
        val failing = flow<Boolean> {
            if (calls++ == 0) throw IOException("secret-disk-detail")
            emit(false)
        }

        val seen = failing.holdFailingClosed { lines += it }.toList()

        assertEquals(listOf(true, false), seen)
        assertEquals(1, lines.size)
        assertFalse(lines.single(), lines.single().contains("secret-disk-detail"))
        assertFalse(lines.single(), lines.single().contains("IOException"))
    }
}
