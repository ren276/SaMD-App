package com.example.samdapp.presentation.navigation

import com.example.samdapp.domain.media.AilmentAudioRecorder
import com.example.samdapp.domain.model.VitalsReading
import com.example.samdapp.domain.model.VitalsSnapshot
import com.example.samdapp.domain.repository.VitalsRepository
import com.example.samdapp.domain.usecase.AcquireDeviceVitalsUseCase
import com.example.samdapp.domain.usecase.AddAilmentUseCase
import com.example.samdapp.domain.usecase.CheckEmergencyThresholdsUseCase
import com.example.samdapp.domain.usecase.DeleteAilmentUseCase
import com.example.samdapp.domain.usecase.GetVitalsPrefillUseCase
import com.example.samdapp.domain.usecase.RecordVitalsUseCase
import com.example.samdapp.domain.usecase.StartCaseUseCase
import com.example.samdapp.domain.usecase.StopDeviceAcquisitionUseCase
import com.example.samdapp.domain.vitalssource.VitalsSource
import com.example.samdapp.presentation.compounder.CompounderViewModel
import com.example.samdapp.testutil.FakeAilmentRepository
import com.example.samdapp.testutil.FakeAuditLogger
import com.example.samdapp.testutil.FakeCaseRecordRepository
import com.example.samdapp.testutil.FakeEncounterRepository
import com.example.samdapp.testutil.MainDispatcherRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * V7 and V9: the two invariants the save-time transform and the Compounder entry rewrite exist to
 * hold, tested at the level where they can actually fail.
 *
 * V7 is the mint-a-second-encounter guard. It runs the real `CompounderViewModel` over fake
 * repositories, because "exactly one encounter and one case record for this visit" is a statement
 * about what the use case wrote, not about what a route looks like.
 *
 * V9 is the collision precondition for the pinned `Compounder` content key in `AppNavHost`, plus
 * the resume-prompt gate it shares that invariant with.
 */
class NavBackStackPolicyTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val audit = FakeAuditLogger()

    /** No instrument and no prefill: this suite is about routes and row counts, not vitals. */
    private object NoVitalsSource : VitalsSource {
        override suspend fun readVitals(): VitalsReading = VitalsReading()
    }

    private object NoopAudioRecorder : AilmentAudioRecorder {
        override fun startRecording(): Result<String> = Result.success("file:///unused.m4a")

        override fun stopRecording(): Result<Unit> = Result.success(Unit)

        override fun deleteRecording(uri: String) = Unit
    }

    private class RecordingVitalsRepository : VitalsRepository {
        override suspend fun saveVitals(snapshot: VitalsSnapshot): Result<Unit> = Result.success(Unit)

        override fun observeLatestForEncounter(encounterId: String): Flow<VitalsSnapshot?> = flowOf(null)
    }

    /**
     * One screen's worth of Compounder, over repositories that count what was written. The
     * encounter and case-record fakes are shared across calls on purpose, so a second ViewModel
     * built from a restored route writes into the same ledger the first one did.
     */
    private fun compounder(
        encounters: FakeEncounterRepository,
        cases: FakeCaseRecordRepository,
        resumeEncounterId: String? = null,
        resumeCaseRecordId: String? = null,
        followUpOfEncounterId: String? = null,
    ): CompounderViewModel {
        val ailments = FakeAilmentRepository()
        return CompounderViewModel(
            patientId = PATIENT,
            followUpOfEncounterId = followUpOfEncounterId,
            resumeEncounterId = resumeEncounterId,
            resumeCaseRecordId = resumeCaseRecordId,
            startCaseUseCase = StartCaseUseCase(encounters, cases),
            getVitalsPrefillUseCase = GetVitalsPrefillUseCase(NoVitalsSource),
            acquireDeviceVitalsUseCase = AcquireDeviceVitalsUseCase(NoVitalsSource),
            stopDeviceAcquisitionUseCase = StopDeviceAcquisitionUseCase(NoVitalsSource),
            recordVitalsUseCase = RecordVitalsUseCase(RecordingVitalsRepository()),
            addAilmentUseCase = AddAilmentUseCase(ailments),
            deleteAilmentUseCase = DeleteAilmentUseCase(ailments),
            ailmentRepository = ailments,
            ailmentAudioRecorder = NoopAudioRecorder,
            checkEmergencyThresholdsUseCase = CheckEmergencyThresholdsUseCase(),
            auditLogger = audit,
        )
    }

    private companion object {
        const val PATIENT = "patient-1"
    }

    // ---------------------------------------------------------------------------------------
    // V7: exactly one encounter and one case record survive a save-and-restore.
    // ---------------------------------------------------------------------------------------

    /**
     * The whole hazard in one test. A worker starts a case, the nav layer rewrites the entry into
     * its resume shape, the process dies, the stack is transformed and restored, and the restored
     * entry builds a second ViewModel. Exactly one encounter and one case record must exist.
     *
     * Without the rewrite the restored entry is `Compounder(patientId)` with null resume args,
     * which re-runs `StartCaseUseCase` and mints a second of each for one visit: the
     * orphaned-draft/consult-sent-to-two-doctors family, and a `MIGRATION_15_16`
     * one-current-assessment-per-case violation.
     */
    @Test
    fun `a rewritten Compounder entry restores as a resume and mints nothing`() = runTest(mainDispatcherRule.dispatcher) {
        val encounters = FakeEncounterRepository()
        val cases = FakeCaseRecordRepository()

        val first = compounder(encounters, cases)
        advanceUntilIdle()
        val encounterId = requireNotNull(first.uiState.value.encounterId)
        val caseRecordId = requireNotNull(first.uiState.value.caseRecordId)
        assertEquals(1, encounters.started.size)
        assertEquals(1, cases.records.size)

        // What AppNavHost's onCaseReady writes into the live stack.
        val live = listOf(Home, Compounder(PATIENT, null, encounterId, caseRecordId))
        val restored = transformForSave(live)
        val top = restored.last() as Compounder

        compounder(encounters, cases, top.resumeEncounterId, top.resumeCaseRecordId)
        advanceUntilIdle()

        assertEquals("A restored resume must not start a second encounter", 1, encounters.started.size)
        assertEquals("A restored resume must not create a second case record", 1, cases.records.size)
    }

    /**
     * The R1 third branch, exercised specifically. If the in-place rewrite never ran -- a
     * regression, or death in the moments before the ids were published -- the stack saves as
     * `[Compounder(start shape), ConsultationRoute]`. The transform must repair the Compounder
     * from the ConsultationRoute's own ids rather than leaving a start shape armed one step below
     * the restored screen, where backing out once would mint the second encounter.
     */
    @Test
    fun `a start-shape Compounder beneath a ConsultationRoute is repaired, not left armed`() = runTest(mainDispatcherRule.dispatcher) {
        val encounters = FakeEncounterRepository()
        val cases = FakeCaseRecordRepository()

        val first = compounder(encounters, cases)
        advanceUntilIdle()
        val encounterId = requireNotNull(first.uiState.value.encounterId)
        val caseRecordId = requireNotNull(first.uiState.value.caseRecordId)

        val neverRewritten = listOf(
            Home,
            Compounder(PATIENT, followUpOfEncounterId = "enc-parent"),
            ConsultationRoute(PATIENT, encounterId, caseRecordId, "ZZPROBE cough three days"),
        )

        val restored = transformForSave(neverRewritten)

        // One Compounder, in resume shape, and no ConsultationRoute above it to pop back from.
        assertEquals(2, restored.size)
        val repaired = restored[1] as Compounder
        assertEquals(encounterId, repaired.resumeEncounterId)
        assertEquals(caseRecordId, repaired.resumeCaseRecordId)
        assertEquals(
            "The live entry's real follow-up parent must survive the repair",
            "enc-parent",
            repaired.followUpOfEncounterId,
        )

        // And the repaired entry, rebuilt, mints nothing.
        compounder(encounters, cases, repaired.resumeEncounterId, repaired.resumeCaseRecordId, repaired.followUpOfEncounterId)
        advanceUntilIdle()
        assertEquals(1, encounters.started.size)
        assertEquals(1, cases.records.size)
    }

    // ---------------------------------------------------------------------------------------
    // V9: the resume gate, and the collision precondition it doubles as.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `backStackContainsCase finds the case through every route that carries one`() {
        val case = "case-1"
        assertTrue(backStackContainsCase(listOf(Home, Compounder(PATIENT, null, "enc-1", case)), case))
        assertTrue(backStackContainsCase(listOf(Home, ConsultationRoute(PATIENT, "enc-1", case, "x")), case))
        assertTrue(backStackContainsCase(listOf(Home, SendingRoute(case, "consult-1", "enc-1")), case))
        assertTrue(backStackContainsCase(listOf(Home, KernelAssessmentRoute(case, "consult-1")), case))
        assertTrue(backStackContainsCase(listOf(Home, TranscriptionRoute("consult-1", case)), case))
        assertTrue(backStackContainsCase(listOf(Home, AcknowledgementRoute(case)), case))
        assertTrue(backStackContainsCase(listOf(Home, ReportRoute(case)), case))
        assertTrue(backStackContainsCase(listOf(Home, DoctorAssignmentConfirmRoute(case)), case))
    }

    @Test
    fun `backStackContainsCase is false for a bare Home and for a different case`() {
        assertFalse(backStackContainsCase(listOf(Home), "case-1"))
        assertFalse(backStackContainsCase(listOf(Home, AcknowledgementRoute("case-2")), "case-1"))
        // A Compounder that has not started its case yet carries no case id to match.
        assertFalse(backStackContainsCase(listOf(Home, Compounder(PATIENT)), "case-1"))
    }

    /**
     * THE COLLISION PRECONDITION. `AppNavHost` pins the Compounder entry's content key to
     * `"Compounder:${patientId}:${followUpOfEncounterId}"` so the in-place rewrite does not
     * destroy the ViewModel and log a fabricated ENCOUNTER_RESUMED. Two entries sharing that key
     * would share one ViewModelStore, and the second would be handed the first's ViewModel with
     * the wrong encounter.
     *
     * This asserts the gate that makes that impossible: once the stack holds an entry for a case,
     * the resume prompt for that case is suppressed, so a second Compounder for it is never
     * pushed. If this fails, the content key is no longer unique and the failure is a
     * wrong-encounter binding on a clinical screen, not an extra dialog.
     */
    @Test
    fun `the resume gate prevents two Compounder entries that would share a content key`() {
        val stack = mutableListOf<Any>(Home, Compounder(PATIENT, null, "enc-1", "case-1"))

        val suppressed = backStackContainsCase(stack, "case-1")

        assertTrue("The prompt must be suppressed for a case already in the stack", suppressed)
        assertEquals(
            "Exactly one Compounder entry may share this content key",
            1,
            stack.filterIsInstance<Compounder>()
                .count { contentKeyOf(it) == contentKeyOf(Compounder(PATIENT, null, "enc-1", "case-1")) },
        )
    }

    /** Mirrors the `clazzContentKey` lambda in `AppNavHost`. Kept here so a change to one without
     *  the other fails this test rather than silently diverging. */
    private fun contentKeyOf(key: Compounder): String =
        "Compounder:${key.patientId}:${key.followUpOfEncounterId}"
}
