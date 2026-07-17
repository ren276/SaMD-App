package com.example.samdapp.presentation.common

import com.example.samdapp.testutil.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Item 2, privacy hardening: idle auto-lock timer behavior. */
@OptIn(ExperimentalCoroutinesApi::class)
class IdleLockViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `locks after 75 seconds of no interaction`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = IdleLockViewModel()
        viewModel.onUserInteraction()

        advanceTimeBy(75_001)

        assertTrue(viewModel.isLocked.value)
    }

    @Test
    fun `interaction resets the timer before it fires`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = IdleLockViewModel()
        viewModel.onUserInteraction()

        advanceTimeBy(50_000)
        viewModel.onUserInteraction()
        advanceTimeBy(50_000)

        assertFalse(viewModel.isLocked.value)
    }

    @Test
    fun `taps on the lock screen itself do not reset or unlock`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = IdleLockViewModel()
        viewModel.onUserInteraction()
        advanceTimeBy(75_001)
        assertTrue(viewModel.isLocked.value)

        viewModel.onUserInteraction()
        advanceTimeBy(1)

        assertTrue(viewModel.isLocked.value)
    }

    @Test
    fun `onUnlocked clears the lock and restarts the timer`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = IdleLockViewModel()
        viewModel.onUserInteraction()
        advanceTimeBy(75_001)
        assertTrue(viewModel.isLocked.value)

        viewModel.onUnlocked()
        assertFalse(viewModel.isLocked.value)

        advanceTimeBy(75_001)
        assertTrue(viewModel.isLocked.value)
    }

    @Test
    fun `reset clears an already-tripped lock from before sign-in`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = IdleLockViewModel()
        viewModel.onUserInteraction()
        advanceTimeBy(75_001)
        assertTrue(viewModel.isLocked.value)

        viewModel.reset()

        assertFalse(viewModel.isLocked.value)
    }
}
