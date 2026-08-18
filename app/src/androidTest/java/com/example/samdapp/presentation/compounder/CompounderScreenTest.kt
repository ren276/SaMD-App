package com.example.samdapp.presentation.compounder

import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import com.example.samdapp.domain.model.MeasurementType
import com.example.samdapp.domain.model.VitalsCaptureMethod
import org.junit.Rule
import org.junit.Test

private class FakeCompounderActions : CompounderActions {
    var chiefComplaint: String? = null
    var continued = false

    override fun onPulseChange(value: String) = Unit
    override fun onBpSystolicChange(value: String) = Unit
    override fun onBpDiastolicChange(value: String) = Unit
    override fun onSpo2Change(value: String) = Unit
    override fun onTemperatureChange(value: String) = Unit
    override fun onRespiratoryRateChange(value: String) = Unit
    override fun onWeightChange(value: String) = Unit
    override fun onHeightChange(value: String) = Unit
    override fun onPainScoreChange(value: String) = Unit
    override fun onCaptureMethodChange(method: VitalsCaptureMethod) = Unit
    override fun onTogglePointOfCareTests() = Unit
    override fun onBloodGlucoseChange(value: String) = Unit
    override fun onUrinalysisChange(value: String) = Unit
    override fun onChiefComplaintChange(value: String) {
        chiefComplaint = value
    }
    override fun onAilmentDescriptionChange(value: String) = Unit
    override fun onAilmentMeasurementTypeChange(type: MeasurementType) = Unit
    override fun onAilmentMeasuredValueChange(value: String) = Unit
    override fun onAilmentMeasuredUnitChange(value: String) = Unit
    override fun onAilmentSeverityChange(value: String) = Unit
    override fun onAilmentDurationChange(value: String) = Unit
    override fun onAilmentOnsetChange(value: String) = Unit
    override fun onAilmentQualifiersChange(value: String) = Unit
    override fun onAilmentVisibilityToggle() = Unit
    override fun onPrivateHandoffAcknowledged() = Unit
    override fun onPrivateHandoffCancelled() = Unit
    override fun onStartAilmentAudioRecording() = Unit
    override fun onStopAilmentAudioRecording() = Unit
    override fun onAddAilment() = Unit
    override fun onDeleteAilment(id: String, audioUri: String?) = Unit
    override fun onContinue() {
        continued = true
    }
    override fun fillDemoData() = Unit
}

class CompounderScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun continueIsDisabledWithoutAChiefComplaint() {
        val actions = FakeCompounderActions()
        val uiState = CompounderUiState(isLoadingPrefill = false, encounterId = "encounter-1", caseRecordId = "case-1")
        composeRule.setContent {
            CompounderContent(uiState = uiState, actions = actions)
        }

        composeRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("continue_button"))
        composeRule.onNodeWithTag("continue_button").performClick()

        assert(!actions.continued) { "Continue should be disabled with no chief complaint" }
    }

    @Test
    fun typingChiefComplaintReportsTheChange() {
        val actions = FakeCompounderActions()
        val uiState = CompounderUiState(isLoadingPrefill = false, encounterId = "encounter-1", caseRecordId = "case-1")
        composeRule.setContent {
            CompounderContent(uiState = uiState, actions = actions)
        }

        composeRule.onNodeWithText("Main concern *").performTextInput("Fever for 2 days")

        assert(actions.chiefComplaint == "Fever for 2 days")
    }

    @Test
    fun continueFiresOnceChiefComplaintAndEncounterArePresent() {
        val actions = FakeCompounderActions()
        val uiState = CompounderUiState(
            isLoadingPrefill = false,
            encounterId = "encounter-1",
            caseRecordId = "case-1",
            chiefComplaint = "Fever",
        )
        composeRule.setContent {
            CompounderContent(uiState = uiState, actions = actions)
        }

        composeRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("continue_button"))
        composeRule.onNodeWithTag("continue_button").performClick()

        assert(actions.continued)
    }
}
