package com.example.samdapp.presentation.abha

import com.example.samdapp.domain.abha.AbhaApiResult
import com.example.samdapp.domain.abha.AbhaSessionSnapshot
import com.example.samdapp.domain.abha.AbhaTransactionState
import com.example.samdapp.domain.usecase.RequestAbhaOtpUseCase
import com.example.samdapp.testutil.FakeAbdmAbhaSource
import com.example.samdapp.testutil.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Aadhaar step of the real ABHA create flow, from the worker-facing side: the consent gate as it
 *  actually reaches the screen (button disabled, backend never touched), and the three error
 *  classes [RequestAbhaOtpUseCase] can hand back, surfaced distinctly in UI state. */
@OptIn(ExperimentalCoroutinesApi::class)
class AbhaAadhaarEntryViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val validAadhaar = "123456789012"

    @Test
    fun `submit is disabled until both a valid aadhaar and consent are present`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = AbhaAadhaarEntryViewModel(RequestAbhaOtpUseCase(FakeAbdmAbhaSource()))

        assertFalse("neither field set", viewModel.uiState.value.canSubmit)

        viewModel.onAadhaarNumberChange(validAadhaar)
        assertFalse("aadhaar set, no consent", viewModel.uiState.value.canSubmit)

        viewModel.onConsentChange(true)
        assertTrue("both set", viewModel.uiState.value.canSubmit)

        viewModel.onConsentChange(false)
        assertFalse("consent withdrawn again", viewModel.uiState.value.canSubmit)
    }

    @Test
    fun `submitting without consent never reaches the backend`() = runTest(mainDispatcherRule.dispatcher) {
        val source = FakeAbdmAbhaSource()
        val viewModel = AbhaAadhaarEntryViewModel(RequestAbhaOtpUseCase(source))

        viewModel.onAadhaarNumberChange(validAadhaar)
        viewModel.onSubmit()
        advanceUntilIdle()

        assertFalse(source.startCalled)
        assertFalse(source.submitIdentityCalled)
    }

    @Test
    fun `successful submit emits OtpRequested with the sessionId and masked mobile`() = runTest(mainDispatcherRule.dispatcher) {
        val source = FakeAbdmAbhaSource(
            submitIdentityResult = AbhaApiResult.Success(
                AbhaSessionSnapshot(sessionId = "s-77", state = AbhaTransactionState.OTP_REQUESTED, maskedMobile = "XXXXXX3210"),
            ),
        )
        val viewModel = AbhaAadhaarEntryViewModel(RequestAbhaOtpUseCase(source))
        val effects = mutableListOf<AbhaAadhaarEntryEffect>()
        val collectJob = launch { viewModel.effects.toList(effects) }

        viewModel.onAadhaarNumberChange(validAadhaar)
        viewModel.onConsentChange(true)
        viewModel.onSubmit()
        advanceUntilIdle()

        val effect = effects.single() as AbhaAadhaarEntryEffect.OtpRequested
        assertEquals("s-77", effect.sessionId)
        assertEquals("XXXXXX3210", effect.maskedMobile)
        assertFalse(viewModel.uiState.value.isSubmitting)

        collectJob.cancel()
    }

    @Test
    fun `offline failure surfaces as retryable in ui state, no fabricated session`() = runTest(mainDispatcherRule.dispatcher) {
        val source = FakeAbdmAbhaSource(startResult = AbhaApiResult.Failure(code = null, message = "host unreachable"))
        val viewModel = AbhaAadhaarEntryViewModel(RequestAbhaOtpUseCase(source))
        val effects = mutableListOf<AbhaAadhaarEntryEffect>()
        val collectJob = launch { viewModel.effects.toList(effects) }

        viewModel.onAadhaarNumberChange(validAadhaar)
        viewModel.onConsentChange(true)
        viewModel.onSubmit()
        advanceUntilIdle()

        assertTrue(effects.isEmpty())
        val state = viewModel.uiState.value
        assertTrue(state.errorRetryable)
        assertFalse(state.errorMessage!!.contains("host unreachable"))

        collectJob.cancel()
    }

    @Test
    fun `backend-signalled failure surfaces as not retryable in ui state`() = runTest(mainDispatcherRule.dispatcher) {
        val source = FakeAbdmAbhaSource(
            submitIdentityResult = AbhaApiResult.Failure(code = "SAMD-ABHA-2006", message = "upstream rejected"),
        )
        val viewModel = AbhaAadhaarEntryViewModel(RequestAbhaOtpUseCase(source))

        viewModel.onAadhaarNumberChange(validAadhaar)
        viewModel.onConsentChange(true)
        viewModel.onSubmit()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.errorRetryable)
        assertFalse(state.errorMessage!!.contains("upstream rejected"))
    }

    @Test
    fun `protocol violation surfaces distinctly, not as the offline message`() = runTest(mainDispatcherRule.dispatcher) {
        val source = FakeAbdmAbhaSource(
            submitIdentityResult = AbhaApiResult.ProtocolViolation("Unknown ABHA session state \"WEIRD\" from backend"),
        )
        val viewModel = AbhaAadhaarEntryViewModel(RequestAbhaOtpUseCase(source))

        viewModel.onAadhaarNumberChange(validAadhaar)
        viewModel.onConsentChange(true)
        viewModel.onSubmit()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.errorRetryable)
        assertFalse(state.errorMessage!!.contains("No connection"))
    }

    @Test
    fun `changing the aadhaar field clears a prior error`() = runTest(mainDispatcherRule.dispatcher) {
        val source = FakeAbdmAbhaSource(startResult = AbhaApiResult.Failure(code = null, message = "offline"))
        val viewModel = AbhaAadhaarEntryViewModel(RequestAbhaOtpUseCase(source))
        viewModel.onAadhaarNumberChange(validAadhaar)
        viewModel.onConsentChange(true)
        viewModel.onSubmit()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.errorMessage != null)

        viewModel.onAadhaarNumberChange("999999999999")

        assertNull(viewModel.uiState.value.errorMessage)
    }
}
