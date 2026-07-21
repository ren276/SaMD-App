package com.example.samdapp.presentation.register

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

private class FakeRegisterActions : RegisterActions {
    val changes = mutableListOf<Pair<RegisterField, String>>()
    var submitted = false

    override fun onFieldChange(field: RegisterField, value: String) {
        changes.add(field to value)
    }

    override fun onBiologicalSexChange(sex: String) = Unit
    override fun onSubmit() {
        submitted = true
    }
    override fun fillDemoData() = Unit
}

class RegisterScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun submitButtonIsDisabledUntilRequiredFieldsAreFilled() {
        val actions = FakeRegisterActions()
        composeRule.setContent {
            RegisterContent(uiState = RegisterUiState(), actions = actions)
        }

        composeRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("submit_button"))
        composeRule.onNodeWithTag("submit_button").performClick()

        assert(!actions.submitted) { "Submit should be disabled with no required fields filled" }
    }

    @Test
    fun typingIntoFullNameFieldReportsTheChange() {
        val actions = FakeRegisterActions()
        composeRule.setContent {
            RegisterContent(uiState = RegisterUiState(), actions = actions)
        }

        composeRule.onNodeWithText("Full name *").performTextInput("Anita Kumari")

        assert(actions.changes.any { it.first == RegisterField.FULL_NAME })
    }

    @Test
    fun submitButtonIsEnabledOnceRequiredFieldsArePresent() {
        val actions = FakeRegisterActions()
        val uiState = RegisterUiState(
            fields = mapOf(
                RegisterField.FULL_NAME to "Anita Kumari",
                RegisterField.MOBILE_NUMBER to "9999999999",
            ),
        )
        composeRule.setContent {
            RegisterContent(uiState = uiState, actions = actions)
        }

        composeRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("submit_button"))
        composeRule.onNodeWithTag("submit_button").performClick()

        assert(actions.submitted) { "Submit should fire once required fields are present" }
    }
}
