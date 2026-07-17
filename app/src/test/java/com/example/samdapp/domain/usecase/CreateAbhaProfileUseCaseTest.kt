package com.example.samdapp.domain.usecase

import com.example.samdapp.testutil.FakeAbhaProfileRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** REQ-ABH-01: mock ABHA sign-up mints a 14-digit id and persists a verified profile. */
class CreateAbhaProfileUseCaseTest {

    @Test
    fun `valid signup generates a 14-digit id and persists a KYC-verified profile`() = runTest {
        val repo = FakeAbhaProfileRepository()
        val useCase = CreateAbhaProfileUseCase(repo)

        val result = useCase(name = "Anita Kumari", dateOfBirth = null, gender = "Female", mobileNumber = "9998887776")

        val profile = result.getOrThrow()
        assertEquals(14, profile.abhaId.length)
        assertTrue(profile.abhaId.all(Char::isDigit))
        assertTrue(profile.kycVerified)
        assertEquals(profile, repo.profiles[profile.abhaId])
    }

    @Test
    fun `blank name is rejected`() = runTest {
        val repo = FakeAbhaProfileRepository()
        val result = CreateAbhaProfileUseCase(repo)(name = "", dateOfBirth = null, gender = "Female", mobileNumber = "9998887776")
        assertTrue(result.isFailure)
        assertTrue(repo.profiles.isEmpty())
    }

    @Test
    fun `non-10-digit mobile number is rejected`() = runTest {
        val repo = FakeAbhaProfileRepository()
        val result = CreateAbhaProfileUseCase(repo)(name = "Anita", dateOfBirth = null, gender = "Female", mobileNumber = "12345")
        assertTrue(result.isFailure)
        assertTrue(repo.profiles.isEmpty())
    }
}
