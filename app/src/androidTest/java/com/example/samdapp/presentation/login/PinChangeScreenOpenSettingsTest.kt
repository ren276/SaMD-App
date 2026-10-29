package com.example.samdapp.presentation.login

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.example.samdapp.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Memo Q12, P2-R11, P3-R6: the PIN-change failure copy and its Open settings action, rendered on the
 * device with fake state. A live PIN change is not reachable (Profile has no entry; the screen shows
 * only for must_change_pin), so this is the render proof. No storage, no network, no Context files:
 * the settings action is a recording callback, never a real Intent.
 */
class PinChangeScreenOpenSettingsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private class NoActions : PinChangeActions {
        override fun onCurrentPinChange(value: String) = Unit
        override fun onNewPinChange(value: String) = Unit
        override fun onConfirmPinChange(value: String) = Unit
        override fun onSubmit() = Unit
    }

    private fun show(error: PinChangeError?, onOpenSettings: () -> Unit = {}) {
        composeRule.setContent {
            MaterialTheme { PinChangeContent(PinChangeUiState(error = error), NoActions(), onOpenSettings) }
        }
    }

    private fun text(res: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(res)

    private val openSettingsLabel get() = text(R.string.lnp_open_settings)

    @Test
    fun localNetworkShowsItsTextAndOpenSettings() {
        show(PinChangeError.Worded(R.string.pin_change_failed_local_network))

        composeRule.onNodeWithText(text(R.string.pin_change_failed_local_network)).assertIsDisplayed()
        composeRule.onNodeWithTag("pin_change_open_settings").assertIsDisplayed()
        composeRule.onNodeWithText(openSettingsLabel).assertIsDisplayed()
    }

    @Test
    fun noConnectionShowsItsTextAndNoOpenSettings() {
        show(PinChangeError.Worded(R.string.pin_change_failed_no_connection))

        composeRule.onNodeWithText(text(R.string.pin_change_failed_no_connection)).assertIsDisplayed()
        composeRule.onNodeWithTag("pin_change_open_settings").assertDoesNotExist()
    }

    @Test
    fun secureConnectionShowsItsTextAndNoOpenSettings() {
        show(PinChangeError.Worded(R.string.pin_change_failed_secure_connection))

        composeRule.onNodeWithText(text(R.string.pin_change_failed_secure_connection)).assertIsDisplayed()
        composeRule.onNodeWithTag("pin_change_open_settings").assertDoesNotExist()
    }

    @Test
    fun aServerRefusalShowsItsDetailAndNoOpenSettings() {
        show(PinChangeError.ServerDetail("Current PIN is incorrect."))

        composeRule.onNodeWithText("Current PIN is incorrect.").assertIsDisplayed()
        composeRule.onNodeWithTag("pin_change_open_settings").assertDoesNotExist()
    }

    @Test
    fun noErrorShowsNoOpenSettings() {
        show(null)

        composeRule.onNodeWithTag("pin_change_open_settings").assertDoesNotExist()
    }

    @Test
    fun tappingOpenSettingsCallsTheSettingsCallbackExactlyOnce() {
        var calls = 0
        show(PinChangeError.Worded(R.string.pin_change_failed_local_network)) { calls++ }

        composeRule.onNodeWithTag("pin_change_open_settings").performClick()
        composeRule.waitForIdle()

        assertEquals(1, calls)
    }
}
