package com.example.samdapp.domain.usecase

import com.example.samdapp.testutil.FakeAbhaProfileRepository
import com.example.samdapp.testutil.testAbhaProfile
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** REQ-ABH-02: mock OTP verification accepts any 6-digit code and resolves only a locally-known profile. */
class VerifyAbhaLoginUseCaseTest {

    @Test
    fun `any 6-digit otp succeeds for a known abhaId`() = runTest {
        val profile = testAbhaProfile(abhaId = "43422151056749")
        val repo = FakeAbhaProfileRepository(listOf(profile))

        val result = VerifyAbhaLoginUseCase(repo)(abhaId = profile.abhaId, otp = "654321")

        assertEquals(profile, result.getOrThrow())
    }

    @Test
    fun `non-6-digit otp is rejected`() = runTest {
        val profile = testAbhaProfile(abhaId = "43422151056749")
        val repo = FakeAbhaProfileRepository(listOf(profile))

        val result = VerifyAbhaLoginUseCase(repo)(abhaId = profile.abhaId, otp = "123")

        assertTrue(result.isFailure)
    }

    @Test
    fun `unknown abhaId fails even with a valid otp shape`() = runTest {
        val repo = FakeAbhaProfileRepository()

        val result = VerifyAbhaLoginUseCase(repo)(abhaId = "00000000000000", otp = "123456")

        assertTrue(result.isFailure)
    }
}
