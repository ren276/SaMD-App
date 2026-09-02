package com.example.samdapp.presentation.licenses

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

/**
 * The Parakeet row's licence identifier is the assertion with a legal consequence (CC BY 4.0
 * attribution obligation, open since PR 4a merged, attaches at APK distribution) — the rest of
 * the list is data. See docs/sbom/README.md and scratchpad/pr4b-flag-flip-design-memo.md Part E.
 */
class OpenSourceLicensesScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun parakeetEntry_rendersWithCcBy4LicenceIdentifier() {
        composeRule.setContent { OpenSourceLicensesScreen() }

        composeRule.onNodeWithText("NVIDIA Parakeet TDT 0.6B v2 (int8)").assertExists()
        composeRule.onNodeWithText("CC BY 4.0").assertExists()
    }
}
