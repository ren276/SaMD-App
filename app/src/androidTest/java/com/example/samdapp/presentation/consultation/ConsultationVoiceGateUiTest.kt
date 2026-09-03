package com.example.samdapp.presentation.consultation

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.example.samdapp.domain.model.AttachmentType
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

private class FakeConsultationActions : ConsultationActions {
    var recordedImpactVoice = false
    var usedSuggestion = false
    var editedSuggestion = false
    var discardedSuggestion = false

    override fun onChiefComplaintChange(value: String) = Unit
    override fun onToggleVoiceMode() = Unit
    override fun onRecordChiefComplaintVoice() = Unit
    override fun onOnsetChange(value: String) = Unit
    override fun onDurationBucketChange(value: String) = Unit
    override fun onSeverityScoreChange(value: Int) = Unit
    override fun onAggravatingFactorsChange(value: String) = Unit
    override fun onRelievingFactorsChange(value: String) = Unit
    override fun onImpactChange(value: String) = Unit
    override fun onRecordImpactVoice() {
        recordedImpactVoice = true
    }
    override fun onUseImpactSuggestion() {
        usedSuggestion = true
    }
    override fun onEditImpactSuggestion() {
        editedSuggestion = true
    }
    override fun onDiscardImpactSuggestion() {
        discardedSuggestion = true
    }
    override fun onVoicePermissionDenied() = Unit
    override fun onRelevantHistoryChange(value: String) = Unit
    override fun onAddAttachment(type: AttachmentType, uri: String) = Unit
    override fun onRecordAudioAttachment() = Unit
    override fun onDocumentDepartmentSelected(code: com.example.samdapp.domain.model.DepartmentCode) = Unit
    override fun onDocumentRecordTypeSelected(code: com.example.samdapp.domain.model.RecordTypeCode) = Unit
    override fun onDocumentLabelChange(text: String) = Unit
    override fun onDocumentPicked(uri: String, claimedMimeType: String?) = Unit
    override fun onDismissDocumentUploadFailures() = Unit
    override fun onSend() = Unit
    override fun fillDemoData() = Unit
}

/**
 * PR 3c (`scratchpad/pr3-voice-gate-design-memo.md` Part A.4, Part E.1), inverted at the flag flip
 * (`scratchpad/pr4b-flag-flip-design-memo.md` Part 0 finding 1, B.3 commit 5). Proves the mic entry
 * point and the suggestion surface render in `ConsultationContent` while
 * `FeatureFlags.VOICE_FIELD_IMPACT_ENABLED` is on, which is now the shipped default, and that the
 * entry point is wired to `onRecordImpactVoice`.
 *
 * `VOICE_FIELD_IMPACT_ENABLED` is a compile-time `const val`, so exactly one side of the guard is
 * testable per build: the assertion that both are absent was correct while the flag was off and is
 * false now, the same limitation `ConsultationViewModelTest` documents for `VOICE_INPUT_ENABLED`.
 * The suggestion surface's own rendering and its three actions are proven separately by calling
 * `ImpactVoiceSuggestionSurface` directly, the same pattern `CompounderScreenTest` uses for
 * `CompounderContent`: both composables are `internal`, not `private`, for exactly this. Those
 * three tests are flag-independent and are unchanged by the flip.
 *
 * The entry point is addressed by its `impact_voice_mic_button` test tag, never by its shape, so
 * the test says nothing about whether it is a standalone button or a trailing icon.
 */
class ConsultationVoiceGateUiTest {

    private companion object {
        const val GRANT_POLL_ATTEMPTS = 50
        const val GRANT_POLL_INTERVAL_MS = 100L
    }

    @get:Rule
    val composeRule = createComposeRule()

    private fun baseState(impactVoiceSuggestion: String? = null) = ConsultationUiState(
        chiefComplaint = "fever",
        impactOnDailyActivities = "cannot walk far",
        impactVoiceSuggestion = impactVoiceSuggestion,
    )

    /** The mic is only reachable with `RECORD_AUDIO` held: `rememberPermissionAction` calls
     *  `onRecordImpactVoice` on a granted permission and launches the system prompt otherwise, and
     *  a prompt in an instrumented run is an indefinite hang, not a failure. Granting it here, and
     *  failing loudly if the grant does not take, keeps "the handler is wired" distinguishable from
     *  "the permission was missing", the same discipline as the up-to-date-check finding in 4b. */
    @Before
    fun grantRecordAudio() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        fun granted() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted()) return
        instrumentation.uiAutomation
            .executeShellCommand("pm grant ${context.packageName} ${Manifest.permission.RECORD_AUDIO}")
            .close()
        repeat(GRANT_POLL_ATTEMPTS) {
            if (granted()) return
            Thread.sleep(GRANT_POLL_INTERVAL_MS)
        }
        error("RECORD_AUDIO was not granted; this class cannot tell a missing handler from a missing permission")
    }

    @Test
    fun theMicButtonAndSuggestionSurfaceRenderWhileTheFlagIsOn() {
        // FeatureFlags.VOICE_FIELD_IMPACT_ENABLED is true, so ConsultationContent's own
        // `if (FeatureFlags.VOICE_FIELD_IMPACT_ENABLED)` guards admit both. Scrolled to rather
        // than asserted in place: the field sits below the fold of a LazyColumn on a phone-sized
        // viewport, and an offscreen lazy item is not composed, so a bare assertExists here would
        // fail for a reason that has nothing to do with the guard under test.
        composeRule.setContent {
            ConsultationContent(
                uiState = baseState(impactVoiceSuggestion = "cannot lift water buckets"),
                actions = FakeConsultationActions(),
            )
        }

        composeRule.onNode(hasScrollAction())
            .performScrollToNode(hasTestTag("impact_voice_mic_button"))
        composeRule.onNodeWithTag("impact_voice_mic_button").assertExists()

        composeRule.onNode(hasScrollAction())
            .performScrollToNode(hasTestTag("impact_voice_suggestion_surface"))
        composeRule.onNodeWithTag("impact_voice_suggestion_surface").assertExists()
    }

    @Test
    fun tappingTheMicEntryPointInvokesOnRecordImpactVoice() {
        // No engine and no permission prompt are involved: the assertion is that the entry point
        // reaches the gate's entry action, which is the half of the flip that the flag alone does
        // not prove. Addressed by test tag, so a change of shape does not change this test.
        val actions = FakeConsultationActions()
        composeRule.setContent {
            ConsultationContent(uiState = baseState(), actions = actions)
        }

        composeRule.onNode(hasScrollAction())
            .performScrollToNode(hasTestTag("impact_voice_mic_button"))
        composeRule.onNodeWithTag("impact_voice_mic_button").performClick()

        assertTrue("The mic entry point must call onRecordImpactVoice", actions.recordedImpactVoice)
    }

    @Test
    fun aSuggestionRendersThreeEqualWeightActions() {
        composeRule.setContent {
            ImpactVoiceSuggestionSurface(
                suggestion = "cannot lift water buckets",
                actions = FakeConsultationActions(),
            )
        }

        composeRule.onNodeWithText("cannot lift water buckets").assertExists()
        composeRule.onNodeWithTag("impact_voice_use_button").assertExists()
        composeRule.onNodeWithTag("impact_voice_edit_button").assertExists()
        composeRule.onNodeWithTag("impact_voice_discard_button").assertExists()
    }

    @Test
    fun useItInvokesOnUseImpactSuggestion() {
        val actions = FakeConsultationActions()
        composeRule.setContent {
            ImpactVoiceSuggestionSurface(suggestion = "cannot lift water buckets", actions = actions)
        }

        composeRule.onNodeWithTag("impact_voice_use_button").performClick()

        assert(actions.usedSuggestion) { "Use it must call onUseImpactSuggestion" }
        assert(!actions.editedSuggestion && !actions.discardedSuggestion)
    }

    @Test
    fun editInvokesOnEditImpactSuggestion() {
        val actions = FakeConsultationActions()
        composeRule.setContent {
            ImpactVoiceSuggestionSurface(suggestion = "cannot lift water buckets", actions = actions)
        }

        composeRule.onNodeWithTag("impact_voice_edit_button").performClick()

        assert(actions.editedSuggestion) { "Edit must call onEditImpactSuggestion" }
        assert(!actions.usedSuggestion && !actions.discardedSuggestion)
    }

    @Test
    fun discardInvokesOnDiscardImpactSuggestion() {
        val actions = FakeConsultationActions()
        composeRule.setContent {
            ImpactVoiceSuggestionSurface(suggestion = "cannot lift water buckets", actions = actions)
        }

        composeRule.onNodeWithTag("impact_voice_discard_button").performClick()

        assert(actions.discardedSuggestion) { "Discard must call onDiscardImpactSuggestion" }
        assert(!actions.usedSuggestion && !actions.editedSuggestion)
    }
}
