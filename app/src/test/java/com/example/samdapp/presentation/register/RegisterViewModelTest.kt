package com.example.samdapp.presentation.register

import com.example.samdapp.domain.usecase.RegisterPatientUseCase
import com.example.samdapp.testutil.FakeAbhaProfileRepository
import com.example.samdapp.testutil.FakeAuditLogger
import com.example.samdapp.testutil.FakePatientRepository
import com.example.samdapp.testutil.MainDispatcherRule
import com.example.samdapp.testutil.testAbhaProfile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** REQ-REG-01, REQ-AUD-01: a successful registration emits Registered and logs patient_registered. */
@OptIn(ExperimentalCoroutinesApi::class)
class RegisterViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `successful submit registers, logs audit, and emits Registered`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repo = FakePatientRepository()
            val audit = FakeAuditLogger()
            val viewModel = RegisterViewModel(RegisterPatientUseCase(repo), audit, FakeAbhaProfileRepository())

            val effects = mutableListOf<RegisterEffect>()
            val collectJob = launch { viewModel.effects.toList(effects) }

            viewModel.onFieldChange(RegisterField.FULL_NAME, "Asha")
            viewModel.onFieldChange(RegisterField.MOBILE_NUMBER, "9998887776")
            viewModel.onSubmit()
            advanceUntilIdle()

            assertEquals("Asha", repo.registered?.fullName)
            assertTrue(audit.logged.any { it.action == "patient_registered" })
            assertTrue(effects.firstOrNull() is RegisterEffect.Registered)

            collectJob.cancel()
        }

    @Test
    fun `submit is ignored when required fields are missing`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repo = FakePatientRepository()
            val audit = FakeAuditLogger()
            val viewModel = RegisterViewModel(RegisterPatientUseCase(repo), audit, FakeAbhaProfileRepository())

            viewModel.onFieldChange(RegisterField.FULL_NAME, "Asha") // no contact method
            viewModel.onSubmit()
            advanceUntilIdle()

            assertEquals(null, repo.registered)
            assertTrue(audit.logged.isEmpty())
        }

    /** REQ-ABH-02: loading a stored ABHA profile autofills fields and tags them. */
    @Test
    fun `loadAbhaProfile autofills fields and tags them, manual edit clears the tag`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repo = FakePatientRepository()
            val audit = FakeAuditLogger()
            val profile = testAbhaProfile(abhaId = "43422151056749", name = "Anita Kumari")
            val abhaRepo = FakeAbhaProfileRepository(listOf(profile))
            val viewModel = RegisterViewModel(RegisterPatientUseCase(repo), audit, abhaRepo)

            viewModel.loadAbhaProfile(profile.abhaId)
            advanceUntilIdle()

            var state = viewModel.uiState.value
            assertEquals("Anita Kumari", state.fields[RegisterField.FULL_NAME])
            assertTrue(RegisterField.FULL_NAME in state.autofilledFields)
            assertTrue(RegisterField.MOBILE_NUMBER in state.autofilledFields)

            viewModel.onFieldChange(RegisterField.FULL_NAME, "Anita K.")
            state = viewModel.uiState.value
            assertTrue(RegisterField.FULL_NAME !in state.autofilledFields)
            assertTrue(RegisterField.MOBILE_NUMBER in state.autofilledFields)
        }

    /** Phase 6c, W2: a masked ABHA mobile (the real ABDM `/profile` shape, `docs/requirements/
     *  abha-field-mapping.md`) must never satisfy REQ-REG-01's contact-method rule on its own —
     *  the mock's old fabricated full number was the only reason autofill "satisfied" it before. */
    @Test
    fun `a masked ABHA mobile is not autofilled into the field and does not satisfy REQ-REG-01`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repo = FakePatientRepository()
            val audit = FakeAuditLogger()
            val profile = testAbhaProfile(abhaId = "43422151056749", name = "Anita Kumari", mobileNumber = "XXXXXX3210")
            val abhaRepo = FakeAbhaProfileRepository(listOf(profile))
            val viewModel = RegisterViewModel(RegisterPatientUseCase(repo), audit, abhaRepo)

            viewModel.loadAbhaProfile(profile.abhaId)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(null, state.fields[RegisterField.MOBILE_NUMBER])
            assertTrue(RegisterField.MOBILE_NUMBER !in state.autofilledFields)
            assertEquals("XXXXXX3210", state.maskedAbhaMobile)

            // No village/district either (testAbhaProfile's other address fields fill those), so
            // isolate: clear them to prove the masked mobile alone cannot satisfy canSubmit.
            viewModel.onFieldChange(RegisterField.VILLAGE, "")
            viewModel.onFieldChange(RegisterField.DISTRICT, "")
            viewModel.onFieldChange(RegisterField.FULL_NAME, "Anita Kumari")
            assertTrue("a masked mobile alone must not satisfy the contact-method rule", !viewModel.uiState.value.canSubmit)
        }

    /** A stale full mobile from a PRIOR profile load must not survive a later load of a
     *  masked-mobile profile — otherwise it keeps silently satisfying canSubmit for a profile
     *  that no longer has a usable mobile at all. Only clears while still ABHA-autofilled. */
    @Test
    fun `loading a masked-mobile profile clears a stale full mobile from a prior ABHA load`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repo = FakePatientRepository()
            val audit = FakeAuditLogger()
            val fullMobileProfile = testAbhaProfile(abhaId = "11111111111111", name = "First Patient", mobileNumber = "9876543210")
            val maskedMobileProfile = testAbhaProfile(abhaId = "22222222222222", name = "Second Patient", mobileNumber = "XXXXXX3210")
            val abhaRepo = FakeAbhaProfileRepository(listOf(fullMobileProfile, maskedMobileProfile))
            val viewModel = RegisterViewModel(RegisterPatientUseCase(repo), audit, abhaRepo)

            viewModel.loadAbhaProfile(fullMobileProfile.abhaId)
            advanceUntilIdle()
            assertEquals("9876543210", viewModel.uiState.value.fields[RegisterField.MOBILE_NUMBER])
            assertTrue(RegisterField.MOBILE_NUMBER in viewModel.uiState.value.autofilledFields)

            viewModel.loadAbhaProfile(maskedMobileProfile.abhaId)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(null, state.fields[RegisterField.MOBILE_NUMBER])
            assertTrue(RegisterField.MOBILE_NUMBER !in state.autofilledFields)
            assertEquals("XXXXXX3210", state.maskedAbhaMobile)
        }

    /** The other half of the same rule: once the worker types a real, usable number over the
     *  masked one, it counts normally. */
    @Test
    fun `a manually entered real mobile number satisfies REQ-REG-01 after a masked autofill`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repo = FakePatientRepository()
            val audit = FakeAuditLogger()
            val profile = testAbhaProfile(abhaId = "43422151056749", name = "Anita Kumari", mobileNumber = "XXXXXX3210")
            val abhaRepo = FakeAbhaProfileRepository(listOf(profile))
            val viewModel = RegisterViewModel(RegisterPatientUseCase(repo), audit, abhaRepo)

            viewModel.loadAbhaProfile(profile.abhaId)
            advanceUntilIdle()
            viewModel.onFieldChange(RegisterField.VILLAGE, "")
            viewModel.onFieldChange(RegisterField.DISTRICT, "")
            viewModel.onFieldChange(RegisterField.FULL_NAME, "Anita Kumari")
            viewModel.onFieldChange(RegisterField.MOBILE_NUMBER, "9876543210")

            assertTrue(viewModel.uiState.value.canSubmit)
        }

    @Test
    fun `loadAbhaProfile with unknown abhaId leaves state unchanged`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repo = FakePatientRepository()
            val audit = FakeAuditLogger()
            val viewModel = RegisterViewModel(RegisterPatientUseCase(repo), audit, FakeAbhaProfileRepository())

            viewModel.loadAbhaProfile("00000000000000")
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.autofilledFields.isEmpty())
            assertEquals(null, viewModel.uiState.value.abhaId)
        }

    /** Before abhaGenderToBiologicalSex existed, this comparison was `profile.gender in
     *  listOf("Female", "Male", "Other")` directly, so a real ABDM single-letter code never
     *  matched and gender silently failed to autofill (broken under ABDM_MODE=stub too, since
     *  the stub profile also returns "F", see AbhaProfile.kt's abhaGenderToBiologicalSex). */
    @Test
    fun `loadAbhaProfile autofills biological sex from ABDM single-letter gender code`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repo = FakePatientRepository()
            val audit = FakeAuditLogger()
            val profile = testAbhaProfile(abhaId = "43422151056749", name = "Sunita Devi", gender = "F")
            val abhaRepo = FakeAbhaProfileRepository(listOf(profile))
            val viewModel = RegisterViewModel(RegisterPatientUseCase(repo), audit, abhaRepo)

            viewModel.loadAbhaProfile(profile.abhaId)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals("Female", state.biologicalSex)
            assertTrue(state.sexAutofilledFromAbha)
        }

    /** An unrecognised gender code (contract drift, or a real "O"/"U" this vocabulary doesn't yet
     *  cover) must leave the worker's current selection alone rather than silently defaulting. */
    @Test
    fun `loadAbhaProfile leaves biological sex unchanged for an unrecognised gender code`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repo = FakePatientRepository()
            val audit = FakeAuditLogger()
            val profile = testAbhaProfile(abhaId = "43422151056749", name = "Anita Kumari", gender = "U")
            val abhaRepo = FakeAbhaProfileRepository(listOf(profile))
            val viewModel = RegisterViewModel(RegisterPatientUseCase(repo), audit, abhaRepo)
            val sexBefore = viewModel.uiState.value.biologicalSex

            viewModel.loadAbhaProfile(profile.abhaId)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(sexBefore, state.biologicalSex)
            assertTrue(!state.sexAutofilledFromAbha)
        }
}
