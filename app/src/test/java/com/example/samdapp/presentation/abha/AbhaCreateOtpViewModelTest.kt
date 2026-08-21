package com.example.samdapp.presentation.abha

import com.example.samdapp.domain.abha.AbhaApiResult
import com.example.samdapp.domain.abha.AbhaIdentity
import com.example.samdapp.domain.abha.AbhaSessionSnapshot
import com.example.samdapp.domain.abha.AbhaTransactionState
import com.example.samdapp.domain.usecase.EnrolAbhaUseCase
import com.example.samdapp.testutil.FakeAbdmAbhaSource
import com.example.samdapp.testutil.FakeAbhaProfileRepository
import com.example.samdapp.testutil.FakeAuditLogger
import com.example.samdapp.testutil.MainDispatcherRule
import com.example.samdapp.testutil.testAbhaIdentity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** OTP step of the real ABHA create flow, from the worker-facing side: the round transition into
 *  [AbhaOtpRound.COMMUNICATION_MOBILE], the spent-OTP clear on that transition, and the three
 *  error classes [EnrolAbhaUseCase] can hand back, surfaced distinctly in UI state. */
@OptIn(ExperimentalCoroutinesApi::class)
class AbhaCreateOtpViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun viewModel(
        source: FakeAbdmAbhaSource,
        repo: FakeAbhaProfileRepository = FakeAbhaProfileRepository(),
        audit: FakeAuditLogger = FakeAuditLogger(),
        maskedMobile: String? = "XXXXXX3210",
    ) = AbhaCreateOtpViewModel(
        sessionId = "s-1",
        maskedMobile = maskedMobile,
        enrolAbhaUseCase = EnrolAbhaUseCase(source, repo),
        auditLogger = audit,
    )

    @Test
    fun `verify is disabled until otp and mobile are both the right length in the aadhaar round`() {
        val vm = viewModel(FakeAbdmAbhaSource())

        assertFalse(vm.uiState.value.canVerify)

        vm.onOtpChange("123456")
        assertFalse("otp alone is not enough in the aadhaar round", vm.uiState.value.canVerify)

        vm.onMobileNumberChange("9998887776")
        assertTrue(vm.uiState.value.canVerify)
    }

    @Test
    fun `mobile verification round only needs the otp, not the mobile number again`() = runTest(mainDispatcherRule.dispatcher) {
        val source = FakeAbdmAbhaSource(
            verifyOtpResult = AbhaApiResult.Success(
                AbhaSessionSnapshot(sessionId = "s-1", state = AbhaTransactionState.MOBILE_VERIFICATION_REQUIRED),
            ),
        )
        val vm = viewModel(source)
        vm.onOtpChange("111111")
        vm.onMobileNumberChange("9998887776")
        vm.onVerify()
        advanceUntilIdle()

        assertEquals(AbhaOtpRound.COMMUNICATION_MOBILE, vm.uiState.value.round)
        vm.onOtpChange("222222")
        assertTrue("no mobile number needed this round", vm.uiState.value.canVerify)
    }

    @Test
    fun `transitioning into the mobile verification round clears the spent otp`() = runTest(mainDispatcherRule.dispatcher) {
        val source = FakeAbdmAbhaSource(
            verifyOtpResult = AbhaApiResult.Success(
                AbhaSessionSnapshot(sessionId = "s-1", state = AbhaTransactionState.MOBILE_VERIFICATION_REQUIRED),
            ),
        )
        val vm = viewModel(source)
        vm.onOtpChange("111111")
        vm.onMobileNumberChange("9998887776")

        vm.onVerify()
        advanceUntilIdle()

        assertEquals(
            "the aadhaar-round otp must not still be sitting in the field, or a stray tap resubmits it",
            "",
            vm.uiState.value.otp,
        )
    }

    @Test
    fun `full two-round flow emits Enrolled and audits only the abhaId`() = runTest(mainDispatcherRule.dispatcher) {
        val identity = testAbhaIdentity(abhaNumber = "43422151056749")
        val source = FakeAbdmAbhaSource(
            verifyOtpResult = AbhaApiResult.Success(
                AbhaSessionSnapshot(sessionId = "s-1", state = AbhaTransactionState.MOBILE_VERIFICATION_REQUIRED),
            ),
            verifyMobileOtpResult = AbhaApiResult.Success(
                AbhaSessionSnapshot(sessionId = "s-1", state = AbhaTransactionState.MOBILE_VERIFIED),
            ),
            getProfileResult = AbhaApiResult.Success(identity),
        )
        val audit = FakeAuditLogger()
        val vm = viewModel(source, audit = audit)
        val effects = mutableListOf<AbhaCreateOtpEffect>()
        val collectJob = launch { vm.effects.toList(effects) }

        vm.onOtpChange("111111")
        vm.onMobileNumberChange("9998887776")
        vm.onVerify()
        advanceUntilIdle()

        vm.onOtpChange("222222")
        vm.onVerify()
        advanceUntilIdle()

        val effect = effects.single() as AbhaCreateOtpEffect.Enrolled
        assertEquals(identity.abhaNumber, effect.abhaId)

        val entry = audit.logged.single()
        assertEquals("abha_profile_created", entry.action)
        assertTrue(entry.payload.contains(identity.abhaNumber))
        assertFalse("otp must never appear in an audit payload", entry.payload.contains("111111"))
        assertFalse("otp must never appear in an audit payload", entry.payload.contains("222222"))
        assertFalse("session id must never appear in an audit payload", entry.payload.contains("s-1"))

        collectJob.cancel()
    }

    @Test
    fun `offline failure surfaces as retryable, no fabricated outcome`() = runTest(mainDispatcherRule.dispatcher) {
        val source = FakeAbdmAbhaSource(verifyOtpResult = AbhaApiResult.Failure(code = null, message = "socket timeout"))
        val vm = viewModel(source)
        val effects = mutableListOf<AbhaCreateOtpEffect>()
        val collectJob = launch { vm.effects.toList(effects) }

        vm.onOtpChange("111111")
        vm.onMobileNumberChange("9998887776")
        vm.onVerify()
        advanceUntilIdle()

        assertTrue(effects.isEmpty())
        val state = vm.uiState.value
        assertTrue(state.errorRetryable)
        assertFalse(state.errorMessage!!.contains("socket timeout"))
        assertEquals(AbhaOtpRound.AADHAAR, state.round)

        collectJob.cancel()
    }

    @Test
    fun `backend-signalled wrong otp surfaces as not retryable, stays on the aadhaar round`() = runTest(mainDispatcherRule.dispatcher) {
        val source = FakeAbdmAbhaSource(
            verifyOtpResult = AbhaApiResult.Failure(code = "SAMD-ABHA-2004", message = "otp mismatch"),
        )
        val vm = viewModel(source)

        vm.onOtpChange("000000")
        vm.onMobileNumberChange("9998887776")
        vm.onVerify()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.errorRetryable)
        assertTrue(state.errorMessage!!.contains("incorrect", ignoreCase = true))
        assertEquals(AbhaOtpRound.AADHAAR, state.round)
    }

    @Test
    fun `protocol violation surfaces distinctly, not as the offline message`() = runTest(mainDispatcherRule.dispatcher) {
        val source = FakeAbdmAbhaSource(
            verifyOtpResult = AbhaApiResult.ProtocolViolation("Unknown ABHA session state \"WEIRD\" from backend"),
        )
        val vm = viewModel(source)

        vm.onOtpChange("111111")
        vm.onMobileNumberChange("9998887776")
        vm.onVerify()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.errorRetryable)
        assertFalse(state.errorMessage!!.contains("No connection"))
    }
}
