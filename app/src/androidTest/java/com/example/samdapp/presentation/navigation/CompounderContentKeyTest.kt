package com.example.samdapp.presentation.navigation

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import com.example.samdapp.domain.audit.AuditAction
import com.example.samdapp.domain.audit.AuditLogger
import com.example.samdapp.domain.media.AilmentAudioRecorder
import com.example.samdapp.domain.model.AilmentEntry
import com.example.samdapp.domain.model.CaseRecord
import com.example.samdapp.domain.model.CaseStatus
import com.example.samdapp.domain.model.ConsultationHistoryEntry
import com.example.samdapp.domain.model.DoctorTrackerEntry
import com.example.samdapp.domain.model.Encounter
import com.example.samdapp.domain.model.VitalsReading
import com.example.samdapp.domain.model.VitalsSnapshot
import com.example.samdapp.domain.repository.AilmentRepository
import com.example.samdapp.domain.repository.CaseRecordRepository
import com.example.samdapp.domain.repository.EncounterRepository
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * The verification behind option (a) of the content-key decision, and the one claim in this phase
 * that could not be proved by reasoning about library sources alone.
 *
 * WHAT IS AT STAKE. `AppNavHost` rewrites the top `Compounder` entry in place once the encounter
 * and case record exist, so a stack saved from that screen restores as "resume that case" instead
 * of "start a case". `NavEntry.contentKey` defaults to `key.toString()`, and `Compounder` is a
 * data class whose toString() includes the resume ids, so with the default key that rewrite reads
 * as a different entry: `ViewModelStoreNavEntryDecorator` clears the store, a fresh
 * `CompounderViewModel` is built with the resume ids, and its init takes the RESUME branch and
 * logs `ENCOUNTER_RESUMED` for a visit that was started seconds earlier and never left.
 *
 * A fabricated row on a clinical audit trail is worse than the flicker or the lost form state, and
 * it is the specific reason the alternative fix (suppressing the row conditionally) was rejected:
 * that would have papered over the fabrication rather than not producing it. So the property this
 * test asserts is the absence of that row. The single-construction assertion is the mechanism, kept
 * only to explain a failure.
 *
 * The control arm is the point. It runs the identical flow with the DEFAULT content key and
 * asserts the false row DOES appear, so this test cannot quietly pass because the rewrite stopped
 * happening or because nothing was ever recreated in the first place.
 */
class CompounderContentKeyTest {

    @get:Rule
    val composeRule = createComposeRule()

    private companion object {
        const val PATIENT = "pat-1"
        const val PARENT = "enc-parent"

        /** Mirrors the `clazzContentKey` lambda in `AppNavHost`. */
        fun pinnedKey(key: Compounder): Any = "Compounder:${key.patientId}:${key.followUpOfEncounterId}"
    }

    // -------------------------------------------------------------------------------------------
    // Minimal fakes. The unit-test Fakes.kt lives in src/test and is not on the androidTest
    // classpath, so these are written out here rather than shared.
    // -------------------------------------------------------------------------------------------

    private class RecordingAuditLogger : AuditLogger {
        val actions = mutableListOf<AuditAction>()

        override suspend fun log(action: AuditAction, patientId: String?, caseRecordId: String?, payload: String) {
            actions += action
        }
    }

    private class CountingEncounterRepository : EncounterRepository {
        val started = AtomicInteger(0)

        override suspend fun startEncounter(patientId: String, followUpOfEncounterId: String?): Result<Encounter> {
            val n = started.incrementAndGet()
            return Result.success(
                Encounter(
                    id = "enc-$n", patientId = patientId, startedAt = Instant.EPOCH,
                    createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
                    followUpOfEncounterId = followUpOfEncounterId,
                ),
            )
        }

        override fun observeEncounter(encounterId: String): Flow<Encounter?> = flowOf(null)

        override fun observeHistoryForPatient(patientId: String): Flow<List<ConsultationHistoryEntry>> = flowOf(emptyList())
    }

    private class CountingCaseRecordRepository : CaseRecordRepository {
        val created = AtomicInteger(0)

        override suspend fun createDraft(patientId: String, encounterId: String): Result<CaseRecord> {
            val n = created.incrementAndGet()
            return Result.success(
                CaseRecord(
                    id = "case-$n", patientId = patientId, encounterId = encounterId,
                    status = CaseStatus.DRAFT, assignedDoctorId = null,
                    createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
                ),
            )
        }

        override suspend fun markSavedLocally(caseRecordId: String): Result<Unit> = Result.success(Unit)
        override suspend fun assignDoctor(caseRecordId: String, doctorId: String, isOnline: Boolean): Result<Unit> = Result.success(Unit)
        override suspend fun sendAllPendingCases(): Result<Unit> = Result.success(Unit)
        override fun observePendingSyncCount(): Flow<Int> = flowOf(0)
        override suspend fun markPrescriptionReceived(caseRecordId: String): Result<Unit> = Result.success(Unit)
        override fun observeCaseRecord(caseRecordId: String): Flow<CaseRecord?> = flowOf(null)
        override suspend fun getDayOrdinal(caseRecordId: String): Int? = null
        override fun observeLatestForPatient(patientId: String): Flow<CaseRecord?> = flowOf(null)
        override fun observeByEncounterId(encounterId: String): Flow<CaseRecord?> = flowOf(null)
        override fun observeResumableDraftForUser(userId: String): Flow<CaseRecord?> = flowOf(null)
        override fun observeOpenCaseCount(doctorId: String): Flow<Int> = flowOf(0)
        override fun observeDoctorTrackerRows(): Flow<List<DoctorTrackerEntry>> = flowOf(emptyList())
    }

    private object EmptyAilmentRepository : AilmentRepository {
        override suspend fun addAilment(ailment: AilmentEntry): Result<Unit> = Result.success(Unit)
        override fun observeForEncounter(encounterId: String): Flow<List<AilmentEntry>> = flowOf(emptyList())
        override suspend fun markDeleted(id: String): Result<Unit> = Result.success(Unit)
    }

    private object NoVitalsSource : VitalsSource {
        override suspend fun readVitals(): VitalsReading = VitalsReading()
    }

    private object NoVitalsRepository : VitalsRepository {
        override suspend fun saveVitals(snapshot: VitalsSnapshot): Result<Unit> = Result.success(Unit)
        override fun observeLatestForEncounter(encounterId: String): Flow<VitalsSnapshot?> = flowOf(null)
    }

    private object NoopAudioRecorder : AilmentAudioRecorder {
        override fun startRecording(): Result<String> = Result.success("file:///unused.m4a")
        override fun stopRecording(): Result<Unit> = Result.success(Unit)
        override fun deleteRecording(uri: String) = Unit
    }

    /** Builds the real `CompounderViewModel` and counts how many times it is constructed. */
    private class CountingViewModelFactory(
        private val key: Compounder,
        private val encounters: CountingEncounterRepository,
        private val cases: CountingCaseRecordRepository,
        private val audit: RecordingAuditLogger,
        /** Shared across the whole arm, NOT per factory. The factory is `remember(key)`-scoped, so
         *  a rewritten key produces a fresh factory; a counter living on the factory would reset
         *  and report 1 no matter how many times the ViewModel was rebuilt. */
        private val constructions: AtomicInteger,
    ) : ViewModelProvider.Factory {

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            constructions.incrementAndGet()
            return CompounderViewModel(
                patientId = key.patientId,
                followUpOfEncounterId = key.followUpOfEncounterId,
                resumeEncounterId = key.resumeEncounterId,
                resumeCaseRecordId = key.resumeCaseRecordId,
                startCaseUseCase = StartCaseUseCase(encounters, cases),
                getVitalsPrefillUseCase = GetVitalsPrefillUseCase(NoVitalsSource),
                acquireDeviceVitalsUseCase = AcquireDeviceVitalsUseCase(NoVitalsSource),
                stopDeviceAcquisitionUseCase = StopDeviceAcquisitionUseCase(NoVitalsSource),
                recordVitalsUseCase = RecordVitalsUseCase(NoVitalsRepository),
                addAilmentUseCase = AddAilmentUseCase(EmptyAilmentRepository),
                deleteAilmentUseCase = DeleteAilmentUseCase(EmptyAilmentRepository),
                ailmentRepository = EmptyAilmentRepository,
                ailmentAudioRecorder = NoopAudioRecorder,
                checkEmergencyThresholdsUseCase = CheckEmergencyThresholdsUseCase(),
                auditLogger = audit,
            ) as T
        }
    }

    private class Arm(
        val audit: RecordingAuditLogger = RecordingAuditLogger(),
        val encounters: CountingEncounterRepository = CountingEncounterRepository(),
        val cases: CountingCaseRecordRepository = CountingCaseRecordRepository(),
    ) {
        val constructions = AtomicInteger(0)
        lateinit var backStack: NavBackStack<NavKey>
    }

    /**
     * Drives the real production shape: NavDisplay, both decorators AppNavHost uses, an
     * `entry<Compounder>` whose content obtains the real `CompounderViewModel` from the entry's own
     * ViewModelStore, and `AppNavHost`'s in-place rewrite fired the same way `CompounderScreen`
     * fires it -- from a LaunchedEffect keyed on the ids appearing in uiState.
     *
     * [contentKey] is the variable under test: null means "let it default", which is the behaviour
     * the pin exists to avoid.
     */
    private fun runArm(arm: Arm, contentKey: ((Compounder) -> Any)?) {
        composeRule.setContent {
            val backStack = androidx.compose.runtime.remember {
                NavBackStack<NavKey>(Compounder(PATIENT, followUpOfEncounterId = PARENT)).also { arm.backStack = it }
            }
            NavDisplay(
                backStack = backStack,
                entryDecorators = listOf(
                    rememberSaveableStateHolderNavEntryDecorator(),
                    rememberViewModelStoreNavEntryDecorator(),
                ),
                entryProvider = entryProvider {
                    entry<Compounder>(
                        clazzContentKey = { key -> contentKey?.invoke(key) ?: key.toString() },
                    ) { key ->
                        val factory = androidx.compose.runtime.remember(key) {
                            CountingViewModelFactory(key, arm.encounters, arm.cases, arm.audit, arm.constructions)
                        }
                        val viewModel: CompounderViewModel = viewModel(factory = factory)
                        val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                        val encounterId = uiState.encounterId
                        val caseRecordId = uiState.caseRecordId
                        // Exactly what CompounderScreen's onCaseReady LaunchedEffect does, and then
                        // exactly what AppNavHost does with it.
                        LaunchedEffect(encounterId, caseRecordId) {
                            if (encounterId != null && caseRecordId != null) {
                                val top = backStack.lastOrNull()
                                if (top is Compounder &&
                                    top.patientId == key.patientId &&
                                    top.followUpOfEncounterId == key.followUpOfEncounterId &&
                                    (top.resumeEncounterId != encounterId || top.resumeCaseRecordId != caseRecordId)
                                ) {
                                    backStack[backStack.lastIndex] = Compounder(
                                        patientId = key.patientId,
                                        followUpOfEncounterId = key.followUpOfEncounterId,
                                        resumeEncounterId = encounterId,
                                        resumeCaseRecordId = caseRecordId,
                                    )
                                }
                            }
                        }
                        Text("compounder")
                    }
                },
            )
        }
        composeRule.waitForIdle()
        composeRule.waitUntil(5_000) {
            (arm.backStack.last() as Compounder).resumeEncounterId != null
        }
        composeRule.waitForIdle()
    }

    /**
     * THE PROPERTY. With the key pinned, the rewrite lands in the saved shape and no
     * ENCOUNTER_RESUMED is written for a visit this session started.
     */
    @Test
    fun theRewriteProducesNoFabricatedResumeRow() {
        val arm = Arm()

        runArm(arm, ::pinnedKey)

        val top = arm.backStack.last() as Compounder
        assertEquals("The entry must end up in resume shape", "enc-1", top.resumeEncounterId)
        assertEquals("case-1", top.resumeCaseRecordId)

        assertTrue(
            "The visit was started in this session, so exactly one ENCOUNTER_STARTED belongs here",
            arm.audit.actions.count { it == AuditAction.ENCOUNTER_STARTED } == 1,
        )
        assertEquals(
            "A resume that never happened must not be recorded. If this fails, check the " +
                "construction count below: the content key is no longer stable across the rewrite.",
            0,
            arm.audit.actions.count { it == AuditAction.ENCOUNTER_RESUMED },
        )

        // The mechanism, kept to explain a failure of the assertion above rather than to stand on
        // its own.
        assertEquals("The ViewModel must survive the rewrite", 1, arm.constructions.get())
        assertEquals("And no second encounter may be minted", 1, arm.encounters.started.get())
        assertEquals(1, arm.cases.created.get())
    }

    /**
     * THE CONTROL, and the reason the test above is not vacuous. Identical flow, default content
     * key. The entry is torn down and rebuilt, and the fabricated row appears. If this ever stops
     * failing that way, the pin is no longer doing anything and the test above proves nothing.
     */
    @Test
    fun withTheDefaultContentKeyTheSameRewriteFabricatesAResumeRow() {
        val arm = Arm()

        runArm(arm, contentKey = null)

        assertEquals(
            "With the default key the rewrite must recreate the ViewModel; if it does not, the " +
                "pinned-key test above is no longer testing anything",
            2,
            arm.constructions.get(),
        )
        assertEquals(
            "And the recreated ViewModel takes the resume branch, fabricating the row",
            1,
            arm.audit.actions.count { it == AuditAction.ENCOUNTER_RESUMED },
        )
    }
}
