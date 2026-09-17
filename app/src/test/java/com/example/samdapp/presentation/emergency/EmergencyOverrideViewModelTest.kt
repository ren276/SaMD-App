package com.example.samdapp.presentation.emergency

import com.example.samdapp.domain.model.VitalsSnapshot
import com.example.samdapp.domain.usecase.CheckEmergencyThresholdsUseCase
import com.example.samdapp.testutil.FakeVitalsRepository
import com.example.samdapp.testutil.MainDispatcherRule
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

/**
 * Phase 1 removed the tripped threshold reasons from `EmergencyOverrideRoute` and had this
 * ViewModel re-derive them from the vitals row already persisted for the encounter, so clinical
 * text never travels through a navigation argument into the saved-state Bundle.
 *
 * That was accepted on an ARGUMENT: `CheckEmergencyThresholdsUseCase` is a pure function of three
 * fields, and `CompounderViewModel` persists the snapshot before running the check on those same
 * values, so re-running the same function over the same values must produce the same strings. The
 * argument is sound. It was never observed.
 *
 * This is the observation. It matters more than a usual ViewModel test because the screen is a
 * terminal red flag: if the re-derived text ever diverges from what the forward path showed, a
 * worker sees different reasons for the same vitals depending on whether the process died, and
 * nothing anywhere else would catch it.
 *
 * Deliberately a plain JVM test. The restore memo called for a Robolectric one; Robolectric is not
 * a dependency of this project and is not needed, because this ViewModel's only Android reference
 * is the `@HiltViewModel` annotation.
 */
class EmergencyOverrideViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val thresholds = CheckEmergencyThresholdsUseCase()

    private fun snapshot(
        spo2Percent: Int? = null,
        bpSystolic: Int? = null,
        bpDiastolic: Int? = null,
    ) = VitalsSnapshot(
        encounterId = ENCOUNTER,
        patientId = "pat-1",
        spo2Percent = spo2Percent,
        bpSystolic = bpSystolic,
        bpDiastolic = bpDiastolic,
        recordedAt = Instant.EPOCH,
    )

    private fun viewModel(persisted: VitalsSnapshot?) = EmergencyOverrideViewModel(
        encounterId = ENCOUNTER,
        vitalsRepository = FakeVitalsRepository(mapOf(ENCOUNTER to persisted)),
        checkEmergencyThresholdsUseCase = thresholds,
    )

    private companion object {
        const val ENCOUNTER = "enc-1"
    }

    /**
     * The property phase 1 claimed: the re-derived list is what the forward path would have shown
     * for the same vitals. The expectation is computed the way `CompounderViewModel` computes it,
     * from the same use case over the same three fields, so this asserts equality of behaviour
     * rather than equality with a string this test made up.
     */
    @Test
    fun `re-derived reasons equal what the forward path would have shown`() = runTest(mainDispatcherRule.dispatcher) {
        val persisted = snapshot(spo2Percent = 85, bpSystolic = 186, bpDiastolic = 124)
        val forwardPath = thresholds(
            spo2Percent = persisted.spo2Percent,
            bpSystolic = persisted.bpSystolic,
            bpDiastolic = persisted.bpDiastolic,
        )
        assertTrue("The fixture must actually trip, or this test is vacuous", forwardPath.triggered)

        val viewModel = viewModel(persisted)
        advanceUntilIdle()

        assertEquals(forwardPath.reasons, viewModel.reasons.value)
    }

    /**
     * And the text itself, pinned once. The assertion above would still pass if someone reworded
     * every threshold string, because both sides come from the same function. This one fails on a
     * reword, which is correct: the wording on a terminal emergency screen is clinical content, not
     * an implementation detail, and changing it should be a deliberate act with a test to update.
     */
    @Test
    fun `the reason text is the text the worker is shown`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = viewModel(snapshot(spo2Percent = 85, bpSystolic = 186, bpDiastolic = 124))
        advanceUntilIdle()

        assertEquals(
            listOf(
                "SpO2 85% is below the 90% floor",
                "Systolic BP 186 mmHg is outside the safe 90–180 mmHg range",
                "Diastolic BP 124 mmHg is at or above the 120 mmHg ceiling",
            ),
            viewModel.reasons.value,
        )
    }

    /** Only the fields that tripped are listed. A snapshot with one bad value must not acquire
     *  reasons for the two that were fine. */
    @Test
    fun `only the crossed threshold is listed`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = viewModel(snapshot(spo2Percent = 85, bpSystolic = 120, bpDiastolic = 80))
        advanceUntilIdle()

        assertEquals(listOf("SpO2 85% is below the 90% floor"), viewModel.reasons.value)
    }

    /**
     * No vitals row means no bullets, and that is a complete emergency screen, not a broken one.
     * The screen's standing instruction (refer to a physical hospital now) does not depend on the
     * list, which is why the ViewModel returns early rather than failing. An empty list must never
     * become a reason to not show the screen.
     */
    @Test
    fun `a missing vitals row yields no reasons rather than an error`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = viewModel(persisted = null)
        advanceUntilIdle()

        assertEquals(emptyList<String>(), viewModel.reasons.value)
    }

    /** A persisted row whose values are all inside the thresholds also yields nothing. Restoring
     *  this screen cannot invent a reason that the vitals do not support. */
    @Test
    fun `a row that crosses nothing yields no reasons`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = viewModel(snapshot(spo2Percent = 98, bpSystolic = 118, bpDiastolic = 76))
        advanceUntilIdle()

        assertEquals(emptyList<String>(), viewModel.reasons.value)
    }
}
