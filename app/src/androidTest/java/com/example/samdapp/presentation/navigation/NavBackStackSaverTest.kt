package com.example.samdapp.presentation.navigation

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.savedstate.SavedState
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /**
     * Every entry here is one the save-time transform leaves alone, so this stack round trips
     * byte for byte. Stacks that DO get transformed are covered in `NavBackStackPhiFreeTest`;
     * mixing them in here would make a round-trip assertion test two things at once and fail for
     * the wrong reason.
     */
    private fun deepStack() = NavBackStack<NavKey>(
        Home,
        PatientSummary("pat-1"),
        ConsentRoute("pat-1", followUpOfEncounterId = "enc-0"),
        Compounder("pat-1", resumeEncounterId = "enc-1", resumeCaseRecordId = "case-1"),
        KernelAssessmentRoute("case-1", "consult-1"),
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

    /**
     * V8. The save-time transform must never touch the LIVE stack.
     *
     * `onSaveInstanceState` fires whenever the app is backgrounded, not only before process death.
     * If the transform mutated the live list, a worker who took a phone call mid-consultation
     * would come back to find their `ConsultationRoute` had become a `Compounder`, their chief
     * complaint gone, and no crash anywhere to explain it. That is a worse defect than the one
     * this whole phase fixes, and it would be invisible in every other test here, all of which
     * only look at what was serialized.
     */
    @Test
    fun savingDoesNotMutateTheLiveStack() {
        val complaint = "ZZPROBE cough three days"
        val live = NavBackStack<NavKey>(
            Home,
            Compounder("pat-1", resumeEncounterId = "enc-1", resumeCaseRecordId = "case-1"),
            ConsultationRoute("pat-1", "enc-1", "case-1", complaint),
            AbhaCreateOtpRoute("session-1", "XXXXXX9999"),
        )
        val before = live.toList()

        val saved = navBackStackSaver(ownerUserId = "worker-a").saveStack(live)

        assertNotNull("The fixture must persist, or this proves nothing", saved)
        assertEquals("The live stack must be identical after a save", before, live.toList())
        val top = live[2] as ConsultationRoute
        assertEquals("The worker's chief complaint must still be on the live entry", complaint, top.chiefComplaint)
        // And the serialized copy genuinely did diverge, so the assertion above is not passing
        // because the transform quietly did nothing.
        assertFalse(saved!!.flattenToString().contains(complaint))
    }

    /**
     * The OTHER budget. Until this test existed the byte check had never executed: the only
     * over-budget case drove 60 entries, which trips the 50-entry cap first and returns before
     * anything is encoded. So the branch guarding the Binder transaction was dead code as far as
     * the suite was concerned.
     *
     * Ten entries is well inside the entry cap, and each carries a 10 KB argument, so the encoded
     * payload passes 64 KB and the byte check is the thing that rejects it. A stack this shape is
     * not something the app produces; the point is to execute the guard, not to model a real
     * stack.
     */
    @Test
    fun aStackOverTheByteBudgetIsNotPersisted() {
        val saver = navBackStackSaver(ownerUserId = "worker-a")
        val bloated = NavBackStack<NavKey>(Home).apply {
            repeat(10) { add(PatientSummary("x".repeat(10_000))) }
        }
        assertTrue("The fixture must stay under the entry cap, or it tests the wrong guard", bloated.size < 50)

        assertNull(saver.saveStack(bloated))
        assertEquals(NavStackDiscardReason.TOO_LARGE, NavStackRestoreReporter.consume())
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
