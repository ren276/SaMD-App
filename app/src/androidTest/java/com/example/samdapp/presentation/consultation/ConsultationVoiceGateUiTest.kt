package com.example.samdapp.presentation.consultation

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.samdapp.domain.model.AttachmentType
import org.junit.Rule
import org.junit.Test

private class FakeConsultationActions : ConsultationActions {
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
    override fun onRecordImpactVoice() = Unit
    override fun onUseImpactSuggestion() {
        usedSuggestion = true
    }
    override fun onEditImpactSuggestion() {
        editedSuggestion = true
    }
    override fun onDiscardImpactSuggestion() {
        discardedSuggestion = true
    }
    override fun onRelevantHistoryChange(value: String) = Unit
    override fun onAddAttachment(type: AttachmentType, uri: String) = Unit
    override fun onRecordAudioAttachment() = Unit
    override fun onSend() = Unit
    override fun fillDemoData() = Unit
}

/**
 * PR 3c (`scratchpad/pr3-voice-gate-design-memo.md` Part A.4, Part E.1). Proves the mic button
 * and the suggestion surface are absent from `ConsultationContent` while
 * `FeatureFlags.VOICE_FIELD_IMPACT_ENABLED` is off, the shipped default.
 *
 * `VOICE_FIELD_IMPACT_ENABLED` is a compile-time `const val`, so there is no runtime path to
 * exercise the flag-on rendering through `ConsultationContent` itself in this build, the same
 * limitation `ConsultationViewModelTest` already documents for `VOICE_INPUT_ENABLED`. The
 * suggestion surface's own rendering and its three actions are proven instead by calling
 * `ImpactVoiceSuggestionSurface` directly, the same pattern `CompounderScreenTest` uses for
 * `CompounderContent`: both composables are `internal`, not `private`, for exactly this.
 */
class ConsultationVoiceGateUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun baseState(impactVoiceSuggestion: String? = null) = ConsultationUiState(
        chiefComplaint = "fever",
        impactOnDailyActivities = "cannot walk far",
        impactVoiceSuggestion = impactVoiceSuggestion,
    )

    @Test
    fun theMicButtonAndSuggestionSurfaceAreAbsentWhileTheFlagIsOff() {
        // FeatureFlags.VOICE_FIELD_IMPACT_ENABLED defaults to false, so ConsultationContent's own
        // `if (FeatureFlags.VOICE_FIELD_IMPACT_ENABLED)` guards keep both out of composition
        // regardless of uiState. A suggestion existing in state with the flag off is not a
        // reachable combination in production code (onRecordImpactVoice has no caller unless the
        // flag is on), but asserting it here proves the UI guard itself, not just the handler
        // guard tested in ConsultationVoiceGateTest.
        composeRule.setContent {
            ConsultationContent(
                uiState = baseState(impactVoiceSuggestion = "cannot lift water buckets"),
                actions = FakeConsultationActions(),
            )
        }

        composeRule.onNodeWithTag("impact_voice_mic_button").assertDoesNotExist()
        composeRule.onNodeWithTag("impact_voice_suggestion_surface").assertDoesNotExist()
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
