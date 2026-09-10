package com.example.samdapp.presentation.compounder

import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.example.samdapp.domain.model.MeasurementType
import com.example.samdapp.domain.model.VitalsCaptureMethod
import com.example.samdapp.domain.vitalssource.Instrument
import com.example.samdapp.domain.vitalssource.Scenario
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
    override fun onAilmentAudioPermissionDenied() = Unit
    override fun onStopAilmentAudioRecording() = Unit
    override fun onAddAilment() = Unit
    override fun onDeleteAilment(id: String, audioUri: String?) = Unit
    override fun onContinue() {
        continued = true
    }
    override fun onInstrumentSelected(instrument: Instrument) = Unit
    override fun onScenarioSelected(scenario: Scenario) = Unit
    override fun onStartAcquisition() = Unit
    override fun onStopAcquisition() = Unit
    override fun onLocalNetworkPermissionDenied() = Unit
    override fun fillDemoData() = Unit
}

/** Records the systolic edit so RC-3 can assert the callback actually fired, not merely that the
 *  node accepted text. */
private class EditTrackingActions : CompounderActions by FakeCompounderActions() {
    var bpSystolic: String? = null
    override fun onBpSystolicChange(value: String) {
        bpSystolic = value
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

    /**
     * RC-3. An instrument reading the worker can see is wrong, a cuff that slipped, must be
     * correctable. Autofill writes the same state fields the keyboard writes, so no field is ever
     * made read-only or disabled by a device write; the two differ only in the provenance mark.
     */
    @Test
    fun everyDeviceWritableFieldStaysEditableAfterAnAutofilledReading() {
        val uiState = autofilledState()
        composeRule.setContent { CompounderContent(uiState = uiState, actions = FakeCompounderActions()) }

        val labels = listOf(
            "Pulse (bpm)",
            "BP systolic",
            "BP diastolic",
            "SpO2 (%)",
            "Temperature (°C)",
            "Weight (kg)",
            "Blood glucose (mg/dL)",
        )
        labels.forEach { label ->
            composeRule.onNode(hasScrollAction()).performScrollToNode(hasText(label))
            composeRule.onNode(hasText(label) and hasSetTextAction())
                .assertExists("$label must remain editable after a device write")
        }
    }

    @Test
    fun editingAnAutofilledSystolicTakesEffect() {
        val actions = EditTrackingActions()
        composeRule.setContent { CompounderContent(uiState = autofilledState(), actions = actions) }

        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("BP systolic"))
        composeRule.onNode(hasText("BP systolic") and hasSetTextAction()).performTextReplacement("160")

        assert(actions.bpSystolic == "160") { "expected the systolic edit to reach the callback" }
    }

    /**
     * The form must stay on screen during an acquisition. The full-screen isLoadingPrefill spinner
     * would hide both the fields the worker is watching and anything already typed, which is why
     * the acquiring indicator is field-level instead.
     */
    @Test
    fun theFormStaysVisibleWhileAReadingIsInFlight() {
        val uiState = autofilledState().copy(acquiringInstrument = Instrument.BP)
        composeRule.setContent { CompounderContent(uiState = uiState, actions = FakeCompounderActions()) }

        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("BP systolic"))
        composeRule.onNode(hasText("BP systolic") and hasSetTextAction()).assertIsDisplayed()
    }

    /**
     * Renders only because this is the dev flavour: PI_GATEWAY_ENABLED is false in staging and
     * prod, and the gateway client is not compiled into those builds at all. Instrumented tests
     * run against one flavour at a time, so the absent case cannot be asserted from here; it rests
     * on the buildConfigField declarations and on src/dev containment.
     */
    @Test
    fun theAcquisitionControlsRenderInTheDevFlavour() {
        composeRule.setContent {
            CompounderContent(uiState = autofilledState(), actions = FakeCompounderActions())
        }

        composeRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("acquisition_controls"))
        composeRule.onNodeWithTag("start_acquisition_button").assertExists()
        composeRule.onNodeWithTag("stop_acquisition_button").assertExists()
    }

    private fun autofilledState() = CompounderUiState(
        isLoadingPrefill = false,
        encounterId = "encounter-1",
        caseRecordId = "case-1",
        chiefComplaint = "Headache",
        showPointOfCareTests = true,
        pulseBpm = "112",
        bpSystolic = "174",
        bpDiastolic = "105",
        spo2Percent = "98",
        temperatureCelsius = "36.7",
        weightKg = "68.4",
        bloodGlucoseMgDl = "95",
        fieldProvenance = mapOf(
            VitalsField.PULSE_BPM to VitalsFieldProvenance.DEVICE,
            VitalsField.BP_SYSTOLIC to VitalsFieldProvenance.DEVICE,
            VitalsField.BP_DIASTOLIC to VitalsFieldProvenance.DEVICE,
        ),
    )
}
