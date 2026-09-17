package com.example.samdapp.presentation.navigation

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.savedstate.SavedState
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * V3, V5 and V6 of the navigation process-death phase.
 *
 * Instrumented rather than a JVM unit test because `SavedState` is a `Bundle` on Android and the
 * size bound measures it through a real `Parcel`. Testing the saver against a stubbed Bundle
 * would test something other than what ships.
 */
@RunWith(AndroidJUnit4::class)
class NavBackStackSaverTest {

    private val saverScope = SaverScope { true }

    private fun Saver<NavBackStack<NavKey>, SavedState>.saveStack(
        stack: NavBackStack<NavKey>,
    ): SavedState? = with(saverScope) { save(stack) }

    private fun deepStack() = NavBackStack<NavKey>(
        Home,
        PatientSummary("pat-1"),
        ConsentRoute("pat-1", followUpOfEncounterId = "enc-0"),
        Compounder("pat-1", resumeEncounterId = "enc-1", resumeCaseRecordId = "case-1"),
        ConsultationRoute("pat-1", "enc-1", "case-1", "ZZPROBE cough three days"),
    )

    @Before
    fun drainReporter() {
        // The reporter is process-wide by design (a Saver has nowhere else to put a reason), so a
        // leftover from an earlier test would make the next one lie.
        NavStackRestoreReporter.consume()
    }

    /** V3: a representative deep stack survives a save and restore unchanged. */
    @Test
    fun deepStackRoundTripsThroughTheProductionSaver() {
        val saver = navBackStackSaver(ownerUserId = "worker-a")
        val original = deepStack()

        val saved = saver.saveStack(original)
        assertNotNull("The stack should have been persisted, well under the 64 KB budget", saved)

        val restored = saver.restore(saved!!)
        assertNotNull("A stack saved by this build must restore", restored)
        assertEquals(original.toList(), restored!!.toList())
        assertNull("A clean round trip must not report a discard", NavStackRestoreReporter.consume())
    }

    /**
     * V5, saver half: a route class that no longer exists must not throw out of composition.
     * Uncaught, that is a crash on every launch until app data is cleared. The audit row this
     * discard produces is asserted separately, in NavRestoreViewModelTest.
     */
    @Test
    fun aRouteThatCannotBeDeserializedDiscardsTheStackInsteadOfThrowing() {
        val saver = navBackStackSaver(ownerUserId = "worker-a")
        val saved = saver.saveStack(deepStack())!!

        val replaced = saved.renameStoredClass(
            from = PatientSummary::class.java.name,
            to = "com.example.samdapp.presentation.navigation.RouteThatNoLongerExists",
        )
        assertTrue(
            "The saved payload should carry the route's fully qualified class name; if this " +
                "fails the wire format changed and this test is no longer testing anything",
            replaced > 0,
        )

        val restored = saver.restore(saved)

        assertNull("A stack naming a missing class must be discarded, not restored", restored)
        assertEquals(NavStackDiscardReason.CORRUPT, NavStackRestoreReporter.consume())
    }

    /**
     * V6: a stack saved by one worker must never be adopted by another. Without this, a sign-out
     * and sign-in inside one process hands worker B the tail of worker A's stack, patient ids
     * included.
     */
    @Test
    fun aStackSavedByAnotherWorkerIsDiscarded() {
        val saved = navBackStackSaver(ownerUserId = "worker-a").saveStack(deepStack())!!

        val restored = navBackStackSaver(ownerUserId = "worker-b").restore(saved)

        assertNull("Worker B must not inherit worker A's stack", restored)
        assertEquals(NavStackDiscardReason.SESSION_CHANGED, NavStackRestoreReporter.consume())
    }

    /** The same worker signing back in does keep their stack: V6's opposite, so the check above
     *  is a session check and not a blanket "always discard". */
    @Test
    fun theSameWorkerKeepsTheirStack() {
        val saved = navBackStackSaver(ownerUserId = "worker-a").saveStack(deepStack())!!

        val restored = navBackStackSaver(ownerUserId = "worker-a").restore(saved)

        assertNotNull(restored)
        assertEquals(deepStack().toList(), restored!!.toList())
    }

    /** The entry cap: a runaway stack persists nothing at all rather than risking the Binder
     *  transaction the whole Activity's saved state shares. */
    @Test
    fun anOverlongStackIsNotPersisted() {
        val saver = navBackStackSaver(ownerUserId = "worker-a")
        val runaway = NavBackStack<NavKey>(Home).apply {
            repeat(60) { add(PatientSummary("pat-$it")) }
        }

        assertNull(saver.saveStack(runaway))
        assertEquals(NavStackDiscardReason.TOO_LARGE, NavStackRestoreReporter.consume())
    }
}
