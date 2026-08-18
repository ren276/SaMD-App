package com.example.samdapp.domain.usecase

import com.example.samdapp.domain.model.AbhaProfile
import com.example.samdapp.domain.repository.AbhaProfileRepository
import kotlinx.coroutines.delay
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import kotlin.random.Random

/**
 * Mock ABHA sign-up ("Create ABHA ID"). No real ABDM/Aadhaar-OTP gateway call — the delay
 * simulates the portal round-trip, reusing the same mock-delay pattern as
 * [SendToKernelUseCase][com.example.samdapp.domain.usecase.SendToKernelUseCase]. The delay is
 * deliberately what the caller's "Redirecting to ABHA Portal (simulated)" screen is shown against.
 *
 * [kycVerified] is true for a freshly created mock profile — the real Aadhaar-OTP flow this
 * mimics *is* the KYC step, so a successfully created profile is, by construction, verified.
 *
 * **Phase 6c (2026-08-18): still on the mock, deliberately.** The real backend flow this mimics
 * ([com.example.samdapp.domain.abha.AbdmAbhaSource], wired and DI-ready) needs an Aadhaar number
 * and an OTP round-trip; this use case's params (name/DOB/gender/mobile) and its caller
 * ([com.example.samdapp.presentation.abha.AbhaSignUpScreen]) collect neither and have no OTP
 * step at all. Wiring this to the real flow needs a UI change, which was explicitly out of
 * scope for the 6c brief's "do not rebuild these screens" instruction — see PROGRESS.md's Phase
 * 6c Part 2 entry for the full decision record. Not a silent gap: reported and deferred on
 * purpose, waiting on a product decision about collecting Aadhaar in this flow.
 */
class CreateAbhaProfileUseCase @Inject constructor(
    private val repository: AbhaProfileRepository,
) {
    companion object {
        private val secureRandom = SecureRandom()
    }

    suspend operator fun invoke(
        name: String,
        dateOfBirth: LocalDate?,
        gender: String,
        mobileNumber: String,
    ): Result<AbhaProfile> {
        if (name.isBlank()) return Result.failure(IllegalArgumentException("Name is required"))
        if (mobileNumber.length != 10 || !mobileNumber.all(Char::isDigit)) {
            return Result.failure(IllegalArgumentException("Mobile number must be 10 digits"))
        }

        delay(Random.nextLong(1000L, 2000L))

        val profile = AbhaProfile(
            abhaId = generateAbhaId(),
            abhaAddress = null,
            name = name,
            dateOfBirth = dateOfBirth,
            gender = gender,
            address = null,
            district = null,
            state = null,
            pincode = null,
            mobileNumber = mobileNumber,
            emailAddress = null,
            photoUrlMock = null,
            kycVerified = true,
            createdAt = Instant.now(),
        )
        return repository.saveProfile(profile).map { profile }
    }

    /** Canonical 14 digits — see [com.example.samdapp.domain.model.formatAbhaId] for display. */
    private fun generateAbhaId(): String = (1..14).joinToString("") { secureRandom.nextInt(10).toString() }
}
