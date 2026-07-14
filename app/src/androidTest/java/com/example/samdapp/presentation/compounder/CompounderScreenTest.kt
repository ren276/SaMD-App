package com.example.samdapp.presentation.compounder

import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
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
    override fun onTogglePointOfCareTests() = Unit
    override fun onBloodGlucoseChange(value: String) = Unit
    override fun onUrinalysisChange(value: String) = Unit
    override fun onChiefComplaintChange(value: String) {
        chiefComplaint = value
    }
    override fun onNewSymptomTextChange(value: String) = Unit
    override fun onAddSymptom() = Unit
    override fun onContinue() {
        continued = true
    }
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

        composeRule.onNodeWithText("Chief complaint *").performTextInput("Fever for 2 days")

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
